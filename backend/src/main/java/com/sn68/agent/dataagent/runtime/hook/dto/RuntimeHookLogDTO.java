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
import java.time.Instant;

/**
 * 运行时钩子Log数据传输对象。
 */
@Schema(description = "运行时钩子Log数据传输对象")
public record RuntimeHookLogDTO(
		@Schema(description = "主键ID") Long id,
		@Schema(description = "编码") String hookCode,
		@Schema(description = "类型") String eventType,
		@Schema(description = "状态") String status,
		@Schema(description = "类型") String actionType,
		@Schema(description = "工具字段") String toolKey,
		@Schema(description = "Agent ID") Long agentId,
		@Schema(description = "Skill code") String skillCode,
		@Schema(description = "Pinned Skill version ID") Long skillVersionId,
		@Schema(description = "资源键") String resourceKey,
		@Schema(description = "会话ID") String sessionId,
		@Schema(description = "运行请求ID") String runtimeRequestId,
		@Schema(description = "幂等键") String idempotencyKey,
		@Schema(description = "请求字段") String requestSummary,
		@Schema(description = "响应字段") String responseSummary,
		@Schema(description = "错误信息") String errorMessage,
		@Schema(description = "elapsedMs字段") Long elapsedMs,
		@Schema(description = "创建时间") Instant createTime
) {
}
