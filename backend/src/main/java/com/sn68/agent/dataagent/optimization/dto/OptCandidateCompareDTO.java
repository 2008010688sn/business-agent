/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.optimization.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.Builder;

/**
 * 候选版本对比结果。
 */
@Builder
@Schema(description = "候选版本对比结果")
public record OptCandidateCompareDTO(

		@Schema(description = "候选ID") Long candidateId,

		@Schema(description = "基线运行ID") Long baselineRunId,

		@Schema(description = "沙箱运行ID") Long sandboxRunId,

		@Schema(description = "基线平均分") BigDecimal baselineScore,

		@Schema(description = "候选平均分") BigDecimal candidateScore,

		@Schema(description = "分数差值") BigDecimal scoreDelta,

		@Schema(description = "基线通过率") BigDecimal baselinePassRate,

		@Schema(description = "候选通过率") BigDecimal candidatePassRate,

		@Schema(description = "通过率差值") BigDecimal passRateDelta,

		@Schema(description = "耗时变化百分比") BigDecimal durationDeltaPct,

		@Schema(description = "Token 变化百分比") BigDecimal tokenDeltaPct,

		@Schema(description = "候选运行执行意图（离线评估必须为 DRY_RUN）") String sandboxExecutionIntent,

		@Schema(description = "候选运行写副作用违规数（硬门禁须为 0）") Integer writeViolationCount,

		@Schema(description = "候选运行权限或租户隔离违规数（硬门禁须为 0）") Integer isolationViolationCount,

		@Schema(description = "分片评估结果（按租户/岗位/业务类型标签分片对比；无分片维度时如实降级并记录）")
		List<Map<String, Object>> shardResults,

		@Schema(description = "是否通过门禁") Boolean passed,

		@Schema(description = "门禁原因") List<String> gateReasons) {
}
