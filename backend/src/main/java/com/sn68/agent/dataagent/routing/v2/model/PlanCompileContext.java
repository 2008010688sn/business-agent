/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.v2.model;

import java.time.Instant;
import org.springframework.util.StringUtils;

/**
 * 服务端编译上下文:租户/用户/发布版本、策略快照、绝对截止时间、预算与敏感级别授权上限。
 *
 * <p>全部为服务端事实,提案不可影响;policySnapshot 为 JSON 字符串快照,缺省为 "{}"。
 * {@code workspaceId} 是 Workspace 删除后的遗留哈希字段，空值按 0 写入以保持 planHash 稳定。
 */
public record PlanCompileContext(String tenantId, Long workspaceId, Long userId, Long releaseId,
		String policySnapshot, Instant absoluteDeadline, long stepBudgetTokens, long totalBudgetTokens,
		PortSensitivity sensitivityClearance) {

	public PlanCompileContext {
		if (!StringUtils.hasText(tenantId) || userId == null || releaseId == null) {
			throw new IllegalArgumentException("Plan compile context requires tenantId, userId and releaseId");
		}
		workspaceId = workspaceId == null ? 0L : workspaceId;
		tenantId = tenantId.trim();
		policySnapshot = StringUtils.hasText(policySnapshot) ? policySnapshot : "{}";
		if (absoluteDeadline == null) {
			throw new IllegalArgumentException("Plan compile context requires an absoluteDeadline");
		}
		if (stepBudgetTokens <= 0 || totalBudgetTokens < stepBudgetTokens) {
			throw new IllegalArgumentException("Plan compile context budget parameters are invalid");
		}
		sensitivityClearance = sensitivityClearance == null ? PortSensitivity.INTERNAL : sensitivityClearance;
	}

}
