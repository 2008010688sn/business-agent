/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.dataagent.repository.typehandler.JsonbStringTypeHandler;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 持久运行时编译执行计划实体（CompiledPlan 持久化，发布后不可原地修改）。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "agent_runtime_plan", autoResultMap = true)
@Schema(description = "持久运行时编译执行计划")
public class AgentRuntimePlan extends SuperEntity<Long> {

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

	@Schema(description = "计划版本，同一 run 内唯一")
	private Integer planVersion;

	@Schema(description = "规范化计划内容哈希")
	private String planHash;

	@Schema(description = "计划状态：ACTIVE、SUPERSEDED、SHADOW（影子对比计划，不参与执行）")
	private String status;

	@Schema(description = "编译后的规范化计划 JSON")
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String compiledPlan;

	@Schema(description = "DAG 依赖边清单，元素为 [fromStepKey, toStepKey]")
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String dagEdges;

	@Schema(description = "步骤数量")
	private Integer stepCount;

	@Schema(description = "执行策略快照 JSON")
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String policySnapshot;

	@Schema(description = "计划级绝对截止时间")
	private Instant absoluteDeadlineAt;

}
