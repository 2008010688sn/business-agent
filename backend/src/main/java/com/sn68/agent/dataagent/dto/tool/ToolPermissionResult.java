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

/**
 * 能力Permission结果实体。
 */
@Schema(description = "能力Permission结果实体")
public record ToolPermissionResult(
		@Schema(description = "是否允许") boolean allowed,
		@Schema(description = "消息字段") String denyMessage,
		@Schema(description = "稳定原因码") String reasonCode
) {

	public static final String AUTH_CONTEXT_MISSING = "AUTH_CONTEXT_MISSING";

	public static final String FUNCTION_PERMISSION_DENIED = "FUNCTION_PERMISSION_DENIED";

	public static final String AUTH_CONTEXT_ERROR = "AUTH_CONTEXT_ERROR";

	public ToolPermissionResult(boolean allowed, String denyMessage) {
		this(allowed, denyMessage, allowed ? null : FUNCTION_PERMISSION_DENIED);
	}
}
