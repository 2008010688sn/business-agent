/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeRelease;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeReleaseMapper;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeEventResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunCreateReq;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunDetailResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunPageQueryReq;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeStepResp;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimePlan;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRun;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeErrorCode;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeEventType;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeRunState;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimePlanMapper;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeRunMapper;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeStepMapper;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeCancellationService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeEventService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeRunService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStateService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStateService.RunStateChange;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 持久运行时 Run 服务实现。
 *
 * <p>幂等创建刻意不使用 @Transactional：唯一约束冲突后需要在新语句中回查既有 Run，
 * 单个 PostgreSQL 事务在首次冲突后即进入 aborted 状态无法继续；其余写入均为单条 CAS/插入，
 * 幂等与互斥由数据库约束保证。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeRunServiceImpl implements RuntimeRunService {

	private static final String OWNER_DIGITAL_EMPLOYEE = "DIGITAL_EMPLOYEE";

	private static final int DEFAULT_EVENT_LIMIT = 200;

	private static final int MAX_EVENT_LIMIT = 1000;

	private static final int RESUME_CAS_RETRY = 3;

	private static final int INTERACTIVE_CAS_RETRY = 3;

	private static final String TRIGGER_SOURCE_API = "API";

	private static final Duration DEFAULT_CHAT_DEADLINE = Duration.ofMinutes(20);

	private final AgentRuntimeRunMapper runMapper;

	private final AgentRuntimeStepMapper stepMapper;

	private final AgentRuntimePlanMapper planMapper;

	private final RuntimeStateService runtimeStateService;

	private final RuntimeEventService runtimeEventService;

	private final RuntimeCancellationService runtimeCancellationService;

	private final DigitalEmployeeMapper employeeMapper;

	private final DigitalEmployeeReleaseMapper employeeReleaseMapper;

	private final DataAgentProperties dataAgentProperties;

	@Override
	public RuntimeRunResp create(String tenantId, String userId, RuntimeRunCreateReq req) {
		requireTenant(tenantId);
		if (req == null || !StringUtils.hasText(req.clientRequestId())) {
			throw CheckedException.badRequest("clientRequestId 不能为空");
		}
		// PR-1: owner 维度幂等键；去 workspaceId 命名空间
		String ownerType = StringUtils.hasText(req.ownerType()) ? req.ownerType().trim() : "CALLER";
		Long ownerId = req.ownerId();
		AgentRuntimeRun existing = runMapper.findByOwnerAndClientRequest(tenantId, ownerType, ownerId,
				req.clientRequestId());
		if (existing != null) {
			return toResp(existing);
		}
		Instant now = Instant.now();
		AgentRuntimeRun run = AgentRuntimeRun.builder()
			.tenantId(tenantId)
			.ownerType(ownerType)
			.ownerId(ownerId)
			.digitalEmployeeId(req.digitalEmployeeId())
			.tenantIdStr(String.valueOf(tenantId))
			.releaseId(req.releaseId())
			.agentId(req.agentId())
			.clientRequestId(req.clientRequestId())
			.threadId(req.threadId())
			.runtimeRequestId(resolveRuntimeRequestId(req))
			.triggerSource(StringUtils.hasText(req.triggerSource()) ? req.triggerSource().trim() : TRIGGER_SOURCE_API)
			.runMode(req.runMode())
			.query(req.query())
			.state(RuntimeRunState.PENDING.getValue())
			.stateVersion(0L)
			.fenceToken(0L)
			.cancellationEpoch(0L)
			.deadlineAt(req.deadlineSeconds() == null ? null : now.plusSeconds(req.deadlineSeconds()))
			.createTime(now)
			.lastModifyTime(now)
			.deleted(false)
			.build();
		try {
			runMapper.insert(run);
		}
		catch (DuplicateKeyException conflict) {
			// 两把唯一键都可能撞：先认幂等键 winner；没有再认 CHAT 一会话一轮槽。
			AgentRuntimeRun winner = runMapper.findByOwnerAndClientRequest(tenantId, ownerType, ownerId,
					req.clientRequestId());
			if (winner != null) {
				return toResp(winner);
			}
			if (isChatRun(req) && StringUtils.hasText(req.threadId())) {
				AgentRuntimeRun active = runMapper.findActiveChatByTenantAndThread(tenantId, req.threadId().trim());
				if (active != null) {
					throw CheckedException.badRequest(AgentRuntimeErrorCode.SESSION_BUSY.getLabel());
				}
			}
			throw CheckedException.fail("运行幂等创建冲突后回查失败，clientRequestId=" + req.clientRequestId());
		}
		appendEventQuietly(tenantId, run.getId(), "run-created", RuntimeEventType.RUN_CREATED,
				Map.of("clientRequestId", req.clientRequestId(), "ownerType", ownerType,
						"ownerId", ownerId == null ? "" : ownerId));
		return toResp(runMapper.selectById(run.getId()));
	}

	@Override
	public IPage<RuntimeRunResp> page(String tenantId, RuntimeRunPageQueryReq req) {
		requireTenant(tenantId);
		RuntimeRunPageQueryReq query = req == null ? new RuntimeRunPageQueryReq() : req;
		IPage<AgentRuntimeRun> page = runMapper.selectPage(query.buildPage(), Wraps.<AgentRuntimeRun>lbQ()
			.eq(AgentRuntimeRun::getTenantId, tenantId)
			.eq(AgentRuntimeRun::getState, query.getState())
			.eq(AgentRuntimeRun::getRunMode, query.getRunMode())
			.eq(AgentRuntimeRun::getOwnerType, query.getOwnerType())
			// Wraps 自动跳空：digitalEmployeeId 为空表示不按数字员工过滤，不能退化成 digital_employee_id IS NULL
			.eq(AgentRuntimeRun::getOwnerId, query.getOwnerId())
			.eq(AgentRuntimeRun::getDigitalEmployeeId, query.getDigitalEmployeeId())
			.eq(AgentRuntimeRun::getAgentId, query.getAgentId())
			.eq(AgentRuntimeRun::getThreadId, query.getThreadId())
			.like(AgentRuntimeRun::getQuery, query.getKeyword())
			.orderByDesc(AgentRuntimeRun::getId));
		List<AgentRuntimeRun> records = page.getRecords();
		Map<Long, DigitalEmployee> employees = loadEmployees(records);
		Map<Long, DigitalEmployeeRelease> releases = loadReleases(records);
		return page.convert(run -> toResp(run, employees, releases));
	}

	@Override
	public RuntimeRunState findRunState(String tenantId, Long id) {
		if (tenantId == null || id == null) {
			return null;
		}
		AgentRuntimeRun run = runMapper.findByIdAndTenantId(id, tenantId);
		return run == null ? null : RuntimeRunState.of(run.getState());
	}

	@Override
	public Long findRunIdByRuntimeRequestId(String runtimeRequestId) {
		if (!StringUtils.hasText(runtimeRequestId)) {
			return null;
		}
		AgentRuntimeRun run = runMapper.findByRuntimeRequestId(runtimeRequestId);
		return run == null ? null : run.getId();
	}

	@Override
	public RuntimeRunResp findActiveChatRun(String tenantId, String threadId) {
		requireTenant(tenantId);
		if (!StringUtils.hasText(threadId)) {
			return null;
		}
		AgentRuntimeRun run = runMapper.findActiveChatByTenantAndThread(tenantId, threadId.trim());
		return run == null ? null : toResp(run);
	}

	@Override
	public RuntimeRunDetailResp detail(String tenantId, Long id) {
		AgentRuntimeRun run = requireRun(tenantId, id);
		List<RuntimeStepResp> steps = stepMapper.listByRunId(run.getId()).stream().map(RuntimeStepResp::from).toList();
		AgentRuntimePlan plan = planMapper.findActiveByRunId(run.getId());
		return new RuntimeRunDetailResp(toResp(run), run.getFinalAnswer(),
				plan == null ? null : plan.getPlanVersion(), plan == null ? null : plan.getPlanHash(), steps,
				runtimeEventService.latestSeq(run.getId()));
	}

	@Override
	public List<RuntimeEventResp> events(String tenantId, Long id, Long afterSeq, Integer limit) {
		AgentRuntimeRun run = requireRun(tenantId, id);
		int safeLimit = limit == null || limit <= 0 ? DEFAULT_EVENT_LIMIT : Math.min(limit, MAX_EVENT_LIMIT);
		return runtimeEventService.replayAfter(run.getId(), afterSeq, safeLimit).stream()
			.map(RuntimeEventResp::from)
			.toList();
	}

	@Override
	public void cancel(String tenantId, String userId, Long id, String reason) {
		AgentRuntimeRun run = requireRun(tenantId, id);
		RuntimeRunState state = RuntimeRunState.of(run.getState());
		if (state != null && state.terminal()) {
			throw CheckedException.badRequest("运行已进入终态，无法取消: " + run.getState());
		}
		Long epoch = runtimeCancellationService.requestCancel(run, reason, userId);
		if (epoch == null) {
			throw CheckedException.badRequest("运行已进入终态，无法取消");
		}
	}

	@Override
	public RuntimeRunResp startInteractiveRun(String tenantId, String userId, RuntimeRunCreateReq req) {
		RuntimeRunResp created = create(tenantId, userId, req);
		if (created == null || created.id() == null) {
			return created;
		}
		RuntimeRunState current = RuntimeRunState.of(created.state());
		if (current != null && current.terminal()) {
			return created;
		}
		if (current != RuntimeRunState.RUNNING) {
			boolean started = runtimeStateService.transitionRunWithRetry(created.id(), RuntimeRunState.RUNNING,
					RunStateChange.started(Instant.now()), INTERACTIVE_CAS_RETRY);
			if (started) {
				appendEventQuietly(tenantId, created.id(), "run-started", RuntimeEventType.RUN_STARTED,
						Map.of("triggerSource", created.triggerSource() == null ? "" : created.triggerSource()));
			}
		}
		AgentRuntimeRun latest = runMapper.findByIdAndTenantId(created.id(), tenantId);
		return latest == null ? created : toResp(latest);
	}

	@Override
	public void succeedInteractiveRun(String tenantId, Long runId, String finalAnswer) {
		finishInteractiveRun(tenantId, runId, RuntimeRunState.SUCCEEDED, finalAnswer, null, null);
	}

	@Override
	public void failInteractiveRun(String tenantId, Long runId, String errorCode, String errorMessage) {
		finishInteractiveRun(tenantId, runId, RuntimeRunState.FAILED, null, errorCode, errorMessage);
	}

	@Override
	public void resume(String tenantId, String userId, Long id) {
		AgentRuntimeRun run = requireRun(tenantId, id);
		RuntimeRunState state = RuntimeRunState.of(run.getState());
		if (state != RuntimeRunState.WAITING_APPROVAL && state != RuntimeRunState.WAITING_INPUT) {
			throw CheckedException.badRequest("仅等待审批/等待输入的运行可恢复，当前状态: " + run.getState());
		}
		boolean resumed = runtimeStateService.transitionRunWithRetry(run.getId(), RuntimeRunState.RUNNING,
				RunStateChange.deadline(Instant.now().plus(chatDeadline())), RESUME_CAS_RETRY);
		if (!resumed) {
			throw CheckedException.badRequest("恢复失败，运行状态已被并发变更，请刷新后重试");
		}
		appendEventQuietly(run.getTenantId(), run.getId(), "run-resumed:" + Instant.now().toEpochMilli(),
				RuntimeEventType.RUN_RESUMED, Map.of("resumedBy", userId == null ? "" : userId));
	}

	private RuntimeRunResp toResp(AgentRuntimeRun run) {
		if (run == null) {
			return null;
		}
		return toResp(run, loadEmployees(List.of(run)), loadReleases(List.of(run)));
	}

	private RuntimeRunResp toResp(AgentRuntimeRun run, Map<Long, DigitalEmployee> employees,
			Map<Long, DigitalEmployeeRelease> releases) {
		if (run == null) {
			return null;
		}
		Long employeeId = resolveEmployeeId(run);
		DigitalEmployee employee = employeeId == null || employees == null ? null : employees.get(employeeId);
		DigitalEmployeeRelease release = run.getReleaseId() == null || releases == null ? null
				: releases.get(run.getReleaseId());
		String principalId = employee == null ? null : employee.getIamPrincipalId();
		String specHash = release == null ? null : release.getSpecHash();
		return RuntimeRunResp.from(run, principalId, specHash);
	}

	private Long resolveEmployeeId(AgentRuntimeRun run) {
		if (run.getDigitalEmployeeId() != null) {
			return run.getDigitalEmployeeId();
		}
		if (OWNER_DIGITAL_EMPLOYEE.equals(run.getOwnerType())) {
			return run.getOwnerId();
		}
		return null;
	}

	private Map<Long, DigitalEmployee> loadEmployees(List<AgentRuntimeRun> runs) {
		Set<Long> ids = new HashSet<>();
		if (runs != null) {
			for (AgentRuntimeRun run : runs) {
				Long employeeId = resolveEmployeeId(run);
				if (employeeId != null) {
					ids.add(employeeId);
				}
			}
		}
		if (ids.isEmpty() || employeeMapper == null) {
			return Map.of();
		}
		List<DigitalEmployee> employees = employeeMapper.selectList(Wraps.<DigitalEmployee>lbQ()
			.in(DigitalEmployee::getId, ids)
			.eq(DigitalEmployee::getDeleted, false));
		Map<Long, DigitalEmployee> map = new HashMap<>();
		if (employees != null) {
			for (DigitalEmployee employee : employees) {
				if (employee.getId() != null) {
					map.put(employee.getId(), employee);
				}
			}
		}
		return map;
	}

	private Map<Long, DigitalEmployeeRelease> loadReleases(List<AgentRuntimeRun> runs) {
		Set<Long> ids = new HashSet<>();
		if (runs != null) {
			for (AgentRuntimeRun run : runs) {
				if (run.getReleaseId() != null) {
					ids.add(run.getReleaseId());
				}
			}
		}
		if (ids.isEmpty() || employeeReleaseMapper == null) {
			return Map.of();
		}
		List<DigitalEmployeeRelease> releases = employeeReleaseMapper.selectList(Wraps.<DigitalEmployeeRelease>lbQ()
			.in(DigitalEmployeeRelease::getId, ids)
			.eq(DigitalEmployeeRelease::getDeleted, false));
		Map<Long, DigitalEmployeeRelease> map = new HashMap<>();
		if (releases != null) {
			for (DigitalEmployeeRelease release : releases) {
				if (release.getId() != null) {
					map.put(release.getId(), release);
				}
			}
		}
		return map;
	}

	private void finishInteractiveRun(String tenantId, Long runId, RuntimeRunState toState, String finalAnswer,
			String errorCode, String errorMessage) {
		if (runId == null) {
			return;
		}
		try {
			requireRun(tenantId, runId);
			boolean finished = runtimeStateService.transitionRunWithRetry(runId, toState,
					RunStateChange.finished(finalAnswer, errorCode, errorMessage), INTERACTIVE_CAS_RETRY);
			if (!finished) {
				log.error("对话运行终态推进未生效. runId={}, toState={}", runId, toState);
				return;
			}
		}
		catch (RuntimeException ex) {
			log.error("对话运行终态写入失败. runId={}, toState={}", runId, toState, ex);
			return;
		}
		RuntimeEventType eventType = toState == RuntimeRunState.SUCCEEDED ? RuntimeEventType.RUN_SUCCEEDED
				: RuntimeEventType.RUN_FAILED;
		try {
			runtimeEventService.append(tenantId, runId, eventType.getValue().toLowerCase().replace('_', '-'), eventType,
					null, Map.of("state", toState.getValue()));
		}
		catch (RuntimeException ex) {
			log.error("运行终态事件追加失败. runId={}, eventType={}", runId, eventType, ex);
			throw ex;
		}
	}

	private AgentRuntimeRun requireRun(String tenantId, Long id) {
		requireTenant(tenantId);
		if (id == null) {
			throw CheckedException.badRequest("运行ID不能为空");
		}
		AgentRuntimeRun run = runMapper.findByIdAndTenantId(id, tenantId);
		if (run == null) {
			throw CheckedException.notFound("运行不存在或无权访问: " + id);
		}
		return run;
	}

	private static boolean isChatRun(RuntimeRunCreateReq req) {
		return req != null && "CHAT".equalsIgnoreCase(req.runMode());
	}

	private static String resolveRuntimeRequestId(RuntimeRunCreateReq req) {
		if (isChatRun(req) && StringUtils.hasText(req.clientRequestId())) {
			String clientRequestId = req.clientRequestId().trim();
			int lastColon = clientRequestId.lastIndexOf(':');
			if (clientRequestId.regionMatches(true, 0, "CHAT:", 0, 5) && lastColon > 4) {
				String suffix = clientRequestId.substring(lastColon + 1).trim();
				if (isUuid(suffix)) {
					return suffix;
				}
			}
		}
		return UUID.randomUUID().toString();
	}

	private static boolean isUuid(String value) {
		if (!StringUtils.hasText(value) || value.length() != 36) {
			return false;
		}
		for (int index = 0; index < value.length(); index++) {
			char ch = value.charAt(index);
			if (index == 8 || index == 13 || index == 18 || index == 23) {
				if (ch != '-') {
					return false;
				}
				continue;
			}
			boolean hex = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F');
			if (!hex) {
				return false;
			}
		}
		return true;
	}

	private Duration chatDeadline() {
		if (dataAgentProperties == null || dataAgentProperties.getRuntime() == null
				|| dataAgentProperties.getRuntime().getChatDeadline() == null
				|| dataAgentProperties.getRuntime().getChatDeadline().isZero()
				|| dataAgentProperties.getRuntime().getChatDeadline().isNegative()) {
			return DEFAULT_CHAT_DEADLINE;
		}
		return dataAgentProperties.getRuntime().getChatDeadline();
	}

	private void requireTenant(String tenantId) {
		if (!StringUtils.hasText(tenantId) || "0".equals(tenantId.trim())) {
			throw CheckedException.badRequest("租户上下文缺失");
		}
	}

	private void appendEventQuietly(String tenantId, Long runId, String eventKey, RuntimeEventType eventType,
			Map<String, Object> payload) {
		try {
			runtimeEventService.append(tenantId, runId, eventKey, eventType, null, payload);
		}
		catch (RuntimeException ex) {
			// 事件是状态变更的附属记录，主流程已生效；失败必须可见但不回滚主流程
			log.error("运行事件追加失败. runId={}, eventKey={}", runId, eventKey, ex);
		}
	}

}
