/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service.impl;

import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRun;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeStep;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeRunState;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeStepState;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeRunMapper;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeStepMapper;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStateService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 持久运行时状态机服务实现。
 *
 * <p>本服务刻意不使用 @Transactional：所有写入都是单条 CAS/原子 UPDATE，幂等与互斥由
 * state_version、fence_token、唯一约束在数据库端保证；把重试式 CAS 放进单个 PostgreSQL
 * 事务反而会在首次冲突后使整个事务进入 aborted 状态，无法继续重试。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeStateServiceImpl implements RuntimeStateService {

	private final AgentRuntimeRunMapper runMapper;

	private final AgentRuntimeStepMapper stepMapper;

	@Override
	public boolean transitionRun(RunTransition transition) {
		if (transition == null || transition.runId() == null || transition.expectedVersion() == null
				|| transition.toState() == null) {
			throw CheckedException.badRequest("Run 状态迁移参数不完整");
		}
		RunStateChange change = transition.change() == null ? RunStateChange.none() : transition.change();
		int updated = runMapper.casState(transition.runId(), transition.expectedVersion(), transition.fenceToken(),
				transition.toState().getValue(), change.finalAnswer(), change.errorCode(), change.errorMessage(),
				change.startedAt(), change.finishedAt(), change.deadlineAt());
		return updated > 0;
	}

	@Override
	public boolean transitionRunWithRetry(Long runId, RuntimeRunState toState, RunStateChange change, int maxRetries) {
		if (runId == null || toState == null) {
			throw CheckedException.badRequest("Run 状态迁移参数不完整");
		}
		int attempts = Math.max(1, maxRetries);
		for (int i = 0; i < attempts; i++) {
			AgentRuntimeRun run = runMapper.selectById(runId);
			if (run == null) {
				return false;
			}
			RuntimeRunState current = RuntimeRunState.of(run.getState());
			if (current != null && current.terminal()) {
				return false;
			}
			if (transitionRun(new RunTransition(runId, run.getStateVersion(), null, toState, change))) {
				return true;
			}
		}
		log.warn("Run 状态 CAS 重试耗尽. runId={}, toState={}", runId, toState);
		return false;
	}

	@Override
	public Long acquireRunLease(Long runId, String owner, Duration leaseDuration) {
		requireLeaseArgs(runId, owner, leaseDuration);
		Instant now = Instant.now();
		return runMapper.acquireLease(runId, owner, now.plus(leaseDuration), now);
	}

	@Override
	public boolean renewRunLease(Long runId, String owner, Long fenceToken, Duration leaseDuration) {
		requireLeaseArgs(runId, owner, leaseDuration);
		if (fenceToken == null) {
			throw CheckedException.badRequest("续期租约必须携带 fence_token");
		}
		return runMapper.renewLease(runId, owner, fenceToken, Instant.now().plus(leaseDuration)) > 0;
	}

	@Override
	public boolean releaseRunLease(Long runId, String owner, Long fenceToken) {
		if (runId == null || owner == null || owner.isBlank() || fenceToken == null) {
			throw CheckedException.badRequest("释放租约参数不完整");
		}
		return runMapper.releaseLease(runId, owner, fenceToken) > 0;
	}

	@Override
	public Long bumpCancellationEpoch(Long runId) {
		if (runId == null) {
			throw CheckedException.badRequest("runId 不能为空");
		}
		return runMapper.bumpCancellationEpoch(runId);
	}

	@Override
	public boolean transitionStep(StepTransition transition) {
		if (transition == null || transition.stepId() == null || transition.expectedVersion() == null
				|| transition.toState() == null) {
			throw CheckedException.badRequest("Step 状态迁移参数不完整");
		}
		StepStateChange change = transition.change() == null ? StepStateChange.none() : transition.change();
		int updated = stepMapper.casState(transition.stepId(), transition.expectedVersion(), transition.fenceToken(),
				transition.toState().getValue(), change.errorCode(), change.errorMessage(), change.outputArtifactId(),
				change.startedAt(), change.finishedAt());
		return updated > 0;
	}

	@Override
	public boolean transitionStepWithRetry(Long stepId, RuntimeStepState toState, StepStateChange change,
			int maxRetries) {
		if (stepId == null || toState == null) {
			throw CheckedException.badRequest("Step 状态迁移参数不完整");
		}
		int attempts = Math.max(1, maxRetries);
		for (int i = 0; i < attempts; i++) {
			AgentRuntimeStep step = stepMapper.selectById(stepId);
			if (step == null) {
				return false;
			}
			RuntimeStepState current = RuntimeStepState.of(step.getState());
			if (current != null && current.terminal()) {
				return false;
			}
			if (transitionStep(new StepTransition(stepId, step.getStateVersion(), null, toState, change))) {
				return true;
			}
		}
		log.warn("Step 状态 CAS 重试耗尽. stepId={}, toState={}", stepId, toState);
		return false;
	}

	@Override
	public Long acquireStepLease(Long stepId, String owner, Duration leaseDuration) {
		requireLeaseArgs(stepId, owner, leaseDuration);
		Instant now = Instant.now();
		return stepMapper.acquireLease(stepId, owner, now.plus(leaseDuration), now);
	}

	@Override
	public boolean renewStepLease(Long stepId, String owner, Long fenceToken, Duration leaseDuration) {
		requireLeaseArgs(stepId, owner, leaseDuration);
		if (fenceToken == null) {
			throw CheckedException.badRequest("续期租约必须携带 fence_token");
		}
		return stepMapper.renewLease(stepId, owner, fenceToken, Instant.now().plus(leaseDuration)) > 0;
	}

	@Override
	public boolean releaseStepLease(Long stepId, String owner, Long fenceToken) {
		if (stepId == null || owner == null || owner.isBlank() || fenceToken == null) {
			throw CheckedException.badRequest("释放租约参数不完整");
		}
		return stepMapper.releaseLease(stepId, owner, fenceToken) > 0;
	}

	@Override
	public Integer allocateAttemptNo(Long stepId) {
		if (stepId == null) {
			throw CheckedException.badRequest("stepId 不能为空");
		}
		return stepMapper.allocateAttemptNo(stepId);
	}

	private void requireLeaseArgs(Long id, String owner, Duration leaseDuration) {
		if (id == null || owner == null || owner.isBlank() || leaseDuration == null || leaseDuration.isZero()
				|| leaseDuration.isNegative()) {
			throw CheckedException.badRequest("租约参数不完整");
		}
	}

}
