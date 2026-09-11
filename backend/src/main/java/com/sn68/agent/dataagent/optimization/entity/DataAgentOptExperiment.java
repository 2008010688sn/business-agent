/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.optimization.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Agent 自优化实验。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("data_agent_opt_experiment")
@Schema(description = "Agent 自优化实验")
public class DataAgentOptExperiment extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Override
	@JsonSerialize(using = ToStringSerializer.class)
	public Long getId() {
		return super.getId();
	}

	@Schema(description = "租户ID")
	private String tenantId;

	@Schema(description = "租户编码")
	private String tenantCode;

	@Schema(description = "实验名称")
	private String experimentName;

	@JsonSerialize(using = ToStringSerializer.class)
	@Schema(description = "评估对象ID")
	private Long subjectId;

	@Schema(description = "实验归属类型：DATA_AGENT 或 DIGITAL_EMPLOYEE")
	private String ownerType;

	@Schema(description = "实验归属业务 ID：agentId 或 digitalEmployeeId")
	private String ownerId;

	@JsonSerialize(using = ToStringSerializer.class)
	@Schema(description = "基线评估运行ID")
	private Long baselineRunId;

	@Schema(description = "实验状态")
	private String status;

	@Schema(description = "基线指标JSON")
	private String baselineMetricsJson;

	@Schema(description = "诊断结果JSON")
	private String diagnosisJson;

	@Schema(description = "实验说明")
	private String description;

}
