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
package com.sn68.agent.dataagent.channel.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.channel.entity.AgentChannelSessionMapping;
import com.sn68.agent.dataagent.dto.channel.ChannelSessionPageQueryReq;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * 渠道会话映射 Mapper：维护 (provider, connectorCode, agentId, sessionScope, scopeKey)
 * 到内部会话的绑定关系。
 */
@Repository
public interface AgentChannelSessionMappingMapper extends SuperMapper<AgentChannelSessionMapping> {

	/**
	 * 按渠道五元组（平台、连接器编码、Agent、会话范围、范围键）查询启用且未删除的会话映射；
	 * 任一参数为空返回 null，不放宽条件。
	 */
	default AgentChannelSessionMapping findEnabled(String provider, String connectorCode, Long agentId,
			String sessionScope, String scopeKey) {
		if (!StringUtils.hasText(provider) || !StringUtils.hasText(connectorCode) || agentId == null
				|| !StringUtils.hasText(sessionScope) || !StringUtils.hasText(scopeKey)) {
			return null;
		}
		return selectOne(new LambdaQueryWrapper<AgentChannelSessionMapping>()
			.eq(AgentChannelSessionMapping::getDeleted, false)
			.eq(AgentChannelSessionMapping::getProvider, provider.trim().toUpperCase())
			.eq(AgentChannelSessionMapping::getConnectorCode, connectorCode.trim())
			.eq(AgentChannelSessionMapping::getAgentId, agentId)
			.eq(AgentChannelSessionMapping::getSessionScope, sessionScope.trim().toUpperCase())
			.eq(AgentChannelSessionMapping::getScopeKey, scopeKey.trim())
			.eq(AgentChannelSessionMapping::getStatus, "enabled")
			.last(" limit 1"));
	}

	/**
	 * 管理端分页查询会话映射：按 Agent/会话/平台/连接器编码/外部用户/状态可选过滤，
	 * 未删除，按最后修改时间与 ID 倒序。
	 */
	default IPage<AgentChannelSessionMapping> selectPage(IPage<AgentChannelSessionMapping> page,
			ChannelSessionPageQueryReq request) {
		ChannelSessionPageQueryReq query = request == null ? new ChannelSessionPageQueryReq() : request;
		LambdaQueryWrapper<AgentChannelSessionMapping> wrapper = new LambdaQueryWrapper<AgentChannelSessionMapping>()
			.eq(AgentChannelSessionMapping::getDeleted, false)
			.orderByDesc(AgentChannelSessionMapping::getLastModifyTime)
			.orderByDesc(AgentChannelSessionMapping::getId);
		if (query.getAgentId() != null) {
			wrapper.eq(AgentChannelSessionMapping::getAgentId, query.getAgentId());
		}
		if (query.getSessionId() != null) {
			wrapper.eq(AgentChannelSessionMapping::getSessionId, query.getSessionId());
		}
		if (StringUtils.hasText(query.getProvider())) {
			wrapper.eq(AgentChannelSessionMapping::getProvider, query.getProvider().trim().toUpperCase());
		}
		if (StringUtils.hasText(query.getConnectorCode())) {
			wrapper.eq(AgentChannelSessionMapping::getConnectorCode, query.getConnectorCode().trim());
		}
		if (StringUtils.hasText(query.getExternalUserId())) {
			wrapper.eq(AgentChannelSessionMapping::getExternalUserId, query.getExternalUserId().trim());
		}
		if (StringUtils.hasText(query.getStatus())) {
			wrapper.eq(AgentChannelSessionMapping::getStatus, query.getStatus().trim());
		}
		return selectPage(page, wrapper);
	}

}
