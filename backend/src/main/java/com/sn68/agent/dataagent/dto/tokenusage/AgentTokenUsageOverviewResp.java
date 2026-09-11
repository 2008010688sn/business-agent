/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.tokenusage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

/**
 * Agent Token 用量总览：总量摘要与按用户/Agent/模型/日期的分组明细。
 */
@Builder
@Schema(description = "AgentToken用量总览")
public record AgentTokenUsageOverviewResp(
		@Schema(description = "用量摘要") AgentTokenUsageSummaryResp summary,
		@Schema(description = "按用户分组明细") List<AgentTokenUsageBreakdownResp> userBreakdown,
		@Schema(description = "按Agent分组明细") List<AgentTokenUsageBreakdownResp> agentBreakdown,
		@Schema(description = "按模型分组明细") List<AgentTokenUsageBreakdownResp> modelBreakdown,
		@Schema(description = "按日期分组明细") List<AgentTokenUsageBreakdownResp> dayBreakdown
) {
}
