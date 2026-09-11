/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.dto.routing;

import com.sn68.agent.dataagent.properties.DataAgentProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * 路由平台只读配置视图：平台默认值、校验约束与运行时预算。
 */
@Schema(description = "路由Profile平台配置")
public record RouteProfileConfigurationDTO(
		@Schema(description = "平台默认值") Defaults defaults,
		@Schema(description = "校验约束") Constraints constraints,
		@Schema(description = "运行时耗时预算") RuntimeBudget runtimeBudget,
		@Schema(description = "探针超时配置") ProbeTimeouts probeTimeouts) {

	public static RouteProfileConfigurationDTO from(DataAgentProperties.Routing routing, Defaults defaults,
			Constraints constraints) {
		return new RouteProfileConfigurationDTO(defaults, constraints,
				new RuntimeBudget(routing.getTotalTimeout().toMillis(), routing.getVectorTimeout().toMillis(),
						routing.getModelTimeout().toMillis(), routing.getModelMinStart().toMillis(),
						routing.getFinishBuffer().toMillis()),
				new ProbeTimeouts(routing.getModelProbeTimeout().toMillis(),
						routing.getModelProbeConnectTimeout().toMillis(), routing.getEmbeddingProbeTimeout().toMillis()));
	}

	/**
	 * 新建 Profile 时使用的平台默认阈值与开关。
	 */
	@Schema(description = "路由平台默认值")
	public record Defaults(
			@Schema(description = "是否启用词法自动选择") boolean lexicalAutoSelectEnabled,
			@Schema(description = "是否启用语义召回") boolean semanticRecallEnabled,
			@Schema(description = "是否启用语义自动选择") boolean semanticAutoSelectEnabled,
			@Schema(description = "是否启用模型消歧") boolean modelDisambiguationEnabled,
			@Schema(description = "词法最低得分阈值") int lexicalMinScore,
			@Schema(description = "词法最小分差阈值") int lexicalMinGap,
			@Schema(description = "向量召回阈值") BigDecimal vectorRecallThreshold,
			@Schema(description = "向量自动选择阈值") BigDecimal vectorAutoSelectThreshold,
			@Schema(description = "向量最小分差阈值") BigDecimal vectorMinGap,
			@Schema(description = "模型置信度阈值") BigDecimal modelConfidenceThreshold) {
	}

	/**
	 * 保存 Profile 时前后端共用的校验约束。
	 */
	@Schema(description = "路由配置校验约束")
	public record Constraints(
			@Schema(description = "Profile名称最大长度") int profileNameMaxLength,
			@Schema(description = "词法阈值下限") int lexicalThresholdMin,
			@Schema(description = "词法阈值上限") int lexicalThresholdMax,
			@Schema(description = "比例类阈值下限") BigDecimal ratioMin,
			@Schema(description = "比例类阈值上限") BigDecimal ratioMax,
			@Schema(description = "启用模型消歧时是否必须配置路由模型") boolean routeModelRequiredWhenDisambiguationEnabled,
			@Schema(description = "启用语义能力时是否必须配置向量模型") boolean embeddingModelRequiredWhenSemanticEnabled,
			@Schema(description = "向量召回阈值是否不得超过自动选择阈值") boolean vectorRecallThresholdMustNotExceedAutoSelectThreshold) {
	}

	/**
	 * 路由运行时各阶段耗时预算（毫秒）。
	 */
	@Schema(description = "路由运行时耗时预算")
	public record RuntimeBudget(
			@Schema(description = "总超时（毫秒）") long totalTimeoutMs,
			@Schema(description = "向量阶段超时（毫秒）") long vectorTimeoutMs,
			@Schema(description = "模型阶段超时（毫秒）") long modelTimeoutMs,
			@Schema(description = "模型阶段最小启动预留（毫秒）") long modelMinStartMs,
			@Schema(description = "收尾缓冲（毫秒）") long finishBufferMs) {
	}

	/**
	 * 模型与向量探针的超时配置（毫秒）。
	 */
	@Schema(description = "路由探针超时配置")
	public record ProbeTimeouts(
			@Schema(description = "模型探针超时（毫秒）") long modelTimeoutMs,
			@Schema(description = "模型探针连接超时（毫秒）") long modelConnectTimeoutMs,
			@Schema(description = "向量探针超时（毫秒）") long embeddingTimeoutMs) {
	}

}
