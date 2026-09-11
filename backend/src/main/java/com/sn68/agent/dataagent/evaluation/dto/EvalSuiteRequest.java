/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 评估集保存请求。
 */
@Data
@Schema(description = "评估集保存请求")
public class EvalSuiteRequest {

	@Schema(description = "评估集名称")
	private String suiteName;

	@Schema(description = "默认评估对象ID")
	private Long subjectId;

	@Schema(description = "评估策略ID")
	private Long policyId;

	@Schema(description = "评估集状态")
	private String status;

	@Schema(description = "评估集说明")
	private String description;

}
