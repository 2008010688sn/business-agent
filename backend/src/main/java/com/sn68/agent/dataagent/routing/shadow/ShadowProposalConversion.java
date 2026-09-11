/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.shadow;

import com.sn68.agent.dataagent.routing.v2.model.RouteProposalV2;

/**
 * V1 决策 → RouteProposalV2 确定性转换结果：要么产出提案，要么给出跳过原因（SKIPPED 场景），
 * 二者互斥；跳过不是失败，代表该 V1 决策不具备可对比的执行计划（如 CLARIFY / NO_MATCH）。
 */
public record ShadowProposalConversion(RouteProposalV2 proposal, String skippedReason) {

	/** V1 决策类型不携带执行计划（CLARIFY / NO_MATCH / ROUTE_UNAVAILABLE / DIRECT 等）。 */
	public static final String SKIP_NO_PLAN_DECISION = "NO_PLAN_DECISION";

	/** 当次路由候选列表为空，无法构造候选能力集（不伪造候选）。 */
	public static final String SKIP_EMPTY_CANDIDATES = "EMPTY_CANDIDATES";

	/** 决策目标不在当次候选列表内，无法反查能力句柄（不伪造候选）。 */
	public static final String SKIP_TARGET_NOT_IN_CANDIDATES = "TARGET_NOT_IN_CANDIDATES";

	/** 步骤既无 queryFragment、上下文 query 也为空，V2 步骤 task 必填。 */
	public static final String SKIP_TASK_TEXT_MISSING = "TASK_TEXT_MISSING";

	/** 多选决策缺失执行计划（V1 校验下不应出现，防御性记录）。 */
	public static final String SKIP_PLAN_MISSING = "PLAN_MISSING";

	public ShadowProposalConversion {
		if (proposal == null && skippedReason == null || proposal != null && skippedReason != null) {
			throw new IllegalArgumentException("Shadow proposal conversion requires exactly one of proposal / skippedReason");
		}
	}

	public static ShadowProposalConversion of(RouteProposalV2 proposal) {
		return new ShadowProposalConversion(proposal, null);
	}

	public static ShadowProposalConversion skipped(String reason) {
		return new ShadowProposalConversion(null, reason);
	}

	public boolean skipped() {
		return proposal == null;
	}

}
