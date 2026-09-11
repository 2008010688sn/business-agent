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
package com.sn68.agent.dataagent.runtime.durable.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.authorization.observability.AuthorizationMetrics;
import com.sn68.agent.dataagent.context.DataAgentAsyncContextBridge;
import com.sn68.agent.dataagent.context.DataAgentOutboundContext;
import com.sn68.agent.dataagent.employee.auth.EmployeeAuthTokenContext;
import com.sn68.agent.dataagent.employee.auth.EmployeeExecutionContextClient;
import com.sn68.agent.dataagent.employee.dto.EmployeeReleaseSnapshot;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.employee.service.EmployeeReleaseSnapshotResolver;
import com.sn68.agent.dataagent.repository.DataAgentMapper;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRun;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeStep;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeRunState;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeStepState;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeDagScheduler;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStateService.StepStateChange;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStateService.StepTransition;
import com.sn68.agent.dataagent.runtime.durable.service.impl.SingleTurnRuntimeStepExecutor;
import com.sn68.agent.dataagent.runtime.durable.support.InMemoryDurableRuntime;
import com.sn68.agent.dataagent.runtime.durable.support.RuntimeStructuredWorkProductComposer;
import com.sn68.agent.dataagent.service.agent.AgentInvocationService;
import com.sn68.agent.dataagent.task.enums.TaskRunStatus;
import com.sn68.agent.dataagent.task.service.AgentTaskRunService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import com.sn68.agent.framework.redis.plus.lock.RedisLockHelper;
import com.sn68.agent.framework.security.domain.UserInfoDetails;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运行时守护作业测试：验证 PENDING 运行被拉起并收敛、在途运行不被重复拉起、
 * 租约过期步骤以更高 fence 被接管（旧 fence 写入随即失效）、有效租约不被误接管，
 * 以及单条运行拉起失败不中断整批。
 *
 * <p>超时收敛部分覆盖：过期运行收敛为 TIMED_OUT 并同步 agent_task_run、崩溃遗留步骤一并收敛、
 * 租约仍有效的在途运行不被误杀、重复扫描幂等。</p>
 */
class RuntimeRunDaemonJobTest {

	private static final String TENANT_ID = "7";

	private static final long DIGITAL_EMPLOYEE_ID = 88L;

	private static final long RELEASE_ID = 999L;

	private final InMemoryDurableRuntime durable = new InMemoryDurableRuntime();

	private final ExecutorService pool = Executors.newFixedThreadPool(2);

	private final AtomicInteger invocations = new AtomicInteger();

	private AgentInvocationService invocationService;

	private SingleTurnRuntimeStepExecutor executor;

	private RuntimeDagScheduler scheduler;

	private AgentTaskRunService taskRunService;

	private RuntimeRunDaemonJob daemon;

	@BeforeEach
	void setUp() {
		invocationService = mock(AgentInvocationService.class);
		when(invocationService.invoke(any(AgentRequest.class))).thenAnswer(invocation -> {
			invocations.incrementAndGet();
			return "done";
		});
		DataAgentMapper dataAgentMapper = mock(DataAgentMapper.class);
		EmployeeReleaseSnapshotResolver snapshotResolver = mock(EmployeeReleaseSnapshotResolver.class);
		when(snapshotResolver.resolveById(any(), any(), any())).thenReturn(new EmployeeReleaseSnapshot(RELEASE_ID,
				DIGITAL_EMPLOYEE_ID, 1, "1", "spec-hash", "E-001", "测试员工", null, "提示词", null, 1L, null, null,
				null, Map.of(), List.of(), List.of(), null));
		DigitalEmployeeMapper employeeMapper = mock(DigitalEmployeeMapper.class);
		when(employeeMapper.findByIdAndTenantId(DIGITAL_EMPLOYEE_ID, "7")).thenReturn(DigitalEmployee.builder()
			.id(DIGITAL_EMPLOYEE_ID)
			.tenantId("7")
			.employeeName("测试员工")
			.iamPrincipalId("sp_abc")
			.build());
		EmployeeExecutionContextClient tokenClient = mock(EmployeeExecutionContextClient.class);
		when(tokenClient.issueContext(any(), any(), any()))
			.thenReturn(new EmployeeAuthTokenContext("principal-token", "Bearer", 600L, 1L));
		DataAgentAsyncContextBridge bridge = mock(DataAgentAsyncContextBridge.class);
		when(bridge.snapshotForDelegatedToken(any(), any(DataAgentOutboundContext.Snapshot.class)))
			.thenReturn(DataAgentAsyncContextBridge.Snapshot.empty());
		when(bridge.supplyWith(any(), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
		AuthenticationContext authenticationContext = mock(AuthenticationContext.class);
		when(authenticationContext.getContext())
			.thenReturn(UserInfoDetails.builder().userId("sp_abc").tenantId("7").build());
		RuntimeStructuredWorkProductComposer composer = mock(RuntimeStructuredWorkProductComposer.class);
		when(composer.compose(any())).thenReturn(List.of());
		executor = new SingleTurnRuntimeStepExecutor(durable.stepMapper, dataAgentMapper, snapshotResolver,
				employeeMapper, tokenClient, bridge, durable.stateService, invocationService, authenticationContext,
				new ObjectMapper(),
				new AuthorizationMetrics(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT)), composer);
		scheduler = durable.scheduler(pool);
		taskRunService = mock(AgentTaskRunService.class);
		daemon = newDaemon(scheduler, executor);
	}

	@AfterEach
	void tearDown() {
		pool.shutdownNow();
	}

	@Test
	void pendingTaskRunIsStartedAndConverges() throws Exception {
		Long runId = durable.seedTaskRun(TENANT_ID, "统计昨天的异常箱数", DIGITAL_EMPLOYEE_ID, RELEASE_ID);

		assertEquals(1, daemon.startStalledRuns());

		pollUntil(() -> RuntimeRunState.SUCCEEDED.getValue().equals(durable.runById(runId).getState()),
				"任务型运行未收敛为成功");
		assertEquals(1, invocations.get());
		AgentRuntimeStep step = durable.stepByKey(runId, SingleTurnRuntimeStepExecutor.SINGLE_TURN_STEP_KEY);
		assertNotNull(step);
		assertEquals(RuntimeStepState.SUCCEEDED.getValue(), step.getState());
		assertEquals(1, durable.artifactsOfRun(runId).size());
	}

	@Test
	void runWithInFlightStepIsNotPickedUpBySecondNode() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		when(invocationService.invoke(any(AgentRequest.class))).thenAnswer(invocation -> {
			invocations.incrementAndGet();
			release.await(5, TimeUnit.SECONDS);
			return "done";
		});
		Long runId = durable.seedTaskRun(TENANT_ID, "长任务", DIGITAL_EMPLOYEE_ID, RELEASE_ID);
		ExecutorService otherPool = Executors.newFixedThreadPool(2);
		try {
			RuntimeRunDaemonJob otherNode = newDaemon(durable.scheduler(otherPool), executor);
			assertEquals(1, daemon.startStalledRuns());
			pollUntil(() -> stepInState(runId, RuntimeStepState.RUNNING), "步骤未进入 RUNNING");

			// 步骤在途：另一副本本轮扫描不得再次拉起同一运行
			assertEquals(0, otherNode.startStalledRuns());

			release.countDown();
			pollUntil(() -> RuntimeRunState.SUCCEEDED.getValue().equals(durable.runById(runId).getState()),
					"任务型运行未收敛为成功");
			assertEquals(1, invocations.get());
		}
		finally {
			release.countDown();
			otherPool.shutdownNow();
		}
	}

	@Test
	void expiredLeaseStepIsTakenOverWithHigherFence() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		when(invocationService.invoke(any(AgentRequest.class))).thenAnswer(invocation -> {
			invocations.incrementAndGet();
			release.await(5, TimeUnit.SECONDS);
			return "done";
		});
		Long runId = durable.seedTaskRun(TENANT_ID, "崩溃节点遗留的任务", DIGITAL_EMPLOYEE_ID, RELEASE_ID);
		durable.forceRunState(runId, RuntimeRunState.RUNNING, Instant.now());
		AgentRuntimeStep step = executor.ensureSingleStep(durable.runById(runId));
		Long deadFence = durable.forceRunningLease(step.getId(), "dead-node", Instant.now().minusSeconds(300));

		try {
			assertEquals(1, daemon.reclaimExpiredSteps());

			// 接管后 fence 必须变大，崩溃节点持旧 fence 的终态写入随即失效
			AgentRuntimeStep reclaimed = durable.stepById(step.getId());
			assertTrue(reclaimed.getFenceToken() > deadFence);
			assertFalse(durable.stateService.transitionStep(new StepTransition(step.getId(),
					reclaimed.getStateVersion(), deadFence, RuntimeStepState.SUCCEEDED, StepStateChange.none())));

			release.countDown();
			pollUntil(() -> RuntimeRunState.SUCCEEDED.getValue().equals(durable.runById(runId).getState()),
					"接管后的运行未收敛为成功");
			assertEquals(1, invocations.get());
		}
		finally {
			release.countDown();
		}
	}

	@Test
	void liveLeaseStepIsNotReclaimed() {
		Long runId = durable.seedTaskRun(TENANT_ID, "在途任务", DIGITAL_EMPLOYEE_ID, RELEASE_ID);
		durable.forceRunState(runId, RuntimeRunState.RUNNING, Instant.now());
		AgentRuntimeStep step = executor.ensureSingleStep(durable.runById(runId));
		durable.forceRunningLease(step.getId(), "healthy-node", Instant.now().plusSeconds(180));

		assertEquals(0, daemon.reclaimExpiredSteps());
		assertEquals(0, invocations.get());
		assertEquals(RuntimeStepState.RUNNING.getValue(), durable.stepById(step.getId()).getState());
	}

	@Test
	void singleRunFailureDoesNotBreakTheBatch() throws Exception {
		Long brokenRunId = durable.seedTaskRun(TENANT_ID, "会失败的任务", DIGITAL_EMPLOYEE_ID, RELEASE_ID);
		Long healthyRunId = durable.seedTaskRun(TENANT_ID, "正常任务", DIGITAL_EMPLOYEE_ID, RELEASE_ID);
		SingleTurnRuntimeStepExecutor spyExecutor = spy(executor);
		doThrow(CheckedException.fail("单步行物化失败")).when(spyExecutor)
			.ensureSingleStep(argThat(run -> run != null && brokenRunId.equals(run.getId())));
		RuntimeRunDaemonJob job = newDaemon(scheduler, spyExecutor);

		assertEquals(1, job.startStalledRuns());

		pollUntil(() -> RuntimeRunState.SUCCEEDED.getValue().equals(durable.runById(healthyRunId).getState()),
				"批内后续运行未被拉起");
		assertEquals(RuntimeRunState.PENDING.getValue(), durable.runById(brokenRunId).getState());
	}

	@Test
	void expiredDeadlinePendingRunIsConvergedToTimedOutAndSyncsTaskRun() {
		Long runId = durable.seedTaskRun(TENANT_ID, "早该结束的任务", DIGITAL_EMPLOYEE_ID, RELEASE_ID);
		durable.forceDeadline(runId, Instant.now().minusSeconds(120));

		assertEquals(1, daemon.expireDeadlineRuns());

		AgentRuntimeRun run = durable.runById(runId);
		assertEquals(RuntimeRunState.TIMED_OUT.getValue(), run.getState());
		assertEquals("RUN_DEADLINE_EXCEEDED", run.getErrorCode());
		assertNotNull(run.getFinishedAt());
		// 过期运行不得被执行：收敛发生在拉起之前，模型调用一次都不该发生
		assertEquals(0, invocations.get());
		verify(taskRunService).markTerminalByRuntimeRun(eq(runId), eq(TaskRunStatus.FAILED),
				argThat(message -> message.contains("RUN_DEADLINE_EXCEEDED") && message.contains("超过绝对截止时间")));
		assertTrue(durable.outboxAppends().stream()
			.anyMatch(append -> ("run-terminal:" + runId + ":RUN_TIMED_OUT").equals(append.eventKey())));
	}

	@Test
	void expiredDeadlineRunConvergesLeftoverStepsFromCrashedNode() {
		Long runId = durable.seedTaskRun(TENANT_ID, "崩溃节点遗留的过期任务", DIGITAL_EMPLOYEE_ID, RELEASE_ID);
		durable.forceRunState(runId, RuntimeRunState.RUNNING, Instant.now());
		AgentRuntimeStep step = executor.ensureSingleStep(durable.runById(runId));
		durable.forceRunningLease(step.getId(), "dead-node", Instant.now().minusSeconds(300));
		durable.forceDeadline(runId, Instant.now().minusSeconds(60));

		assertEquals(1, daemon.expireDeadlineRuns());

		assertEquals(RuntimeRunState.TIMED_OUT.getValue(), durable.runById(runId).getState());
		AgentRuntimeStep converged = durable.stepById(step.getId());
		assertEquals(RuntimeStepState.TIMED_OUT.getValue(), converged.getState());
		assertEquals("RUN_DEADLINE_EXCEEDED", converged.getErrorCode());
	}

	@Test
	void runWithLiveLeaseStepIsNotTimedOutEvenPastDeadline() {
		Long runId = durable.seedTaskRun(TENANT_ID, "仍在执行的长任务", DIGITAL_EMPLOYEE_ID, RELEASE_ID);
		durable.forceRunState(runId, RuntimeRunState.RUNNING, Instant.now());
		AgentRuntimeStep step = executor.ensureSingleStep(durable.runById(runId));
		// 租约仍有效 = 执行节点还在续期心跳，绝不能被判定超时
		durable.forceRunningLease(step.getId(), "healthy-node", Instant.now().plusSeconds(180));
		durable.forceDeadline(runId, Instant.now().minusSeconds(60));

		assertEquals(0, daemon.expireDeadlineRuns());

		assertEquals(RuntimeRunState.RUNNING.getValue(), durable.runById(runId).getState());
		assertEquals(RuntimeStepState.RUNNING.getValue(), durable.stepById(step.getId()).getState());
		verify(taskRunService, never()).markTerminalByRuntimeRun(anyLong(), any(), any());
	}

	@Test
	void deadlineConvergenceIsIdempotentAcrossScans() {
		Long runId = durable.seedTaskRun(TENANT_ID, "重复扫描的过期任务", DIGITAL_EMPLOYEE_ID, RELEASE_ID);
		durable.forceDeadline(runId, Instant.now().minusSeconds(90));

		assertEquals(1, daemon.expireDeadlineRuns());
		Long versionAfterFirst = durable.runById(runId).getStateVersion();

		// 第二轮扫描不得重复推进：终态运行已不在扫描集内
		assertEquals(0, daemon.expireDeadlineRuns());

		assertEquals(versionAfterFirst, durable.runById(runId).getStateVersion());
		verify(taskRunService, times(1)).markTerminalByRuntimeRun(eq(runId), eq(TaskRunStatus.FAILED), any());
	}

	@Test
	void expiredChatRunWithoutLiveLeaseIsTimedOut() {
		Long runId = durable.seedChatRun(TENANT_ID, "88", RuntimeRunState.RUNNING);
		durable.forceDeadline(runId, Instant.now().minusSeconds(30));

		assertEquals(1, daemon.expireDeadlineRuns());
		assertEquals(RuntimeRunState.TIMED_OUT.getValue(), durable.runById(runId).getState());
	}

	@Test
	void waitingChatRunIsNotDeadlineExpired() {
		Long runId = durable.seedChatRun(TENANT_ID, "88", RuntimeRunState.WAITING_INPUT);
		durable.forceDeadline(runId, Instant.now().minusSeconds(30));

		assertEquals(0, daemon.expireDeadlineRuns());
		assertEquals(RuntimeRunState.WAITING_INPUT.getValue(), durable.runById(runId).getState());
	}

	@Test
	void waitingChatRunExpiresAfterWaitTimeout() {
		Long runId = durable.seedChatRun(TENANT_ID, "88", RuntimeRunState.WAITING_INPUT);
		durable.forceRunState(runId, RuntimeRunState.WAITING_INPUT, Instant.now().minus(java.time.Duration.ofDays(8)));

		assertEquals(1, daemon.expireWaitingChatRuns());
		assertEquals(RuntimeRunState.CANCELLED.getValue(), durable.runById(runId).getState());
		assertEquals("WAIT_TIMEOUT", durable.runById(runId).getErrorCode());
	}

	@Test
	void singleExpiredRunFailureDoesNotBreakTheBatch() {
		Long brokenRunId = durable.seedTaskRun(TENANT_ID, "同步会失败的过期任务", DIGITAL_EMPLOYEE_ID, RELEASE_ID);
		Long healthyRunId = durable.seedTaskRun(TENANT_ID, "正常的过期任务", DIGITAL_EMPLOYEE_ID, RELEASE_ID);
		durable.forceDeadline(brokenRunId, Instant.now().minusSeconds(30));
		durable.forceDeadline(healthyRunId, Instant.now().minusSeconds(30));
		doThrow(CheckedException.fail("任务运行同步失败")).when(taskRunService)
			.markTerminalByRuntimeRun(eq(brokenRunId), any(), any());

		assertEquals(1, daemon.expireDeadlineRuns());

		assertEquals(RuntimeRunState.TIMED_OUT.getValue(), durable.runById(healthyRunId).getState());
	}

	private RuntimeRunDaemonJob newDaemon(RuntimeDagScheduler dagScheduler, SingleTurnRuntimeStepExecutor stepExecutor) {
		return new RuntimeRunDaemonJob(durable.runMapper, durable.stepMapper, durable.stateService, dagScheduler,
				stepExecutor, durable.eventService, durable.outboxService, taskRunService,
				mock(RedisLockHelper.class), new DataAgentProperties());
	}

	private boolean stepInState(Long runId, RuntimeStepState state) {
		AgentRuntimeStep step = durable.stepByKey(runId, SingleTurnRuntimeStepExecutor.SINGLE_TURN_STEP_KEY);
		return step != null && state.getValue().equals(step.getState());
	}

	private static void pollUntil(Supplier<Boolean> condition, String message) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000L;
		while (System.currentTimeMillis() < deadline) {
			if (Boolean.TRUE.equals(condition.get())) {
				return;
			}
			Thread.sleep(20L);
		}
		throw new AssertionError(message);
	}

}
