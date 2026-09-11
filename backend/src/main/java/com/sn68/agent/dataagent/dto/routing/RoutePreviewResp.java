/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.routing;

import com.sn68.agent.dataagent.routing.model.RouteRisk;
import com.sn68.agent.dataagent.routing.model.RouteTargetType;
import com.sn68.agent.dataagent.routing.model.RouteTiming;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 混合路由预览结果，含最终决策、候选列表与阶段耗时。
 */
@Schema(description = "混合路由预览结果")
public record RoutePreviewResp(
		@Schema(description = "路由决策结果") String decision,
		@Schema(description = "决策原因编码") String reasonCode,
		@Schema(description = "降级模式") String degradeMode,
		@Schema(description = "使用的路由Profile ID") Long profileId,
		@Schema(description = "是否调用了模型消歧") boolean modelInvoked,
		@Schema(description = "路由候选列表") List<Candidate> candidates,
		@Schema(description = "各阶段耗时统计") RouteTiming timing) {

	public RoutePreviewResp {
		candidates = candidates == null ? List.of() : List.copyOf(candidates);
	}

	/**
	 * 单个路由候选的评分明细。
	 */
	@Schema(description = "路由候选明细")
	public record Candidate(
			@Schema(description = "候选排名") int rank,
			@Schema(description = "目标类型") RouteTargetType targetType,
			@Schema(description = "目标ID") Long targetId,
			@Schema(description = "目标版本ID") Long targetVersionId,
			@Schema(description = "路由物料ID") Long routeArtifactId,
			@Schema(description = "目标名称") String name,
			@Schema(description = "词法匹配得分") int lexicalScore,
			@Schema(description = "向量相似度得分") Double vectorScore,
			@Schema(description = "命中的路由信号列表") List<String> matchedSignals,
			@Schema(description = "风险级别") RouteRisk riskLevel) {

		public Candidate {
			matchedSignals = matchedSignals == null ? List.of() : List.copyOf(matchedSignals);
		}
	}
}
