/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import lombok.Builder;

/**
 * 评估运行失败汇总。
 */
@Builder
@Schema(description = "评估运行失败汇总")
public record EvalRunFailureSummaryDTO(

		@Schema(description = "评估运行ID") Long runId,

		@Schema(description = "失败结果数") Integer failedCount,

		@Schema(description = "硬失败结果数") Integer hardFailCount,

		@Schema(description = "失败原因明细") List<Map<String, Object>> failureReasons) {
}
