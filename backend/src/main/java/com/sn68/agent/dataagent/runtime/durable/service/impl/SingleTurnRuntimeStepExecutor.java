/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeBudgetExceededException;
import com.sn68.agent.dataagent.authorization.observability.AuthorizationMetrics;
import com.sn68.agent.dataagent.context.DataAgentAsyncContextBridge;
import com.sn68.agent.dataagent.context.DataAgentOutboundContext;
import com.sn68.agent.dataagent.employee.auth.EmployeeAuthContextException;
import com.sn68.agent.dataagent.employee.auth.EmployeeAuthTokenContext;
import com.sn68.agent.dataagent.employee.auth.EmployeeExecutionContextClient;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.employee.service.EmployeeReleaseSnapshotResolver;
import com.sn68.agent.dataagent.constant.AgentStatusConstant;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.repository.DataAgentMapper;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRun;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeStep;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeStepState;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeStepMapper;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStateService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStepExecutor;
import com.sn68.agent.dataagent.runtime.durable.support.RuntimeStructuredWorkProductComposer;
import com.sn68.agent.dataagent.service.agent.AgentInvocationService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 单步任务型运行的步骤执行体。
 *
 * <p>任务链路（{@code DefaultTaskRunLauncher}）创建的权威运行只有一句自然语言任务描述，
 * 没有编译计划、没有 DAG，本执行体把它落成「一个步骤」并交给既有单轮执行入口
 * （{@code AgentInvocationService#invoke} → {@code executeAgentOnce}）执行。</p>
 *
 * <p>职责边界：本类只负责「跑完一次单轮请求并给出 StepOutcome」。租约认领、fence 校验、
 * READY→RUNNING→终态的 CAS、尝试记录、产物落库、Run 终态收敛与事件全部由
 * {@code RuntimeDagSchedulerImpl} 完成，本类不重复实现任何状态机；唯一主动写状态的动作是
 * 长任务期间的租约续期（否则会被守护作业判定为过期并重复接管）。</p>
 *
 * <p>入口闸门：数字员工仍按 {@code digital_employee_release} 快照校验；普通智能体只认
 * {@code DataAgent.status=published}，不再查 {@code data_agent_release}。</p>
 *
 * <p>取消不在本类实现：{@code AgentRuntimeRegistry} 已按 runtimeRequestId 做取消写穿与
 * 周期性回源，会中断执行线程；因此本执行体把 Run 的 runtimeRequestId 原样带进 AgentRequest，
 * 而不是另造一套取消检查。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SingleTurnRuntimeStepExecutor implements RuntimeStepExecutor {

	/** 单步任务型运行的唯一步骤键（同一 run 内唯一，重复准备命中唯一约束）。 */
	public static final String SINGLE_TURN_STEP_KEY = "single-turn";

	private static final String STEP_NAME = "单轮任务执行";

	/** 单步任务由任务描述直接驱动，不经能力目录，能力句柄用固定伪能力标识本次执行动作。 */
	private static final String CAPABILITY_HANDLE = "agent-runtime:single-turn";

	private static final String ARTIFACT_SCHEMA_VERSION = "single-turn-answer/v1";

	/** Release 非 PUBLISHED 时的失败码：与能力网关的「Release 固定」检查同一判定标准。 */
	private static final String RELEASE_NOT_PUBLISHED = "RELEASE_NOT_PUBLISHED";

	/** 请求来源标识，落入 chat turn / 遥测时可区分任务链路与人机会话。 */
	private static final String REQUEST_SOURCE = "RUNTIME_TASK";

	/** 任务型 Run 没有会话，用运行ID 派生稳定 threadId，避免与真实会话ID 撞键。 */
	private static final String THREAD_ID_PREFIX = "runtime-run-";

	/** 续期间隔远小于续期时长：一次 GC/抖动漏掉一拍也不会让租约过期被误接管。 */
	private static final Duration LEASE_RENEW_INTERVAL = Duration.ofSeconds(45);

	/** 每次续期把租约推到的时长，与调度器认领时的租约时长口径一致。 */
	private static final Duration LEASE_RENEW_DURATION = Duration.ofMinutes(3);

	/**
	 * 租约心跳线程：只做定时 UPDATE，不承载业务，属固定参数的局部线程池，按 static final 持有
	 * 而不是注册 Bean（无需 Spring 生命周期与外部配置）。
	 */
	private static final ScheduledExecutorService LEASE_HEARTBEAT = Executors
		.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "runtime-step-lease-heartbeat");
			thread.setDaemon(true);
			return thread;
		});

	private static final String OWNER_TYPE_DIGITAL_EMPLOYEE = "DIGITAL_EMPLOYEE";

	private final AgentRuntimeStepMapper stepMapper;

	private final DataAgentMapper dataAgentMapper;

	private final EmployeeReleaseSnapshotResolver employeeReleaseSnapshotResolver;

	private final DigitalEmployeeMapper digitalEmployeeMapper;

	private final EmployeeExecutionContextClient executionContextClient;

	private final DataAgentAsyncContextBridge asyncContextBridge;

	private final RuntimeStateService runtimeStateService;

	private final AgentInvocationService agentInvocationService;

	private final AuthenticationContext authenticationContext;

	private final ObjectMapper objectMapper;

	/** PR-22-C1: token_budget_exceeded_total(Prometheus) 的唯一生产者：预算超限拒绝计数 */
	private final AuthorizationMetrics authorizationMetrics;

	private final RuntimeStructuredWorkProductComposer structuredWorkProductComposer;

	/**
	 * 幂等准备单步任务型运行的唯一步骤行。任务链路创建 Run 时不落任何步骤，而调度器只调度步骤，
	 * 因此拉起前必须先把这一步物化出来；(run_id, step_key) 唯一约束保证多节点并发准备只有一行。
	 *
	 * @return 该运行的单步行；准备失败返回 null（调用方跳过本轮，下一轮重扫）
	 */
	public AgentRuntimeStep ensureSingleStep(AgentRuntimeRun run) {
		if (run == null || run.getId() == null) {
			return null;
		}
		AgentRuntimeStep existing = stepMapper.findByRunIdAndStepKey(run.getId(), SINGLE_TURN_STEP_KEY);
		if (existing != null) {
			return existing;
		}
		Instant now = Instant.now();
		AgentRuntimeStep step = AgentRuntimeStep.builder()
			.tenantId(run.getTenantId())
			.runId(run.getId())
			.stepKey(SINGLE_TURN_STEP_KEY)
			.stepName(STEP_NAME)
			.capabilityHandle(CAPABILITY_HANDLE)
			.dependsOn("[]")
			.inputBindings("[]")
			.state(RuntimeStepState.PENDING.getValue())
			.stateVersion(0L)
			.fenceToken(0L)
			.cancellationEpoch(0L)
			.deadlineAt(run.getDeadlineAt())
			.attemptCount(0)
			.maxAttempts(1)
			.createTime(now)
			.lastModifyTime(now)
			.deleted(false)
			.build();
		try {
			stepMapper.insert(step);
			return step;
		}
		catch (DuplicateKeyException conflict) {
			// 并发准备同一步骤：唯一约束保证只有一行，回读 winner
			return stepMapper.findByRunIdAndStepKey(run.getId(), SINGLE_TURN_STEP_KEY);
		}
	}

	@Override
	public StepOutcome execute(StepExecution execution) {
		AgentRuntimeRun run = execution.run();
		if (!StringUtils.hasText(run.getQuery())) {
			return StepOutcome.failure("RUN_QUERY_EMPTY", "运行缺少任务描述, 无法执行单轮任务, runId=" + run.getId());
		}
		if (OWNER_TYPE_DIGITAL_EMPLOYEE.equals(run.getOwnerType())) {
			return executeDigitalEmployee(execution);
		}
		Long agentId = run.getAgentId();
		if (agentId == null) {
			return StepOutcome.failure("AGENT_UNRESOLVED", "运行未绑定智能体, runId=" + run.getId());
		}
		DataAgent agent = dataAgentMapper.findById(agentId);
		if (agent == null) {
			return StepOutcome.failure("AGENT_UNRESOLVED",
					"智能体不存在, runId=" + run.getId() + ", agentId=" + agentId);
		}
		if (!AgentStatusConstant.PUBLISHED.equalsIgnoreCase(agent.getStatus())) {
			log.warn("智能体未发布, 任务在执行入口失败关闭. runId={}, agentId={}, status={}", run.getId(), agentId,
					agent.getStatus());
			return StepOutcome.failure("AGENT_NOT_PUBLISHED",
					"智能体未发布, 无法执行任务, agentId=" + agentId + ", status=" + agent.getStatus());
		}
		return invokeOnce(execution, buildRequest(run, agentId, null));
	}

	/**
	 * 数字员工任务：Release 闸门读 {@code digital_employee_release} 快照，agentId 钉员工主键，
	 * 执行期签发 Principal token 并覆盖出站头（拉起阶段的签发只证明凭据可用，不能带到这里）。
	 */
	private StepOutcome executeDigitalEmployee(StepExecution execution) {
		AgentRuntimeRun run = execution.run();
		String tenantId = run.getTenantId() == null ? null : String.valueOf(run.getTenantId());
		if (!StringUtils.hasText(tenantId) || run.getOwnerId() == null || run.getReleaseId() == null) {
			return StepOutcome.failure("AGENT_UNRESOLVED",
					"数字员工任务缺少租户、员工或 Release, runId=" + run.getId());
		}
		try {
			employeeReleaseSnapshotResolver.resolveById(tenantId, run.getOwnerId(), run.getReleaseId());
		}
		catch (CheckedException ex) {
			log.warn("数字员工任务 Release 快照不可执行. runId={}, releaseId={}", run.getId(), run.getReleaseId(), ex);
			return StepOutcome.failure(RELEASE_NOT_PUBLISHED, ex.getMessage());
		}
		DigitalEmployee employee = digitalEmployeeMapper.findByIdAndTenantId(run.getOwnerId(), tenantId);
		if (employee == null) {
			return StepOutcome.failure("AGENT_UNRESOLVED",
					"数字员工不存在, runId=" + run.getId() + ", employeeId=" + run.getOwnerId());
		}
		if (!StringUtils.hasText(employee.getIamPrincipalId())) {
			return StepOutcome.failure("WAITING_AUTH",
					"数字员工执行身份未就绪, 无法执行自动任务, employeeId=" + employee.getId());
		}
		EmployeeAuthTokenContext tokenContext;
		try {
			tokenContext = executionContextClient.issueContext(tenantId, employee.getIamPrincipalId(),
					employee.getEmployeeName());
		}
		catch (EmployeeAuthContextException ex) {
			return StepOutcome.failure(ex.getReasonCode(), ex.getMessage());
		}
		AgentRequest request = buildRequest(run, run.getOwnerId(), employee.getIamPrincipalId());
		DataAgentOutboundContext.Snapshot outbound = DataAgentOutboundContext.withPrincipalToken(
				DataAgentOutboundContext.get(), tokenContext.tokenValue(), tenantId);
		DataAgentAsyncContextBridge.Snapshot snapshot = asyncContextBridge.snapshotForDelegatedToken(
				tokenContext.tokenValue(), outbound);
		ScheduledFuture<?> heartbeat = startLeaseHeartbeat(execution);
		try {
			String answer = asyncContextBridge.supplyWith(snapshot, () -> {
				if (authenticationContext.getContext() == null) {
					throw CheckedException.badRequest("授权上下文未建立，员工 Principal 身份无法用于本次执行");
				}
				applyPrincipalAuthSnapshot(request);
				return agentInvocationService.invoke(request);
			});
			log.info("单轮任务执行完成. runId={}, stepKey={}, attemptNo={}, agentId={}", run.getId(),
					execution.step().getStepKey(), execution.attemptNo(), run.getOwnerId());
			return successWithStructuredArtifacts(answer, request);
		}
		catch (RuntimeException ex) {
			return failureOf(run, execution.step(), execution.attemptNo(), ex);
		}
		finally {
			if (heartbeat != null) {
				heartbeat.cancel(false);
			}
		}
	}

	private StepOutcome invokeOnce(StepExecution execution, AgentRequest request) {
		AgentRuntimeRun run = execution.run();
		ScheduledFuture<?> heartbeat = startLeaseHeartbeat(execution);
		try {
			String answer = agentInvocationService.invoke(request);
			log.info("单轮任务执行完成. runId={}, stepKey={}, attemptNo={}, agentId={}", run.getId(),
					execution.step().getStepKey(), execution.attemptNo(), request.getAgentId());
			return successWithStructuredArtifacts(answer, request);
		}
		catch (RuntimeException ex) {
			return failureOf(run, execution.step(), execution.attemptNo(), ex);
		}
		finally {
			if (heartbeat != null) {
				heartbeat.cancel(false);
			}
		}
	}

	/**
	 * 失败如实反映：中断（取消链路中断执行线程）单列错误码，交由调度器收敛为 CANCELLED；
	 * 其余异常保留原始消息，不吞异常、不返回空答案伪装成功。
	 */
	private StepOutcome failureOf(AgentRuntimeRun run, AgentRuntimeStep step, int attemptNo, RuntimeException ex) {
		recordTokenBudgetExceededIfPresent(run, ex);
		String message = StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName();
		if (Thread.currentThread().isInterrupted()) {
			log.warn("单轮任务执行被中断. runId={}, stepKey={}, attemptNo={}", run.getId(), step.getStepKey(), attemptNo,
					ex);
			return StepOutcome.failure("STEP_INTERRUPTED", message);
		}
		log.warn("单轮任务执行失败. runId={}, stepKey={}, attemptNo={}", run.getId(), step.getStepKey(), attemptNo, ex);
		return StepOutcome.failure("SINGLE_TURN_EXECUTION_FAILED", message);
	}

	/**
	 * Token 预算超限拒绝埋点（P1 告警 increase(token_budget_exceeded_total[1h])&gt;0 的唯一生产者）：
	 * invoke 链路抛出的 {@link AgentRuntimeBudgetExceededException}（含被包装在 cause 链里的场景，
	 * 如响应式链路异常包装）在置终态处计数；SHADOW/ENFORCE 均计数，指标经
	 * {@link AuthorizationMetrics#recordTokenBudgetExceeded} 统一注册，不散落 MeterRegistry 调用。
	 */
	private void recordTokenBudgetExceededIfPresent(AgentRuntimeRun run, Throwable error) {
		Throwable current = error;
		while (current != null) {
			if (current instanceof AgentRuntimeBudgetExceededException) {
				authorizationMetrics.recordTokenBudgetExceeded(parseTenantIdForMetrics(run.getTenantId()));
				return;
			}
			current = current.getCause();
		}
	}

	private Long parseTenantIdForMetrics(String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			return null;
		}
		try {
			return Long.valueOf(tenantId.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	/**
	 * Principal token 还原后写入数据权限快照。任务工具线程不再依赖现场登录与 sp_ 对齐。
	 */
	private void applyPrincipalAuthSnapshot(AgentRequest request) {
		try {
			request.setDataPermissionSnapshot(authenticationContext.dataPermission());
		}
		catch (Exception ex) {
			log.warn("数字员工任务无法解析 Principal 数据权限快照. runtimeRequestId={}", request.getRuntimeRequestId(),
					ex);
		}
		try {
			request.setUserNickNameSnapshot(authenticationContext.nickName());
		}
		catch (Exception ex) {
			log.debug("数字员工任务无法解析 Principal 昵称快照. runtimeRequestId={}", request.getRuntimeRequestId(), ex);
		}
		try {
			request.setTenantCodeSnapshot(authenticationContext.tenantCode());
		}
		catch (Exception ex) {
			log.debug("数字员工任务无法解析 Principal 租户编码快照. runtimeRequestId={}", request.getRuntimeRequestId(),
					ex);
		}
		try {
			request.setClientIdSnapshot(authenticationContext.clientId());
		}
		catch (Exception ex) {
			log.debug("数字员工任务无法解析 Principal 客户端快照. runtimeRequestId={}", request.getRuntimeRequestId(), ex);
		}
		try {
			request.setTeamIdsSnapshot(authenticationContext.teamIds());
		}
		catch (Exception ex) {
			log.debug("数字员工任务无法解析 Principal 团队快照. runtimeRequestId={}", request.getRuntimeRequestId(), ex);
		}
	}

	private AgentRequest buildRequest(AgentRuntimeRun run, Long agentId, String userIdSnapshot) {
		String runtimeRequestId = StringUtils.hasText(run.getRuntimeRequestId()) ? run.getRuntimeRequestId()
				: THREAD_ID_PREFIX + run.getId();
		return AgentRequest.builder()
			.agentId(String.valueOf(agentId))
			.threadId(StringUtils.hasText(run.getThreadId()) ? run.getThreadId() : THREAD_ID_PREFIX + run.getId())
			// 与权威 Run 同一 runtimeRequestId：取消写穿/回源按该键定位本次执行并中断线程
			.runtimeRequestId(runtimeRequestId)
			.rootRuntimeRequestId(runtimeRequestId)
			.durableRunId(run.getId())
			.query(run.getQuery())
			.requestSource(REQUEST_SOURCE)
			.ownerType(run.getOwnerType())
			.ownerId(run.getOwnerId())
			.releaseId(run.getReleaseId())
			.tenantIdSnapshot(run.getTenantId() == null ? null : String.valueOf(run.getTenantId()))
			.userIdSnapshot(userIdSnapshot)
			.build();
	}

	/**
	 * 结构化产物：调度器负责落 agent_runtime_artifact，这里只给出下游可读的 JSON。
	 * 序列化失败不吞：返回 null 让步骤按「无产物成功」落库并留下 error 日志。
	 */
	private StepOutcome successWithStructuredArtifacts(String answer, AgentRequest request) {
		String primary = answerArtifact(answer, request);
		try {
			return StepOutcome.success(primary, ARTIFACT_SCHEMA_VERSION, structuredWorkProductComposer.compose(request));
		}
		catch (RuntimeException ex) {
			log.warn("结构化交卷产物组装失败, 仍保留单轮回答. runtimeRequestId={}", request.getRuntimeRequestId(),
					ex);
			return StepOutcome.success(primary, ARTIFACT_SCHEMA_VERSION);
		}
	}

	private String answerArtifact(String answer, AgentRequest request) {
		Map<String, Object> artifact = new LinkedHashMap<>();
		artifact.put("answer", answer == null ? "" : answer);
		artifact.put("runtimeRequestId", request.getRuntimeRequestId());
		artifact.put("agentId", request.getAgentId());
		try {
			return objectMapper.writeValueAsString(artifact);
		}
		catch (Exception ex) {
			log.error("单轮任务产物序列化失败, 步骤将不落产物. runtimeRequestId={}", request.getRuntimeRequestId(), ex);
			return null;
		}
	}

	/**
	 * 挂上租约续期心跳：单轮 Agent 执行可能远超调度器认领时的租约时长，不续期会被守护作业
	 * 判定为过期并以更高 fence 接管，导致同一步骤被重复执行、本次结果因 fence 失效被丢弃。
	 *
	 * @return 心跳句柄；缺少租约持有者信息（未经调度器认领的直接调用）时返回 null，不续期
	 */
	private ScheduledFuture<?> startLeaseHeartbeat(StepExecution execution) {
		Long stepId = execution.step().getId();
		String owner = execution.step().getLeaseOwner();
		long fenceToken = execution.fenceToken();
		if (stepId == null || !StringUtils.hasText(owner)) {
			log.debug("步骤缺少租约持有者信息, 跳过租约续期. stepId={}", stepId);
			return null;
		}
		long intervalMillis = LEASE_RENEW_INTERVAL.toMillis();
		return LEASE_HEARTBEAT.scheduleAtFixedRate(() -> renewLease(stepId, owner, fenceToken), intervalMillis,
				intervalMillis, TimeUnit.MILLISECONDS);
	}

	/** 单次租约续期。包级可见以便单测直接驱动一拍心跳，而不必等待真实的续期间隔。 */
	void renewLease(Long stepId, String owner, long fenceToken) {
		try {
			if (!runtimeStateService.renewStepLease(stepId, owner, fenceToken, LEASE_RENEW_DURATION)) {
				// 续期失败说明租约已被他人接管或步骤已终态：本次执行结果稍后会因 fence 失效被丢弃
				log.warn("步骤租约续期未生效, 租约可能已被接管. stepId={}, owner={}, fenceToken={}", stepId, owner,
						fenceToken);
			}
		}
		catch (RuntimeException ex) {
			log.warn("步骤租约续期异常. stepId={}, owner={}, fenceToken={}", stepId, owner, fenceToken, ex);
		}
	}

}
