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
package com.sn68.agent.dataagent.service.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.entity.AgentCollaborator;
import com.sn68.agent.dataagent.entity.AgentOrchestrationRun;
import com.sn68.agent.dataagent.entity.AgentOrchestrationStep;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.routing.model.DelegationMode;
import com.sn68.agent.dataagent.routing.v2.PlanCompiler;
import com.sn68.agent.dataagent.routing.v2.ProposalForbiddenFieldGuard;
import com.sn68.agent.dataagent.routing.v2.RouteProposalV2Parser;
import com.sn68.agent.dataagent.routing.v2.model.CompiledPlan;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimePlan;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeStep;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeStepState;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimePlanMapper;
import com.sn68.agent.dataagent.runtime.durable.service.CompiledPlanPersistenceService;
import com.sn68.agent.dataagent.runtime.durable.support.InMemoryDurableRuntime;
import com.sn68.agent.dataagent.service.agent.orchestration.EventDrivenCollaboratorEngine.CollaboratorStepRunner;
import com.sn68.agent.dataagent.service.agent.orchestration.EventDrivenCollaboratorEngine.InFlightHandle;
import com.sn68.agent.dataagent.service.routing.RoutePendingService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 事件驱动协作者执行引擎测试：真实调度器 + 内存权威表夹具，覆盖单分支即时释放下游、
 * 阻断级联、fail_fast、交互挂起、交互独占、恢复续跑、超时放弃与回落条件。
 */
class EventDrivenCollaboratorEngineTest {

	private final InMemoryDurableRuntime durable = new InMemoryDurableRuntime();

	private final ExecutorService schedulerPool = Executors.newFixedThreadPool(4);

	private final ExecutorService callerPool = Executors.newSingleThreadExecutor();

	private final DurableCompiledPlanActivator compiledPlanActivator = mock(DurableCompiledPlanActivator.class);

	private final EventDrivenCollaboratorEngine engine = new EventDrivenCollaboratorEngine(durable.mirror(),
			durable.scheduler(schedulerPool), new ObjectMapper(), compiledPlanActivator);

	private final AgentOrchestrationRun legacyRun = AgentOrchestrationRun.builder().id(100L).build();

	@AfterEach
	void tearDown() {
		callerPool.shutdownNow();
		schedulerPool.shutdownNow();
	}

	@Test
	void downstreamStartsImmediatelyAfterItsOwnBranchWithoutWaitingForSlowSibling() throws Exception {
		Long runtimeRunId = durable.seedMirroredRun(100L, "7");
		CountDownLatch releaseSlow = new CountDownLatch(1);
		CountDownLatch downstreamStarted = new CountDownLatch(1);
		TestRunner runner = new TestRunner();
		CollaboratorRoute slow = route("a1", List.of());
		CollaboratorRoute fast = route("b1", List.of());
		CollaboratorRoute downstream = route("b2", List.of("b1"));
		runner.onRun("a1", () -> {
			awaitQuietly(releaseSlow, 5);
			return success(slow, "slow-answer");
		});
		runner.onRun("b1", () -> success(fast, "fast-answer"));
		runner.onRun("b2", () -> {
			downstreamStarted.countDown();
			return success(downstream, "downstream-answer");
		});
		try {
			Future<List<CollaboratorExecutionResult>> resultFuture = callerPool
				.submit(() -> engine.tryExecute(legacyRun, List.of(slow, fast, downstream), Map.of(), false, runner));

			// 整批 barrier 会等 a1/b1 全部完成才放 b2；事件驱动下 b1 一完成 b2 就该启动
			assertTrue(downstreamStarted.await(3, TimeUnit.SECONDS),
					"downstream did not start before the slow sibling completed");
			releaseSlow.countDown();
			List<CollaboratorExecutionResult> results = resultFuture.get(5, TimeUnit.SECONDS);
			assertEquals(3, results.size());
			assertEquals("slow-answer", results.get(0).answer());
			assertEquals("fast-answer", results.get(1).answer());
			assertEquals("downstream-answer", results.get(2).answer());
			// 权威步骤终态由调度器在工作线程收敛，可能晚于引擎结果返回，轮询断言
			pollUntil(() -> RuntimeStepState.SUCCEEDED.getValue()
				.equals(durable.stepByKey(runtimeRunId, "b2").getState()),
					"authoritative downstream step did not converge to SUCCEEDED");
		}
		finally {
			releaseSlow.countDown();
		}
	}

	@Test
	void failedDependencyMarksDownstreamWithoutExecutingUnderContinuePolicy() throws Exception {
		Long runtimeRunId = durable.seedMirroredRun(100L, "7");
		TestRunner runner = new TestRunner();
		CollaboratorRoute first = route("s1", List.of());
		CollaboratorRoute second = route("s2", List.of("s1"));
		runner.onRun("s1", () -> failure(first, "prerequisite failed"));
		runner.onRun("s2", () -> success(second, "should-not-run"));

		List<CollaboratorExecutionResult> results = engine.tryExecute(legacyRun, List.of(first, second), Map.of(),
				false, runner);

		assertEquals(2, results.size());
		assertFalse(results.get(0).success());
		assertFalse(results.get(1).success());
		assertEquals("前置步骤执行失败，当前协作者未执行", results.get(1).errorMessage());
		assertEquals(List.of("s1"), runner.executed);
		assertEquals(List.of("s2"), runner.unexecuted);
		pollUntil(() -> RuntimeStepState.SKIPPED.getValue().equals(durable.stepByKey(runtimeRunId, "s2").getState()),
				"authoritative downstream step was not skipped");
	}

	@Test
	void failFastPropagatesFirstFailureWithoutMarkingDownstream() {
		durable.seedMirroredRun(100L, "7");
		TestRunner runner = new TestRunner();
		CollaboratorRoute first = route("s1", List.of());
		CollaboratorRoute second = route("s2", List.of("s1"));
		runner.onRun("s1", () -> failure(first, "boom"));
		runner.onRun("s2", () -> success(second, "should-not-run"));

		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> engine.tryExecute(legacyRun, List.of(first, second), Map.of(), true, runner));

		assertEquals("boom", error.getMessage());
		assertEquals(List.of("s1"), runner.executed);
		assertTrue(runner.unexecuted.isEmpty(), "fail_fast must not mark downstream as blocked");
	}

	@Test
	void waitingInteractiveCollaboratorSuspendsAndKeepsUnstartedStepsPending() throws Exception {
		Long runtimeRunId = durable.seedMirroredRun(100L, "7");
		TestRunner runner = new TestRunner();
		CollaboratorRoute interactive = interactiveRoute("i1", List.of());
		CollaboratorRoute normal = route("n2", List.of());
		runner.interactiveSteps.add("i1");
		runner.onRun("i1", () -> waiting(interactive));
		runner.onRun("n2", () -> success(normal, "never"));

		List<CollaboratorExecutionResult> results = engine.tryExecute(legacyRun, List.of(interactive, normal),
				Map.of(), false, runner);

		assertEquals(1, results.size());
		assertTrue(results.get(0).waiting());
		assertEquals(List.of("i1"), runner.executed);
		// 权威步骤挂起态由调度器在工作线程收敛，可能晚于引擎结果返回，轮询断言
		pollUntil(() -> RuntimeStepState.WAITING.getValue().equals(durable.stepByKey(runtimeRunId, "i1").getState()),
				"authoritative interactive step did not converge to WAITING");
		// 未启动路由保持 PENDING，恢复续跑时由调度器重新释放
		assertEquals(RuntimeStepState.PENDING.getValue(), durable.stepByKey(runtimeRunId, "n2").getState());
	}

	@Test
	void interactiveCollaboratorNeverOverlapsOtherSteps() throws Exception {
		durable.seedMirroredRun(100L, "7");
		TestRunner runner = new TestRunner();
		CollaboratorRoute interactive = interactiveRoute("i1", List.of());
		CollaboratorRoute normal = route("n1", List.of());
		runner.interactiveSteps.add("i1");
		runner.onRun("i1", () -> {
			sleepQuietly(150);
			return success(interactive, "interactive-answer");
		});
		runner.onRun("n1", () -> {
			sleepQuietly(150);
			return success(normal, "normal-answer");
		});

		List<CollaboratorExecutionResult> results = engine.tryExecute(legacyRun, List.of(interactive, normal),
				Map.of(), false, runner);

		assertEquals(2, results.size());
		assertTrue(results.stream().allMatch(CollaboratorExecutionResult::success));
		assertEquals(1, runner.maxConcurrent.get(), "interactive collaborator overlapped another step");
	}

	@Test
	void restoredResultsFeedDependenciesWithoutReExecution() {
		Long runtimeRunId = durable.seedMirroredRun(100L, "7");
		TestRunner runner = new TestRunner();
		CollaboratorRoute first = route("s1", List.of());
		CollaboratorRoute second = route("s2", List.of("s1"));
		CollaboratorExecutionResult restored = success(first, "restored-answer");
		runner.onRun("s2", () -> success(second, "fresh-answer"));

		List<CollaboratorExecutionResult> results = engine.tryExecute(legacyRun, List.of(first, second),
				Map.of("s1", restored), false, runner);

		assertEquals(2, results.size());
		assertEquals("restored-answer", results.get(0).answer());
		assertEquals("fresh-answer", results.get(1).answer());
		assertEquals(List.of("s2"), runner.executed);
		assertEquals("restored-answer", runner.dependencyAnswers.get("s2"));
		assertEquals(RuntimeStepState.SUCCEEDED.getValue(), durable.stepByKey(runtimeRunId, "s1").getState());
	}

	@Test
	void lastCollaboratorTimeoutDoesNotFailWhenParentBudgetIsExhausted() throws Exception {
		durable.seedMirroredRun(100L, "7");
		TestRunner runner = new TestRunner();
		CollaboratorRoute hanging = route("a1", List.of());
		runner.budgetMillisByStep.put("a1", 150L);
		runner.throwWhenBudgetCheckedAfterTimeout = true;
		runner.onRun("a1", () -> {
			sleepQuietly(10_000);
			return failure(hanging, "协作者已被取消");
		});

		List<CollaboratorExecutionResult> results = engine.tryExecute(legacyRun, List.of(hanging), Map.of(), false,
				runner);

		assertEquals(1, results.size());
		assertEquals("协作者执行超时", results.get(0).errorMessage());
		assertEquals(List.of("a1"), runner.abandonedTimedOut);
	}

	@Test
	void timedOutCollaboratorIsAbandonedWhileSiblingContinues() throws Exception {
		Long runtimeRunId = durable.seedMirroredRun(100L, "7");
		TestRunner runner = new TestRunner();
		CollaboratorRoute hanging = route("a1", List.of());
		CollaboratorRoute sibling = route("b1", List.of());
		runner.budgetMillisByStep.put("a1", 150L);
		runner.onRun("a1", () -> {
			sleepQuietly(10_000);
			return failure(hanging, "协作者已被取消");
		});
		runner.onRun("b1", () -> success(sibling, "sibling-answer"));

		List<CollaboratorExecutionResult> results = engine.tryExecute(legacyRun, List.of(hanging, sibling), Map.of(),
				false, runner);

		assertEquals(2, results.size());
		assertEquals("协作者执行超时", results.get(0).errorMessage());
		assertEquals("sibling-answer", results.get(1).answer());
		assertEquals(List.of("a1"), runner.abandonedTimedOut);
		pollUntil(() -> RuntimeStepState.TIMED_OUT.getValue()
			.equals(durable.stepByKey(runtimeRunId, "a1").getState()),
				"authoritative step of the abandoned collaborator was not timed out");
	}

	@Test
	void persistsCompiledPlanBeforeSchedulerStart() {
		Long runtimeRunId = durable.seedMirroredRun(100L, "7");
		TestRunner runner = new TestRunner();
		CollaboratorRoute first = route("s1", List.of());
		runner.onRun("s1", () -> success(first, "ok"));

		List<CollaboratorExecutionResult> results = engine.tryExecute(legacyRun, List.of(first), Map.of(), false,
				runner);

		assertEquals(1, results.size());
		verify(compiledPlanActivator).requireActivePlan(eq(runtimeRunId), eq(legacyRun), anyList());
	}

	@Test
	void doesNotStartWhenCompiledPlanActivationFails() {
		durable.seedMirroredRun(100L, "7");
		doThrow(CheckedException.fail("编排运行拒绝执行: reasonCode=PORT_NOT_FOUND, runId=1, 无 PortSpec"))
			.when(compiledPlanActivator)
			.requireActivePlan(any(), any(), anyList());
		TestRunner runner = new TestRunner();
		CollaboratorRoute first = route("s1", List.of());
		runner.onRun("s1", () -> success(first, "ok"));

		CheckedException ex = assertThrows(CheckedException.class,
				() -> engine.tryExecute(legacyRun, List.of(first), Map.of(), false, runner));
		assertTrue(ex.getMessage().contains("PORT_NOT_FOUND"));
		assertTrue(runner.executed.isEmpty());
	}

	@Test
	void productionPathSavesActiveCompiledPlanBeforeStart() {
		Long runtimeRunId = durable.seedMirroredRun(100L, "7");
		CompiledPlanPersistenceService persistence = mock(CompiledPlanPersistenceService.class);
		AgentRuntimePlanMapper planMapper = mock(AgentRuntimePlanMapper.class);
		when(planMapper.findActiveByRunId(runtimeRunId)).thenReturn(null);
		when(persistence.save(eq(runtimeRunId), any(CompiledPlan.class)))
			.thenReturn(AgentRuntimePlan.builder().id(9L).status("ACTIVE").build());
		PlanCompiler compiler = new PlanCompiler(new RouteProposalV2Parser(new ObjectMapper()),
				new ProposalForbiddenFieldGuard(), new ObjectMapper());
		DurableCompiledPlanActivator activator = new DurableCompiledPlanActivator(compiler, persistence, planMapper,
				durable.runMapper);
		EventDrivenCollaboratorEngine production = new EventDrivenCollaboratorEngine(durable.mirror(),
				durable.scheduler(schedulerPool), new ObjectMapper(), activator);
		TestRunner runner = new TestRunner();
		CollaboratorRoute first = route("s1", List.of());
		runner.onRun("s1", () -> success(first, "ok"));

		List<CollaboratorExecutionResult> results = production.tryExecute(legacyRun, List.of(first), Map.of(), false,
				runner);

		assertEquals(1, results.size());
		verify(persistence).save(eq(runtimeRunId), any(CompiledPlan.class));
		verify(persistence, never()).saveShadow(any(), any());
	}

	@Test
	void fallsBackWhenAuthoritativeMirrorRunIsMissing() {
		TestRunner runner = new TestRunner();
		CollaboratorRoute first = route("s1", List.of());

		assertNull(engine.tryExecute(legacyRun, List.of(first), Map.of(), false, runner));
		assertTrue(runner.executed.isEmpty());
		verify(compiledPlanActivator, never()).requireActivePlan(any(), any(), anyList());
	}

	@Test
	void fallsBackWhenRouteHasNoStepId() {
		durable.seedMirroredRun(100L, "7");
		TestRunner runner = new TestRunner();
		CollaboratorRoute noStepId = route(null, List.of());

		assertNull(engine.tryExecute(legacyRun, List.of(noStepId), Map.of(), false, runner));
		assertTrue(runner.executed.isEmpty());
		verify(compiledPlanActivator, never()).requireActivePlan(any(), any(), anyList());
	}

	private CollaboratorRoute route(String stepId, List<String> dependsOn) {
		return buildRoute(stepId, dependsOn, DelegationMode.AUTO_READ_ONLY.name());
	}

	private CollaboratorRoute interactiveRoute(String stepId, List<String> dependsOn) {
		return buildRoute(stepId, dependsOn, DelegationMode.INTERACTIVE.name());
	}

	private CollaboratorRoute buildRoute(String stepId, List<String> dependsOn, String delegationMode) {
		AgentCollaborator collaborator = AgentCollaborator.builder()
			.id(1L)
			.collaboratorAgentId(10L)
			.roleName("角色")
			.delegationMode(delegationMode)
			.enabled(true)
			.build();
		DataAgent dataAgent = DataAgent.builder().id(10L).name("协作者").build();
		return new CollaboratorRoute(collaborator, dataAgent, "任务" + stepId, "命中规则", "返回结果", stepId, dependsOn,
				List.of(), List.of(), delegationMode);
	}

	private CollaboratorExecutionResult success(CollaboratorRoute route, String answer) {
		return new CollaboratorExecutionResult(route, answer, null, 1L, null, null, Map.of());
	}

	private CollaboratorExecutionResult failure(CollaboratorRoute route, String message) {
		return new CollaboratorExecutionResult(route, null, new IllegalStateException(message), 1L);
	}

	private CollaboratorExecutionResult waiting(CollaboratorRoute route) {
		return new CollaboratorExecutionResult(route, "需要澄清", null, 1L,
				AgentRequest.builder().agentId("10").threadId("child-thread").runtimeRequestId("child-runtime").build(),
				new RoutePendingService.PendingInteraction("token", Map.of("schemaVersion", "business-clarify/v1")));
	}

	private static void awaitQuietly(CountDownLatch latch, int seconds) {
		try {
			if (!latch.await(seconds, TimeUnit.SECONDS)) {
				throw new IllegalStateException("timed out waiting for latch");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while waiting for latch", ex);
		}
	}

	private static void sleepQuietly(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
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

	/**
	 * 可编排行为的执行体桩：按 stepId 配置执行结果，记录执行/阻断/放弃轨迹与并发度。
	 */
	private static final class TestRunner implements CollaboratorStepRunner {

		private final Map<String, Function<List<CollaboratorExecutionResult>, CollaboratorExecutionResult>> behaviors =
				new ConcurrentHashMap<>();

		private final Set<String> interactiveSteps = ConcurrentHashMap.newKeySet();

		private final Map<String, Long> budgetMillisByStep = new ConcurrentHashMap<>();

		private final Map<String, String> dependencyAnswers = new ConcurrentHashMap<>();

		private final List<String> executed = Collections.synchronizedList(new java.util.ArrayList<>());

		private final List<String> unexecuted = Collections.synchronizedList(new java.util.ArrayList<>());

		private final List<String> abandonedTimedOut = Collections.synchronizedList(new java.util.ArrayList<>());

		private final AtomicInteger concurrent = new AtomicInteger();

		private final AtomicInteger maxConcurrent = new AtomicInteger();

		private boolean throwWhenBudgetCheckedAfterTimeout;

		private void onRun(String stepId, Supplier<CollaboratorExecutionResult> behavior) {
			behaviors.put(stepId, dependencies -> behavior.get());
		}

		@Override
		public CollaboratorExecutionResult runRoute(CollaboratorRoute route,
				List<CollaboratorExecutionResult> dependencyResults, InFlightHandle handle) {
			executed.add(route.stepId());
			if (!dependencyResults.isEmpty()) {
				dependencyAnswers.put(route.stepId(), dependencyResults.get(0).answer());
			}
			int now = concurrent.incrementAndGet();
			maxConcurrent.accumulateAndGet(now, Math::max);
			try {
				handle.attach(AgentOrchestrationStep.builder().id(1L).build(),
						AgentRequest.builder()
							.agentId(String.valueOf(route.collaboratorAgentId()))
							.threadId("child-thread-" + route.stepId())
							.runtimeRequestId("child-runtime-" + route.stepId())
							.build(),
						budgetMillisByStep.getOrDefault(route.stepId(), 0L));
				return behaviors.get(route.stepId()).apply(dependencyResults);
			}
			finally {
				concurrent.decrementAndGet();
			}
		}

		@Override
		public CollaboratorExecutionResult markUnexecuted(CollaboratorRoute route, String errorMessage) {
			unexecuted.add(route.stepId());
			return new CollaboratorExecutionResult(route, null, new IllegalStateException(errorMessage), 0L);
		}

		@Override
		public CollaboratorExecutionResult abandonTimedOut(InFlightHandle handle) {
			abandonedTimedOut.add(handle.route().stepId());
			handle.workerThread().interrupt();
			return new CollaboratorExecutionResult(handle.route(), null, new IllegalStateException("协作者执行超时"),
					1L);
		}

		@Override
		public void abandon(InFlightHandle handle) {
			handle.workerThread().interrupt();
		}

		@Override
		public void ensureParentBudget() {
			if (throwWhenBudgetCheckedAfterTimeout && !abandonedTimedOut.isEmpty()) {
				throw new IllegalStateException("编排者没有剩余时间等待协作者");
			}
		}

		@Override
		public boolean interactive(CollaboratorRoute route) {
			return interactiveSteps.contains(route.stepId());
		}

	}

}
