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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeBudgetExceededException;
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
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRun;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeStep;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStepExecutor.StepExecution;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStepExecutor.StepOutcome;
import com.sn68.agent.dataagent.runtime.durable.support.InMemoryDurableRuntime;
import com.sn68.agent.dataagent.runtime.durable.support.RuntimeStructuredWorkProductComposer;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStepExecutor.ExtraArtifact;
import com.sn68.agent.dataagent.service.agent.AgentInvocationService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import com.sn68.agent.framework.commons.security.DataPermission;
import com.sn68.agent.framework.commons.security.DataScopeType;
import com.sn68.agent.framework.security.domain.UserInfoDetails;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单步任务执行体测试：覆盖单步行幂等物化、Release 反查智能体与入口状态闸门（PUBLISHED 放行、
 * DRAFT/RETIRED 失败关闭且不调用运行时）、owner/releaseId 透传（PR-1 起 owner 维度）、
 * 执行异常如实反映为失败结果，以及租约续期的成功/失效两条路径。
 *
 * <p>续期心跳的真实节拍（45s）不在单测中等待，这里直接驱动一次续期动作验证接线与告警。</p>
 */
class SingleTurnRuntimeStepExecutorTest {

	private static final String TENANT_ID = "7";

	private static final long DIGITAL_EMPLOYEE_ID = 88L;

	private static final long RELEASE_ID = 999L;

	private final InMemoryDurableRuntime durable = new InMemoryDurableRuntime();

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final PrometheusMeterRegistry meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

	private AgentInvocationService invocationService;

	private DataAgentMapper dataAgentMapper;

	private EmployeeReleaseSnapshotResolver snapshotResolver;

	private DigitalEmployeeMapper employeeMapper;

	private EmployeeExecutionContextClient executionContextClient;

	private DataAgentAsyncContextBridge asyncContextBridge;

	private SingleTurnRuntimeStepExecutor executor;

	private RuntimeStructuredWorkProductComposer structuredWorkProductComposer;

	@BeforeEach
	void setUp() {
		invocationService = mock(AgentInvocationService.class);
		dataAgentMapper = mock(DataAgentMapper.class);
		snapshotResolver = mock(EmployeeReleaseSnapshotResolver.class);
		employeeMapper = mock(DigitalEmployeeMapper.class);
		executionContextClient = mock(EmployeeExecutionContextClient.class);
		asyncContextBridge = mock(DataAgentAsyncContextBridge.class);
		when(snapshotResolver.resolveById(anyString(), any(), any())).thenReturn(dummySnapshot());
		when(employeeMapper.findByIdAndTenantId(DIGITAL_EMPLOYEE_ID, "7")).thenReturn(DigitalEmployee.builder()
			.id(DIGITAL_EMPLOYEE_ID)
			.tenantId("7")
			.employeeName("测试员工")
			.iamPrincipalId("sp_abc")
			.build());
		when(executionContextClient.issueContext(anyString(), anyString(), any()))
			.thenReturn(new EmployeeAuthTokenContext("principal-token", "Bearer", 600L, 1L));
		when(asyncContextBridge.snapshotForDelegatedToken(anyString(), any(DataAgentOutboundContext.Snapshot.class)))
			.thenReturn(DataAgentAsyncContextBridge.Snapshot.empty());
		when(asyncContextBridge.supplyWith(any(), any()))
			.thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
		AuthenticationContext authenticationContext = mock(AuthenticationContext.class);
		when(authenticationContext.getContext())
			.thenReturn(UserInfoDetails.builder().userId("sp_abc").tenantId("7").build());
		when(authenticationContext.dataPermission())
			.thenReturn(DataPermission.builder().scopeType(DataScopeType.ALL).build());
		when(authenticationContext.nickName()).thenReturn("运营小助手");
		when(authenticationContext.tenantCode()).thenReturn("0000");
		when(authenticationContext.teamIds()).thenReturn(List.of());
		structuredWorkProductComposer = mock(RuntimeStructuredWorkProductComposer.class);
		when(structuredWorkProductComposer.compose(any())).thenReturn(List.of());
		executor = new SingleTurnRuntimeStepExecutor(durable.stepMapper, dataAgentMapper, snapshotResolver,
				employeeMapper, executionContextClient, asyncContextBridge, durable.stateService, invocationService,
				authenticationContext, objectMapper, new AuthorizationMetrics(meterRegistry),
				structuredWorkProductComposer);
	}

	@Test
	void ensureSingleStepMaterialisesExactlyOneStep() {
		AgentRuntimeRun run = seedRun("统计昨天的异常箱数");

		AgentRuntimeStep first = executor.ensureSingleStep(run);
		AgentRuntimeStep second = executor.ensureSingleStep(run);

		assertNotNull(first);
		assertNotNull(second);
		assertEquals(first.getId(), second.getId());
		assertEquals(1, durable.stepMapper.listByRunId(run.getId()).size());
		assertEquals(SingleTurnRuntimeStepExecutor.SINGLE_TURN_STEP_KEY, first.getStepKey());
	}

	@Test
	void executeResolvesAgentFromReleaseAndCarriesOwnerContext() throws Exception {
		AgentRuntimeRun run = seedRun("统计昨天的异常箱数");
		AgentRuntimeStep step = executor.ensureSingleStep(run);
		when(invocationService.invoke(any(AgentRequest.class))).thenReturn("昨天共 12 个异常箱");

		StepOutcome outcome = executor.execute(new StepExecution(run, step, 1, 1L));

		assertTrue(outcome.success());
		ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);
		verify(invocationService).invoke(captor.capture());
		AgentRequest request = captor.getValue();
		assertEquals(String.valueOf(DIGITAL_EMPLOYEE_ID), request.getAgentId());
		assertEquals("DIGITAL_EMPLOYEE", request.getOwnerType());
		assertEquals(DIGITAL_EMPLOYEE_ID, request.getOwnerId());
		assertEquals(RELEASE_ID, request.getReleaseId());
		assertEquals(String.valueOf(TENANT_ID), request.getTenantIdSnapshot());
		assertEquals("sp_abc", request.getUserIdSnapshot());
		assertEquals(DataScopeType.ALL, request.getDataPermissionSnapshot().getScopeType());
		assertEquals("运营小助手", request.getUserNickNameSnapshot());
		assertEquals("0000", request.getTenantCodeSnapshot());
		assertEquals("统计昨天的异常箱数", request.getQuery());
		assertEquals(run.getRuntimeRequestId(), request.getRuntimeRequestId());
		assertEquals(run.getId(), request.getDurableRunId());
		verify(executionContextClient).issueContext("7", "sp_abc", "测试员工");
		JsonNode artifact = objectMapper.readTree(outcome.artifactJson());
		assertEquals("昨天共 12 个异常箱", artifact.path("answer").asText());
	}

	@Test
	void executeAttachesStructuredWorkProductsFromToolSnapshots() throws Exception {
		AgentRuntimeRun run = seedRun("统计昨天的异常箱数");
		AgentRuntimeStep step = executor.ensureSingleStep(run);
		when(invocationService.invoke(any(AgentRequest.class))).thenReturn("昨天共 12 个异常箱");
		when(structuredWorkProductComposer.compose(any())).thenReturn(List.of(
				new ExtraArtifact("query-result/v1", "{\"snapshots\":[]}")));

		StepOutcome outcome = executor.execute(new StepExecution(run, step, 1, 1L));

		assertTrue(outcome.success());
		assertEquals(1, outcome.extraArtifacts().size());
		assertEquals("query-result/v1", outcome.extraArtifacts().get(0).schemaVersion());
	}

	@Test
	void executeKeepsAnswerWhenStructuredComposeThrows() throws Exception {
		AgentRuntimeRun run = seedRun("统计昨天的异常箱数");
		AgentRuntimeStep step = executor.ensureSingleStep(run);
		when(invocationService.invoke(any(AgentRequest.class))).thenReturn("昨天共 12 个异常箱");
		when(structuredWorkProductComposer.compose(any())).thenThrow(new IllegalStateException("explain missing"));

		StepOutcome outcome = executor.execute(new StepExecution(run, step, 1, 1L));

		assertTrue(outcome.success());
		assertTrue(outcome.extraArtifacts().isEmpty());
		assertEquals("昨天共 12 个异常箱", objectMapper.readTree(outcome.artifactJson()).path("answer").asText());
	}

	@Test
	void executeReportsFailureWithOriginalMessageWhenInvocationThrows() {
		AgentRuntimeRun run = seedRun("生成周报");
		AgentRuntimeStep step = executor.ensureSingleStep(run);
		when(invocationService.invoke(any(AgentRequest.class)))
			.thenThrow(CheckedException.fail("模型调用失败: 上游 429"));
		ListAppender<ILoggingEvent> appender = attachAppender();

		try {
			StepOutcome outcome = executor.execute(new StepExecution(run, step, 1, 1L));

			assertFalse(outcome.success());
			assertEquals("SINGLE_TURN_EXECUTION_FAILED", outcome.errorCode());
			assertEquals("模型调用失败: 上游 429", outcome.errorMessage());
			assertNull(outcome.artifactJson());
			assertTrue(appender.list.stream()
				.filter(event -> event.getLevel() == Level.WARN)
				.anyMatch(event -> event.getFormattedMessage().contains("单轮任务执行失败")));
		}
		finally {
			detachAppender(appender);
		}
	}

	@Test
	void executeCountsTokenBudgetExceededWhenBudgetRejected() {
		// C1 埋点：预算超限拒绝（PR-6 置终态路径）递增 token.budget.exceeded，
		// Prometheus 导出名必须精确为 token_budget_exceeded_total（P1 告警数据源）
		AgentRuntimeRun run = seedRun("生成超长报告");
		AgentRuntimeStep step = executor.ensureSingleStep(run);
		when(invocationService.invoke(any(AgentRequest.class))).thenThrow(
				new AgentRuntimeBudgetExceededException(AgentRuntimeBudgetExceededException.Reason.PROMPT_TOKENS,
						1000L, 900L, 200L));

		StepOutcome outcome = executor.execute(new StepExecution(run, step, 1, 1L));

		assertFalse(outcome.success());
		assertEquals("SINGLE_TURN_EXECUTION_FAILED", outcome.errorCode());
		assertEquals(1.0, meterRegistry.get(AuthorizationMetrics.METRIC_TOKEN_BUDGET_EXCEEDED)
				.tag("tenant", String.valueOf(TENANT_ID)).counter().count());
		assertTrue(meterRegistry.scrape().contains("token_budget_exceeded_total"));
	}

	@Test
	void executeCountsWrappedTokenBudgetExceededFromCauseChain() {
		// 响应式链路包装场景：预算异常作为 cause 被外层异常包裹时仍计数
		AgentRuntimeRun run = seedRun("生成超长报告");
		AgentRuntimeStep step = executor.ensureSingleStep(run);
		when(invocationService.invoke(any(AgentRequest.class))).thenThrow(new IllegalStateException("执行中断",
				new AgentRuntimeBudgetExceededException(AgentRuntimeBudgetExceededException.Reason.MODEL_CALLS,
						10L, 10L, 1L)));

		StepOutcome outcome = executor.execute(new StepExecution(run, step, 1, 1L));

		assertFalse(outcome.success());
		assertEquals(1.0, meterRegistry.get(AuthorizationMetrics.METRIC_TOKEN_BUDGET_EXCEEDED)
				.tag("tenant", String.valueOf(TENANT_ID)).counter().count());
	}

	@Test
	void executeFailsClosedWhenAgentCannotBeResolved() {
		AgentRuntimeRun run = seedRun("生成周报");
		AgentRuntimeStep step = executor.ensureSingleStep(run);
		when(employeeMapper.findByIdAndTenantId(DIGITAL_EMPLOYEE_ID, "7")).thenReturn(null);

		StepOutcome outcome = executor.execute(new StepExecution(run, step, 1, 1L));

		assertFalse(outcome.success());
		assertEquals("AGENT_UNRESOLVED", outcome.errorCode());
		verify(invocationService, never()).invoke(any(AgentRequest.class));
	}

	@Test
	void executeFailsClosedWhenReleaseIsRetired() {
		AgentRuntimeRun run = seedRun("生成周报");
		AgentRuntimeStep step = executor.ensureSingleStep(run);
		when(snapshotResolver.resolveById("7", DIGITAL_EMPLOYEE_ID, RELEASE_ID))
			.thenThrow(CheckedException.fail("数字员工 Release 快照解析失败（失败关闭）：发布版本未处于已发布状态, releaseId="
					+ RELEASE_ID + ", status=RETIRED"));

		StepOutcome outcome = executor.execute(new StepExecution(run, step, 1, 1L));

		assertFalse(outcome.success());
		assertEquals("RELEASE_NOT_PUBLISHED", outcome.errorCode());
		assertTrue(outcome.errorMessage().contains("已发布"));
		assertTrue(outcome.errorMessage().contains(String.valueOf(RELEASE_ID)));
		verify(invocationService, never()).invoke(any(AgentRequest.class));
	}

	@Test
	void executeFailsClosedWhenReleaseIsDraft() {
		AgentRuntimeRun run = seedRun("生成周报");
		AgentRuntimeStep step = executor.ensureSingleStep(run);
		when(snapshotResolver.resolveById("7", DIGITAL_EMPLOYEE_ID, RELEASE_ID))
			.thenThrow(CheckedException.fail("数字员工 Release 快照解析失败（失败关闭）：发布版本未处于已发布状态, releaseId="
					+ RELEASE_ID + ", status=DRAFT"));

		StepOutcome outcome = executor.execute(new StepExecution(run, step, 1, 1L));

		assertFalse(outcome.success());
		assertEquals("RELEASE_NOT_PUBLISHED", outcome.errorCode());
		assertTrue(outcome.errorMessage().contains("DRAFT"));
		verify(invocationService, never()).invoke(any(AgentRequest.class));
	}

	@Test
	void executeFailsClosedWhenRunHasNoQuery() {
		AgentRuntimeRun run = seedRun(null);
		AgentRuntimeStep step = executor.ensureSingleStep(run);

		StepOutcome outcome = executor.execute(new StepExecution(run, step, 1, 1L));

		assertFalse(outcome.success());
		assertEquals("RUN_QUERY_EMPTY", outcome.errorCode());
		verify(invocationService, never()).invoke(any(AgentRequest.class));
	}

	@Test
	void renewLeaseExtendsOwnLeaseAndWarnsWhenFenceIsStale() {
		AgentRuntimeRun run = seedRun("长任务");
		AgentRuntimeStep step = executor.ensureSingleStep(run);
		Instant leaseUntil = Instant.now().plusSeconds(30);
		Long fence = durable.forceRunningLease(step.getId(), "node-a", leaseUntil);

		executor.renewLease(step.getId(), "node-a", fence);

		assertTrue(durable.stepById(step.getId()).getLeaseUntil().isAfter(leaseUntil));

		Instant renewed = durable.stepById(step.getId()).getLeaseUntil();
		ListAppender<ILoggingEvent> appender = attachAppender();
		try {
			// fence 已被他人接管：续期必须失败且可见，不能静默假装续上
			executor.renewLease(step.getId(), "node-a", fence + 1);

			assertEquals(renewed, durable.stepById(step.getId()).getLeaseUntil());
			assertTrue(appender.list.stream()
				.filter(event -> event.getLevel() == Level.WARN)
				.anyMatch(event -> event.getFormattedMessage().contains("步骤租约续期未生效")));
		}
		finally {
			detachAppender(appender);
		}
	}

	private AgentRuntimeRun seedRun(String query) {
		Long runId = durable.seedTaskRun(TENANT_ID, query, DIGITAL_EMPLOYEE_ID, RELEASE_ID);
		return durable.runById(runId);
	}

	private EmployeeReleaseSnapshot dummySnapshot() {
		return new EmployeeReleaseSnapshot(RELEASE_ID, DIGITAL_EMPLOYEE_ID, 1, "1", "spec-hash", "E-001", "测试员工",
				null, "系统提示词", null, 1L, null, null, null, Map.of(), List.of(), List.of(), null);
	}

	private ListAppender<ILoggingEvent> attachAppender() {
		Logger logger = (Logger) LoggerFactory.getLogger(SingleTurnRuntimeStepExecutor.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private void detachAppender(ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger) LoggerFactory.getLogger(SingleTurnRuntimeStepExecutor.class);
		logger.detachAppender(appender);
		appender.stop();
	}

}
