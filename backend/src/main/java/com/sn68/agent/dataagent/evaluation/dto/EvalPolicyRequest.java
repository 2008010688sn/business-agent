/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 评估策略保存请求。
 */
@Data
@Schema(description = "评估策略保存请求")
public class EvalPolicyRequest {

	@Schema(description = "策略编码")
	private String policyCode;

	@Schema(description = "策略名称")
	private String policyName;

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
