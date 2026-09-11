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
package com.sn68.agent.dataagent.runtime.hook.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * 运行时钩子Event实体。
 */
@Schema(description = "运行时钩子Event实体")
public record RuntimeHookEvent(
		@Schema(description = "类型") String eventType,
		@Schema(description = "Agent ID") Long agentId,
		@Schema(description = "Skill code") String skillCode,
		@Schema(description = "Pinned Skill version ID") Long skillVersionId,
		@Schema(description = "资源键") String resourceKey,
		@Schema(description = "会话ID") String sessionId,
		@Schema(description = "运行请求ID") String runtimeRequestId,
		@Schema(description = "幂等键") String idempotencyKey,
		@Schema(description = "input字段") Map<String, Object> input,
		@Schema(description = "output字段") Map<String, Object> output
) {
}
