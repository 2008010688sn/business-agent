/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sn68.agent.dataagent.runtime.durable.support;

import cn.hutool.core.bean.BeanUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeArtifact;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRun;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeStep;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeStepAttempt;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeRunState;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeStepState;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeArtifactMapper;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeRunMapper;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeStepAttemptMapper;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeStepMapper;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeDagScheduler;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeEventService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeMirrorService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeOutboxService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStateService;
import com.sn68.agent.dataagent.runtime.durable.service.impl.RuntimeDagSchedulerImpl;
import com.sn68.agent.dataagent.runtime.durable.service.impl.RuntimeMirrorServiceImpl;
import com.sn68.agent.dataagent.runtime.durable.service.impl.RuntimeStateServiceImpl;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * 内存版权威运行时夹具：以 Mockito answer 精确复刻 Run/Step Mapper 的 CAS、租约、
 * 尝试分配等原子 SQL 语义，让真实的 {@code RuntimeStateServiceImpl}、
 * {@code RuntimeDagSchedulerImpl} 与 {@code RuntimeMirrorServiceImpl} 在无数据库环境下
 * 按生产行为运行，供事件驱动调度相关测试使用。
 */
public final class InMemoryDurableRuntime {

	private final AtomicLong idSequence = new AtomicLong(1000L);

	private final Map<Long, AgentRuntimeRun> runs = new ConcurrentHashMap<>();

	private final Map<Long, AgentRuntimeStep> steps = new ConcurrentHashMap<>();

	private final Map<Long, AgentRuntimeStepAttempt> attempts = new ConcurrentHashMap<>();

	private final Map<Long, AgentRuntimeArtifact> artifacts = new ConcurrentHashMap<>();

	private final List<String> eventKeys = Collections.synchronizedList(new ArrayList<>());

	/**
	 * 记录型 Outbox：按调用顺序收集全部 append 载体，供终态旁路写入断言。
	 */
	private final List<RuntimeOutboxService.OutboxAppend> outboxAppends = Collections
		.synchronizedList(new ArrayList<>());

	private final Object lock = new Object();

	public final AgentRuntimeRunMapper runMapper;

	public final AgentRuntimeStepMapper stepMapper;

	public final AgentRuntimeStepAttemptMapper attemptMapper;

	public final AgentRuntimeArtifactMapper artifactMapper;

	public final RuntimeStateService stateService;

	public final RuntimeEventService eventService;

	public final RuntimeOutboxService outboxService;

	public InMemoryDurableRuntime() {
		this.runMapper = buildRunMapper();
		this.stepMapper = buildStepMapper();
		this.attemptMapper = buildAttemptMapper();
		this.artifactMapper = buildArtifactMapper();
		this.stateService = new RuntimeStateServiceImpl(runMapper, stepMapper);
		this.eventService = buildEventService();
		this.outboxService = buildOutboxService();
	}

	public RuntimeDagScheduler scheduler(ExecutorService executor) {
		return new RuntimeDagSchedulerImpl(runMapper, stepMapper, attemptMapper, artifactMapper, stateService,
				eventService, outboxService, new ObjectMapper(), executor);
	}

	public RuntimeMirrorService mirror() {
		return new RuntimeMirrorServiceImpl(runMapper, stepMapper, stateService, eventService, outboxService,
				new ObjectMapper());
	}

	/**
	 * 按顺序返回已写入 Outbox 的载体快照。
	 */
	public List<RuntimeOutboxService.OutboxAppend> outboxAppends() {
		synchronized (outboxAppends) {
			return List.copyOf(outboxAppends);
		}
	}

	private RuntimeOutboxService buildOutboxService() {
		return new RuntimeOutboxService() {
			@Override
			public Long append(OutboxAppend append) {
				outboxAppends.add(append);
				return (long) outboxAppends.size();
			}

			@Override
			public int dispatchPending(int limit) {
				return 0;
			}
		};
	}

	/**
	 * 预置一条 RUNNING 的权威 Run 镜像（sourceRunId 关联遥测 Run），返回权威 Run ID。
	 */
	public Long seedMirroredRun(Long sourceRunId, String tenantId) {
		AgentRuntimeRun run = AgentRuntimeRun.builder()
			.tenantId(tenantId)
			// PR-1：镜像 Run 落 owner=PLATFORM 命名空间，与 RuntimeMirrorServiceImpl 写入口径一致
			.ownerType("PLATFORM")
			.agentId(1L)
			.sourceRunId(sourceRunId)
			.runtimeRequestId("runtime-" + sourceRunId)
			.state(RuntimeRunState.RUNNING.getValue())
			.stateVersion(0L)
			.fenceToken(0L)
			.cancellationEpoch(0L)
			.startedAt(Instant.now())
			.createTime(Instant.now())
			.lastModifyTime(Instant.now())
			.deleted(false)
			.build();
		run.setId(idSequence.incrementAndGet());
		runs.put(run.getId(), run);
		return run.getId();
	}

	/**
	 * 预置一条原生（非镜像）PENDING 权威 Run，调度器负责其终态收敛。
	 */
	public Long seedNativeRun(String tenantId) {
		AgentRuntimeRun run = AgentRuntimeRun.builder()
			.tenantId(tenantId)
			// PR-1：原生 Run 按 create 默认口径落 owner=CALLER
			.ownerType("CALLER")
			.agentId(1L)
			.runtimeRequestId("native-" + idSequence.incrementAndGet())
			.state(RuntimeRunState.PENDING.getValue())
			.stateVersion(0L)
			.fenceToken(0L)
			.cancellationEpoch(0L)
			.createTime(Instant.now())
			.lastModifyTime(Instant.now())
			.deleted(false)
			.build();
		run.setId(idSequence.incrementAndGet());
		runs.put(run.getId(), run);
		return run.getId();
	}

	/**
	 * 预置一条任务链路形态的原生 Run（无步骤、无 agentId，只有数字员工归属与任务描述），
	 * 用于验证守护作业拉起与单步执行体。
	 */
	public Long seedTaskRun(String tenantId, String query, Long digitalEmployeeId, Long releaseId) {
		Instant now = Instant.now();
		AgentRuntimeRun run = AgentRuntimeRun.builder()
			.tenantId(tenantId)
			.ownerType("DIGITAL_EMPLOYEE")
			.ownerId(digitalEmployeeId)
			.digitalEmployeeId(digitalEmployeeId)
			.releaseId(releaseId)
			.clientRequestId("SCHEDULE:" + idSequence.incrementAndGet())
			.runtimeRequestId("task-" + idSequence.incrementAndGet())
			.triggerSource("API")
			.runMode("AGENT_LOOP")
			.query(query)
			.state(RuntimeRunState.PENDING.getValue())
			.stateVersion(0L)
			.fenceToken(0L)
			.cancellationEpoch(0L)
			.createTime(now)
			.lastModifyTime(now)
			.deleted(false)
			.build();
		run.setId(idSequence.incrementAndGet());
		runs.put(run.getId(), run);
		return run.getId();
	}

	public Long seedChatRun(String tenantId, String threadId, RuntimeRunState state) {
		Instant now = Instant.now();
		AgentRuntimeRun run = AgentRuntimeRun.builder()
			.tenantId(tenantId)
			.ownerType("CALLER")
			.threadId(threadId)
			.clientRequestId("CHAT:" + threadId + ":" + idSequence.incrementAndGet())
			.runtimeRequestId("chat-" + idSequence.incrementAndGet())
			.triggerSource("CHAT")
			.runMode("CHAT")
			.query("问数")
			.state(state.getValue())
			.stateVersion(0L)
			.fenceToken(0L)
			.cancellationEpoch(0L)
			.createTime(now)
			.lastModifyTime(now)
			.deleted(false)
			.build();
		run.setId(idSequence.incrementAndGet());
		runs.put(run.getId(), run);
		return run.getId();
	}

	/**
	 * 把 Run 改成指定状态与最后修改时间，用于构造「已被其他副本启动」「停摆已久」等场景。
	 */
	public void forceRunState(Long runId, RuntimeRunState state, Instant lastModifyTime) {
		synchronized (lock) {
			AgentRuntimeRun run = runs.get(runId);
			if (run != null) {
				run.setState(state.getValue());
				run.setLastModifyTime(lastModifyTime);
			}
		}
	}

	/**
	 * 设置运行的绝对截止时间，用于构造「已过 deadline」场景。
	 */
	public void forceDeadline(Long runId, Instant deadlineAt) {
		synchronized (lock) {
			AgentRuntimeRun run = runs.get(runId);
			if (run != null) {
				run.setDeadlineAt(deadlineAt);
			}
		}
	}

	/**
	 * 把步骤改成 RUNNING 并指定租约持有者与到期时间（leaseUntil 取过去时刻即模拟节点崩溃遗留），
	 * 返回该步骤当时的 fence。
	 */
	public Long forceRunningLease(Long stepId, String owner, Instant leaseUntil) {
		synchronized (lock) {
			AgentRuntimeStep step = steps.get(stepId);
			step.setState(RuntimeStepState.RUNNING.getValue());
			step.setLeaseOwner(owner);
			step.setLeaseUntil(leaseUntil);
			step.setFenceToken(step.getFenceToken() == null ? 1L : step.getFenceToken() + 1);
			return step.getFenceToken();
		}
	}

	public AgentRuntimeStep stepById(Long stepId) {
		AgentRuntimeStep step = steps.get(stepId);
		return step == null ? null : copyStep(step);
	}

	/**
	 * 预置一条 PENDING 权威步骤行，返回步骤 ID。
	 */
	public Long seedStep(Long runId, String stepKey, List<String> dependsOn, int maxAttempts) {
		String dependsOnJson;
		try {
			dependsOnJson = new ObjectMapper().writeValueAsString(dependsOn == null ? List.of() : dependsOn);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
		AgentRuntimeStep step = AgentRuntimeStep.builder()
			.tenantId("7")
			.runId(runId)
			.stepKey(stepKey)
			.stepName("step-" + stepKey)
			.dependsOn(dependsOnJson)
			.inputBindings("[]")
			.state(RuntimeStepState.PENDING.getValue())
			.stateVersion(0L)
			.fenceToken(0L)
			.cancellationEpoch(0L)
			.attemptCount(0)
			.maxAttempts(maxAttempts)
			.createTime(Instant.now())
			.lastModifyTime(Instant.now())
			.deleted(false)
			.build();
		step.setId(idSequence.incrementAndGet());
		steps.put(step.getId(), step);
		return step.getId();
	}

	public AgentRuntimeRun runById(Long runId) {
		AgentRuntimeRun run = runs.get(runId);
		return run == null ? null : copyRun(run);
	}

	public AgentRuntimeStep stepByKey(Long runId, String stepKey) {
		synchronized (lock) {
			return steps.values().stream()
				.filter(step -> Objects.equals(step.getRunId(), runId) && Objects.equals(step.getStepKey(), stepKey))
				.findFirst()
				.map(this::copyStep)
				.orElse(null);
		}
	}

	public List<AgentRuntimeStepAttempt> attemptsOfStep(Long stepId) {
		synchronized (lock) {
			return attempts.values().stream()
				.filter(attempt -> Objects.equals(attempt.getStepId(), stepId))
				.sorted(Comparator.comparing(AgentRuntimeStepAttempt::getAttemptNo))
				.toList();
		}
	}

	public List<AgentRuntimeArtifact> artifactsOfRun(Long runId) {
		synchronized (lock) {
			return artifacts.values().stream()
				.filter(artifact -> Objects.equals(artifact.getRunId(), runId))
				.toList();
		}
	}

	public List<String> eventKeys() {
		return List.copyOf(eventKeys);
	}

	private AgentRuntimeRunMapper buildRunMapper() {
		AgentRuntimeRunMapper mapper = mock(AgentRuntimeRunMapper.class, withSettings().stubOnly());
		doAnswer(invocation -> {
			AgentRuntimeRun run = runs.get(invocation.<Long>getArgument(0));
			return run == null ? null : copyRun(run);
		}).when(mapper).selectById(anyLong());
		doAnswer(invocation -> {
			Long sourceRunId = invocation.getArgument(0);
			synchronized (lock) {
				return runs.values().stream()
					.filter(run -> Objects.equals(run.getSourceRunId(), sourceRunId))
					.max(Comparator.comparing(AgentRuntimeRun::getId))
					.map(this::copyRun)
					.orElse(null);
			}
		}).when(mapper).findBySourceRunId(anyLong());
		doAnswer(invocation -> {
			synchronized (lock) {
				AgentRuntimeRun run = runs.get(invocation.<Long>getArgument(0));
				Long expectedVersion = invocation.getArgument(1);
				Long fenceToken = invocation.getArgument(2);
				String toState = invocation.getArgument(3);
				if (run == null || RuntimeRunState.of(run.getState()) != null
						&& RuntimeRunState.of(run.getState()).terminal()
						|| !Objects.equals(run.getStateVersion(), expectedVersion)
						|| fenceToken != null && !Objects.equals(run.getFenceToken(), fenceToken)) {
					return 0;
				}
				run.setState(toState);
				run.setStateVersion(run.getStateVersion() + 1);
				run.setFinalAnswer(coalesce(invocation.getArgument(4), run.getFinalAnswer()));
				run.setErrorCode(coalesce(invocation.getArgument(5), run.getErrorCode()));
				run.setErrorMessage(coalesce(invocation.getArgument(6), run.getErrorMessage()));
				run.setStartedAt(run.getStartedAt() != null ? run.getStartedAt() : invocation.getArgument(7));
				run.setFinishedAt(coalesce(invocation.getArgument(8), run.getFinishedAt()));
				run.setDeadlineAt(coalesce(invocation.getArgument(9), run.getDeadlineAt()));
				// 与 SQL 的 last_modify_time = CURRENT_TIMESTAMP 对齐，守护作业的停摆判定依赖该列
				run.setLastModifyTime(Instant.now());
				return 1;
			}
		}).when(mapper).casState(anyLong(), anyLong(), nullable(Long.class), any(), nullable(String.class),
				nullable(String.class), nullable(String.class), nullable(Instant.class), nullable(Instant.class),
				nullable(Instant.class));
		// 复刻取消纪元递增 SQL：终态运行不再递增（返回 null），非终态返回递增后的纪元
		doAnswer(invocation -> {
			synchronized (lock) {
				AgentRuntimeRun run = runs.get(invocation.<Long>getArgument(0));
				RuntimeRunState state = run == null ? null : RuntimeRunState.of(run.getState());
				if (run == null || state != null && state.terminal()) {
					return null;
				}
				run.setCancellationEpoch(run.getCancellationEpoch() == null ? 1L : run.getCancellationEpoch() + 1);
				return run.getCancellationEpoch();
			}
		}).when(mapper).bumpCancellationEpoch(anyLong());
		doAnswer(invocation -> listStartableRuns(invocation.getArgument(0), invocation.getArgument(1),
				invocation.getArgument(2))).when(mapper).listStartableRuns(any(Instant.class), any(Instant.class),
						org.mockito.ArgumentMatchers.anyInt());
		doAnswer(invocation -> listExpiredDeadlineRuns(invocation.getArgument(0), invocation.getArgument(1)))
			.when(mapper).listExpiredDeadlineRuns(any(Instant.class), org.mockito.ArgumentMatchers.anyInt());
		doAnswer(invocation -> listExpiredWaitingChatRuns(invocation.getArgument(0), invocation.getArgument(1)))
			.when(mapper).listExpiredWaitingChatRuns(any(Instant.class), org.mockito.ArgumentMatchers.anyInt());
		return mapper;
	}

	/**
	 * 复刻守护作业扫描 SQL：未过 deadline 的原生单轮运行中，PENDING 或停摆 RUNNING，
	 * 且没有任何步骤处于 RUNNING/WAITING。
	 */
	private List<AgentRuntimeRun> listStartableRuns(Instant now, Instant staleBefore, int limit) {
		synchronized (lock) {
			return runs.values().stream()
				.filter(run -> run.getSourceRunId() == null && startableRunMode(run.getRunMode()))
				.filter(run -> run.getDeadlineAt() == null || run.getDeadlineAt().isAfter(now))
				.filter(run -> startableRunState(run, staleBefore))
				.filter(run -> !hasInFlightStep(run.getId()))
				.sorted(Comparator.comparing(AgentRuntimeRun::getId))
				.limit(limit)
				.map(this::copyRun)
				.toList();
		}
	}

	/**
	 * 复刻守护作业超时收敛扫描 SQL：已过 deadline 的非终态（且非 CANCELLING）原生单轮运行，
	 * 且没有任何步骤持有仍然有效的租约（有效租约表示执行节点仍在续期心跳，不得误判超时）。
	 */
	private List<AgentRuntimeRun> listExpiredDeadlineRuns(Instant now, int limit) {
		synchronized (lock) {
			return runs.values().stream()
				.filter(run -> run.getSourceRunId() == null)
				.filter(run -> run.getDeadlineAt() != null && !run.getDeadlineAt().isAfter(now))
				.filter(run -> expiredDeadlineEligible(run, now))
				.sorted(Comparator.comparing(AgentRuntimeRun::getId))
				.limit(limit)
				.map(this::copyRun)
				.toList();
		}
	}

	private boolean expiredDeadlineEligible(AgentRuntimeRun run, Instant now) {
		if (startableRunMode(run.getRunMode())) {
			return expirableRunState(run.getState()) && !hasLiveLeaseStep(run.getId(), now);
		}
		if (!"CHAT".equals(run.getRunMode())) {
			return false;
		}
		RuntimeRunState state = RuntimeRunState.of(run.getState());
		if (state != RuntimeRunState.PENDING && state != RuntimeRunState.RUNNING
				&& state != RuntimeRunState.CANCELLING) {
			return false;
		}
		return run.getLeaseUntil() == null || !run.getLeaseUntil().isAfter(now);
	}

	private List<AgentRuntimeRun> listExpiredWaitingChatRuns(Instant staleBefore, int limit) {
		synchronized (lock) {
			return runs.values().stream()
				.filter(run -> run.getSourceRunId() == null)
				.filter(run -> "CHAT".equals(run.getRunMode()))
				.filter(run -> RuntimeRunState.WAITING_INPUT.getValue().equals(run.getState())
						|| RuntimeRunState.WAITING_APPROVAL.getValue().equals(run.getState()))
				.filter(run -> run.getLastModifyTime() != null && !run.getLastModifyTime().isAfter(staleBefore))
				.sorted(Comparator.comparing(AgentRuntimeRun::getId))
				.limit(limit)
				.map(this::copyRun)
				.toList();
		}
	}

	private boolean expirableRunState(String state) {
		RuntimeRunState runState = RuntimeRunState.of(state);
		return runState != null && !runState.terminal() && runState != RuntimeRunState.CANCELLING;
	}

	private boolean hasLiveLeaseStep(Long runId, Instant now) {
		return steps.values().stream()
			.filter(step -> Objects.equals(step.getRunId(), runId))
			.filter(step -> RuntimeStepState.RUNNING.getValue().equals(step.getState())
					|| RuntimeStepState.WAITING.getValue().equals(step.getState()))
			.anyMatch(step -> step.getLeaseUntil() != null && step.getLeaseUntil().isAfter(now));
	}

	private boolean startableRunMode(String runMode) {
		return "AGENT_LOOP".equals(runMode) || "DIRECT".equals(runMode);
	}

	private boolean startableRunState(AgentRuntimeRun run, Instant staleBefore) {
		if (RuntimeRunState.PENDING.getValue().equals(run.getState())) {
			return true;
		}
		return RuntimeRunState.RUNNING.getValue().equals(run.getState()) && run.getLastModifyTime() != null
				&& run.getLastModifyTime().isBefore(staleBefore);
	}

	private boolean hasInFlightStep(Long runId) {
		return steps.values().stream()
			.anyMatch(step -> Objects.equals(step.getRunId(), runId)
					&& (RuntimeStepState.RUNNING.getValue().equals(step.getState())
							|| RuntimeStepState.WAITING.getValue().equals(step.getState())));
	}

	private AgentRuntimeStepMapper buildStepMapper() {
		AgentRuntimeStepMapper mapper = mock(AgentRuntimeStepMapper.class, withSettings().stubOnly());
		doAnswer(invocation -> {
			AgentRuntimeStep step = steps.get(invocation.<Long>getArgument(0));
			return step == null ? null : copyStep(step);
		}).when(mapper).selectById(anyLong());
		doAnswer(invocation -> {
			AgentRuntimeStep step = invocation.getArgument(0);
			synchronized (lock) {
				boolean duplicated = steps.values().stream()
					.anyMatch(existing -> Objects.equals(existing.getRunId(), step.getRunId())
							&& Objects.equals(existing.getStepKey(), step.getStepKey()));
				if (duplicated) {
					throw new org.springframework.dao.DuplicateKeyException("(run_id, step_key) duplicated");
				}
				step.setId(idSequence.incrementAndGet());
				steps.put(step.getId(), copyStep(step));
			}
			return 1;
		}).when(mapper).insert(any(AgentRuntimeStep.class));
		doAnswer(invocation -> {
			Long runId = invocation.getArgument(0);
			synchronized (lock) {
				return steps.values().stream()
					.filter(step -> Objects.equals(step.getRunId(), runId))
					.sorted(Comparator.comparing(AgentRuntimeStep::getId))
					.map(this::copyStep)
					.toList();
			}
		}).when(mapper).listByRunId(anyLong());
		doAnswer(invocation -> {
			Long runId = invocation.getArgument(0);
			String stepKey = invocation.getArgument(1);
			synchronized (lock) {
				return steps.values().stream()
					.filter(step -> Objects.equals(step.getRunId(), runId)
							&& Objects.equals(step.getStepKey(), stepKey))
					.findFirst()
					.map(this::copyStep)
					.orElse(null);
			}
		}).when(mapper).findByRunIdAndStepKey(anyLong(), any());
		doAnswer(this::stepCasState).when(mapper).casState(anyLong(), anyLong(), nullable(Long.class), any(),
				nullable(String.class), nullable(String.class), nullable(Long.class), nullable(Instant.class),
				nullable(Instant.class));
		doAnswer(invocation -> {
			synchronized (lock) {
				AgentRuntimeStep step = steps.get(invocation.<Long>getArgument(0));
				String owner = invocation.getArgument(1);
				Instant until = invocation.getArgument(2);
				Instant now = invocation.getArgument(3);
				if (step == null || RuntimeStepState.of(step.getState()) != null
						&& RuntimeStepState.of(step.getState()).terminal()) {
					return null;
				}
				boolean available = step.getLeaseOwner() == null || step.getLeaseUntil() == null
						|| step.getLeaseUntil().isBefore(now) || Objects.equals(step.getLeaseOwner(), owner);
				if (!available) {
					return null;
				}
				step.setLeaseOwner(owner);
				step.setLeaseUntil(until);
				step.setFenceToken(step.getFenceToken() + 1);
				return step.getFenceToken();
			}
		}).when(mapper).acquireLease(anyLong(), any(), any(Instant.class), any(Instant.class));
		doAnswer(invocation -> {
			synchronized (lock) {
				AgentRuntimeStep step = steps.get(invocation.<Long>getArgument(0));
				if (step == null) {
					return null;
				}
				step.setAttemptCount(step.getAttemptCount() == null ? 1 : step.getAttemptCount() + 1);
				return step.getAttemptCount();
			}
		}).when(mapper).allocateAttemptNo(anyLong());
		doAnswer(invocation -> {
			synchronized (lock) {
				AgentRuntimeStep step = steps.get(invocation.<Long>getArgument(0));
				Long epoch = invocation.getArgument(1);
				if (step == null || step.getCancellationEpoch() != null && step.getCancellationEpoch() >= epoch) {
					return 0;
				}
				step.setCancellationEpoch(epoch);
				return 1;
			}
		}).when(mapper).syncCancellationEpoch(anyLong(), anyLong());
		doAnswer(invocation -> {
			synchronized (lock) {
				AgentRuntimeStep step = steps.get(invocation.<Long>getArgument(0));
				String owner = invocation.getArgument(1);
				Long fenceToken = invocation.getArgument(2);
				if (step == null || !Objects.equals(step.getLeaseOwner(), owner)
						|| !Objects.equals(step.getFenceToken(), fenceToken)) {
					return 0;
				}
				step.setLeaseUntil(invocation.getArgument(3));
				return 1;
			}
		}).when(mapper).renewLease(anyLong(), any(), anyLong(), any(Instant.class));
		doAnswer(invocation -> {
			synchronized (lock) {
				AgentRuntimeStep step = steps.get(invocation.<Long>getArgument(0));
				String owner = invocation.getArgument(1);
				Long fenceToken = invocation.getArgument(2);
				if (step == null || !Objects.equals(step.getLeaseOwner(), owner)
						|| !Objects.equals(step.getFenceToken(), fenceToken)) {
					return 0;
				}
				// 与 SQL 一致：只清持有者与到期时间，不回退 fence_token
				step.setLeaseOwner(null);
				step.setLeaseUntil(null);
				return 1;
			}
		}).when(mapper).releaseLease(anyLong(), any(), anyLong());
		doAnswer(invocation -> listExpiredLeaseStepIds(invocation.getArgument(0), invocation.getArgument(1)))
			.when(mapper).listExpiredLeaseStepIds(any(Instant.class), org.mockito.ArgumentMatchers.anyInt());
		return mapper;
	}

	/**
	 * 复刻守护作业扫描 SQL：RUNNING 且租约已过期的步骤，且所属运行仍是 RUNNING 的原生单轮运行。
	 */
	private List<Long> listExpiredLeaseStepIds(Instant now, int limit) {
		synchronized (lock) {
			return steps.values().stream()
				.filter(step -> RuntimeStepState.RUNNING.getValue().equals(step.getState()))
				.filter(step -> step.getLeaseUntil() != null && step.getLeaseUntil().isBefore(now))
				.filter(step -> reclaimableRun(step.getRunId(), now))
				.sorted(Comparator.comparing(AgentRuntimeStep::getLeaseUntil))
				.limit(limit)
				.map(AgentRuntimeStep::getId)
				.toList();
		}
	}

	private boolean reclaimableRun(Long runId, Instant now) {
		AgentRuntimeRun run = runs.get(runId);
		return run != null && RuntimeRunState.RUNNING.getValue().equals(run.getState()) && run.getSourceRunId() == null
				&& startableRunMode(run.getRunMode())
				&& (run.getDeadlineAt() == null || run.getDeadlineAt().isAfter(now));
	}

	private Object stepCasState(org.mockito.invocation.InvocationOnMock invocation) {
		synchronized (lock) {
			AgentRuntimeStep step = steps.get(invocation.<Long>getArgument(0));
			Long expectedVersion = invocation.getArgument(1);
			Long fenceToken = invocation.getArgument(2);
			String toState = invocation.getArgument(3);
			if (step == null || RuntimeStepState.of(step.getState()) != null
					&& RuntimeStepState.of(step.getState()).terminal()
					|| !Objects.equals(step.getStateVersion(), expectedVersion)
					|| fenceToken != null && !Objects.equals(step.getFenceToken(), fenceToken)) {
				return 0;
			}
			step.setState(toState);
			step.setStateVersion(step.getStateVersion() + 1);
			step.setErrorCode(coalesce(invocation.getArgument(4), step.getErrorCode()));
			step.setErrorMessage(coalesce(invocation.getArgument(5), step.getErrorMessage()));
			step.setOutputArtifactId(coalesce(invocation.getArgument(6), step.getOutputArtifactId()));
			step.setStartedAt(step.getStartedAt() != null ? step.getStartedAt() : invocation.getArgument(7));
			step.setFinishedAt(coalesce(invocation.getArgument(8), step.getFinishedAt()));
			return 1;
		}
	}

	private AgentRuntimeStepAttemptMapper buildAttemptMapper() {
		AgentRuntimeStepAttemptMapper mapper = mock(AgentRuntimeStepAttemptMapper.class, withSettings().stubOnly());
		doAnswer(invocation -> {
			AgentRuntimeStepAttempt attempt = invocation.getArgument(0);
			synchronized (lock) {
				attempt.setId(idSequence.incrementAndGet());
				attempts.put(attempt.getId(), attempt);
			}
			return 1;
		}).when(mapper).insert(any(AgentRuntimeStepAttempt.class));
		doAnswer(invocation -> {
			synchronized (lock) {
				AgentRuntimeStepAttempt attempt = attempts.get(invocation.<Long>getArgument(0));
				if (attempt == null || !RuntimeStepState.RUNNING.getValue().equals(attempt.getState())) {
					return 0;
				}
				attempt.setState(invocation.getArgument(1));
				attempt.setErrorCode(invocation.getArgument(2));
				attempt.setErrorMessage(invocation.getArgument(3));
				attempt.setFinishedAt(invocation.getArgument(4));
				return 1;
			}
		}).when(mapper).finishAttempt(anyLong(), any(), nullable(String.class), nullable(String.class),
				any(Instant.class));
		return mapper;
	}

	private AgentRuntimeArtifactMapper buildArtifactMapper() {
		AgentRuntimeArtifactMapper mapper = mock(AgentRuntimeArtifactMapper.class, withSettings().stubOnly());
		doAnswer(invocation -> {
			AgentRuntimeArtifact artifact = invocation.getArgument(0);
			synchronized (lock) {
				artifact.setId(idSequence.incrementAndGet());
				artifacts.put(artifact.getId(), artifact);
			}
			return 1;
		}).when(mapper).insert(any(AgentRuntimeArtifact.class));
		return mapper;
	}

	private RuntimeEventService buildEventService() {
		RuntimeEventService service = mock(RuntimeEventService.class, withSettings().stubOnly());
		doAnswer(invocation -> {
			eventKeys.add(invocation.getArgument(2));
			return true;
		}).when(service).append(nullable(String.class), anyLong(), any(), any(), nullable(String.class), any());
		doAnswer(invocation -> {
			eventKeys.add(invocation.getArgument(2));
			return true;
		}).when(service).appendFenced(nullable(String.class), anyLong(), any(), any(), nullable(String.class), any(),
				anyLong(), any());
		return service;
	}

	private AgentRuntimeRun copyRun(AgentRuntimeRun source) {
		synchronized (lock) {
			AgentRuntimeRun copy = new AgentRuntimeRun();
			BeanUtil.copyProperties(source, copy);
			return copy;
		}
	}

	private AgentRuntimeStep copyStep(AgentRuntimeStep source) {
		AgentRuntimeStep copy = new AgentRuntimeStep();
		BeanUtil.copyProperties(source, copy);
		return copy;
	}

	private <T> T coalesce(T preferred, T fallback) {
		return preferred != null ? preferred : fallback;
	}

}
