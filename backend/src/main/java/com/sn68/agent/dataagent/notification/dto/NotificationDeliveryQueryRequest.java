package com.sn68.agent.dataagent.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 通知投递查询请求。
 */
@Schema(description = "通知投递查询请求")
public record NotificationDeliveryQueryRequest(
		@Schema(description = "Agent ID") Long agentId,
		@Schema(description = "Skill code") String skillCode,
		@Schema(description = "Pinned Skill version ID") Long skillVersionId,
		@Schema(description = "Tool resource key") String resourceKey,
		@Schema(description = "目标别名") String targetAlias,
		@Schema(description = "模板编码") String templateCode,
		@Schema(description = "投递ID") String deliveryId
) {
}
