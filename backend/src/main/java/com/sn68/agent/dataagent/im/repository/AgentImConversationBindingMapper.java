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
package com.sn68.agent.dataagent.im.repository;

import com.sn68.agent.dataagent.im.entity.AgentImConversationBinding;
import com.sn68.agent.dataagent.im.enums.ImConstants;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * AgentIM会话绑定Mapper服务契约。
 */
@Repository
public interface AgentImConversationBindingMapper extends SuperMapper<AgentImConversationBinding> {

	/**
	 * 查询指定租户下某连接器的全部会话绑定。租户ID为空直接返回空集合，禁止退化为全局查询。
	 */
	default List<AgentImConversationBinding> findByConnector(String tenantId, String provider, String connectorCode) {
		if (!StringUtils.hasText(tenantId)) {
			return List.of();
		}
		return selectList(baseWrapper(tenantId, provider, connectorCode)
			.orderByAsc(AgentImConversationBinding::getDisplayOrder)
			.orderByAsc(AgentImConversationBinding::getId));
	}

	/**
	 * 查询指定租户下启用的全部会话绑定（运行时事件回传 IM 使用）。
	 * PR-1 拆除 workspace 维度后按租户广播；待数字员工域（PR-5）接入 digitalEmployeeId 后收敛为精确路由。
	 * 租户ID缺失直接返回空集合，禁止退化为全局查询。
	 */
	default List<AgentImConversationBinding> findEnabledByTenant(String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			return List.of();
		}
		return selectList(Wraps.<AgentImConversationBinding>lbQ()
			.eq(AgentImConversationBinding::getTenantId, tenantId.trim())
			.eq(AgentImConversationBinding::getStatus, ImConstants.STATUS_ENABLED)
			.orderByAsc(AgentImConversationBinding::getDisplayOrder)
			.orderByAsc(AgentImConversationBinding::getId));
	}

	/**
	 * 查询指定租户下启用的会话绑定（回调链路使用，租户以验签通过的连接器为准）。
	 */
	default AgentImConversationBinding findEnabled(String tenantId, String provider, String connectorCode,
			String conversationType, String externalConversationId) {
		if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(conversationType)
				|| !StringUtils.hasText(externalConversationId)) {
			return null;
		}
		return selectOne(baseWrapper(tenantId, provider, connectorCode)
			.eq(AgentImConversationBinding::getConversationType, conversationType.trim().toUpperCase())
			.eq(AgentImConversationBinding::getExternalConversationId, externalConversationId.trim())
			.eq(AgentImConversationBinding::getStatus, ImConstants.STATUS_ENABLED)
			.last(" limit 1"));
	}

	private static LbqWrapper<AgentImConversationBinding> baseWrapper(String tenantId, String provider,
			String connectorCode) {
		// Wraps 自动跳空：provider/connectorCode 为空（null 或空白）时不拼条件，与原 hasText 判断等价
		return Wraps.<AgentImConversationBinding>lbQ()
			.eq(AgentImConversationBinding::getTenantId, tenantId.trim())
			.eq(AgentImConversationBinding::getProvider, provider == null ? null : provider.trim().toUpperCase())
			.eq(AgentImConversationBinding::getConnectorCode, connectorCode == null ? null : connectorCode.trim());
	}

}
