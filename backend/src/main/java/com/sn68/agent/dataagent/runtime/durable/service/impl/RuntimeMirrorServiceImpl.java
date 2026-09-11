/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.constant.OrchestrationStatus;
import com.sn68.agent.dataagent.entity.AgentOrchestrationRun;
import com.sn68.agent.dataagent.entity.AgentOrchestrationStep;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRun;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeStep;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeEventType;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeRunState;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeStepState;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeRunMapper;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeStepMapper;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeEventService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeMirrorService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeOutboxService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStateService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStateService.RunStateChange;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStateService.StepStateChange;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 双写迁移期镜像服务实现。
 *
 * <p>刻意不使用 @Transactional：本服务被既有编排链路内联调用，镜像写入的唯一约束冲突
 * 不得污染调用方事务（PostgreSQL 事务冲突后即 aborted）；各写入幂等，重复调用自愈。
 * 所有公开方法整体 try/catch，镜像失败只记 error 日志、不中断编排（见接口注释中的迁移期约定）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeMirrorServiceImpl implements RuntimeMirrorService {

	private static final int CAS_RETRY = 3;

	private final AgentRuntimeRunMapper runMapper;

	private final AgentRuntimeStepMapper stepMapper;

	private final RuntimeStateService runtimeStateService;

	private final RuntimeEventService runtimeEventService;

	private final RuntimeOutboxService runtimeOutboxService;

	private final ObjectMapper objectMapper;

	@Override
	public void mirrorRunStarted(AgentRequest request, AgentOrchestrationRun legacyRun) {
		if (legacyRun == null || legacyRun.getId() == null) {
			return;
		}
		try {
			AgentRuntimeRun existing = runMapper.findBySourceRunId(legacyRun.getId());
			if (existing != null) {
				return;
			}
			String tenantId = parseTenantId(request);
			Instant now = Instant.now();
			AgentRuntimeRun mirror = AgentRuntimeRun.builder()
				.tenantId(tenantId)
				.tenantIdStr(tenantId)
				.ownerType("PLATFORM")
				.agentId(legacyRun.getAgentId())
				// 遥测 runtimeRequestId 全局唯一，作为迁移期幂等键（owner=PLATFORM 命名空间）
				.clientRequestId(legacyRun.getRuntimeRequestId())
				.threadId(legacyRun.getThreadId())
				.runtimeRequestId(legacyRun.getRuntimeRequestId())
				.sourceRunId(legacyRun.getId())
				.triggerSource("LEGACY_ORCHESTRATION")
				.runMode("ORCHESTRATION")
				.query(legacyRun.getQuery())
				.state(RuntimeRunState.RUNNING.getValue())
				.stateVersion(0L)
				.fenceToken(0L)
				.cancellationEpoch(0L)
				.startedAt(legacyRun.getStartedAt() == null ? now : legacyRun.getStartedAt())
				.createTime(now)
				.lastModifyTime(now)
				.deleted(false)
				.build();
			try {
				runMapper.insert(mirror);
			}
			catch (DuplicateKeyException conflict) {
				// 并发镜像：唯一约束保证只有一条，直接复用已有镜像
				return;
			}
			appendEventQuietly(tenantId, mirror.getId(), "run-created", RuntimeEventType.RUN_CREATED, null,
					Map.of("sourceRunId", legacyRun.getId()));
			appendEventQuietly(tenantId, mirror.getId(), "run-started", RuntimeEventType.RUN_STARTED, null, null);
		}
		catch (RuntimeException ex) {
			log.error("镜像权威 Run 创建失败. sourceRunId={}", legacyRun.getId(), ex);
		}
	}

	@Override
	public void mirrorRunFinished(AgentOrchestrationRun legacyRun, String legacyStatus) {
		if (legacyRun == null || legacyRun.getId() == null) {
			return;
		}
		try {
			AgentRuntimeRun mirror = runMapper.findBySourceRunId(legacyRun.getId());
			if (mirror == null) {
				return;
			}
			RuntimeRunState target = mapRunState(legacyStatus);
			if (target == null) {
				return;
			}
			if (target == RuntimeRunState.WAITING_INPUT) {
				runtimeStateService.transitionRunWithRetry(mirror.getId(), target, RunStateChange.none(), CAS_RETRY);
				appendEventQuietly(mirror.getTenantId(), mirror.getId(),
						"run-waiting:" + Instant.now().toEpochMilli(), RuntimeEventType.RUN_WAITING, null, null);
				return;
			}
			boolean converged = runtimeStateService.transitionRunWithRetry(mirror.getId(), target,
					RunStateChange.finished(legacyRun.getFinalAnswer(), legacyRun.getErrorMessage() == null ? null
							: "LEGACY_RUN_FAILED", legacyRun.getErrorMessage()), CAS_RETRY);
			if (converged) {
				appendEventQuietly(mirror.getTenantId(), mirror.getId(), "run-finished", runEventType(target), null,
						Map.of("state", target.getValue()));
				appendRunTerminalOutboxQuietly(mirror, target,
						legacyRun.getErrorMessage() == null ? null : "LEGACY_RUN_FAILED");
			}
		}
		catch (RuntimeException ex) {
			log.error("镜像权威 Run 收尾失败. sourceRunId={}, legacyStatus={}", legacyRun.getId(), legacyStatus, ex);
		}
	}

	/**
	 * run 终态迁移 CAS 成功后旁路写 Outbox（IM 等消费方按 eventKey 幂等去重）。
	 * 负载只含状态与错误码，不放最终答案等业务原文；写入失败仅记 error，不影响镜像收敛。
	 */
	private void appendRunTerminalOutboxQuietly(AgentRuntimeRun mirror, RuntimeRunState state, String errorCode) {
		RuntimeEventType eventType = runEventType(state);
		try {
			Map<String, Object> payload = errorCode == null ? Map.of("state", state.getValue())
					: Map.of("state", state.getValue(), "errorCode", errorCode);
			runtimeOutboxService.append(new RuntimeOutboxService.OutboxAppend(mirror.getTenantId(),
					null, mirror.getId(), null, eventType.getValue(),
					"run-terminal:" + mirror.getId() + ":" + eventType.getValue(), payload));
		}
		catch (RuntimeException ex) {
			log.error("运行终态 Outbox 写入失败, 终态已收敛不受影响. runId={}, eventType={}", mirror.getId(),
					eventType.getValue(), ex);
		}
	}

	@Override
	public void mirrorStepStarted(AgentOrchestrationRun legacyRun, AgentOrchestrationStep legacyStep) {
		if (legacyRun == null || legacyStep == null || legacyStep.getId() == null) {
			return;
		}
		try {
			AgentRuntimeRun mirror = runMapper.findBySourceRunId(legacyRun.getId());
			if (mirror == null) {
				return;
			}
			String stepKey = stepKeyOf(legacyStep);
			AgentRuntimeStep existing = stepMapper.findByRunIdAndStepKey(mirror.getId(), stepKey);
			if (schedulerManaged(existing)) {
				return;
			}
			if (existing == null) {
				Instant now = Instant.now();
				AgentRuntimeStep step = AgentRuntimeStep.builder()
					.tenantId(mirror.getTenantId())
					.runId(mirror.getId())
					.stepKey(stepKey)
					.stepName(legacyStep.getTask())
					.capabilityHandle(legacyStep.getCollaboratorAgentId() == null ? null
							: "collaborator:" + legacyStep.getCollaboratorAgentId())
					.dependsOn("[]")
					.inputBindings("[]")
					.state(RuntimeStepState.RUNNING.getValue())
					.stateVersion(0L)
					.fenceToken(0L)
					.cancellationEpoch(0L)
					.attemptCount(1)
					.maxAttempts(1)
					.startedAt(legacyStep.getStartedAt() == null ? now : legacyStep.getStartedAt())
					.createTime(now)
					.lastModifyTime(now)
					.deleted(false)
					.build();
				try {
					stepMapper.insert(step);
				}
				catch (DuplicateKeyException conflict) {
					// 并发镜像同一步骤：唯一约束 (run_id, step_key) 保证只有一条
					return;
				}
			}
			else {
				runtimeStateService.transitionStepWithRetry(existing.getId(), RuntimeStepState.RUNNING,
						StepStateChange.started(Instant.now()), CAS_RETRY);
			}
			appendEventQuietly(mirror.getTenantId(), mirror.getId(), "step:" + stepKey + ":started",
					RuntimeEventType.STEP_STARTED, stepKey, Map.of("sourceStepId", legacyStep.getId()));
		}
		catch (RuntimeException ex) {
			log.error("镜像权威 Step 创建失败. sourceStepId={}", legacyStep.getId(), ex);
		}
	}

	@Override
	public void mirrorStepFinished(AgentOrchestrationStep legacyStep, String legacyStatus) {
		if (legacyStep == null || legacyStep.getRunId() == null) {
			return;
		}
		try {
			AgentRuntimeRun mirror = runMapper.findBySourceRunId(legacyStep.getRunId());
			if (mirror == null) {
				return;
			}
			String stepKey = stepKeyOf(legacyStep);
			AgentRuntimeStep step = stepMapper.findByRunIdAndStepKey(mirror.getId(), stepKey);
			if (step == null || schedulerManaged(step)) {
				return;
			}
			RuntimeStepState target = mapStepState(legacyStatus);
			if (target == null) {
				return;
			}
			if (target == RuntimeStepState.WAITING) {
				runtimeStateService.transitionStepWithRetry(step.getId(), target, StepStateChange.none(), CAS_RETRY);
				appendEventQuietly(mirror.getTenantId(), mirror.getId(),
						"step:" + stepKey + ":waiting:" + Instant.now().toEpochMilli(), RuntimeEventType.STEP_WAITING,
						stepKey, null);
				return;
			}
			boolean converged = runtimeStateService.transitionStepWithRetry(step.getId(), target,
					StepStateChange.finished(legacyStep.getErrorCode(), legacyStep.getErrorMessage(), null),
					CAS_RETRY);
			if (converged) {
				appendEventQuietly(mirror.getTenantId(), mirror.getId(), "step:" + stepKey + ":finished",
						stepEventType(target), stepKey, Map.of("state", target.getValue()));
			}
		}
		catch (RuntimeException ex) {
			log.error("镜像权威 Step 收尾失败. sourceStepId={}, legacyStatus={}", legacyStep.getId(), legacyStatus, ex);
		}
	}

	@Override
	public Long prepareScheduledSteps(AgentOrchestrationRun legacyRun, List<ScheduledStepSpec> specs) {
		if (legacyRun == null || legacyRun.getId() == null || specs == null || specs.isEmpty()) {
			return null;
		}
		try {
			AgentRuntimeRun mirror = runMapper.findBySourceRunId(legacyRun.getId());
			if (mirror == null) {
				return null;
			}
			for (ScheduledStepSpec spec : specs) {
				prepareScheduledStep(mirror, spec);
			}
			return mirror.getId();
		}
		catch (RuntimeException ex) {
			log.error("调度步骤准备失败，编排应回落整批执行. sourceRunId={}", legacyRun.getId(), ex);
			return null;
		}
	}

	@Override
	public List<AgentRuntimeStep> listScheduledSteps(Long runtimeRunId) {
		if (runtimeRunId == null) {
			return List.of();
		}
		try {
			return stepMapper.listByRunId(runtimeRunId);
		}
		catch (RuntimeException ex) {
			log.error("调度步骤状态对账查询失败. runtimeRunId={}", runtimeRunId, ex);
			return List.of();
		}
	}

	private void prepareScheduledStep(AgentRuntimeRun mirror, ScheduledStepSpec spec) {
		if (spec == null || !StringUtils.hasText(spec.stepKey())) {
			return;
		}
		AgentRuntimeStep existing = stepMapper.findByRunIdAndStepKey(mirror.getId(), spec.stepKey());
		if (existing != null) {
			convergeRestoredStep(existing, spec);
			return;
		}
		boolean restored = spec.restoredState() != null;
		Instant now = Instant.now();
		AgentRuntimeStep step = AgentRuntimeStep.builder()
			.tenantId(mirror.getTenantId())
			.runId(mirror.getId())
			.stepKey(spec.stepKey())
			.stepName(spec.stepName())
			.capabilityHandle(spec.capabilityHandle())
			.dependsOn(dependsOnJson(spec.dependsOn()))
			.inputBindings("[]")
			.state(restored ? spec.restoredState().getValue() : RuntimeStepState.PENDING.getValue())
			.stateVersion(0L)
			.fenceToken(0L)
			.cancellationEpoch(0L)
			.attemptCount(0)
			.maxAttempts(Math.max(1, spec.maxAttempts()))
			.errorCode(restored ? spec.restoredErrorCode() : null)
			.errorMessage(restored ? spec.restoredErrorMessage() : null)
			.finishedAt(restored ? now : null)
			.createTime(now)
			.lastModifyTime(now)
			.deleted(false)
			.build();
		try {
			stepMapper.insert(step);
		}
		catch (DuplicateKeyException conflict) {
			// 并发准备同一步骤：唯一约束 (run_id, step_key) 保证只有一条
		}
	}

	/**
	 * 恢复链路的既定终态（如上一段挂起交互步骤的最终结果、快照失效步骤）收敛到权威行，
	 * 使调度器把它视为已完成上游；已终态的行不再变更。
	 */
	private void convergeRestoredStep(AgentRuntimeStep existing, ScheduledStepSpec spec) {
		if (spec.restoredState() == null || !spec.restoredState().terminal()) {
			return;
		}
		RuntimeStepState current = RuntimeStepState.of(existing.getState());
		if (current != null && current.terminal()) {
			return;
		}
		runtimeStateService.transitionStepWithRetry(existing.getId(), spec.restoredState(),
				StepStateChange.finished(spec.restoredErrorCode(), spec.restoredErrorMessage(), null), CAS_RETRY);
	}

	/**
	 * 调度器管理的步骤（取得过租约，fence_token &gt; 0）状态机由调度器独占写入，镜像跳过；
	 * 镜像自建与准备阶段的步骤 fence 恒为 0。
	 */
	private boolean schedulerManaged(AgentRuntimeStep step) {
		return step != null && step.getFenceToken() != null && step.getFenceToken() > 0;
	}

	private String dependsOnJson(List<String> dependsOn) {
		if (dependsOn == null || dependsOn.isEmpty()) {
			return "[]";
		}
		try {
			return objectMapper.writeValueAsString(dependsOn);
		}
		catch (Exception ex) {
			// 依赖清单序列化失败必须暴露：静默落空数组会让调度器忽略依赖、提前放行下游
			throw new IllegalStateException("调度步骤依赖清单序列化失败: " + ex.getMessage(), ex);
		}
	}

	/**
	 * 迁移期步骤键：优先采用路由计划 stepId（与未来 CompiledPlan 对齐），无计划时退化为步骤序号。
	 */
	private String stepKeyOf(AgentOrchestrationStep legacyStep) {
		if (StringUtils.hasText(legacyStep.getRouteStepId())) {
			return legacyStep.getRouteStepId();
		}
		return "step-" + (legacyStep.getStepNo() == null ? legacyStep.getId() : legacyStep.getStepNo());
	}

	private String parseTenantId(AgentRequest request) {
		String tenantId = request == null ? null : request.getTenantIdSnapshot();
		return StringUtils.hasText(tenantId) ? tenantId.trim() : null;
	}

	private RuntimeRunState mapRunState(String legacyStatus) {
		if (!StringUtils.hasText(legacyStatus)) {
			return null;
		}
		return switch (legacyStatus) {
			case OrchestrationStatus.SUCCESS, OrchestrationStatus.PARTIAL_SUCCESS -> RuntimeRunState.SUCCEEDED;
			case OrchestrationStatus.FAILED -> RuntimeRunState.FAILED;
			case OrchestrationStatus.TIMED_OUT -> RuntimeRunState.TIMED_OUT;
			case OrchestrationStatus.CANCELLED -> RuntimeRunState.CANCELLED;
			case OrchestrationStatus.WAITING_CLARIFICATION -> RuntimeRunState.WAITING_INPUT;
			default -> null;
		};
	}

	private RuntimeStepState mapStepState(String legacyStatus) {
		if (!StringUtils.hasText(legacyStatus)) {
			return null;
		}
		return switch (legacyStatus) {
			case OrchestrationStatus.SUCCESS, OrchestrationStatus.PARTIAL_SUCCESS -> RuntimeStepState.SUCCEEDED;
			case OrchestrationStatus.FAILED -> RuntimeStepState.FAILED;
			case OrchestrationStatus.TIMED_OUT -> RuntimeStepState.TIMED_OUT;
			case OrchestrationStatus.CANCELLED -> RuntimeStepState.CANCELLED;
			case OrchestrationStatus.WAITING_CLARIFICATION -> RuntimeStepState.WAITING;
			default -> null;
		};
	}

	private RuntimeEventType runEventType(RuntimeRunState state) {
		return switch (state) {
			case FAILED -> RuntimeEventType.RUN_FAILED;
			case CANCELLED -> RuntimeEventType.RUN_CANCELLED;
			case TIMED_OUT -> RuntimeEventType.RUN_TIMED_OUT;
			default -> RuntimeEventType.RUN_SUCCEEDED;
		};
	}

	private RuntimeEventType stepEventType(RuntimeStepState state) {
		return switch (state) {
			case FAILED -> RuntimeEventType.STEP_FAILED;
			case CANCELLED -> RuntimeEventType.STEP_CANCELLED;
			case TIMED_OUT -> RuntimeEventType.STEP_TIMED_OUT;
			case SKIPPED -> RuntimeEventType.STEP_SKIPPED;
			default -> RuntimeEventType.STEP_SUCCEEDED;
		};
	}

	private void appendEventQuietly(String tenantId, Long runId, String eventKey, RuntimeEventType eventType,
			String stepKey, Map<String, Object> payload) {
		try {
			runtimeEventService.append(tenantId, runId, eventKey, eventType, stepKey, payload);
		}
		catch (RuntimeException ex) {
			log.error("镜像事件追加失败. runId={}, eventKey={}", runId, eventKey, ex);
		}
	}

}
