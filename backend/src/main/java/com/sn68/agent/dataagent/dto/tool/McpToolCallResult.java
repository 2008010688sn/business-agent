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
package com.sn68.agent.dataagent.dto.tool;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * MCP工具Call结果实体。
 */
@Schema(description = "MCP工具Call结果实体")
public record McpToolCallResult(
		@Schema(description = "是否成功") boolean success,
		@Schema(description = "是否工具错误") boolean toolError,
		@Schema(description = "消息内容") String message,
		@Schema(description = "数据内容") Map<String, Object> data
) {
}
