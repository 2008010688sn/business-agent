/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeArtifact;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRun;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeStep;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeStepAttempt;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeEventType;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeRunState;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeStepState;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeArtifactMapper;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeRunMapper;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeStepAttemptMapper;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeStepMapper;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeDagScheduler;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeEventService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeOutboxService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStateService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStateService.RunStateChange;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStateService.StepStateChange;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStateService.StepTransition;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStepExecutor;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStepExecutor.StepExecution;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStepExecutor.StepOutcome;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 持久运行时事件驱动 DAG 调度器实现。
 *
 * <p>调度不变量：</p>
 * <ul>
 * <li>一个步骤终态后立即重算下游就绪性并释放，不等待整批（替代 ready-batch barrier）。</li>
 * <li>步骤认领 = 租约（fence 递增）+ CAS READY→RUNNING，多节点同抢只有一个成功。</li>
 * <li>上游存在失败/取消/超时/跳过的下游级联 SKIPPED。</li>
 * <li>全部步骤终态后收敛 Run 终态：有失败即 FAILED，否则有取消即 CANCELLED，否则 SUCCEEDED；
 * 双写迁移期的镜像运行（sourceRunId 非空）例外，其终态语义由遥测状态映射经 RuntimeMirrorService 收敛。</li>
 * <li>复用既有 orchestrationExecutor 线程池，不新建线程池。</li>
 * </ul>
 *
 * <p>重试策略：步骤失败（非取消/中断、非结果未知）且 attempt_no 尚未达到 max_attempts 时，
 * 步骤回到 READY 并立即重新认领执行，新尝试分配新的 attempt_no；OUTCOME_UNKNOWN（外部副作用
 * 结果未知）永不自动重试，进对账。默认 max_attempts=1，行为与单次尝试一致。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeDagSchedulerImpl implements RuntimeDagScheduler {

	/**
	 * 步骤租约时长：覆盖单步协作者默认 30s 超时并留恢复余量；到期未续期即可被其他节点接管。
	 */
	private static final Duration STEP_LEASE_DURATION = Duration.ofMinutes(3);

	private static final int CAS_RETRY = 3;

	/**
	 * 本节点租约持有者标识（进程级唯一）。
	 */
	private static final String NODE_ID = buildNodeId();

	/**
	 * 节点内在途步骤（已提交尚未执行完）。dispatch 重算与 READY 重派会重复命中同一步骤，
	 * 重复提交会经「同 owner 重取租约」使在途执行的 fence 失效，必须在 JVM 层去重。
	 */
	private final Set<Long> inFlightStepIds = ConcurrentHashMap.newKeySet();

	private final AgentRuntimeRunMapper runMapper;

	private final AgentRuntimeStepMapper stepMapper;

	private final AgentRuntimeStepAttemptMapper attemptMapper;

	private final AgentRuntimeArtifactMapper artifactMapper;

	private final RuntimeStateService runtimeStateService;

	private final RuntimeEventService runtimeEventService;

	private final RuntimeOutboxService runtimeOutboxService;

	private final ObjectMapper objectMapper;

	@Qualifier("orchestrationExecutor")
	private final ExecutorService orchestrationExecutor;

	@Override
	public void start(Long runId, RuntimeStepExecutor executor) {
		if (runId == null || executor == null) {
			throw CheckedException.badRequest("调度启动参数不完整");
		}
		AgentRuntimeRun run = runMapper.selectById(runId);
		if (run == null) {
			throw CheckedException.notFound("运行不存在: " + runId);
		}
		RuntimeRunState state = RuntimeRunState.of(run.getState());
		if (state != null && state.terminal()) {
			log.info("运行已终态，跳过调度. runId={}, state={}", runId, run.getState());
			return;
		}
		if (state == RuntimeRunState.PENDING) {
			boolean started = runtimeStateService.transitionRunWithRetry(runId, RuntimeRunState.RUNNING,
					RunStateChange.started(Instant.now()), CAS_RETRY);
			if (started) {
				appendEventQuietly(run.getTenantId(), runId, "run-started", RuntimeEventType.RUN_STARTED, null, null);
			}
		}
		else if (state == RuntimeRunState.WAITING_INPUT || state == RuntimeRunState.WAITING_APPROVAL) {
			// 挂起运行的续跑入口：等待态回到 RUNNING 后再释放剩余步骤
			boolean resumed = runtimeStateService.transitionRunWithRetry(runId, RuntimeRunState.RUNNING,
					RunStateChange.none(), CAS_RETRY);
			if (resumed) {
				appendEventQuietly(run.getTenantId(), runId, "run-resumed:" + Instant.now().toEpochMilli(),
						RuntimeEventType.RUN_RESUMED, null, null);
			}
		}
		dispatchReadySteps(runId, executor);
		maybeFinishRun(runId);
	}

	@Override
	public void onStepTerminal(Long runId, String stepKey, RuntimeStepExecutor executor) {
		if (runId == null) {
			return;
		}
		// 事件驱动核心：单个步骤终态立即触发下游就绪计算，互不依赖的分支不会互相等待
		dispatchReadySteps(runId, executor);
		maybeFinishRun(runId);
	}

	private void dispatchReadySteps(Long runId, RuntimeStepExecutor executor) {
		AgentRuntimeRun run = runMapper.selectById(runId);
		if (run == null) {
			return;
		}
		if (isCancelTrack(run)) {
			cancelIdleSteps(run);
			return;
		}
		// 级联释放：一次 SKIPPED 可能立刻让更下游满足终态判定，循环直到无新变化
		boolean changed = true;
		int guard = 0;
		while (changed && guard++ < 64) {
			changed = false;
			List<AgentRuntimeStep> steps = stepMapper.listByRunId(runId);
			Map<String, AgentRuntimeStep> stepsByKey = steps.stream()
				.collect(Collectors.toMap(AgentRuntimeStep::getStepKey, Function.identity(), (first, second) -> first));
			for (AgentRuntimeStep step : steps) {
				RuntimeStepState stepState = RuntimeStepState.of(step.getState());
				if (stepState == RuntimeStepState.READY) {
					// 已释放但可能丢失执行任务的步骤（线程池拒绝后重派、重试回 READY）：
					// 重新提交是安全的，认领由租约 + fence 保证唯一
					submitStep(run.getId(), step.getId(), executor);
					continue;
				}
				if (stepState != RuntimeStepState.PENDING) {
					continue;
				}
				List<String> dependsOn = parseDependsOn(step);
				if (dependsOn == null) {
					markPendingStepTerminal(run, step, RuntimeStepState.FAILED, "DEPENDS_ON_PARSE_ERROR",
							"步骤依赖清单解析失败", RuntimeEventType.STEP_FAILED);
					changed = true;
					continue;
				}
				DependencyReadiness readiness = evaluate(dependsOn, stepsByKey);
				if (readiness == DependencyReadiness.MISSING) {
					markPendingStepTerminal(run, step, RuntimeStepState.FAILED, "MISSING_DEPENDENCY",
							"步骤依赖的上游步骤不存在", RuntimeEventType.STEP_FAILED);
					changed = true;
				}
				else if (readiness == DependencyReadiness.UPSTREAM_TERMINATED) {
					markPendingStepTerminal(run, step, RuntimeStepState.SKIPPED, "UPSTREAM_NOT_SUCCEEDED",
							"上游步骤未成功，级联跳过", RuntimeEventType.STEP_SKIPPED);
					changed = true;
				}
				else if (readiness == DependencyReadiness.READY && executor.canRelease(run, step)) {
					// 执行体门控（如交互独占、挂起停发）不放行时步骤保持 PENDING，下次终态事件重问
					if (releaseStep(run, step)) {
						submitStep(run.getId(), step.getId(), executor);
						changed = true;
					}
				}
			}
		}
	}

	/**
	 * PENDING → READY（CAS）并发出就绪事件。返回 false 表示被并发调度者抢先。
	 */
	private boolean releaseStep(AgentRuntimeRun run, AgentRuntimeStep step) {
		boolean released = runtimeStateService.transitionStep(new StepTransition(step.getId(), step.getStateVersion(),
				null, RuntimeStepState.READY, StepStateChange.none()));
		if (released) {
			appendEventQuietly(run.getTenantId(), run.getId(), "step:" + step.getStepKey() + ":ready",
					RuntimeEventType.STEP_READY, step.getStepKey(), null);
		}
		return released;
	}

	private void submitStep(Long runId, Long stepId, RuntimeStepExecutor executor) {
		// 节点内在途去重：dispatch 重算与 READY 重派可能重复命中同一步骤，重复提交会经
		// 「同 owner 重取租约」使正在执行的 fence 失效，必须在 JVM 层拦掉
		if (!inFlightStepIds.add(stepId)) {
			return;
		}
		try {
			orchestrationExecutor.execute(() -> {
				try {
					executeStep(runId, stepId, executor);
				}
				finally {
					inFlightStepIds.remove(stepId);
				}
			});
		}
		catch (RejectedExecutionException rejected) {
			inFlightStepIds.remove(stepId);
			log.error("编排线程池拒绝步骤任务. runId={}, stepId={}", runId, stepId, rejected);
			AgentRuntimeStep step = stepMapper.selectById(stepId);
			AgentRuntimeRun run = runMapper.selectById(runId);
			if (step != null && run != null) {
				markPendingStepTerminal(run, step, RuntimeStepState.FAILED, "SCHEDULER_REJECTED", "编排线程池已饱和",
						RuntimeEventType.STEP_FAILED);
				onStepTerminal(runId, step.getStepKey(), executor);
			}
		}
	}

	private void executeStep(Long runId, Long stepId, RuntimeStepExecutor executor) {
		// 失败重试在同一任务内串行推进（attempt_no 递增），直到终态/挂起/重试额度耗尽
		boolean retry = true;
		while (retry) {
			retry = executeStepAttempt(runId, stepId, executor);
		}
	}

	/**
	 * 执行一次步骤尝试。返回 true 表示步骤失败且允许自动重试，应立即开始下一次尝试。
	 */
	private boolean executeStepAttempt(Long runId, Long stepId, RuntimeStepExecutor executor) {
		AgentRuntimeRun run = runMapper.selectById(runId);
		AgentRuntimeStep step = stepMapper.selectById(stepId);
		if (run == null || step == null) {
			return false;
		}
		if (isCancelTrack(run)) {
			markPendingStepTerminal(run, step, RuntimeStepState.CANCELLED, "RUN_CANCELLED", "运行已请求取消",
					RuntimeEventType.STEP_CANCELLED);
			onStepTerminal(runId, step.getStepKey(), executor);
			return false;
		}
		// 只从 READY 认领；RUNNING 步骤绝不重取租约（同 owner 重取会使在途执行的 fence 失效）
		if (RuntimeStepState.of(step.getState()) != RuntimeStepState.READY) {
			return false;
		}
		// 认领：租约 + fence。两个节点同时认领同一步骤只能一个获得有效 fence。
		Long fenceToken = runtimeStateService.acquireStepLease(stepId, NODE_ID, STEP_LEASE_DURATION);
		if (fenceToken == null) {
			log.debug("步骤租约被他人持有，放弃执行. stepId={}", stepId);
			return false;
		}
		stepMapper.syncCancellationEpoch(stepId, run.getCancellationEpoch() == null ? 0L : run.getCancellationEpoch());
		AgentRuntimeStep claimed = stepMapper.selectById(stepId);
		if (claimed == null || RuntimeStepState.of(claimed.getState()) != RuntimeStepState.READY) {
			return false;
		}
		boolean running = runtimeStateService.transitionStep(new StepTransition(stepId, claimed.getStateVersion(),
				fenceToken, RuntimeStepState.RUNNING, StepStateChange.started(Instant.now())));
		if (!running) {
			log.debug("步骤 READY→RUNNING CAS 竞争失败. stepId={}", stepId);
			return false;
		}
		Integer attemptNo = runtimeStateService.allocateAttemptNo(stepId);
		int safeAttemptNo = attemptNo == null ? 1 : attemptNo;
		AgentRuntimeStepAttempt attempt = insertAttempt(run, claimed, safeAttemptNo, fenceToken);
		appendEventQuietly(run.getTenantId(), runId, "step:" + claimed.getStepKey() + ":started:" + safeAttemptNo,
				RuntimeEventType.STEP_STARTED, claimed.getStepKey(), Map.of("attemptNo", safeAttemptNo));
		StepOutcome outcome = invoke(executor, run, claimed, safeAttemptNo, fenceToken);
		StepFinishDisposition disposition = finishStep(run, claimed, attempt, safeAttemptNo, fenceToken, outcome);
		if (disposition == StepFinishDisposition.TERMINAL) {
			onStepTerminal(runId, claimed.getStepKey(), executor);
		}
		return disposition == StepFinishDisposition.RETRY;
	}

	private StepOutcome invoke(RuntimeStepExecutor executor, AgentRuntimeRun run, AgentRuntimeStep step, int attemptNo,
			long fenceToken) {
		try {
			StepOutcome outcome = executor.execute(new StepExecution(run, step, attemptNo, fenceToken));
			return outcome == null ? StepOutcome.failure("STEP_NO_OUTCOME", "步骤执行体未返回结果") : outcome;
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			// 本地中断只证明本地停止；外部副作用未知的场景由执行体先落 OUTCOME_UNKNOWN
			return StepOutcome.failure("STEP_INTERRUPTED", "步骤执行被中断");
		}
		catch (Exception ex) {
			log.error("步骤执行体抛出异常. runId={}, stepKey={}", run.getId(), step.getStepKey(), ex);
			return StepOutcome.failure("STEP_EXECUTION_ERROR", ex.getMessage());
		}
	}

	private StepFinishDisposition finishStep(AgentRuntimeRun run, AgentRuntimeStep step, AgentRuntimeStepAttempt attempt,
			int attemptNo, long fenceToken, StepOutcome outcome) {
		AgentRuntimeStep current = stepMapper.selectById(step.getId());
		if (current == null) {
			return StepFinishDisposition.LOST;
		}
		if (outcome.waiting()) {
			return holdStepWaiting(run, step, attempt, current, fenceToken);
		}
		if (shouldRetry(run, current, attemptNo, outcome)) {
			return requeueForRetry(run, step, attempt, current, attemptNo, fenceToken, outcome);
		}
		RuntimeStepState targetState;
		RuntimeEventType eventType;
		Long artifactId = null;
		if (outcome.success()) {
			artifactId = persistArtifact(run, step, outcome);
			targetState = RuntimeStepState.SUCCEEDED;
			eventType = RuntimeEventType.STEP_SUCCEEDED;
		}
		else if ("STEP_INTERRUPTED".equals(outcome.errorCode()) || "RUN_CANCELLED".equals(outcome.errorCode())) {
			targetState = RuntimeStepState.CANCELLED;
			eventType = RuntimeEventType.STEP_CANCELLED;
		}
		else if ("COLLABORATOR_TIMED_OUT".equals(outcome.errorCode())) {
			targetState = RuntimeStepState.TIMED_OUT;
			eventType = RuntimeEventType.STEP_TIMED_OUT;
		}
		else {
			targetState = RuntimeStepState.FAILED;
			eventType = RuntimeEventType.STEP_FAILED;
		}
		// 终态收敛：携带认领时的 fence，若租约已被接管（fence 过期）则本次写入失败，
		// 新持有者的结果才是有效终态（旧 worker 不能覆盖新 worker 的终态）。
		boolean converged = runtimeStateService.transitionStep(new StepTransition(step.getId(),
				current.getStateVersion(), fenceToken, targetState,
				StepStateChange.finished(outcome.errorCode(), outcome.errorMessage(), artifactId)));
		if (attempt != null && attempt.getId() != null) {
			attemptMapper.finishAttempt(attempt.getId(), targetState.getValue(), outcome.errorCode(),
					outcome.errorMessage(), Instant.now());
		}
		if (!converged) {
			log.warn("步骤终态 CAS 未生效（fence 已被接管或状态已终态）. stepId={}, target={}", step.getId(), targetState);
			return StepFinishDisposition.LOST;
		}
		appendEventQuietly(run.getTenantId(), run.getId(), "step:" + step.getStepKey() + ":finished",
				eventType, step.getStepKey(),
				Map.of("state", targetState.getValue(), "errorCode", outcome.errorCode() == null ? "" : outcome.errorCode()));
		return StepFinishDisposition.TERMINAL;
	}

	/**
	 * 步骤挂起等待人工交互：置 WAITING（非终态），不释放下游、不收敛 Run；
	 * 恢复链路（prepareScheduledSteps）会把交互结果收敛为该步骤的终态后继续调度。
	 */
	private StepFinishDisposition holdStepWaiting(AgentRuntimeRun run, AgentRuntimeStep step,
			AgentRuntimeStepAttempt attempt, AgentRuntimeStep current, long fenceToken) {
		boolean held = runtimeStateService.transitionStep(new StepTransition(step.getId(), current.getStateVersion(),
				fenceToken, RuntimeStepState.WAITING, StepStateChange.none()));
		if (attempt != null && attempt.getId() != null) {
			attemptMapper.finishAttempt(attempt.getId(), RuntimeStepState.WAITING.getValue(), null, null, Instant.now());
		}
		if (!held) {
			log.warn("步骤挂起 CAS 未生效（fence 已被接管或状态已变更）. stepId={}", step.getId());
			return StepFinishDisposition.LOST;
		}
		appendEventQuietly(run.getTenantId(), run.getId(),
				"step:" + step.getStepKey() + ":waiting:" + Instant.now().toEpochMilli(), RuntimeEventType.STEP_WAITING,
				step.getStepKey(), null);
		return StepFinishDisposition.WAITING;
	}

	/**
	 * 自动重试判定：仅普通失败可重试；取消/中断、OUTCOME_UNKNOWN（外部副作用结果未知，
	 * 必须进对账）与超时放弃不重试；attempt_no 达到 max_attempts 后进终态。
	 */
	private boolean shouldRetry(AgentRuntimeRun run, AgentRuntimeStep current, int attemptNo, StepOutcome outcome) {
		if (outcome.success() || isCancelTrack(run)) {
			return false;
		}
		String errorCode = outcome.errorCode();
		if ("STEP_INTERRUPTED".equals(errorCode) || "RUN_CANCELLED".equals(errorCode)
				|| "COLLABORATOR_TIMED_OUT".equals(errorCode) || "OUTCOME_UNKNOWN".equals(errorCode)) {
			return false;
		}
		int maxAttempts = current.getMaxAttempts() == null ? 1 : current.getMaxAttempts();
		return attemptNo < maxAttempts;
	}

	private StepFinishDisposition requeueForRetry(AgentRuntimeRun run, AgentRuntimeStep step,
			AgentRuntimeStepAttempt attempt, AgentRuntimeStep current, int attemptNo, long fenceToken,
			StepOutcome outcome) {
		// 失败但重试额度未耗尽：回 READY，记录最近失败原因但不落 finishedAt，新的认领分配新 attempt_no
		boolean requeued = runtimeStateService.transitionStep(new StepTransition(step.getId(),
				current.getStateVersion(), fenceToken, RuntimeStepState.READY,
				new StepStateChange(outcome.errorCode(), outcome.errorMessage(), null, null, null)));
		if (attempt != null && attempt.getId() != null) {
			attemptMapper.finishAttempt(attempt.getId(), RuntimeStepState.FAILED.getValue(), outcome.errorCode(),
					outcome.errorMessage(), Instant.now());
		}
		if (!requeued) {
			log.warn("步骤重试回 READY CAS 未生效（fence 已被接管或状态已变更）. stepId={}", step.getId());
			return StepFinishDisposition.LOST;
		}
		appendEventQuietly(run.getTenantId(), run.getId(), "step:" + step.getStepKey() + ":retry:" + attemptNo,
				RuntimeEventType.STEP_READY, step.getStepKey(),
				Map.of("retryOfAttemptNo", attemptNo, "errorCode", outcome.errorCode() == null ? "" : outcome.errorCode()));
		log.info("步骤失败进入自动重试. runId={}, stepKey={}, failedAttemptNo={}, maxAttempts={}", run.getId(),
				step.getStepKey(), attemptNo, current.getMaxAttempts());
		return StepFinishDisposition.RETRY;
	}

	private Long persistArtifact(AgentRuntimeRun run, AgentRuntimeStep step, StepOutcome outcome) {
		Long primaryId = insertArtifact(run, step, outcome.artifactSchemaVersion(), outcome.artifactJson());
		if (outcome.extraArtifacts() != null) {
			for (RuntimeStepExecutor.ExtraArtifact extra : outcome.extraArtifacts()) {
				if (extra == null) {
					continue;
				}
				insertArtifact(run, step, extra.schemaVersion(), extra.artifactJson());
			}
		}
		return primaryId;
	}

	private Long insertArtifact(AgentRuntimeRun run, AgentRuntimeStep step, String schemaVersion, String data) {
		if (!StringUtils.hasText(data)) {
			return null;
		}
		AgentRuntimeArtifact artifact = AgentRuntimeArtifact.builder()
			.tenantId(run.getTenantId())
			.runId(run.getId())
			.stepKey(step.getStepKey())
			.schemaVersion(schemaVersion)
			.data(data)
			.sensitivity("INTERNAL")
			.createTime(Instant.now())
			.lastModifyTime(Instant.now())
			.deleted(false)
			.build();
		artifactMapper.insert(artifact);
		return artifact.getId();
	}

	private AgentRuntimeStepAttempt insertAttempt(AgentRuntimeRun run, AgentRuntimeStep step, int attemptNo,
			long fenceToken) {
		try {
			AgentRuntimeStepAttempt attempt = AgentRuntimeStepAttempt.builder()
				.tenantId(run.getTenantId())
				.runId(run.getId())
				.stepId(step.getId())
				.attemptNo(attemptNo)
				.state(RuntimeStepState.RUNNING.getValue())
				.leaseOwner(NODE_ID)
				.fenceToken(fenceToken)
				.startedAt(Instant.now())
				.createTime(Instant.now())
				.lastModifyTime(Instant.now())
				.deleted(false)
				.build();
			attemptMapper.insert(attempt);
			return attempt;
		}
		catch (RuntimeException ex) {
			// 尝试记录是审计性质数据，失败不阻断步骤执行，但必须可见
			log.error("步骤尝试记录写入失败. stepId={}, attemptNo={}", step.getId(), attemptNo, ex);
			return null;
		}
	}

	/**
	 * 未开始执行的步骤（PENDING/READY）与挂起等待交互的步骤（WAITING）直接落终态；
	 * RUNNING 步骤由其执行线程按取消纪元自行收敛。
	 */
	private void cancelIdleSteps(AgentRuntimeRun run) {
		for (AgentRuntimeStep step : stepMapper.listByRunId(run.getId())) {
			RuntimeStepState state = RuntimeStepState.of(step.getState());
			if (state == RuntimeStepState.PENDING || state == RuntimeStepState.READY
					|| state == RuntimeStepState.WAITING) {
				markPendingStepTerminal(run, step, RuntimeStepState.CANCELLED, "RUN_CANCELLED", "运行已请求取消",
						RuntimeEventType.STEP_CANCELLED);
			}
		}
		maybeFinishRun(run.getId());
	}

	private void markPendingStepTerminal(AgentRuntimeRun run, AgentRuntimeStep step, RuntimeStepState targetState,
			String errorCode, String errorMessage, RuntimeEventType eventType) {
		boolean converged = runtimeStateService.transitionStepWithRetry(step.getId(), targetState,
				StepStateChange.finished(errorCode, errorMessage, null), CAS_RETRY);
		if (converged) {
			appendEventQuietly(run.getTenantId(), run.getId(), "step:" + step.getStepKey() + ":finished", eventType,
					step.getStepKey(), Map.of("state", targetState.getValue(), "errorCode", errorCode));
		}
	}

	private void maybeFinishRun(Long runId) {
		AgentRuntimeRun run = runMapper.selectById(runId);
		if (run == null) {
			return;
		}
		RuntimeRunState runState = RuntimeRunState.of(run.getState());
		if (runState == null || runState.terminal()) {
			return;
		}
		// 双写迁移期的镜像运行（sourceRunId 非空）终态语义由遥测状态映射决定
		// （如 PARTIAL_SUCCESS→SUCCEEDED），由 RuntimeMirrorService 在编排收尾时收敛，
		// 调度器的「有失败即 FAILED」规则只适用于原生运行
		if (run.getSourceRunId() != null) {
			return;
		}
		List<AgentRuntimeStep> steps = stepMapper.listByRunId(runId);
		// 无步骤的运行（计划未编译或纯对话）不由调度器收敛，由其执行引擎直接收敛
		if (steps.isEmpty()) {
			return;
		}
		boolean allTerminal = true;
		boolean anyFailed = false;
		boolean anyCancelled = false;
		for (AgentRuntimeStep step : steps) {
			RuntimeStepState state = RuntimeStepState.of(step.getState());
			if (state == null || !state.terminal()) {
				allTerminal = false;
				break;
			}
			if (state == RuntimeStepState.FAILED || state == RuntimeStepState.TIMED_OUT) {
				anyFailed = true;
			}
			if (state == RuntimeStepState.CANCELLED) {
				anyCancelled = true;
			}
		}
		if (!allTerminal) {
			return;
		}
		RuntimeRunState target = anyFailed ? RuntimeRunState.FAILED
				: anyCancelled ? RuntimeRunState.CANCELLED : RuntimeRunState.SUCCEEDED;
		RuntimeEventType eventType = anyFailed ? RuntimeEventType.RUN_FAILED
				: anyCancelled ? RuntimeEventType.RUN_CANCELLED : RuntimeEventType.RUN_SUCCEEDED;
		boolean finished = runtimeStateService.transitionRunWithRetry(runId, target,
				RunStateChange.finished(null, anyFailed ? "STEP_FAILED" : null, anyFailed ? "存在失败步骤" : null),
				CAS_RETRY);
		if (finished) {
			appendEventQuietly(run.getTenantId(), runId, "run-finished", eventType, null,
					Map.of("state", target.getValue()));
			appendRunTerminalOutboxQuietly(run, target, eventType, anyFailed ? "STEP_FAILED" : null);
		}
	}

	/**
	 * run 终态迁移 CAS 成功后旁路写 Outbox（IM 等消费方按 eventKey 幂等去重）。
	 * 负载只含状态与错误码，不放业务原文；写入失败仅记 error，不影响终态收敛。
	 */
	private void appendRunTerminalOutboxQuietly(AgentRuntimeRun run, RuntimeRunState state, RuntimeEventType eventType,
			String errorCode) {
		try {
			Map<String, Object> payload = errorCode == null ? Map.of("state", state.getValue())
					: Map.of("state", state.getValue(), "errorCode", errorCode);
			runtimeOutboxService.append(new RuntimeOutboxService.OutboxAppend(run.getTenantId(), null,
					run.getId(), null, eventType.getValue(),
					"run-terminal:" + run.getId() + ":" + eventType.getValue(), payload));
		}
		catch (RuntimeException ex) {
			log.error("运行终态 Outbox 写入失败, 终态已收敛不受影响. runId={}, eventType={}", run.getId(),
					eventType.getValue(), ex);
		}
	}

	private boolean isCancelTrack(AgentRuntimeRun run) {
		RuntimeRunState state = RuntimeRunState.of(run.getState());
		return state == RuntimeRunState.CANCELLING || state == RuntimeRunState.CANCELLED;
	}

	private DependencyReadiness evaluate(List<String> dependsOn, Map<String, AgentRuntimeStep> stepsByKey) {
		for (String depKey : dependsOn) {
			AgentRuntimeStep dep = stepsByKey.get(depKey);
			if (dep == null) {
				return DependencyReadiness.MISSING;
			}
			RuntimeStepState depState = RuntimeStepState.of(dep.getState());
			if (depState == RuntimeStepState.SUCCEEDED) {
				continue;
			}
			if (depState != null && depState.terminal()) {
				return DependencyReadiness.UPSTREAM_TERMINATED;
			}
			return DependencyReadiness.WAITING;
		}
		return DependencyReadiness.READY;
	}

	/**
	 * 解析步骤依赖清单。返回 null 表示 JSON 非法（调用方应将步骤置为失败而不是静默忽略依赖）。
	 */
	private List<String> parseDependsOn(AgentRuntimeStep step) {
		String json = step.getDependsOn();
		if (!StringUtils.hasText(json)) {
			return List.of();
		}
		try {
			List<String> deps = objectMapper.readValue(json, new TypeReference<List<String>>() {
			});
			return deps == null ? List.of() : deps;
		}
		catch (Exception ex) {
			log.error("步骤依赖清单解析失败. stepId={}, dependsOn={}", step.getId(), json, ex);
			return null;
		}
	}

	private void appendEventQuietly(String tenantId, Long runId, String eventKey, RuntimeEventType eventType,
			String stepKey, Map<String, Object> payload) {
		try {
			runtimeEventService.append(tenantId, runId, eventKey, eventType, stepKey, payload);
		}
		catch (RuntimeException ex) {
			// 事件是状态变更的附属记录，主状态已 CAS 生效；失败必须可见但不中断调度
			log.error("调度事件追加失败. runId={}, eventKey={}", runId, eventKey, ex);
		}
	}

	private static String buildNodeId() {
		String host;
		try {
			host = InetAddress.getLocalHost().getHostName();
		}
		catch (Exception ex) {
			host = "node";
		}
		return host + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	/**
	 * 依赖就绪评估结果。
	 */
	private enum DependencyReadiness {

		READY, WAITING, UPSTREAM_TERMINATED, MISSING

	}

	/**
	 * 步骤收尾处置：TERMINAL 触发下游释放；WAITING 挂起等待交互；RETRY 立即开始下一次尝试；
	 * LOST 表示 fence 已被接管或状态被并发变更，本执行体的结果不再有效。
	 */
	private enum StepFinishDisposition {

		TERMINAL, WAITING, RETRY, LOST

	}

}
