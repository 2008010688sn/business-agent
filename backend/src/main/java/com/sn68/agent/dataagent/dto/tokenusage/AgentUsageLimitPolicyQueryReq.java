/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.tokenusage;

import com.sn68.agent.framework.db.mybatisplus.page.PageRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent用量Limit策略查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "DataAgent 用量限制策略查询")
public class AgentUsageLimitPolicyQueryReq extends PageRequest {

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

	@Schema(description = "是否启用")
	private Boolean enabled;

}
