/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.tokenusage;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Agent用量Limit策略请求。
 */
@Data
@Schema(description = "DataAgent 用量限制策略保存请求")
public class AgentUsageLimitPolicyReq {

	@Schema(description = "限制范围类型")
	private String scopeType;

	@Schema(description = "限制范围主体ID")
	private String scopeId;

	@Schema(description = "Agent ID")
	private Long agentId;

	@Schema(description = "模型配置ID")
	private Long modelConfigId;

	@Schema(description = "策略类型")
	private String policyType;

	@Schema(description = "统计窗口类型")
	private String windowType;

	@Schema(description = "统计窗口时长（秒）")
	private Long windowSeconds;

	@Schema(description = "限制阈值")
	private Long limitValue;

	@Schema(description = "预警阈值比例")
	private Double warnThresholdRatio;

	@Schema(description = "超限动作")
	private String action;

	@Schema(description = "是否启用")
	private Boolean enabled;

	@Schema(description = "描述")
	private String description;

}
