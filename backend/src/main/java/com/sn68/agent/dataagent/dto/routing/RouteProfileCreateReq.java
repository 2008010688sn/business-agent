/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.routing;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * 创建路由 Profile 的请求，指定路由/向量模型与各阶段阈值。
 */
@Schema(description = "路由Profile创建请求")
public record RouteProfileCreateReq(
		@Schema(description = "Profile名称") String profileName,
		@Schema(description = "路由消歧模型配置ID") Long routeModelConfigId,
		@Schema(description = "向量模型配置ID") Long embeddingModelConfigId,
		@Schema(description = "是否启用词法自动选择") Boolean lexicalAutoSelectEnabled,
		@Schema(description = "是否启用语义召回") Boolean semanticRecallEnabled,
		@Schema(description = "是否启用语义自动选择") Boolean semanticAutoSelectEnabled,
		@Schema(description = "是否启用模型消歧") Boolean modelDisambiguationEnabled,
		@Schema(description = "词法最低得分阈值") Integer lexicalMinScore,
		@Schema(description = "词法最小分差阈值") Integer lexicalMinGap,
		@Schema(description = "向量召回阈值") BigDecimal vectorRecallThreshold,
		@Schema(description = "向量自动选择阈值") BigDecimal vectorAutoSelectThreshold,
		@Schema(description = "向量最小分差阈值") BigDecimal vectorMinGap,
		@Schema(description = "模型置信度阈值") BigDecimal modelConfidenceThreshold) {
}
