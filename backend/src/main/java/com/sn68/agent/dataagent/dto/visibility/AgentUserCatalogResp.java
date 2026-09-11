/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.visibility;

import com.sn68.agent.dataagent.entity.DataAgent;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户视角的 Agent 目录项：携带 Agent 信息与当前用户的可见性/申请状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "用户 Agent 目录项")
public class AgentUserCatalogResp {

	@Schema(description = "Agent信息")
	private DataAgent agent;

	@Schema(description = "可见性状态")
	private String visibilityStatus;

	@Schema(description = "申请模式")
	private String applyMode;

	@Schema(description = "审批模式")
	private String approvalMode;

	@Schema(description = "当前用户是否可申请")
	private Boolean canApply;

	@Schema(description = "不可申请原因")
	private String disabledReason;

	@Schema(description = "进行中的申请ID")
	private Long pendingApplicationId;

}
