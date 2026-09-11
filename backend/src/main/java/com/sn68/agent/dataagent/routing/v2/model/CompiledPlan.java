/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.v2.model;

import java.time.Instant;
import java.util.List;
import org.springframework.util.StringUtils;

/**
 * 服务端编译出的不可变执行计划(方案第四章):planHash + 租户/用户 + releaseId
 * + 策略快照 + 绝对截止时间 + 固定能力版本 + 类型化绑定 + 风险分级 + 审批要求 + 每步预算 + 幂等策略。
 *
 * <p>{@code workspaceId} 是 Workspace 删除后的遗留哈希字段，空值按 0 写入以保持 planHash 稳定。
 *
 * <p>steps 按 Kahn 拓扑序(同层按 stepKey 字典序)排列;canonicalJson 为 planHash 的哈希原文,
 * 字段顺序稳定且无多余空白;风险分级只取候选集中所用能力的最高风险,禁止采信提案。
 */
public record CompiledPlan(String planHash, String tenantId, Long workspaceId, Long userId, Long releaseId,
		String policySnapshot, Instant absoluteDeadline, RouteProposalMode mode, String clarifyQuestion,
		List<CompiledPlanStep> steps, CapabilityRiskLevel riskLevel, boolean approvalRequired, long totalBudgetTokens,
		CompiledPlanIdempotencyPolicy idempotencyPolicy, String canonicalJson) {

	/** canonical JSON 的固定 schema 版本号。 */
	public static final String SCHEMA_VERSION = "compiled-plan-v2";

	public CompiledPlan {
		if (!StringUtils.hasText(planHash) || !StringUtils.hasText(tenantId) || userId == null || releaseId == null) {
			throw new IllegalArgumentException("Compiled plan requires planHash, tenantId, userId and releaseId");
		}
		workspaceId = workspaceId == null ? 0L : workspaceId;
		if (mode == null || riskLevel == null || idempotencyPolicy == null || absoluteDeadline == null
				|| !StringUtils.hasText(canonicalJson) || totalBudgetTokens <= 0) {
			throw new IllegalArgumentException("Compiled plan requires mode, riskLevel, idempotencyPolicy, deadline and canonicalJson");
		}
		planHash = planHash.trim();
		tenantId = tenantId.trim();
		policySnapshot = StringUtils.hasText(policySnapshot) ? policySnapshot : "{}";
		steps = steps == null ? List.of() : List.copyOf(steps);
	}

}
