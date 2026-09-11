/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.visibility;

import com.sn68.agent.framework.db.mybatisplus.page.PageRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent可见性授权分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "DataAgent 可见性授权分页查询")
public class AgentVisibilityGrantPageQueryReq extends PageRequest {

	@Schema(description = "Agent ID")
	@NotNull(message = "agentId不能为空")
	private Long agentId;

	@Schema(description = "授权主体类型")
	private String subjectType;

	@Schema(description = "授权主体ID")
	private String subjectId;

	@Schema(description = "授权主体名称")
	private String subjectName;

	@Schema(description = "授权状态")
	private String status;

}
