/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.dto;

import com.sn68.agent.framework.db.mybatisplus.page.PageRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 评估策略分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "评估策略分页查询请求")
public class EvalPolicyQueryRequest extends PageRequest {

	@Schema(description = "策略编码")
	private String policyCode;

	@Schema(description = "策略名称")
	private String policyName;

	@Schema(description = "策略状态")
	private String status;

	@Schema(description = "适用评估对象类型")
	private String subjectType;

	@Schema(description = "是否默认策略")
	private Boolean defaultFlag;

}
