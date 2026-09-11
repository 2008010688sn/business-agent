/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.optimization.dto;

import com.sn68.agent.framework.db.mybatisplus.page.PageRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 优化实验分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "优化实验分页查询请求")
public class OptExperimentQueryRequest extends PageRequest {

	@Schema(description = "实验名称")
	private String experimentName;

	@Schema(description = "评估对象ID")
	private Long subjectId;

	@Schema(description = "实验归属类型：DATA_AGENT 或 DIGITAL_EMPLOYEE")
	private String ownerType;

	@Schema(description = "实验归属业务 ID：agentId 或 digitalEmployeeId")
	private String ownerId;

	@Schema(description = "实验状态")
	private String status;

}
