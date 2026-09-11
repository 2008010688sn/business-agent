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
package com.sn68.agent.dataagent.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 通知投递数据传输对象。
 */
@Schema(description = "通知投递数据传输对象")
public record NotificationDeliveryDTO(
		@Schema(description = "主键ID") Long id,
		@Schema(description = "投递ID") String deliveryId,
		@Schema(description = "幂等键") String idempotencyKey,
		@Schema(description = "会话ID") String sessionId,
		@Schema(description = "运行请求ID") String runtimeRequestId,
		@Schema(description = "Agent ID") Long agentId,
		@Schema(description = "Skill code") String skillCode,
		@Schema(description = "Pinned Skill version ID") Long skillVersionId,
		@Schema(description = "Tool resource key") String resourceKey,
		@Schema(description = "连接器编码") String connectorCode,
		@Schema(description = "目标别名") String targetAlias,
		@Schema(description = "模板编码") String templateCode,
		@Schema(description = "平台类型") String provider,
		@Schema(description = "状态") String status,
		@Schema(description = "previewSummary字段") String previewSummary,
		@Schema(description = "平台请求ID") String platformRequestId,
		@Schema(description = "平台返回码") String platformCode,
		@Schema(description = "错误信息") String errorMessage,
		@Schema(description = "数量") Integer retryCount,
		@Schema(description = "创建时间") Instant createTime
) {
}
