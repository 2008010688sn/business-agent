/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.entity;

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
 * DataAgent 评估策略。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("data_agent_eval_policy")
@Schema(description = "DataAgent 评估策略")
public class DataAgentEvalPolicy extends SuperEntity<Long> {

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

	@Schema(description = "策略编码")
	private String policyCode;

	@Schema(description = "策略名称")
	private String policyName;

	@Schema(description = "策略版本号")
	private Integer versionNo;

	@Schema(description = "策略状态")
	private String status;

	@Schema(description = "是否默认策略")
	private Boolean defaultFlag;

	@Schema(description = "适用评估对象类型")
	private String subjectType;

	@Schema(description = "评分权重JSON")
	private String scoreWeightsJson;

	@Schema(description = "评分器配置JSON")
	private String graderConfigJson;

	@Schema(description = "效率预算JSON")
	private String efficiencyBudgetJson;

	@Schema(description = "门禁规则JSON")
	private String gateRuleJson;

	@Schema(description = "硬失败规则JSON")
	private String hardFailRuleJson;

	@Schema(description = "策略说明")
	private String description;

}
