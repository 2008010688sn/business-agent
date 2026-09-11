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
package com.sn68.agent.dataagent.im.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * IM 消息审计记录。
 */
@Schema(description = "IM消息数据传输对象")
public record ImMessageDTO(
		@Schema(description = "主键ID") Long id,
		@Schema(description = "平台类型") String provider,
		@Schema(description = "连接器编码") String connectorCode,
		@Schema(description = "会话类型") String conversationType,
		@Schema(description = "externalConversationId字段") String externalConversationId,
		@Schema(description = "外部用户ID") String externalUserId,
		@Schema(description = "direction字段") String direction,
		@Schema(description = "类型消息字段") String messageType,
		@Schema(description = "内容") String content,
		@Schema(description = "响应内容") String responseContent,
		@Schema(description = "Agent ID") Long agentId,
		@Schema(description = "会话ID") Long sessionId,
		@Schema(description = "运行请求ID") String runtimeRequestId,
		@Schema(description = "用户ID") String userId,
		@Schema(description = "状态") String status,
		@Schema(description = "错误信息") String errorMessage,
		@Schema(description = "创建时间") Instant createTime
) {
}
