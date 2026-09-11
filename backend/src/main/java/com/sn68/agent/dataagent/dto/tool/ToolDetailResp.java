/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.tool;

import com.sn68.agent.dataagent.entity.AgentExecutionResourceVersion;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 工具资源详情，携带其全部不可变的已发布版本。
 */
@Schema(description = "工具资源详情")
public record ToolDetailResp(
		@Schema(description = "工具资源信息") ToolResourceDTO resource,
		@Schema(description = "已发布版本列表") List<AgentExecutionResourceVersion> versions) {
}
