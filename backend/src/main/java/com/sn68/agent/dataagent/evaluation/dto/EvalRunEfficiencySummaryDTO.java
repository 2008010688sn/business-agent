/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.Builder;

/**
 * 评估运行效率汇总。
 */
@Builder
@Schema(description = "评估运行效率汇总")
public record EvalRunEfficiencySummaryDTO(

		@Schema(description = "评估运行ID") Long runId,

		@Schema(description = "结果总数") Integer totalCount,

		@Schema(description = "平均耗时毫秒") BigDecimal averageDurationMs,

		@Schema(description = "P90 耗时毫秒") Long p90DurationMs,

		@Schema(description = "最大耗时毫秒") Long maxDurationMs,

		@Schema(description = "超时结果数") Integer timeoutCount,

		@Schema(description = "慢请求结果数") Integer slowCount,

		@Schema(description = "慢请求原因明细") List<Map<String, Object>> slowReasons) {
}
