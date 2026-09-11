/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * 预算类型合计：amount 单位由 budgetType 决定，本报表不计费只汇总流水。
 */
@Schema(description = "预算类型合计")
public record RuntimeBudgetTypeAmountResp(

		@Schema(description = "预算类型：MODEL_CALL、TOOL_CALL、PROMPT_TOKENS、COMPLETION_TOKENS、DURATION_MS、COST")
		String budgetType,

		@Schema(description = "消耗合计")
		BigDecimal amount,

		@Schema(description = "产生该类型流水的运行数")
		Long runCount) {
}
