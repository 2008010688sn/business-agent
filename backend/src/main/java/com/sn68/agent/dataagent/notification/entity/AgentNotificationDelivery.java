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
package com.sn68.agent.dataagent.notification.entity;

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
 * Agent通知投递实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_notification_delivery")
@Schema(description = "Agent通知投递实体")
public class AgentNotificationDelivery extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;
	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "投递ID")
	private String deliveryId;

	@Schema(description = "idempotencyKey字段")
	private String idempotencyKey;

	@Schema(description = "会话ID")
	private String sessionId;

	@Schema(description = "运行请求ID")
	private String runtimeRequestId;

	@Schema(description = "Agent ID")
	private Long agentId;

	@Schema(description = "技能字段")
	private String skillCode;

	@Schema(description = "连接器编码")
	private Long skillVersionId;

	@Schema(description = "Tool resource key")
	private String resourceKey;

	@Schema(description = "连接器编码")
	private String connectorCode;

	@Schema(description = "目标别名")
	private String targetAlias;

	@Schema(description = "模板编码")
	private String templateCode;

	@Schema(description = "平台类型")
	private String provider;

	@Schema(description = "状态")
	private String status;

	@Schema(description = "平台请求ID")
	private String previewSummary;

	@Schema(description = "平台请求ID")
	private String platformRequestId;

	@Schema(description = "平台返回码")
	private String platformCode;

	@Schema(description = "错误信息")
	private String errorMessage;

	@Schema(description = "数量")
	private Integer retryCount;

}
