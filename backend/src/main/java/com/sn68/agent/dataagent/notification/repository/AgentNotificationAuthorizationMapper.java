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
import com.sn68.agent.dataagent.notification.entity.AgentNotificationAuthorization;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Agent通知授权Mapper服务契约。
 */
@Repository
public interface AgentNotificationAuthorizationMapper extends SuperMapper<AgentNotificationAuthorization> {

	/**
	 * 查询指定租户的通知授权规则，按展示顺序升序、ID 倒序（管理端列表）。
	 */
	default List<AgentNotificationAuthorization> findAllOrdered(String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			return List.of();
		}
		return selectList(new LambdaQueryWrapper<AgentNotificationAuthorization>()
			.eq(AgentNotificationAuthorization::getTenantId, tenantId.trim())
			.orderByAsc(AgentNotificationAuthorization::getDisplayOrder)
			.orderByDesc(AgentNotificationAuthorization::getId));
	}

	/**
	 * 查询当前生效（未禁用且未过期）且作用域匹配的授权规则。
	 *
	 * <p>作用域匹配语义：每个维度（Agent/技能/版本/资源/目标/模板）传值时命中"该维度为空（通配）
	 * 或等于传值"的规则；不传值时仅命中该维度为空的规则，避免窄授权被宽请求命中。
	 */
	default List<AgentNotificationAuthorization> findActive(Long agentId, String skillCode, Long skillVersionId,
			String resourceKey, String targetAlias, String templateCode) {
		LambdaQueryWrapper<AgentNotificationAuthorization> wrapper = new LambdaQueryWrapper<AgentNotificationAuthorization>()
			.ne(AgentNotificationAuthorization::getStatus, "disabled")
			.and(w -> w.isNull(AgentNotificationAuthorization::getExpireTime)
				.or()
				.gt(AgentNotificationAuthorization::getExpireTime, Instant.now()));
		if (agentId != null) {
			wrapper.and(w -> w.isNull(AgentNotificationAuthorization::getAgentId)
				.or()
				.eq(AgentNotificationAuthorization::getAgentId, agentId));
		}
		else {
			wrapper.isNull(AgentNotificationAuthorization::getAgentId);
		}
		if (StringUtils.hasText(skillCode)) {
			wrapper.and(w -> w.isNull(AgentNotificationAuthorization::getSkillCode)
				.or()
				.eq(AgentNotificationAuthorization::getSkillCode, skillCode.trim()));
		}
		else {
			wrapper.isNull(AgentNotificationAuthorization::getSkillCode);
		}
		if (skillVersionId != null) {
			wrapper.and(w -> w.isNull(AgentNotificationAuthorization::getSkillVersionId)
				.or()
				.eq(AgentNotificationAuthorization::getSkillVersionId, skillVersionId));
		}
		else {
			wrapper.isNull(AgentNotificationAuthorization::getSkillVersionId);
		}
		if (StringUtils.hasText(resourceKey)) {
			wrapper.and(w -> w.isNull(AgentNotificationAuthorization::getResourceKey)
				.or()
				.eq(AgentNotificationAuthorization::getResourceKey, resourceKey.trim()));
		}
		else {
			wrapper.isNull(AgentNotificationAuthorization::getResourceKey);
		}
		if (StringUtils.hasText(targetAlias)) {
			wrapper.and(w -> w.isNull(AgentNotificationAuthorization::getTargetAlias)
				.or()
				.eq(AgentNotificationAuthorization::getTargetAlias, targetAlias.trim()));
		}
		else {
			wrapper.isNull(AgentNotificationAuthorization::getTargetAlias);
		}
		if (StringUtils.hasText(templateCode)) {
			wrapper.and(w -> w.isNull(AgentNotificationAuthorization::getTemplateCode)
				.or()
				.eq(AgentNotificationAuthorization::getTemplateCode, templateCode.trim()));
		}
		else {
			wrapper.isNull(AgentNotificationAuthorization::getTemplateCode);
		}
		return selectList(wrapper.orderByAsc(AgentNotificationAuthorization::getDisplayOrder));
	}

}
