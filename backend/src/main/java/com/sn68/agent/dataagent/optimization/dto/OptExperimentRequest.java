/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.optimization.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 优化实验保存请求。
 */
@Data
@Schema(description = "优化实验保存请求")
public class OptExperimentRequest {

	@Schema(description = "实验名称")
	private String experimentName;

	@Schema(description = "评估对象ID")
	private Long subjectId;

	@Schema(description = "实验归属类型：DATA_AGENT 或 DIGITAL_EMPLOYEE，缺省从评估对象回填")
	private String ownerType;

	@Schema(description = "实验归属业务 ID：agentId 或 digitalEmployeeId，缺省从评估对象回填")
	private String ownerId;

	@Schema(description = "基线评估运行ID")
	private Long baselineRunId;

	@Schema(description = "实验说明")
	private String description;

}
