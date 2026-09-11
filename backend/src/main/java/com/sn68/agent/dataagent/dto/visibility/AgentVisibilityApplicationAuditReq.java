/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.visibility;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 审批 Agent 可见性申请的请求。
 */
@Data
@Schema(description = "DataAgent 可见性申请审批请求")
public class AgentVisibilityApplicationAuditReq {

	@Schema(description = "审批结果状态")
	private String status;

	@Schema(description = "审批意见")
	private String approvalComment;

	@Schema(description = "授权天数")
	private Integer grantDays;

}
