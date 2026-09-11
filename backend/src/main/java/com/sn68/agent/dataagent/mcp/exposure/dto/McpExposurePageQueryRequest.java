/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.mcp.exposure.dto;

import com.sn68.agent.framework.db.mybatisplus.page.PageRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * MCP暴露分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "MCP 暴露分页查询")
public class McpExposurePageQueryRequest extends PageRequest {

	@Schema(description = "工具字段")
	private String toolKey;

	@Schema(description = "状态")
	private String status;

	@Schema(description = "搜索关键字")
	private String keyword;

}
