/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.visibility;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

/**
 * Agent可见性Agent优化ion数据传输对象。
 */
@Data
@Builder
@Schema(description = "Agent 可见性申请筛选选项")
public class AgentVisibilityAgentOptionResp {

	@Schema(description = "主键ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long id;

	@Schema(description = "名称")
	private String name;

	@Schema(description = "状态")
	private String status;

}
