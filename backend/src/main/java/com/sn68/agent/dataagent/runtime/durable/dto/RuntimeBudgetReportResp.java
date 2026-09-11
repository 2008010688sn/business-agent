/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * 租户运行成本报表：汇总 agent_runtime_budget_ledger，不计费、不换算单价。
 */
@Schema(description = "运行成本报表")
public record RuntimeBudgetReportResp(

		@Schema(description = "统计开始（含）")
		Instant fromTime,

		@Schema(description = "统计结束（不含）")
		Instant toTime,

		@Schema(description = "本租户合计")
		List<RuntimeBudgetTypeAmountResp> totals,

		@Schema(description = "按运行主体拆分")
		List<RuntimeBudgetOwnerRowResp> owners) {
}
