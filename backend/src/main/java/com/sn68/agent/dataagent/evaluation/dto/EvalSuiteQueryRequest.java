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
 * 评估集分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "评估集分页查询请求")
public class EvalSuiteQueryRequest extends PageRequest {

	@Schema(description = "评估集名称")
	private String suiteName;

	@Schema(description = "默认评估对象ID")
	private Long subjectId;

	@Schema(description = "评估策略ID")
	private Long policyId;

	@Schema(description = "评估集状态")
	private String status;

}
