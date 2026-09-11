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
import com.sn68.agent.dataagent.notification.entity.AgentNotificationTarget;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Agent通知目标Mapper服务契约。
 */
@Repository
public interface AgentNotificationTargetMapper extends SuperMapper<AgentNotificationTarget> {

	/**
	 * 查询指定租户的通知目标列表，可按连接器编码过滤（编码为空返回该租户全部）。
	 */
	default List<AgentNotificationTarget> findAllOrdered(String tenantId, String connectorCode) {
		if (!StringUtils.hasText(tenantId)) {
			return List.of();
		}
		LambdaQueryWrapper<AgentNotificationTarget> wrapper = new LambdaQueryWrapper<AgentNotificationTarget>()
			.eq(AgentNotificationTarget::getTenantId, tenantId.trim())
			.orderByAsc(AgentNotificationTarget::getDisplayOrder)
			.orderByAsc(AgentNotificationTarget::getTargetAlias);
		if (StringUtils.hasText(connectorCode)) {
			wrapper.eq(AgentNotificationTarget::getConnectorCode, connectorCode.trim());
		}
		return selectList(wrapper);
	}

	/**
	 * 按目标别名查询（不限状态）；别名为空返回 null。
	 */
	default AgentNotificationTarget findByTargetAlias(String targetAlias) {
		if (!StringUtils.hasText(targetAlias)) {
			return null;
		}
		return selectOne(new LambdaQueryWrapper<AgentNotificationTarget>()
			.eq(AgentNotificationTarget::getTargetAlias, targetAlias.trim()));
	}

	/**
	 * 按目标别名查询未禁用的通知目标，用于发送前校验；别名为空返回 null。
	 */
	default AgentNotificationTarget findEnabledByTargetAlias(String targetAlias) {
		if (!StringUtils.hasText(targetAlias)) {
			return null;
		}
		return selectOne(new LambdaQueryWrapper<AgentNotificationTarget>()
			.eq(AgentNotificationTarget::getTargetAlias, targetAlias.trim())
			.ne(AgentNotificationTarget::getStatus, "disabled"));
	}

}
