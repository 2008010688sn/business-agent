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
package com.sn68.agent.dataagent.runtime.durable.service.impl;

import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeStep;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeStepAttempt;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeRunState;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeStepState;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeDagScheduler;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeOutboxService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStepExecutor;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStepExecutor.StepOutcome;
import com.sn68.agent.dataagent.runtime.durable.support.InMemoryDurableRuntime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 事件驱动调度器的重试策略测试：max_attempts 内失败自动重试并分配新 attempt_no，
 * OUTCOME_UNKNOWN 与取消永不自动重试，默认 max_attempts=1 保持单次尝试行为。
 */
class RuntimeDagSchedulerImplTest {

	private final InMemoryDurableRuntime durable = new InMemoryDurableRuntime();

	private final ExecutorService pool = Executors.newFixedThreadPool(2);

	private final RuntimeDagScheduler scheduler = durable.scheduler(pool);

	@AfterEach
	void tearDown() {
		pool.shutdownNow();
	}

	@Test
	void failedStepRetriesUntilMaxAttemptsAndSucceeds() throws Exception {
		Long runId = durable.seedNativeRun("7");
		Long stepId = durable.seedStep(runId, "s1", List.of(), 2);
		AtomicInteger invocations = new AtomicInteger();
		RuntimeStepExecutor executor = execution -> invocations.incrementAndGet() == 1
				? StepOutcome.failure("BOOM", "first attempt failed")
				: StepOutcome.success("{\"answer\":\"ok\"}", "collaborator-result/v1");

		scheduler.start(runId, executor);

		pollUntil(() -> RuntimeStepState.SUCCEEDED.getValue().equals(stepState(runId)), "step did not succeed");
		assertEquals(2, invocations.get());
		List<AgentRuntimeStepAttempt> attempts = durable.attemptsOfStep(stepId);
		assertEquals(2, attempts.size());
		assertEquals(1, attempts.get(0).getAttemptNo());
		assertEquals(RuntimeStepState.FAILED.getValue(), attempts.get(0).getState());
		assertEquals(2, attempts.get(1).getAttemptNo());
		assertEquals(RuntimeStepState.SUCCEEDED.getValue(), attempts.get(1).getState());
		pollUntil(() -> RuntimeRunState.SUCCEEDED.getValue().equals(durable.runById(runId).getState()),
				"native run was not converged");
	}

	@Test
	void failedStepStopsAtMaxAttemptsAndCascadesSkip() throws Exception {
		Long runId = durable.seedNativeRun("7");
		durable.seedStep(runId, "s1", List.of(), 2);
		durable.seedStep(runId, "s2", List.of("s1"), 1);
		AtomicInteger invocations = new AtomicInteger();
		RuntimeStepExecutor executor = execution -> {
			invocations.incrementAndGet();
			return StepOutcome.failure("BOOM", "always failing");
		};

		scheduler.start(runId, executor);

		pollUntil(() -> RuntimeStepState.FAILED.getValue().equals(stepState(runId, "s1")),
				"step did not converge to FAILED");
		pollUntil(() -> RuntimeStepState.SKIPPED.getValue().equals(stepState(runId, "s2")),
				"downstream was not skipped");
		assertEquals(2, invocations.get());
		pollUntil(() -> RuntimeRunState.FAILED.getValue().equals(durable.runById(runId).getState()),
				"native run was not converged to FAILED");
	}

	@Test
	void outcomeUnknownIsNeverRetriedAutomatically() throws Exception {
		Long runId = durable.seedNativeRun("7");
		Long stepId = durable.seedStep(runId, "s1", List.of(), 3);
		AtomicInteger invocations = new AtomicInteger();
		RuntimeStepExecutor executor = execution -> {
			invocations.incrementAndGet();
			return StepOutcome.failure("OUTCOME_UNKNOWN", "外部副作用结果未知");
		};

		scheduler.start(runId, executor);

		pollUntil(() -> RuntimeStepState.FAILED.getValue().equals(stepState(runId)),
				"outcome-unknown step did not converge to FAILED");
		assertEquals(1, invocations.get());
		assertEquals(1, durable.attemptsOfStep(stepId).size());
	}

	@Test
	void defaultSingleAttemptKeepsCurrentBehavior() throws Exception {
		Long runId = durable.seedNativeRun("7");
		Long stepId = durable.seedStep(runId, "s1", List.of(), 1);
		AtomicInteger invocations = new AtomicInteger();
		RuntimeStepExecutor executor = execution -> {
			invocations.incrementAndGet();
			return StepOutcome.failure("BOOM", "failed once");
		};

		scheduler.start(runId, executor);

		pollUntil(() -> RuntimeStepState.FAILED.getValue().equals(stepState(runId)),
				"step did not converge to FAILED");
		assertEquals(1, invocations.get());
		assertEquals(1, durable.attemptsOfStep(stepId).size());
	}

	@Test
	void runTerminalConvergenceWritesOutboxOnceWithIdempotentEventKey() throws Exception {
		Long runId = durable.seedNativeRun("7");
		durable.seedStep(runId, "s1", List.of(), 1);
		RuntimeStepExecutor executor = execution -> StepOutcome.success("{\"answer\":\"ok\"}",
				"collaborator-result/v1");

		scheduler.start(runId, executor);

		pollUntil(() -> RuntimeRunState.SUCCEEDED.getValue().equals(durable.runById(runId).getState()),
				"native run was not converged");
		List<RuntimeOutboxService.OutboxAppend> appends = durable.outboxAppends();
		assertEquals(1, appends.size());
		RuntimeOutboxService.OutboxAppend append = appends.get(0);
		assertEquals("RUN_SUCCEEDED", append.eventType());
		assertEquals("run-terminal:" + runId + ":RUN_SUCCEEDED", append.eventKey());
		assertEquals(runId, append.runId());
		assertEquals(7L, append.tenantId());
		// 负载只含状态，不带最终答案等业务原文
		assertEquals(Map.of("state", RuntimeRunState.SUCCEEDED.getValue()), append.payload());

		// 已终态的运行重复进入调度（如恢复链路重入）是幂等空操作，不产生第二条 outbox
		scheduler.start(runId, executor);
		assertEquals(1, durable.outboxAppends().size());
	}

	@Test
	void failedRunTerminalWritesOutboxWithErrorCodeOnly() throws Exception {
		Long runId = durable.seedNativeRun("7");
		durable.seedStep(runId, "s1", List.of(), 1);
		RuntimeStepExecutor executor = execution -> StepOutcome.failure("BOOM", "内部失败细节不应外泄");

		scheduler.start(runId, executor);

		pollUntil(() -> RuntimeRunState.FAILED.getValue().equals(durable.runById(runId).getState()),
				"native run was not converged to FAILED");
		List<RuntimeOutboxService.OutboxAppend> appends = durable.outboxAppends();
		assertEquals(1, appends.size());
		assertEquals("RUN_FAILED", appends.get(0).eventType());
		assertEquals("run-terminal:" + runId + ":RUN_FAILED", appends.get(0).eventKey());
		// 只有状态与错误码，不带步骤错误信息原文
		assertEquals(Map.of("state", RuntimeRunState.FAILED.getValue(), "errorCode", "STEP_FAILED"),
				appends.get(0).payload());
	}

	private String stepState(Long runId) {
		return stepState(runId, "s1");
	}

	private String stepState(Long runId, String stepKey) {
		AgentRuntimeStep step = durable.stepByKey(runId, stepKey);
		return step == null ? null : step.getState();
	}

	private static void pollUntil(Supplier<Boolean> condition, String message) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 3000L;
		while (System.currentTimeMillis() < deadline) {
			if (Boolean.TRUE.equals(condition.get())) {
				return;
			}
			Thread.sleep(20L);
		}
		throw new AssertionError(message);
	}

}
