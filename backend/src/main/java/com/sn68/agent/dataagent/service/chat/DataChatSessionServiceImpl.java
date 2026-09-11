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
package com.sn68.agent.dataagent.service.chat;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.agentscope.session.AgentScopeNativeSessionService;
import com.sn68.agent.dataagent.agentscope.v2.V2AgentStateStore;
import com.sn68.agent.dataagent.agentscope.v2.V2RuntimeSnapshot;
import com.sn68.agent.dataagent.constant.AgentSessionConstant;
import com.sn68.agent.dataagent.dto.chat.ChatSessionPageQueryReq;
import com.sn68.agent.dataagent.entity.DataChatSession;
import com.sn68.agent.dataagent.enums.ChatSessionChannelDict;
import com.sn68.agent.dataagent.repository.DataChatSessionMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperServiceImpl;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * 数据问答会话管理实现：负责会话创建、命名、归属校验与生命周期维护。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DataChatSessionServiceImpl extends SuperServiceImpl<DataChatSessionMapper, DataChatSession>
		implements DataChatSessionService {

	private final AgentScopeNativeSessionService nativeSessionService;

	private final ObjectProvider<V2AgentStateStore> v2AgentStateStoreProvider;

	/**
	 * Get session list by agent ID
	 */
	@Override
	public List<DataChatSession> findByAgentId(Long agentId) {
		return baseMapper.selectByAgentId(agentId, ChatSessionChannelDict.WEB.getValue());
	}

	/**
	 * Get session list by agent ID and current user
	 */
	@Override
	public List<DataChatSession> findByAgentIdAndUserId(Long agentId, Long userId) {
		requireOwnedSessionQuery(agentId, userId);
		return list(ownedWebSessionQuery(agentId, userId)
			.eq(DataChatSession::getChannelType, ChatSessionChannelDict.WEB.getValue())
			.ne(DataChatSession::getStatus, AgentSessionConstant.SESSION_STATUS_DELETED)
			.orderByDesc(DataChatSession::getIsPinned)
			.orderByDesc(DataChatSession::getLastModifyTime));
	}

	@Override
	public IPage<DataChatSession> queryByAgentId(Long agentId, ChatSessionPageQueryReq request) {
		ChatSessionPageQueryReq pageRequest = request == null ? new ChatSessionPageQueryReq() : request;
		if (!StringUtils.hasText(pageRequest.getChannelType())) {
			pageRequest.setChannelType(ChatSessionChannelDict.WEB.getValue());
		}
		return baseMapper.selectPageByAgentId(pageRequest.buildPage(), agentId, pageRequest);
	}

	/**
	 * Page query session list by agent ID and current user
	 */
	@Override
	public IPage<DataChatSession> queryByAgentIdAndUserId(Long agentId, Long userId, ChatSessionPageQueryReq request) {
		requireOwnedSessionQuery(agentId, userId);
		ChatSessionPageQueryReq pageRequest = request == null ? new ChatSessionPageQueryReq() : request;
		if (!StringUtils.hasText(pageRequest.getChannelType())) {
			pageRequest.setChannelType(ChatSessionChannelDict.WEB.getValue());
		}
		return page(pageRequest.buildPage(), ownedWebSessionQuery(agentId, userId)
			.eq(DataChatSession::getChannelType, pageRequest.getChannelType().trim().toUpperCase())
			.eq(DataChatSession::getProvider, StringUtils.hasText(pageRequest.getProvider())
					? pageRequest.getProvider().trim().toUpperCase() : null)
			.eq(DataChatSession::getConnectorCode, StringUtils.hasText(pageRequest.getConnectorCode())
					? pageRequest.getConnectorCode().trim() : null)
			.ne(DataChatSession::getStatus, AgentSessionConstant.SESSION_STATUS_DELETED)
			.orderByDesc(DataChatSession::getIsPinned)
			.orderByDesc(DataChatSession::getLastModifyTime));
	}

	private LbqWrapper<DataChatSession> ownedWebSessionQuery(Long agentId, Long userId) {
		return Wraps.<DataChatSession>lbQ()
			.eq(DataChatSession::getAgentId, agentId)
			.and(w -> w.eq(DataChatSession::getUserId, userId)
				.or(unowned -> unowned.isNull(DataChatSession::getUserId)
					.eq(DataChatSession::getCreateBy, String.valueOf(userId))));
	}

	private void requireOwnedSessionQuery(Long agentId, Long userId) {
		if (agentId == null) {
			throw CheckedException.badRequest("agentId不能为空");
		}
		if (userId == null) {
			throw CheckedException.forbidden("无法解析当前登录用户，禁止查看会话");
		}
	}

	@Override
	public DataChatSession findBySessionId(Long sessionId) {
		return baseMapper.selectBySessionId(sessionId);
	}

	@Override
	public DataChatSession requireSessionForAgent(Long sessionId, Long agentId) {
		DataChatSession session = baseMapper.selectBySessionId(sessionId);
		if (session == null || agentId == null || session.getAgentId() == null
				|| !agentId.equals(session.getAgentId().longValue())) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found");
		}
		return session;
	}

	/**
	 * Create a new session
	 */
	@Override
	public DataChatSession createSession(Long agentId, String title, Long userId) {
		return createSession(agentId, title, userId, ChatSessionChannelDict.WEB.getValue(), null, null, null, null);
	}

	@Override
	public DataChatSession createSession(Long agentId, String title, Long userId, String channelType, String provider,
			String connectorCode, String sessionScope, String channelSessionKey) {
		DataChatSession session = new DataChatSession(agentId,
				title != null ? title : AgentSessionConstant.DEFAULT_SESSION_TITLE, "active", userId);
		session.setChannelType(StringUtils.hasText(channelType) ? channelType.trim().toUpperCase()
				: ChatSessionChannelDict.WEB.getValue());
		session.setProvider(StringUtils.hasText(provider) ? provider.trim().toUpperCase() : null);
		session.setConnectorCode(StringUtils.hasText(connectorCode) ? connectorCode.trim() : null);
		session.setSessionScope(StringUtils.hasText(sessionScope) ? sessionScope.trim().toUpperCase() : null);
		session.setChannelSessionKey(StringUtils.hasText(channelSessionKey) ? channelSessionKey.trim() : null);
		Instant now = Instant.now();
		session.setLastModifyTime(now);
		baseMapper.insert(session);

		log.info("Created new chat session: {} for agent: {}", session.getId(), agentId);
		return session;
	}

	/**
	 * Clear all sessions for an agent
	 */
	@Override
	public void clearSessionsByAgentId(Long agentId) {
		List<DataChatSession> sessions = findByAgentId(agentId);
		List<String> sessionIds = sessions.stream()
			.map(DataChatSession::getId)
			.map(String::valueOf)
			.collect(Collectors.toList());
		for (DataChatSession session : sessions) {
			deleteV2SessionState(session);
		}
		nativeSessionService.deleteSessionStates(sessionIds);
		Instant now = Instant.now();
		int updated = baseMapper.softDeleteByAgentId(agentId, now);
		log.info("Cleared {} sessions for agent: {}", updated, agentId);
	}

	/**
	 * Update the last activity time of a session
	 */
	@Override
	public void updateSessionTime(Long sessionId) {
		Instant now = Instant.now();
		baseMapper.updateSessionTime(sessionId, now);
	}

	@Override
	public void updateSessionTime(Long sessionId, Long agentId) {
		requireSessionForAgent(sessionId, agentId);
		updateSessionTime(sessionId);
	}

	/**
	 * 置顶/取消置顶会话
	 */
	@Override
	public void pinSession(Long sessionId, boolean isPinned) {
		Instant now = Instant.now();
		baseMapper.updatePinStatus(sessionId, isPinned, now);
		log.info("Updated pin status for session: {} to: {}", sessionId, isPinned);
	}

	@Override
	public void pinSession(Long sessionId, boolean isPinned, Long agentId) {
		requireSessionForAgent(sessionId, agentId);
		pinSession(sessionId, isPinned);
	}

	/**
	 * Rename session
	 */
	@Override
	public void renameSession(Long sessionId, String newTitle) {
		Instant now = Instant.now();
		baseMapper.updateTitle(sessionId, newTitle, now);
		log.info("Renamed session: {} to: {}", sessionId, newTitle);
	}

	@Override
	public void renameSession(Long sessionId, String newTitle, Long agentId) {
		requireSessionForAgent(sessionId, agentId);
		renameSession(sessionId, newTitle);
	}

	/**
	 * Delete a single session
	 */
	@Override
	public void deleteSession(Long sessionId) {
		DataChatSession session = findBySessionId(sessionId);
		deleteV2SessionState(session);
		nativeSessionService.deleteSessionState(String.valueOf(sessionId));
		Instant now = Instant.now();
		baseMapper.softDeleteById(sessionId, now);
		log.info("Deleted session: {}", sessionId);
	}

	/**
	 * 会话删除同时墓碑清理 as2:。缺租户/用户时不能 bind {@code _}，只打日志跳过；store 写入失败向上抛（fail-closed）。
	 */
	private void deleteV2SessionState(DataChatSession session) {
		V2AgentStateStore store = v2AgentStateStoreProvider == null ? null
				: v2AgentStateStoreProvider.getIfAvailable();
		if (store == null) {
			return;
		}
		if (session == null || session.getId() == null || session.getUserId() == null
				|| !StringUtils.hasText(session.getTenantId()) || "_".equals(session.getTenantId().trim())) {
			log.warn("Skip as2: session tombstone, tenant/user/session missing. sessionId={}",
					session == null ? null : session.getId());
			return;
		}
		String userId = String.valueOf(session.getUserId());
		if (!StringUtils.hasText(userId) || "_".equals(userId.trim())) {
			log.warn("Skip as2: session tombstone, blank user. sessionId={}", session.getId());
			return;
		}
		String tenantId = session.getTenantId().trim();
		String sessionId = String.valueOf(session.getId());
		V2RuntimeSnapshot snapshot = new V2RuntimeSnapshot(tenantId, tenantId, userId, null, null, null, List.of(),
				sessionId, null);
		store.bind(snapshot).delete(userId, sessionId);
	}

	@Override
	public void deleteSession(Long sessionId, Long agentId) {
		requireSessionForAgent(sessionId, agentId);
		deleteSession(sessionId);
	}

}
