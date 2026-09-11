/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.shadow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.routing.RouteEngineMode;
import com.sn68.agent.dataagent.routing.RouteEngineModeProperties;
import com.sn68.agent.dataagent.routing.model.RouteCandidate;
import com.sn68.agent.dataagent.routing.model.RouteContext;
import com.sn68.agent.dataagent.routing.model.RouteDecision;
import com.sn68.agent.dataagent.routing.model.RouteDecisionType;
import com.sn68.agent.dataagent.routing.model.RouteDegradeMode;
import com.sn68.agent.dataagent.routing.model.RouteDependencyValueType;
import com.sn68.agent.dataagent.routing.model.RoutePlan;
import com.sn68.agent.dataagent.routing.model.RoutePlanStep;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import com.sn68.agent.dataagent.routing.model.RouteRules;
import com.sn68.agent.dataagent.routing.model.RouteSelection;
import com.sn68.agent.dataagent.routing.model.RouteStepInputMapping;
import com.sn68.agent.dataagent.routing.model.RouteStepOutputBinding;
import com.sn68.agent.dataagent.routing.model.RouteTargetRef;
import com.sn68.agent.dataagent.routing.model.RouteTargetType;
import com.sn68.agent.dataagent.routing.model.RouteTiming;
import com.sn68.agent.dataagent.routing.v2.PlanCompileException;
import com.sn68.agent.dataagent.routing.v2.PlanCompiler;
import com.sn68.agent.dataagent.routing.v2.ProposalForbiddenFieldGuard;
import com.sn68.agent.dataagent.routing.v2.RouteCandidateCapabilityAdapter;
import com.sn68.agent.dataagent.routing.v2.RouteProposalV2Parser;
import com.sn68.agent.dataagent.routing.v2.model.CompiledPlan;
import com.sn68.agent.dataagent.routing.v2.model.PlanCompileContext;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimePlan;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRun;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeRunMapper;
import com.sn68.agent.dataagent.runtime.durable.service.CompiledPlanPersistenceService;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Shadow 路由编译对比服务测试：对比记录生成、差异信号、影子落库、异常不外抛与 LEGACY 零开销。
 */
class ShadowRouteCompileServiceTest {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private static final RouteTargetRef TARGET_A = new RouteTargetRef(RouteTargetType.COLLABORATOR, 1L, null, null);

	private static final RouteTargetRef TARGET_B = new RouteTargetRef(RouteTargetType.COLLABORATOR, 2L, null, null);

	private final PlanCompiler compiler = new PlanCompiler(new RouteProposalV2Parser(OBJECT_MAPPER),
			new ProposalForbiddenFieldGuard(), OBJECT_MAPPER);

	private CompiledPlanPersistenceService persistenceService;

	private AgentRuntimeRunMapper runMapper;

	private ShadowRouteCompileService service;

	@BeforeEach
	void setUp() {
		persistenceService = mock(CompiledPlanPersistenceService.class);
		runMapper = mock(AgentRuntimeRunMapper.class);
		service = new ShadowRouteCompileService(properties(RouteEngineMode.SHADOW), compiler, persistenceService,
				runMapper, Runnable::run);
	}

	// ---------- 对比记录生成 ----------

	@Test
	void compilesMultiStepPlanAndPersistsShadowPlan() {
		AgentRuntimeRun run = mock(AgentRuntimeRun.class);
		when(run.getId()).thenReturn(77L);
		when(runMapper.findByRuntimeRequestId("req-1")).thenReturn(run);
		AgentRuntimePlan saved = mock(AgentRuntimePlan.class);
		when(saved.getId()).thenReturn(501L);
		when(persistenceService.saveShadow(eq(77L), any(CompiledPlan.class))).thenReturn(saved);

		ShadowRouteCompareResult result = service.compare(context("查客户并下单"), multiStepDecision(),
				candidates(TARGET_A, TARGET_B), Instant.now());

		assertEquals(ShadowRouteCompareResult.SCENARIO_COMPILED, result.scenario());
		assertEquals(ShadowRouteCompareResult.DIVERGENCE_NONE, result.divergence());
		assertEquals("MULTI_SELECT", result.v1Decision());
		assertEquals("MODEL_MULTI_SELECTED", result.v1ReasonCode());
		assertEquals(2, result.v1SelectionCount());
		assertEquals(2, result.v1PlanStepCount());
		assertEquals("s1->s2", result.v1StepOrder());
		assertEquals(2, result.candidateCount());
		assertEquals("ORCHESTRATION", result.v2Mode());
		assertEquals(2, result.v2StepCount());
		assertEquals("s1->s2", result.v2ExecutionOrder());
		assertEquals(64, result.planHash().length());
		assertEquals("READ_ONLY", result.v2RiskLevel());
		assertEquals(Boolean.FALSE, result.v2ApprovalRequired());
		assertNull(result.compileFailureCode());
		assertEquals(ShadowRouteCompareResult.PERSISTENCE_PERSISTED, result.persistence());
		assertEquals(501L, result.persistedPlanId());
		assertEquals("查客户并下单".length(), result.queryLength());
		assertNotNull(result.queryHash());
		assertEquals(16, result.queryHash().length());
		verify(persistenceService).saveShadow(eq(77L), any(CompiledPlan.class));
		verify(persistenceService, never()).save(anyLong(), any(CompiledPlan.class));
	}

	@Test
	void shadowPlanHashMatchesDirectCompilationOfSameProposal() {
		when(runMapper.findByRuntimeRequestId(anyString())).thenReturn(null);
		RouteContext context = context("查客户并下单");
		RouteDecision decision = multiStepDecision();
		List<RouteCandidate> candidates = candidates(TARGET_A, TARGET_B);

		ShadowProposalConversion conversion = RouteDecisionProposalConverter.convert(decision, context, candidates);
		CompiledPlan direct = compiler.compile(conversion.proposal(),
				RouteCandidateCapabilityAdapter.toCandidateSet(candidates), new PlanCompileContext("7",
						ShadowRouteCompileService.SHADOW_WORKSPACE_ID, 100L,
						ShadowRouteCompileService.SHADOW_RELEASE_ID, null, Instant.now().plusSeconds(60),
						ShadowRouteCompileService.SHADOW_STEP_BUDGET_TOKENS,
						ShadowRouteCompileService.SHADOW_TOTAL_BUDGET_TOKENS, null));
		ShadowRouteCompareResult result = service.compare(context, decision, candidates, Instant.now());

		assertEquals(direct.planHash(), result.planHash());
		assertEquals(ShadowRouteCompareResult.PERSISTENCE_SKIPPED_NO_RUN, result.persistence());
	}

	// ---------- 差异信号：V2 拒绝而 V1 通过 ----------

	@Test
	void recordsCompileRejectionWithFailureCodeWhenV1PlanUsesUndeclaredPorts() {
		// V1 候选未声明端口，携带 inputMappings 的计划在 V2 编译期必然 PORT_NOT_FOUND：
		// 这是"V2 拒绝而 V1 通过"的典型差异信号，必须完整记录失败码与原因
		ShadowRouteCompareResult result = service.compare(context("查客户并下单"), mappedDecision(),
				candidates(TARGET_A, TARGET_B), Instant.now());

		assertEquals(ShadowRouteCompareResult.SCENARIO_COMPILE_REJECTED, result.scenario());
		assertEquals(ShadowRouteCompareResult.DIVERGENCE_V2_REJECTED_V1_PASSED, result.divergence());
		assertEquals(PlanCompileException.PORT_NOT_FOUND, result.compileFailureCode());
		assertNotNull(result.compileFailureMessage());
		assertNull(result.planHash());
		assertEquals(ShadowRouteCompareResult.PERSISTENCE_SKIPPED_NOT_COMPILED, result.persistence());
		verify(persistenceService, never()).saveShadow(anyLong(), any(CompiledPlan.class));
		verify(persistenceService, never()).save(anyLong(), any(CompiledPlan.class));
	}

	// ---------- SKIPPED 场景 ----------

	@Test
	void recordsSkippedScenarioForClarifyDecision() {
		RouteDecision clarify = new RouteDecision(RouteDecisionType.CLARIFY, "AMBIGUOUS_CANDIDATES",
				RouteDegradeMode.NONE, List.of(), List.of(), null, false, RouteTiming.empty());

		ShadowRouteCompareResult result = service.compare(context("模糊问题"), clarify, candidates(TARGET_A),
				Instant.now());

		assertEquals(ShadowRouteCompareResult.SCENARIO_SKIPPED, result.scenario());
		assertEquals(ShadowProposalConversion.SKIP_NO_PLAN_DECISION, result.skippedReason());
		assertEquals(ShadowRouteCompareResult.DIVERGENCE_NOT_COMPARED, result.divergence());
		assertEquals(ShadowRouteCompareResult.PERSISTENCE_SKIPPED_NOT_COMPILED, result.persistence());
		verifyNoInteractions(persistenceService, runMapper);
	}

	@Test
	void skipsRemainingWorkWhenBudgetExhausted() {
		ShadowRouteCompareResult result = service.compare(context("查客户并下单"), multiStepDecision(),
				candidates(TARGET_A, TARGET_B), Instant.now().minus(ShadowRouteCompileService.SHADOW_TASK_BUDGET)
					.minusSeconds(1));

		assertEquals(ShadowRouteCompareResult.SCENARIO_SKIPPED, result.scenario());
		assertEquals("SHADOW_BUDGET_EXHAUSTED", result.skippedReason());
		verifyNoInteractions(persistenceService, runMapper);
	}

	// ---------- 异常安全：任何失败不得影响主链路 ----------

	@Test
	void neverPropagatesFailuresFromShadowPath() {
		when(runMapper.findByRuntimeRequestId(anyString())).thenThrow(new IllegalStateException("db down"));
		assertDoesNotThrow(() -> service.submitCompare(context("查客户并下单"), multiStepDecision(),
				candidates(TARGET_A, TARGET_B)));

		ShadowRouteCompileService rejecting = new ShadowRouteCompileService(properties(RouteEngineMode.SHADOW),
				compiler, persistenceService, runMapper, task -> {
					throw new RejectedExecutionException("queue full");
				});
		assertDoesNotThrow(() -> rejecting.submitCompare(context("查客户并下单"), multiStepDecision(),
				candidates(TARGET_A, TARGET_B)));
	}

	@Test
	void recordsPersistenceFailureWithoutThrowing() {
		AgentRuntimeRun run = mock(AgentRuntimeRun.class);
		when(run.getId()).thenReturn(77L);
		when(runMapper.findByRuntimeRequestId("req-1")).thenReturn(run);
		when(persistenceService.saveShadow(anyLong(), any(CompiledPlan.class)))
			.thenThrow(new IllegalStateException("insert failed"));

		ShadowRouteCompareResult result = assertDoesNotThrow(() -> service.compare(context("查客户并下单"),
				multiStepDecision(), candidates(TARGET_A, TARGET_B), Instant.now()));

		assertEquals(ShadowRouteCompareResult.SCENARIO_COMPILED, result.scenario());
		assertEquals(ShadowRouteCompareResult.PERSISTENCE_FAILED, result.persistence());
		assertNull(result.persistedPlanId());
	}

	// ---------- 模式开关 ----------

	@Test
	void legacyModeSubmitsNothingAndTouchesNothing() {
		AtomicInteger submitted = new AtomicInteger();
		ShadowRouteCompileService legacy = new ShadowRouteCompileService(properties(RouteEngineMode.LEGACY), compiler,
				persistenceService, runMapper, task -> submitted.incrementAndGet());

		legacy.submitCompare(context("查客户并下单"), multiStepDecision(), candidates(TARGET_A, TARGET_B));

		assertEquals(0, submitted.get());
		verifyNoInteractions(persistenceService, runMapper);
	}

	@Test
	void nativeModeFallsBackToLegacyBehaviourWithoutShadowTasks() {
		AtomicInteger submitted = new AtomicInteger();
		ShadowRouteCompileService nativeMode = new ShadowRouteCompileService(properties(RouteEngineMode.NATIVE),
				compiler, persistenceService, runMapper, task -> submitted.incrementAndGet());

		nativeMode.submitCompare(context("查客户并下单"), multiStepDecision(), candidates(TARGET_A, TARGET_B));
		nativeMode.submitCompare(context("查客户并下单"), multiStepDecision(), candidates(TARGET_A, TARGET_B));

		assertEquals(0, submitted.get());
		verifyNoInteractions(persistenceService, runMapper);
	}

	@Test
	void shadowModeRunsCompareThroughExecutor() {
		when(runMapper.findByRuntimeRequestId(anyString())).thenReturn(null);
		AtomicInteger submitted = new AtomicInteger();
		ShadowRouteCompileService counting = new ShadowRouteCompileService(properties(RouteEngineMode.SHADOW),
				compiler, persistenceService, runMapper, task -> {
					submitted.incrementAndGet();
					task.run();
				});

		counting.submitCompare(context("查客户并下单"), multiStepDecision(), candidates(TARGET_A, TARGET_B));

		assertEquals(1, submitted.get());
	}

	@Test
	void nativeModeIsRejectedByStartupValidation() {
		RouteEngineModeProperties properties = properties(RouteEngineMode.NATIVE);
		assertFalse(properties.isModeExecutable());
		assertTrue(properties(RouteEngineMode.LEGACY).isModeExecutable());
		assertTrue(properties(RouteEngineMode.SHADOW).isModeExecutable());
	}

	// ---------- 夹具 ----------

	private static RouteEngineModeProperties properties(RouteEngineMode mode) {
		RouteEngineModeProperties properties = new RouteEngineModeProperties();
		properties.setMode(mode);
		return properties;
	}

	private static RouteDecision multiStepDecision() {
		RoutePlanStep first = new RoutePlanStep("s1", TARGET_A, "查询客户信息", List.of(), "客户编号");
		RoutePlanStep second = new RoutePlanStep("s2", TARGET_B, "为客户创建订单", List.of("s1"), "订单结果");
		return multiSelect(new RoutePlan(List.of(first, second)));
	}

	private static RouteDecision mappedDecision() {
		RoutePlanStep first = new RoutePlanStep("s1", TARGET_A, "查询客户信息", List.of(), "客户编号",
				List.of(new RouteStepOutputBinding("customerId", RouteDependencyValueType.ID)), List.of());
		RoutePlanStep second = new RoutePlanStep("s2", TARGET_B, "为客户创建订单", List.of("s1"), "订单结果",
				List.of(), List.of(new RouteStepInputMapping("s1", "customerId", "customer",
						RouteDependencyValueType.ID)));
		return multiSelect(new RoutePlan(List.of(first, second)));
	}

	private static RouteDecision multiSelect(RoutePlan plan) {
		List<RouteSelection> selections = List.of(
				new RouteSelection(TARGET_A, null, 1L, RouteRisk.READ_ONLY, "checksum"),
				new RouteSelection(TARGET_B, null, 1L, RouteRisk.READ_ONLY, "checksum"));
		return new RouteDecision(RouteDecisionType.MULTI_SELECT, "MODEL_MULTI_SELECTED", RouteDegradeMode.NONE,
				selections, List.of(), null, true, RouteTiming.empty(), null, plan);
	}

	private static List<RouteCandidate> candidates(RouteTargetRef... targets) {
		return List.of(targets).stream().map(target -> new RouteCandidate(target, "7", 9L, "能力-" + target.targetId(),
				"描述", "QUERY", "REACT", RouteRules.empty(), RouteRisk.READ_ONLY, 10, 1L, 2L, "checksum",
				"fingerprint", null)).toList();
	}

	private static RouteContext context(String query) {
		return new RouteContext("7", 9L, "DATA_ANALYSIS", 33L, "100", "req-1", query, null, null, null,
				Instant.now().plusSeconds(60), 3);
	}

}
