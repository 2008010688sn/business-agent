/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.visibility;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 用户提交 Agent 可见性（使用权限）申请的请求。
 */
@Data
@Schema(description = "DataAgent 可见性申请创建请求")
public class AgentVisibilityApplicationCreateReq {

	@Schema(description = "Agent ID")
	@NotNull(message = "agentId不能为空")
	private Long agentId;

	@Schema(description = "申请理由")
	private String reason;

	@Schema(description = "申请授权天数")
	private Integer grantDays;

}
