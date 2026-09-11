/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.shadow;

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
import com.sn68.agent.dataagent.routing.v2.model.RouteBindingSourceKind;
import com.sn68.agent.dataagent.routing.v2.model.RouteControlEdge;
import com.sn68.agent.dataagent.routing.v2.model.RouteProposalBinding;
import com.sn68.agent.dataagent.routing.v2.model.RouteProposalMode;
import com.sn68.agent.dataagent.routing.v2.model.RouteProposalStep;
import com.sn68.agent.dataagent.routing.v2.model.RouteProposalV2;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V1 决策 → RouteProposalV2 确定性转换测试：多步/依赖/映射的字段级正确性与 SKIPPED 场景。
 */
class RouteDecisionProposalConverterTest {

	private static final RouteTargetRef TARGET_A = new RouteTargetRef(RouteTargetType.COLLABORATOR, 1L, null, null);

	private static final RouteTargetRef TARGET_B = new RouteTargetRef(RouteTargetType.COLLABORATOR, 2L, null, null);

	private static final RouteTargetRef TARGET_C = new RouteTargetRef(RouteTargetType.COLLABORATOR, 3L, null, null);

	@Test
	void convertsMultiStepPlanWithDependenciesAndMappings() {
		RoutePlanStep first = new RoutePlanStep("s1", TARGET_A, "查询客户信息", List.of(), "客户编号",
				List.of(new RouteStepOutputBinding("customerId", RouteDependencyValueType.ID)), List.of());
		RoutePlanStep second = new RoutePlanStep("s2", TARGET_B, "为客户创建订单", List.of("s1"), "订单结果",
				List.of(), List.of(new RouteStepInputMapping("s1", "customerId", "customer",
						RouteDependencyValueType.ID)));
		RouteDecision decision = multiSelect(new RoutePlan(List.of(first, second)), List.of(TARGET_A, TARGET_B));

		ShadowProposalConversion conversion = RouteDecisionProposalConverter.convert(decision, context("查客户并下单"),
				candidates(TARGET_A, TARGET_B, TARGET_C));

		assertFalse(conversion.skipped());
		RouteProposalV2 proposal = conversion.proposal();
		assertEquals(RouteProposalMode.ORCHESTRATION, proposal.mode());
		assertEquals(2, proposal.steps().size());
		RouteProposalStep stepOne = proposal.steps().get(0);
		assertEquals("s1", stepOne.stepKey());
		assertEquals("cap-1", stepOne.capabilityHandle());
		assertEquals("查询客户信息", stepOne.task());
		assertTrue(stepOne.bindings().isEmpty());
		RouteProposalStep stepTwo = proposal.steps().get(1);
		assertEquals("s2", stepTwo.stepKey());
		assertEquals("cap-2", stepTwo.capabilityHandle());
		assertEquals(1, stepTwo.bindings().size());
		RouteProposalBinding binding = stepTwo.bindings().get(0);
		assertEquals("customer", binding.targetPortHandle());
		assertEquals(RouteBindingSourceKind.STEP_OUTPUT, binding.sourceKind());
		assertEquals("s1", binding.sourceStepKey());
		assertEquals("customerId", binding.sourcePortHandle());
		assertEquals(List.of(new RouteControlEdge("s1", "s2")), proposal.controlEdges());
	}

	@Test
	void convertsDiamondDependenciesToControlEdges() {
		RoutePlanStep first = new RoutePlanStep("s1", TARGET_A, "汇总库存", List.of(), "库存");
		RoutePlanStep second = new RoutePlanStep("s2", TARGET_B, "汇总订单", List.of(), "订单");
		RoutePlanStep third = new RoutePlanStep("s3", TARGET_C, "生成报告", List.of("s1", "s2"), "报告");
		RouteDecision decision = multiSelect(new RoutePlan(List.of(first, second, third)),
				List.of(TARGET_A, TARGET_B, TARGET_C));

		ShadowProposalConversion conversion = RouteDecisionProposalConverter.convert(decision, context("先汇总再报告"),
				candidates(TARGET_A, TARGET_B, TARGET_C));

		assertFalse(conversion.skipped());
		assertEquals(List.of(new RouteControlEdge("s1", "s3"), new RouteControlEdge("s2", "s3")),
				conversion.proposal().controlEdges());
	}

	@Test
	void convertsSingleSelectWithoutPlanToDirectProposal() {
		RouteDecision decision = new RouteDecision(RouteDecisionType.SELECT, "UNIQUE_EXACT", RouteDegradeMode.NONE,
				List.of(selection(TARGET_B)), List.of(), null, false, RouteTiming.empty());

		ShadowProposalConversion conversion = RouteDecisionProposalConverter.convert(decision, context("查询库存"),
				candidates(TARGET_A, TARGET_B));

		assertFalse(conversion.skipped());
		RouteProposalV2 proposal = conversion.proposal();
		assertEquals(RouteProposalMode.DIRECT, proposal.mode());
		assertEquals(1, proposal.steps().size());
		assertEquals("s1", proposal.steps().get(0).stepKey());
		assertEquals("cap-2", proposal.steps().get(0).capabilityHandle());
		assertEquals("查询库存", proposal.steps().get(0).task());
		assertTrue(proposal.controlEdges().isEmpty());
	}

	@Test
	void convertsConfirmRequiredSinglePlanFallingBackToContextQuery() {
		RouteSelection selection = selection(TARGET_A);
		// RoutePlan.single 的 queryFragment 为 null，task 应回落到上下文 query
		RouteDecision decision = RouteDecision.confirmRequired(List.of(selection), null,
				RoutePlan.single(selection, null, "业务操作结果"), "MODEL_SELECTED_CONFIRM_REQUIRED",
				RouteTiming.empty());

		ShadowProposalConversion conversion = RouteDecisionProposalConverter.convert(decision, context("发起调拨"),
				candidates(TARGET_A));

		assertFalse(conversion.skipped());
		assertEquals(RouteProposalMode.DIRECT, conversion.proposal().mode());
		assertEquals("发起调拨", conversion.proposal().steps().get(0).task());
	}

	@Test
	void skipsDecisionsWithoutExecutablePlan() {
		RouteDecision clarify = new RouteDecision(RouteDecisionType.CLARIFY, "AMBIGUOUS_CANDIDATES",
				RouteDegradeMode.NONE, List.of(), List.of(), null, false, RouteTiming.empty());
		RouteDecision noMatch = RouteDecision.noMatch(RouteTiming.empty());
		RouteDecision unavailable = RouteDecision.unavailable("ROUTE_MODEL_UNAVAILABLE",
				RouteDegradeMode.DEGRADED_MODEL, RouteTiming.empty());

		List<RouteCandidate> candidates = candidates(TARGET_A);
		assertSkipped(clarify, candidates, ShadowProposalConversion.SKIP_NO_PLAN_DECISION);
		assertSkipped(noMatch, candidates, ShadowProposalConversion.SKIP_NO_PLAN_DECISION);
		assertSkipped(unavailable, candidates, ShadowProposalConversion.SKIP_NO_PLAN_DECISION);
	}

	@Test
	void skipsWhenCandidatesMissingOrTargetUnknown() {
		RouteDecision decision = new RouteDecision(RouteDecisionType.SELECT, "UNIQUE_EXACT", RouteDegradeMode.NONE,
				List.of(selection(TARGET_C)), List.of(), null, false, RouteTiming.empty());

		assertSkipped(decision, List.of(), ShadowProposalConversion.SKIP_EMPTY_CANDIDATES);
		assertSkipped(decision, candidates(TARGET_A, TARGET_B),
				ShadowProposalConversion.SKIP_TARGET_NOT_IN_CANDIDATES);
	}

	@Test
	void skipsWhenNoTaskTextAvailable() {
		RouteDecision decision = new RouteDecision(RouteDecisionType.SELECT, "UNIQUE_EXACT", RouteDegradeMode.NONE,
				List.of(selection(TARGET_A)), List.of(), null, false, RouteTiming.empty());

		ShadowProposalConversion conversion = RouteDecisionProposalConverter.convert(decision, context(" "),
				candidates(TARGET_A));

		assertTrue(conversion.skipped());
		assertEquals(ShadowProposalConversion.SKIP_TASK_TEXT_MISSING, conversion.skippedReason());
		assertNull(conversion.proposal());
	}

	private static void assertSkipped(RouteDecision decision, List<RouteCandidate> candidates, String reason) {
		ShadowProposalConversion conversion = RouteDecisionProposalConverter.convert(decision, context("查询"),
				candidates);
		assertTrue(conversion.skipped());
		assertEquals(reason, conversion.skippedReason());
	}

	private static RouteDecision multiSelect(RoutePlan plan, List<RouteTargetRef> targets) {
		List<RouteSelection> selections = targets.stream().map(RouteDecisionProposalConverterTest::selection).toList();
		return new RouteDecision(RouteDecisionType.MULTI_SELECT, "MODEL_MULTI_SELECTED", RouteDegradeMode.NONE,
				selections, List.of(), null, true, RouteTiming.empty(), null, plan);
	}

	private static RouteSelection selection(RouteTargetRef target) {
		return new RouteSelection(target, null, 1L, RouteRisk.READ_ONLY, "checksum");
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
