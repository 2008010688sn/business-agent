/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.tokenusage;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * Agent Token 用量按维度分组的统计明细。
 */
@Builder
@Schema(description = "AgentToken用量分组明细")
public record AgentTokenUsageBreakdownResp(
		@Schema(description = "分组键") String groupKey,
		@Schema(description = "分组名称") String groupName,
		@Schema(description = "请求次数") Long requestCount,
		@Schema(description = "总Token数") Long totalTokens,
		@Schema(description = "提示词Token数") Long promptTokens,
		@Schema(description = "补全Token数") Long completionTokens,
		@Schema(description = "实际Token数") Long actualTokens,
		@Schema(description = "预估Token数") Long estimatedTokens,
		@Schema(description = "未知次数") Long unknownCount
) {
}
