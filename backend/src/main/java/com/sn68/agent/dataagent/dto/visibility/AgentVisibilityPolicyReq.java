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
 * Agent可见性策略请求。
 */
@Data
@Schema(description = "DataAgent 可见性策略修改请求")
public class AgentVisibilityPolicyReq {

	@Schema(description = "Agent ID")
	@NotNull(message = "agentId不能为空")
	private Long agentId;

	@Schema(description = "会话可见范围")
	private String conversationScope;

	@Schema(description = "目录可见范围")
	private String catalogScope;

	@Schema(description = "申请模式")
	private String applyMode;

	@Schema(description = "审批模式")
	private String approvalMode;

	@Schema(description = "审批工作流编码")
	private String workflowFlowCode;

	@Schema(description = "风险级别")
	private String riskLevel;

	@Schema(description = "默认授权天数")
	private Integer defaultGrantDays;

	@Schema(description = "审批人配置（JSON文本）")
	private String approverConfigJson;

	@Schema(description = "策略状态")
	private String status;

}
