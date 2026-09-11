/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.routing;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.dataagent.entity.DataAgentRouteProfile;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 路由 Profile 详情视图，含模型配置、阈值、探针与构建状态。
 */
@Schema(description = "路由Profile详情")
public record RouteProfileResp(
		@Schema(description = "Profile ID") @JsonSerialize(using = ToStringSerializer.class) Long id,
		@Schema(description = "Profile名称") String profileName,
		@Schema(description = "Profile状态") String status,
		@Schema(description = "路由消歧模型配置ID") @JsonSerialize(using = ToStringSerializer.class) Long routeModelConfigId,
		@Schema(description = "向量模型配置ID") @JsonSerialize(using = ToStringSerializer.class) Long embeddingModelConfigId,
		@Schema(description = "是否启用词法自动选择") Boolean lexicalAutoSelectEnabled,
		@Schema(description = "是否启用语义召回") Boolean semanticRecallEnabled,
		@Schema(description = "是否启用语义自动选择") Boolean semanticAutoSelectEnabled,
		@Schema(description = "是否启用模型消歧") Boolean modelDisambiguationEnabled,
		@Schema(description = "词法最低得分阈值") Integer lexicalMinScore,
		@Schema(description = "词法最小分差阈值") Integer lexicalMinGap,
		@Schema(description = "向量召回阈值") BigDecimal vectorRecallThreshold,
		@Schema(description = "向量自动选择阈值") BigDecimal vectorAutoSelectThreshold,
		@Schema(description = "向量最小分差阈值") BigDecimal vectorMinGap,
		@Schema(description = "模型置信度阈值") BigDecimal modelConfidenceThreshold,
		@Schema(description = "向量模型指纹") String embeddingFingerprint,
		@Schema(description = "向量维度") Integer embeddingDimension,
		@Schema(description = "严格Schema探针状态") String strictSchemaStatus,
		@Schema(description = "探针最近检查时间") Instant probeCheckedAt,
		@Schema(description = "构建状态") String buildStatus,
		@Schema(description = "物料总数") Integer buildTotal,
		@Schema(description = "已就绪数量") Integer buildReady,
		@Schema(description = "构建失败数量") Integer buildFailed,
		@Schema(description = "最近一次错误编码") String lastErrorCode,
		@Schema(description = "乐观锁版本号") Long revision,
		@Schema(description = "路由模型能力探测结果") RouteCapabilityDTO modelCapability,
		@Schema(description = "向量模型能力探测结果") RouteCapabilityDTO embeddingCapability) {

	public static RouteProfileResp from(DataAgentRouteProfile profile) {
		if (profile == null) {
			return null;
		}
		return new RouteProfileResp(profile.getId(), profile.getProfileName(), profile.getStatus(),
				profile.getRouteModelConfigId(), profile.getEmbeddingModelConfigId(),
				profile.getLexicalAutoSelectEnabled(), profile.getSemanticRecallEnabled(),
				profile.getSemanticAutoSelectEnabled(),
				profile.getModelDisambiguationEnabled(), profile.getLexicalMinScore(), profile.getLexicalMinGap(),
				profile.getVectorRecallThreshold(), profile.getVectorAutoSelectThreshold(), profile.getVectorMinGap(),
				profile.getModelConfidenceThreshold(), profile.getEmbeddingFingerprint(),
				profile.getEmbeddingDimension(), profile.getStrictSchemaStatus(), profile.getProbeCheckedAt(),
				profile.getBuildStatus(), profile.getBuildTotal(), profile.getBuildReady(), profile.getBuildFailed(),
				profile.getLastErrorCode(), profile.getRevision(), RouteCapabilityDTO.routeModel(profile),
				RouteCapabilityDTO.embedding(profile));
	}
}
