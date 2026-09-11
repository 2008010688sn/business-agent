/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 按运行主体（数字员工 / 调用方）汇总的预算行。
 */
@Schema(description = "运行主体预算汇总")
public record RuntimeBudgetOwnerRowResp(

		@Schema(description = "数字员工ID，可空")
		@JsonSerialize(using = ToStringSerializer.class)
		Long digitalEmployeeId,

		@Schema(description = "运行主体类型")
		String ownerType,

		@Schema(description = "运行主体ID")
		@JsonSerialize(using = ToStringSerializer.class)
		Long ownerId,

		@Schema(description = "该主体下有流水的运行数（各类型取最大）")
		Long runCount,

		@Schema(description = "按预算类型的消耗")
		List<RuntimeBudgetTypeAmountResp> amounts) {
}
