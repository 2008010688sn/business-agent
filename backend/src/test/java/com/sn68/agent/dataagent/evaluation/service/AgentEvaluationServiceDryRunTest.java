/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.context.ExecutionIntentContext;
import com.sn68.agent.dataagent.evaluation.adapter.AgentEvalAdapter;
import com.sn68.agent.dataagent.evaluation.adapter.EvalInvocationPrepared;
import com.sn68.agent.dataagent.evaluation.adapter.EvalInvocationResult;
import com.sn68.agent.dataagent.evaluation.dto.EvalRunCreateRequest;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalCase;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalCaseResult;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalSubject;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalSuite;
import com.sn68.agent.dataagent.evaluation.enums.AgentEvaluationErrorDict;
import com.sn68.agent.dataagent.evaluation.repository.DataAgentEvalCaseMapper;
import com.sn68.agent.dataagent.evaluation.repository.DataAgentEvalCaseResultMapper;
import com.sn68.agent.dataagent.evaluation.repository.DataAgentEvalPolicyMapper;
import com.sn68.agent.dataagent.evaluation.repository.DataAgentEvalRunMapper;
import com.sn68.agent.dataagent.evaluation.repository.DataAgentEvalSubjectMapper;
import com.sn68.agent.dataagent.evaluation.repository.DataAgentEvalSuiteMapper;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeArtifactMapper;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeRunService;
import com.sn68.agent.dataagent.service.agent.DataAgentService;
import com.sn68.agent.dataagent.service.chat.DataChatTurnService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 评估中心 DRY_RUN 执行意图测试（方案第十四章）：DRY_RUN 运行落库执行意图；用例执行期间
 * 写关口回投的违规被记入用例结果与运行聚合计数，并在失败原因中可见；非法意图失败关闭。
 */
class AgentEvaluationServiceDryRunTest {

	private final DataAgentEvalPolicyMapper policyMapper = mock(DataAgentEvalPolicyMapper.class);

	private final DataAgentEvalSubjectMapper subjectMapper = mock(DataAgentEvalSubjectMapper.class);

	private final DataAgentEvalSuiteMapper suiteMapper = mock(DataAgentEvalSuiteMapper.class);

	private final DataAgentEvalCaseMapper caseMapper = mock(DataAgentEvalCaseMapper.class);

	private final DataAgentEvalRunMapper runMapper = mock(DataAgentEvalRunMapper.class);

	private final DataAgentEvalCaseResultMapper resultMapper = mock(DataAgentEvalCaseResultMapper.class);

	private final AuthenticationContext authenticationContext = mock(AuthenticationContext.class);

	private final List<DataAgentEvalCaseResult> insertedResults = new CopyOnWriteArrayList<>();

	private final AtomicReference<DataAgentEvalRun> storedRun = new AtomicReference<>();

	private final AtomicReference<EvalInvocationPrepared> preparedHolder = new AtomicReference<>();

	private final AtomicLong resultIdSequence = new AtomicLong(1L);

	@BeforeEach
	void setUp() {
		when(authenticationContext.tenantId()).thenReturn("tenant-1");
		when(authenticationContext.tenantCode()).thenReturn("tenant-code-1");
		DataAgentEvalSuite suite = new DataAgentEvalSuite();
		suite.setId(1L);
		suite.setTenantId("tenant-1");
		suite.setSubjectId(2L);
		when(suiteMapper.findEnabledById(1L)).thenReturn(suite);
		DataAgentEvalSubject subject = new DataAgentEvalSubject();
		subject.setId(2L);
		subject.setTenantId("tenant-1");
		subject.setSubjectType("DATA_AGENT");
		subject.setSubjectId("100");
		subject.setAdapterCode("DATA_AGENT");
		when(subjectMapper.findEnabledById(2L)).thenReturn(subject);
		DataAgentEvalCase evalCase = new DataAgentEvalCase();
		evalCase.setId(5L);
		evalCase.setTenantId("tenant-1");
		evalCase.setSuiteId(1L);
		evalCase.setCaseName("冒烟");
		evalCase.setUserInput("你好");
		when(caseMapper.findEnabledBySuiteId(1L)).thenReturn(List.of(evalCase));
		when(runMapper.insert(any(DataAgentEvalRun.class))).thenAnswer(invocation -> {
			DataAgentEvalRun run = invocation.getArgument(0);
			run.setId(700L);
			storedRun.set(run);
			return 1;
		});
		when(runMapper.selectById(anyLong())).thenAnswer(invocation -> storedRun.get());
		when(runMapper.findActiveById(anyLong())).thenAnswer(invocation -> storedRun.get());
		when(runMapper.updateById(any(DataAgentEvalRun.class))).thenAnswer(invocation -> {
			storedRun.set(invocation.getArgument(0));
			return 1;
		});
		when(resultMapper.insert(any(DataAgentEvalCaseResult.class))).thenAnswer(invocation -> {
			DataAgentEvalCaseResult result = invocation.getArgument(0);
			result.setId(resultIdSequence.getAndIncrement());
			insertedResults.add(result);
			return 1;
		});
		when(resultMapper.findByRunId(anyLong())).thenAnswer(invocation -> new ArrayList<>(insertedResults));
	}

	@Test
	void dryRunCaseRecordsGatewayViolationsIntoResultAndRunCounters() {
		AgentEvalAdapter adapter = violationRecordingAdapter();
		AgentEvaluationService service = service(adapter);
		EvalRunCreateRequest request = new EvalRunCreateRequest();
		request.setSuiteId(1L);
		request.setExecutionIntent("DRY_RUN");

		service.createRun(request);

		DataAgentEvalRun run = storedRun.get();
		assertEquals("DRY_RUN", run.getExecutionIntent());
		assertEquals(1, run.getWriteViolationCount());
		assertEquals(1, run.getIsolationViolationCount());
		assertEquals(1, insertedResults.size());
		DataAgentEvalCaseResult result = insertedResults.get(0);
		assertEquals(1, result.getWriteViolationCount());
		assertEquals(1, result.getIsolationViolationCount());
		assertNotNull(result.getViolationDetailJson());
		assertTrue(result.getViolationDetailJson().contains("WRITE_ATTEMPT"));
		assertTrue(result.getFailureReasonsJson().contains("DRY_RUN_WRITE_BLOCKED"));
		assertTrue(result.getFailureReasonsJson().contains("DRY_RUN_ISOLATION_VIOLATION"));
		// 适配器收到的作用域键必须非空且随 AgentRequest 透传（跨线程回投通道）
		assertNotNull(preparedHolder.get());
		assertNotNull(preparedHolder.get().executionScopeKey());
		assertEquals("DRY_RUN", preparedHolder.get().run().getExecutionIntent());
	}

	@Test
	void liveRunByDefaultCarriesNoDryRunScope() {
		AgentEvalAdapter adapter = plainAdapter();
		AgentEvaluationService service = service(adapter);
		EvalRunCreateRequest request = new EvalRunCreateRequest();
		request.setSuiteId(1L);

		service.createRun(request);

		DataAgentEvalRun run = storedRun.get();
		assertEquals("LIVE", run.getExecutionIntent());
		assertEquals(0, run.getWriteViolationCount());
		assertEquals(1, insertedResults.size());
		assertEquals(0, insertedResults.get(0).getWriteViolationCount());
		assertNotNull(preparedHolder.get());
		assertEquals(null, preparedHolder.get().executionScopeKey());
	}

	@Test
	void codeOracleMismatchFailsCaseClosed() {
		evalCaseWithOracle();
		EvalCodeOracleService oracle = mock(EvalCodeOracleService.class);
		when(oracle.evaluate(any(), any())).thenReturn(EvalCodeOracleService.Verdict.mismatch("stdout 不一致"));
		AgentEvaluationService service = service(dryRunAdapter(), oracleProvider(oracle));
		EvalRunCreateRequest request = new EvalRunCreateRequest();
		request.setSuiteId(1L);
		request.setExecutionIntent("DRY_RUN");

		service.createRun(request);

		assertEquals(1, insertedResults.size());
		DataAgentEvalCaseResult result = insertedResults.get(0);
		assertEquals("failed", result.getStatus());
		assertEquals(true, result.getHardFail());
		assertTrue(result.getFailureReasonsJson().contains("CODE_ORACLE_MISMATCH"));
		assertEquals(0, result.getScore().intValue());
	}

	@Test
	void missingCodeOracleProviderFailsCaseClosed() {
		evalCaseWithOracle();
		AgentEvaluationService service = service(dryRunAdapter());
		EvalRunCreateRequest request = new EvalRunCreateRequest();
		request.setSuiteId(1L);
		request.setExecutionIntent("DRY_RUN");

		service.createRun(request);

		DataAgentEvalCaseResult result = insertedResults.get(0);
		assertEquals("failed", result.getStatus());
		assertEquals(true, result.getHardFail());
		assertTrue(result.getFailureReasonsJson().contains("CODE_ORACLE_UNAVAILABLE"));
	}

	@Test
	void unknownExecutionIntentFailsClosed() {
		AgentEvaluationService service = service(plainAdapter());
		EvalRunCreateRequest request = new EvalRunCreateRequest();
		request.setSuiteId(1L);
		request.setExecutionIntent("YOLO");

		CheckedException ex = assertThrows(CheckedException.class, () -> service.createRun(request));

		assertEquals(AgentEvaluationErrorDict.REQUEST_INVALID.getValue(), ex.getCode());
		assertEquals(null, storedRun.get());
	}

	/** 模拟写关口在 DRY_RUN 作用域内拦截后回投违规（同线程 ThreadLocal + scopeKey 双通道均可达）。 */
	private AgentEvalAdapter violationRecordingAdapter() {
		AgentEvalAdapter adapter = mock(AgentEvalAdapter.class);
		when(adapter.adapterCode()).thenReturn("DATA_AGENT");
		when(adapter.supports("DATA_AGENT")).thenReturn(true);
		when(adapter.invoke(any())).thenAnswer(invocation -> {
			EvalInvocationPrepared prepared = invocation.getArgument(0);
			preparedHolder.set(prepared);
			assertTrue(ExecutionIntentContext.dryRunActive(), "DRY_RUN 作用域必须在用例执行期间生效");
			ExecutionIntentContext.recordViolation(prepared.executionScopeKey(),
					ExecutionIntentContext.VIOLATION_WRITE_ATTEMPT, "crm:update", "写能力在 DRY_RUN 下被调用");
			ExecutionIntentContext.recordViolation(prepared.executionScopeKey(),
					ExecutionIntentContext.VIOLATION_ISOLATION, "crm:query", "越权访问被拒绝");
			return invocationSuccess();
		});
		return adapter;
	}

	private AgentEvalAdapter plainAdapter() {
		AgentEvalAdapter adapter = mock(AgentEvalAdapter.class);
		when(adapter.adapterCode()).thenReturn("DATA_AGENT");
		when(adapter.supports("DATA_AGENT")).thenReturn(true);
		when(adapter.invoke(any())).thenAnswer(invocation -> {
			EvalInvocationPrepared prepared = invocation.getArgument(0);
			preparedHolder.set(prepared);
			assertFalse(ExecutionIntentContext.dryRunActive(), "LIVE 运行不得携带 DRY_RUN 作用域");
			return invocationSuccess();
		});
		return adapter;
	}

	private AgentEvalAdapter dryRunAdapter() {
		AgentEvalAdapter adapter = mock(AgentEvalAdapter.class);
		when(adapter.adapterCode()).thenReturn("DATA_AGENT");
		when(adapter.supports("DATA_AGENT")).thenReturn(true);
		when(adapter.invoke(any())).thenAnswer(invocation -> {
			EvalInvocationPrepared prepared = invocation.getArgument(0);
			preparedHolder.set(prepared);
			assertTrue(ExecutionIntentContext.dryRunActive(), "DRY_RUN 作用域必须在用例执行期间生效");
			return invocationSuccess();
		});
		return adapter;
	}

	private EvalInvocationResult invocationSuccess() {
		return new EvalInvocationResult(true, "答案", null, null, "runtime-1", 10L, null, null, null, 0, 0, null);
	}

	private void evalCaseWithOracle() {
		DataAgentEvalCase evalCase = new DataAgentEvalCase();
		evalCase.setId(5L);
		evalCase.setTenantId("tenant-1");
		evalCase.setSuiteId(1L);
		evalCase.setCaseName("冒烟");
		evalCase.setUserInput("你好");
		evalCase.setCodeOracleJson("{\"expectedStdout\":\"42\"}");
		when(caseMapper.findEnabledBySuiteId(1L)).thenReturn(List.of(evalCase));
	}

	@SuppressWarnings("unchecked")
	private org.springframework.beans.factory.ObjectProvider<EvalCodeOracleService> oracleProvider(
			EvalCodeOracleService oracle) {
		org.springframework.beans.factory.ObjectProvider<EvalCodeOracleService> provider = mock(
				org.springframework.beans.factory.ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(oracle);
		return provider;
	}

	private AgentEvaluationService service(AgentEvalAdapter adapter) {
		return service(adapter, mock(org.springframework.beans.factory.ObjectProvider.class));
	}

	private AgentEvaluationService service(AgentEvalAdapter adapter,
			org.springframework.beans.factory.ObjectProvider<EvalCodeOracleService> oracleProvider) {
		EvalRuntimeRunImportEnricher enricher = mock(EvalRuntimeRunImportEnricher.class);
		when(enricher.enrichTags(any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(enricher.enrichExpectedOutput(nullable(String.class), any(), any()))
			.thenAnswer(invocation -> invocation.getArgument(0));
		AgentEvaluationService service = new AgentEvaluationService(policyMapper, subjectMapper, suiteMapper,
				caseMapper, runMapper, resultMapper, mock(DataChatTurnService.class), mock(RuntimeRunService.class),
				mock(AgentRuntimeArtifactMapper.class), enricher,
				mock(DataAgentService.class),
				mock(com.sn68.agent.dataagent.employee.service.EmployeeReleaseLifecycleService.class),
				mock(com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper.class),
				mock(com.sn68.agent.dataagent.employee.service.EmployeeDeploymentService.class), authenticationContext,
				new ObjectMapper(), List.of(adapter),
				new DataAgentProperties(), mock(TransactionTemplate.class), oracleProvider);
		service.initEvaluationExecutor();
		// 用同线程执行器替换异步评估线程池，让 createRun 同步执行完用例，断言可确定
		ReflectionTestUtils.setField(service, "evaluationExecutor", new DirectExecutorService());
		return service;
	}

	/** 同线程执行器：submit 即在调用线程执行完毕。 */
	private static final class DirectExecutorService extends AbstractExecutorService {

		private volatile boolean shutdown;

		@Override
		public void shutdown() {
			shutdown = true;
		}

		@Override
		public List<Runnable> shutdownNow() {
			shutdown = true;
			return List.of();
		}

		@Override
		public boolean isShutdown() {
			return shutdown;
		}

		@Override
		public boolean isTerminated() {
			return shutdown;
		}

		@Override
		public boolean awaitTermination(long timeout, TimeUnit unit) {
			return true;
		}

		@Override
		public void execute(Runnable command) {
			command.run();
		}

	}

}
