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
package com.sn68.agent.dataagent.im.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * IM 消息幂等与审计日志。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_im_message")
@Schema(description = "AgentIM消息实体")
public class AgentImMessage extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "幂等键（tenantId:provider:connectorCode:externalMessageId）")
	private String idempotencyKey;

	@Schema(description = "外部消息ID")
	private String externalMessageId;

	@Schema(description = "平台类型")
	private String provider;

	@Schema(description = "连接器编码")
	private String connectorCode;

	@Schema(description = "会话类型")
	private String conversationType;

	@Schema(description = "外部用户ID")
	private String externalConversationId;

	@Schema(description = "外部用户ID")
	private String externalUserId;

	@Schema(description = "direction字段")
	private String direction;

	@Schema(description = "类型消息字段")
	private String messageType;

	@Schema(description = "内容")
	private String content;

	@Schema(description = "响应内容")
	private String responseContent;

	@Schema(description = "Agent ID")
	private Long agentId;

	@Schema(description = "会话ID")
	private Long sessionId;

	@Schema(description = "运行请求ID")
	private String runtimeRequestId;

	@Schema(description = "用户ID")
	private String userId;

	@Schema(description = "状态")
	private String status;

	@Schema(description = "编码错误字段")
	private String errorCode;

	@Schema(description = "错误信息")
	private String errorMessage;

	@Schema(description = "rawPayload字段")
	private String rawPayload;

}
