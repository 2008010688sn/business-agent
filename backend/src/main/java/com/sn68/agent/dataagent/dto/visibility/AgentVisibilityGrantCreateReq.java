/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.visibility;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import lombok.Data;

/**
 * Agent可见性授权Create请求。
 */
@Data
@Schema(description = "DataAgent 可见性授权创建请求")
public class AgentVisibilityGrantCreateReq {

	@Schema(description = "Agent ID")
	@NotNull(message = "agentId不能为空")
	private Long agentId;

	@Schema(description = "授权主体类型")
	private String subjectType;

	@Schema(description = "授权主体ID")
	private String subjectId;

	@Schema(description = "授权主体名称")
	private String subjectName;

	@Schema(description = "授权过期时间")
	private Instant expireTime;

}
