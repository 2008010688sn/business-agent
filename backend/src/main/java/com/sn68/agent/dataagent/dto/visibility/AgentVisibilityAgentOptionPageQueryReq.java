/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.visibility;

import com.sn68.agent.framework.db.mybatisplus.page.PageRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent可见性Agent优化ion分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "Agent 可见性申请筛选选项分页查询")
public class AgentVisibilityAgentOptionPageQueryReq extends PageRequest {

	@Schema(description = "Agent ID")
	private Long agentId;

	@Schema(description = "搜索关键字")
	private String keyword;

	@Schema(description = "状态")
	private String status;

}
