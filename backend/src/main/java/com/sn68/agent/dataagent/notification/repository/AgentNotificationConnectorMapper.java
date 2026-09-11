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
import com.sn68.agent.dataagent.notification.entity.AgentNotificationConnector;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Agent通知连接器Mapper服务契约。
 */
@Repository
public interface AgentNotificationConnectorMapper extends SuperMapper<AgentNotificationConnector> {

	/**
	 * 查询指定租户的通知连接器，按展示顺序与连接器编码排序（管理端列表）。
	 */
	default List<AgentNotificationConnector> findAllOrdered(String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			return List.of();
		}
		return selectList(new LambdaQueryWrapper<AgentNotificationConnector>()
			.eq(AgentNotificationConnector::getTenantId, tenantId.trim())
			.orderByAsc(AgentNotificationConnector::getDisplayOrder)
			.orderByAsc(AgentNotificationConnector::getConnectorCode));
	}

	/**
	 * 按连接器编码查询（不限状态）；编码为空返回 null。
	 */
	default AgentNotificationConnector findByConnectorCode(String connectorCode) {
		if (!StringUtils.hasText(connectorCode)) {
			return null;
		}
		return selectOne(new LambdaQueryWrapper<AgentNotificationConnector>()
			.eq(AgentNotificationConnector::getConnectorCode, connectorCode.trim()));
	}

	/**
	 * 按连接器编码查询未禁用的连接器，用于发送前校验可用性；编码为空返回 null。
	 */
	default AgentNotificationConnector findEnabledByConnectorCode(String connectorCode) {
		if (!StringUtils.hasText(connectorCode)) {
			return null;
		}
		return selectOne(new LambdaQueryWrapper<AgentNotificationConnector>()
			.eq(AgentNotificationConnector::getConnectorCode, connectorCode.trim())
			.ne(AgentNotificationConnector::getStatus, "disabled"));
	}

}
