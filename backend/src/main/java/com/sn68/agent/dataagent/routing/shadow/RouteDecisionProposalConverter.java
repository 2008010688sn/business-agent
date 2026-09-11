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
import com.sn68.agent.dataagent.routing.model.RoutePlanStep;
import com.sn68.agent.dataagent.routing.model.RouteTargetRef;
import com.sn68.agent.dataagent.routing.v2.RouteCandidateCapabilityAdapter;
import com.sn68.agent.dataagent.routing.v2.model.RouteBindingSourceKind;
import com.sn68.agent.dataagent.routing.v2.model.RouteControlEdge;
import com.sn68.agent.dataagent.routing.v2.model.RouteProposalBinding;
import com.sn68.agent.dataagent.routing.v2.model.RouteProposalMode;
import com.sn68.agent.dataagent.routing.v2.model.RouteProposalStep;
import com.sn68.agent.dataagent.routing.v2.model.RouteProposalV2;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * V1 RoutePlan / RouteSelection → RouteProposalV2 的确定性转换器：纯内存映射，不调用模型
 * （避免影子链路成本翻倍），与 {@link RouteCandidateCapabilityAdapter} 的句柄规则严格一致。
 *
 * <p>映射规则（路由引擎工作流约定）：stepKey 沿用 V1 stepId；capabilityHandle 由 V1 target
 * 反查候选序号句柄；inputMappings → bindings（STEP_OUTPUT）；dependsOn → controlEdges；
 * 单选 / 单步计划转为 DIRECT 单步提案，多步计划转为 ORCHESTRATION；CLARIFY / NO_MATCH
 * 等无计划决策返回 SKIPPED 场景，不伪造提案。
 */
public final class RouteDecisionProposalConverter {

	/** V1 单选决策无计划步骤时的固定 stepKey，与 V1 RoutePlan.single 的 "s1" 约定一致。 */
	static final String SINGLE_STEP_KEY = "s1";

	private RouteDecisionProposalConverter() {
	}

	/** 转换失败不抛异常，统一返回带原因的 SKIPPED 结果，由影子服务记录。 */
	public static ShadowProposalConversion convert(RouteDecision decision, RouteContext context,
			List<RouteCandidate> candidates) {
		if (decision == null || !executable(decision.decision())) {
			return ShadowProposalConversion.skipped(ShadowProposalConversion.SKIP_NO_PLAN_DECISION);
		}
		if (candidates == null || candidates.isEmpty()) {
			return ShadowProposalConversion.skipped(ShadowProposalConversion.SKIP_EMPTY_CANDIDATES);
		}
		Map<RouteTargetRef, String> handleByTarget = RouteCandidateCapabilityAdapter.toHandleByTarget(candidates);
		if (decision.plan().steps().isEmpty()) {
			return singleSelectionProposal(decision, context, handleByTarget);
		}
		return planProposal(decision, context, handleByTarget);
	}

	private static boolean executable(RouteDecisionType type) {
		return type == RouteDecisionType.SELECT || type == RouteDecisionType.MULTI_SELECT
				|| type == RouteDecisionType.CONFIRM_REQUIRED;
	}

	/** 单选且无计划（普通 SELECT）：转为单步 DIRECT 提案，task 取上下文查询。 */
	private static ShadowProposalConversion singleSelectionProposal(RouteDecision decision, RouteContext context,
			Map<RouteTargetRef, String> handleByTarget) {
		if (decision.selections().size() != 1) {
			return ShadowProposalConversion.skipped(ShadowProposalConversion.SKIP_PLAN_MISSING);
		}
		String handle = handleByTarget.get(decision.selections().get(0).target());
		if (handle == null) {
			return ShadowProposalConversion.skipped(ShadowProposalConversion.SKIP_TARGET_NOT_IN_CANDIDATES);
		}
		String task = taskText(null, context);
		if (task == null) {
			return ShadowProposalConversion.skipped(ShadowProposalConversion.SKIP_TASK_TEXT_MISSING);
		}
		RouteProposalStep step = new RouteProposalStep(SINGLE_STEP_KEY, handle, task, List.of());
		return ShadowProposalConversion.of(new RouteProposalV2(RouteProposalV2.SCHEMA_VERSION,
				RouteProposalMode.DIRECT, List.of(step), List.of(), null));
	}

	/** 携带计划的决策（MULTI_SELECT / CONFIRM_REQUIRED）：逐步映射步骤、绑定与控制边。 */
	private static ShadowProposalConversion planProposal(RouteDecision decision, RouteContext context,
			Map<RouteTargetRef, String> handleByTarget) {
		List<RoutePlanStep> planSteps = decision.plan().steps();
		List<RouteProposalStep> steps = new ArrayList<>(planSteps.size());
		List<RouteControlEdge> controlEdges = new ArrayList<>();
		for (RoutePlanStep planStep : planSteps) {
			String handle = handleByTarget.get(planStep.target());
			if (handle == null) {
				return ShadowProposalConversion.skipped(ShadowProposalConversion.SKIP_TARGET_NOT_IN_CANDIDATES);
			}
			String task = taskText(planStep.queryFragment(), context);
			if (task == null) {
				return ShadowProposalConversion.skipped(ShadowProposalConversion.SKIP_TASK_TEXT_MISSING);
			}
			steps.add(new RouteProposalStep(planStep.stepId(), handle, task, bindings(planStep)));
			planStep.dependsOn().forEach(dependency -> controlEdges.add(new RouteControlEdge(dependency,
					planStep.stepId())));
		}
		// V1 校验保证单步计划不可能有前置依赖，因此单步一定满足 DIRECT 的"无控制边"约束
		RouteProposalMode mode = steps.size() == 1 ? RouteProposalMode.DIRECT : RouteProposalMode.ORCHESTRATION;
		return ShadowProposalConversion.of(new RouteProposalV2(RouteProposalV2.SCHEMA_VERSION, mode, steps,
				controlEdges, null));
	}

	/** V1 inputMappings → V2 STEP_OUTPUT 绑定：targetField→targetPortHandle、sourceField→sourcePortHandle。 */
	private static List<RouteProposalBinding> bindings(RoutePlanStep planStep) {
		return planStep.inputMappings().stream()
			.map(mapping -> new RouteProposalBinding(mapping.targetField(), RouteBindingSourceKind.STEP_OUTPUT,
					mapping.sourceStepId(), mapping.sourceField()))
			.toList();
	}

	/** V2 步骤 task 必填：优先 V1 queryFragment，缺省回落到本轮路由查询文本。 */
	private static String taskText(String queryFragment, RouteContext context) {
		if (StringUtils.hasText(queryFragment)) {
			return queryFragment.trim();
		}
		String query = context == null ? null : context.query();
		return StringUtils.hasText(query) ? query.trim() : null;
	}

}
