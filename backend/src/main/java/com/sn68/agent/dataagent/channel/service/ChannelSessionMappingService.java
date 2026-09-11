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
package com.sn68.agent.dataagent.channel.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.channel.entity.AgentChannelSessionMapping;
import com.sn68.agent.dataagent.channel.enums.ChannelSessionErrorDict;
import com.sn68.agent.dataagent.channel.repository.AgentChannelSessionMappingMapper;
import com.sn68.agent.dataagent.dto.channel.ChannelSessionPageQueryReq;
import com.sn68.agent.dataagent.entity.DataChatSession;
import com.sn68.agent.dataagent.enums.ChatSessionChannelDict;
import com.sn68.agent.dataagent.enums.ChatSessionScopeDict;
import com.sn68.agent.dataagent.service.chat.DataChatSessionService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 渠道会话映射服务：维护外部渠道会话（IM 等）与内部聊天会话的绑定、复用与接管。
 */
@Service
@RequiredArgsConstructor
public class ChannelSessionMappingService {

	private static final String STATUS_ENABLED = "enabled";

	private final AgentChannelSessionMappingMapper mappingMapper;

	private final DataChatSessionService chatSessionService;

	public IPage<AgentChannelSessionMapping> query(ChannelSessionPageQueryReq request) {
		ChannelSessionPageQueryReq pageRequest = request == null ? new ChannelSessionPageQueryReq() : request;
		return mappingMapper.selectPage(pageRequest.buildPage(), pageRequest);
	}

	public AgentChannelSessionMapping getOrCreate(ResolveRequest request) {
		validate(request);
		String provider = normalizeUpper(request.provider());
		String connectorCode = request.connectorCode().trim();
		String scope = normalizeScope(request.sessionScope());
		String scopeKey = buildScopeKey(provider, connectorCode, request.agentId(), scope, request.conversationType(),
				request.externalConversationId(), request.externalUserId(), request.externalUnionId());
		AgentChannelSessionMapping mapping = mappingMapper.findEnabled(provider, connectorCode, request.agentId(), scope,
				scopeKey);
		if (mapping != null) {
			return mapping;
		}
		DataChatSession session = chatSessionService.createSession(request.agentId(), request.title(), request.userId(),
				ChatSessionChannelDict.IM.getValue(), provider, connectorCode, scope, scopeKey);
		AgentChannelSessionMapping created = AgentChannelSessionMapping.builder()
			.provider(provider)
			.connectorCode(connectorCode)
			.agentId(request.agentId())
			.sessionId(session.getId())
			.conversationType(normalizeUpper(request.conversationType()))
			.externalConversationId(trimToNull(request.externalConversationId()))
			.externalUserId(trimToNull(request.externalUserId()))
			.externalUnionId(trimToNull(request.externalUnionId()))
			.sessionScope(scope)
			.scopeKey(scopeKey)
			.status(STATUS_ENABLED)
			.build();
		try {
			mappingMapper.insert(created);
			return created;
		}
		catch (DuplicateKeyException ex) {
			AgentChannelSessionMapping existing = mappingMapper.findEnabled(provider, connectorCode, request.agentId(),
					scope, scopeKey);
			if (existing != null) {
				return existing;
			}
			throw badRequest(ChannelSessionErrorDict.MAPPING_CREATE_FAILED);
		}
	}

	public String buildScopeKey(String provider, String connectorCode, Long agentId, String sessionScope,
			String conversationType, String externalConversationId, String externalUserId, String externalUnionId) {
		if (agentId == null) {
			throw badRequest(ChannelSessionErrorDict.REQUEST_INVALID);
		}
		String normalizedProvider = normalizeUpper(provider);
		String normalizedConnectorCode = trimToNull(connectorCode);
		String normalizedScope = normalizeScope(sessionScope);
		if (!StringUtils.hasText(normalizedProvider) || !StringUtils.hasText(normalizedConnectorCode)) {
			throw badRequest(ChannelSessionErrorDict.REQUEST_INVALID);
		}
		if (ChatSessionScopeDict.PER_AGENT.getValue().equals(normalizedScope)) {
			return String.join("|", normalizedProvider, normalizedConnectorCode, String.valueOf(agentId));
		}
		if (ChatSessionScopeDict.PER_CONVERSATION.getValue().equals(normalizedScope)) {
			String normalizedConversationType = normalizeUpper(conversationType);
			String normalizedConversationId = trimToNull(externalConversationId);
			if (!StringUtils.hasText(normalizedConversationType) || !StringUtils.hasText(normalizedConversationId)) {
				throw badRequest(ChannelSessionErrorDict.REQUEST_INVALID);
			}
			return String.join("|", normalizedProvider, normalizedConnectorCode, String.valueOf(agentId),
					normalizedConversationType, normalizedConversationId);
		}
		if (ChatSessionScopeDict.PER_USER.getValue().equals(normalizedScope)) {
			String userKey = firstText(externalUnionId, externalUserId);
			if (!StringUtils.hasText(userKey)) {
				throw badRequest(ChannelSessionErrorDict.REQUEST_INVALID);
			}
			return String.join("|", normalizedProvider, normalizedConnectorCode, String.valueOf(agentId), userKey);
		}
		throw badRequest(ChannelSessionErrorDict.SCOPE_UNSUPPORTED);
	}

	private void validate(ResolveRequest request) {
		if (request == null || request.agentId() == null || !StringUtils.hasText(request.provider())
				|| !StringUtils.hasText(request.connectorCode())) {
			throw badRequest(ChannelSessionErrorDict.REQUEST_INVALID);
		}
	}

	private String normalizeScope(String value) {
		String scope = normalizeUpper(value);
		return StringUtils.hasText(scope) ? scope : ChatSessionScopeDict.PER_USER.getValue();
	}

	private String normalizeUpper(String value) {
		return StringUtils.hasText(value) ? value.trim().toUpperCase() : null;
	}

	private String firstText(String... values) {
		if (values == null) {
			return null;
		}
		for (String value : values) {
			String trimmed = trimToNull(value);
			if (StringUtils.hasText(trimmed)) {
				return trimmed;
			}
		}
		return null;
	}

	private String trimToNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	private CheckedException badRequest(ChannelSessionErrorDict error) {
		return CheckedException.badRequest(error.getValue(), error.getLabel());
	}

	public record ResolveRequest(String provider, String connectorCode, String conversationType,
			String externalConversationId, String externalUserId, String externalUnionId, Long agentId, Long userId,
			String title, String sessionScope) {
	}

}
