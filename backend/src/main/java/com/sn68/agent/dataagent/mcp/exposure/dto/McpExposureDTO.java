/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sn68.agent.dataagent.mcp.exposure.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * MCP暴露数据传输对象。
 */
@Schema(description = "MCP暴露数据传输对象")
public record McpExposureDTO(
		@Schema(description = "主键ID") Long id,
		@Schema(description = "编码") String exposureCode,
		@Schema(description = "名称") String exposureName,
		@Schema(description = "工具字段") String toolKey,
		@Schema(description = "工具名称") String exposedToolName,
		@Schema(description = "类型") String exposureType,
		@Schema(description = "级别字段") String riskLevel,
		@Schema(description = "文本字段") Boolean requireUserContext,
		@Schema(description = "状态") String status,
		@Schema(description = "displayOrder字段") Integer displayOrder,
		@Schema(description = "配置") Map<String, Object> extConfig
) {
}
