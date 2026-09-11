/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 持久运行时预算消耗流水实体（按 budgetType 记账，金额/数量统一用 BigDecimal 承载）。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_runtime_budget_ledger")
@Schema(description = "持久运行时预算消耗流水")
public class AgentRuntimeBudgetLedger extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Override
	@JsonSerialize(using = ToStringSerializer.class)
	public Long getId() {
		return super.getId();
	}

	@Schema(description = "租户ID")
	private String tenantId;

	@Schema(description = "所属运行ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long runId;

	@Schema(description = "关联步骤键，运行级消耗为空")
	private String stepKey;

	@Schema(description = "预算类型：MODEL_CALL、TOOL_CALL、PROMPT_TOKENS、COMPLETION_TOKENS、DURATION_MS、COST")
	private String budgetType;

	@Schema(description = "消耗数量，单位由 budgetType 决定")
	private BigDecimal amount;

	@Schema(description = "来源调用ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long refInvocationId;

	@Schema(description = "备注")
	private String remark;

	@Schema(description = "消耗发生时间")
	private Instant occurredAt;

}
