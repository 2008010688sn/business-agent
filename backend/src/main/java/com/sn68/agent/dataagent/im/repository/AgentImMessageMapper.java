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

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.im.dto.ImMessagePageQuery;
import com.sn68.agent.dataagent.im.entity.AgentImMessage;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * AgentIM消息Mapper服务契约。
 */
@Repository
public interface AgentImMessageMapper extends SuperMapper<AgentImMessage> {

	/**
	 * 按幂等键查询消息，用于回调去重与出站防重。幂等键本身带租户前缀
	 * （tenantId:provider:connectorCode:externalMessageId），故无需额外租户条件；键为空返回 null。
	 */
	default AgentImMessage findByIdempotencyKey(String idempotencyKey) {
		if (!StringUtils.hasText(idempotencyKey)) {
			return null;
		}
		return selectOne(new LambdaQueryWrapper<AgentImMessage>()
			.eq(AgentImMessage::getIdempotencyKey, idempotencyKey.trim())
			.last(" limit 1"));
	}

	/**
	 * 分页查询指定租户的 IM 消息诊断数据。租户ID为空直接返回原分页对象（零记录），禁止退化为全局查询。
	 */
	default IPage<AgentImMessage> selectDiagnosticsPage(IPage<AgentImMessage> page, String tenantId,
			ImMessagePageQuery request) {
		if (!StringUtils.hasText(tenantId)) {
			return page;
		}
		ImMessagePageQuery query = request == null ? new ImMessagePageQuery() : request;
		LambdaQueryWrapper<AgentImMessage> wrapper = new LambdaQueryWrapper<AgentImMessage>()
			.eq(AgentImMessage::getTenantId, tenantId.trim())
			.orderByDesc(AgentImMessage::getCreateTime)
			.orderByDesc(AgentImMessage::getId);
		if (StringUtils.hasText(query.getProvider())) {
			wrapper.eq(AgentImMessage::getProvider, query.getProvider().trim().toUpperCase());
		}
		if (StringUtils.hasText(query.getConnectorCode())) {
			wrapper.eq(AgentImMessage::getConnectorCode, query.getConnectorCode().trim());
		}
		if (StringUtils.hasText(query.getConversationType())) {
			wrapper.eq(AgentImMessage::getConversationType, query.getConversationType().trim().toUpperCase());
		}
		if (StringUtils.hasText(query.getExternalUserId())) {
			wrapper.eq(AgentImMessage::getExternalUserId, query.getExternalUserId().trim());
		}
		if (query.getAgentId() != null) {
			wrapper.eq(AgentImMessage::getAgentId, query.getAgentId());
		}
		if (query.getSessionId() != null) {
			wrapper.eq(AgentImMessage::getSessionId, query.getSessionId());
		}
		if (StringUtils.hasText(query.getRuntimeRequestId())) {
			wrapper.eq(AgentImMessage::getRuntimeRequestId, query.getRuntimeRequestId().trim());
		}
		if (StringUtils.hasText(query.getDirection())) {
			wrapper.eq(AgentImMessage::getDirection, query.getDirection().trim().toUpperCase());
		}
		if (StringUtils.hasText(query.getStatus())) {
			wrapper.eq(AgentImMessage::getStatus, query.getStatus().trim().toUpperCase());
		}
		if (StringUtils.hasText(query.getKeyword())) {
			String keyword = query.getKeyword().trim();
			wrapper.and(nested -> nested.like(AgentImMessage::getContent, keyword)
				.or()
				.like(AgentImMessage::getResponseContent, keyword)
				.or()
				.like(AgentImMessage::getRuntimeRequestId, keyword)
				.or()
				.like(AgentImMessage::getExternalMessageId, keyword));
		}
		return selectPage(page, wrapper);
	}

}
