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
import com.sn68.agent.dataagent.notification.entity.AgentNotificationTemplate;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Agent通知模板Mapper服务契约。
 */
@Repository
public interface AgentNotificationTemplateMapper extends SuperMapper<AgentNotificationTemplate> {

	/**
	 * 查询指定租户的通知模板列表，可按连接器编码过滤（编码为空返回该租户全部）。
	 */
	default List<AgentNotificationTemplate> findAllOrdered(String tenantId, String connectorCode) {
		if (!StringUtils.hasText(tenantId)) {
			return List.of();
		}
		LambdaQueryWrapper<AgentNotificationTemplate> wrapper = new LambdaQueryWrapper<AgentNotificationTemplate>()
			.eq(AgentNotificationTemplate::getTenantId, tenantId.trim())
			.orderByAsc(AgentNotificationTemplate::getDisplayOrder)
			.orderByAsc(AgentNotificationTemplate::getTemplateCode);
		if (StringUtils.hasText(connectorCode)) {
			wrapper.eq(AgentNotificationTemplate::getConnectorCode, connectorCode.trim());
		}
		return selectList(wrapper);
	}

	/**
	 * 按模板编码查询（不限状态）；编码为空返回 null。
	 */
	default AgentNotificationTemplate findByTemplateCode(String templateCode) {
		if (!StringUtils.hasText(templateCode)) {
			return null;
		}
		return selectOne(new LambdaQueryWrapper<AgentNotificationTemplate>()
			.eq(AgentNotificationTemplate::getTemplateCode, templateCode.trim()));
	}

	/**
	 * 按模板编码查询未禁用的模板，用于渲染/发送前校验；编码为空返回 null。
	 */
	default AgentNotificationTemplate findEnabledByTemplateCode(String templateCode) {
		if (!StringUtils.hasText(templateCode)) {
			return null;
		}
		return selectOne(new LambdaQueryWrapper<AgentNotificationTemplate>()
			.eq(AgentNotificationTemplate::getTemplateCode, templateCode.trim())
			.ne(AgentNotificationTemplate::getStatus, "disabled"));
	}

}
