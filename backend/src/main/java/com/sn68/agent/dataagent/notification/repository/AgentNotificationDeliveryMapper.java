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
package com.sn68.agent.dataagent.notification.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sn68.agent.dataagent.notification.entity.AgentNotificationDelivery;
import com.sn68.agent.dataagent.notification.enums.NotificationDeliveryStatus;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Agent通知投递Mapper服务契约。
 */
@Repository
public interface AgentNotificationDeliveryMapper extends SuperMapper<AgentNotificationDelivery> {

	/**
	 * 按幂等键查询投递记录，用于重复发送防护；键为空返回 null。
	 */
	default AgentNotificationDelivery findByIdempotencyKey(String idempotencyKey) {
		if (!StringUtils.hasText(idempotencyKey)) {
			return null;
		}
		return selectOne(new LambdaQueryWrapper<AgentNotificationDelivery>()
			.eq(AgentNotificationDelivery::getIdempotencyKey, idempotencyKey.trim()));
	}

	/**
	 * 查询最近投递记录（诊断/审计用）：按 Agent/技能/资源/目标别名/模板/投递单号可选过滤，
	 * 按 ID 倒序，最多返回 200 条。
	 */
	default List<AgentNotificationDelivery> findRecent(Long agentId, String skillCode, Long skillVersionId,
			String resourceKey,
			String targetAlias, String templateCode, String deliveryId) {
		LambdaQueryWrapper<AgentNotificationDelivery> wrapper = new LambdaQueryWrapper<AgentNotificationDelivery>()
			.orderByDesc(AgentNotificationDelivery::getId);
		if (agentId != null) {
			wrapper.eq(AgentNotificationDelivery::getAgentId, agentId);
		}
		if (StringUtils.hasText(skillCode)) {
			wrapper.eq(AgentNotificationDelivery::getSkillCode, skillCode.trim());
		}
		if (skillVersionId != null) {
			wrapper.eq(AgentNotificationDelivery::getSkillVersionId, skillVersionId);
		}
		if (StringUtils.hasText(resourceKey)) {
			wrapper.eq(AgentNotificationDelivery::getResourceKey, resourceKey.trim());
		}
		if (StringUtils.hasText(targetAlias)) {
			wrapper.eq(AgentNotificationDelivery::getTargetAlias, targetAlias.trim());
		}
		if (StringUtils.hasText(templateCode)) {
			wrapper.eq(AgentNotificationDelivery::getTemplateCode, templateCode.trim());
		}
		if (StringUtils.hasText(deliveryId)) {
			wrapper.eq(AgentNotificationDelivery::getDeliveryId, deliveryId.trim());
		}
		return selectList(wrapper.last("LIMIT 200"));
	}

	/**
	 * 判断同一会话内是否已成功发送过相同目标 + 模板的通知，支撑 SESSION_ONCE 确认策略；会话为空返回 false。
	 */
	default boolean existsSentInSession(String sessionId, Long agentId, String skillCode, Long skillVersionId,
			String resourceKey,
			String targetAlias, String templateCode) {
		if (!StringUtils.hasText(sessionId)) {
			return false;
		}
		LambdaQueryWrapper<AgentNotificationDelivery> wrapper = new LambdaQueryWrapper<AgentNotificationDelivery>()
			.eq(AgentNotificationDelivery::getSessionId, sessionId.trim())
			.eq(AgentNotificationDelivery::getStatus, NotificationDeliveryStatus.SENT.name())
			.eq(AgentNotificationDelivery::getTargetAlias, targetAlias)
			.eq(AgentNotificationDelivery::getTemplateCode, templateCode);
		if (agentId != null) {
			wrapper.eq(AgentNotificationDelivery::getAgentId, agentId);
		}
		if (StringUtils.hasText(skillCode)) {
			wrapper.eq(AgentNotificationDelivery::getSkillCode, skillCode.trim());
		}
		if (skillVersionId != null) {
			wrapper.eq(AgentNotificationDelivery::getSkillVersionId, skillVersionId);
		}
		if (StringUtils.hasText(resourceKey)) {
			wrapper.eq(AgentNotificationDelivery::getResourceKey, resourceKey.trim());
		}
		return selectCount(wrapper) > 0;
	}

}
