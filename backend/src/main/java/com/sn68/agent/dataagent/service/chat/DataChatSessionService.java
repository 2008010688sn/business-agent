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
import com.sn68.agent.dataagent.dto.chat.ChatSessionPageQueryReq;
import com.sn68.agent.dataagent.entity.DataChatSession;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperService;

import java.util.List;

/**
 * Chat Session Service Class
 */
public interface DataChatSessionService extends SuperService<DataChatSession> {

	/**
	 * Get session list by agent ID
	 */
	List<DataChatSession> findByAgentId(Long agentId);

	/**
	 * Get session list by agent ID and current user
	 */
	List<DataChatSession> findByAgentIdAndUserId(Long agentId, Long userId);

	/**
	 * Page query session list by agent ID
	 */
	IPage<DataChatSession> queryByAgentId(Long agentId, ChatSessionPageQueryReq request);

	/**
	 * Page query session list by agent ID and current user
	 */
	IPage<DataChatSession> queryByAgentIdAndUserId(Long agentId, Long userId, ChatSessionPageQueryReq request);

	/**
	 * Create a new session
	 */
	DataChatSession createSession(Long agentId, String title, Long userId);

	/**
	 * Create a new session with channel ownership fields.
	 */
	DataChatSession createSession(Long agentId, String title, Long userId, String channelType, String provider,
			String connectorCode, String sessionScope, String channelSessionKey);

	/**
	 * Find session by id.
	 */
	DataChatSession findBySessionId(Long sessionId);

	/**
	 * 校验Data会话会话。
	 */
	DataChatSession requireSessionForAgent(Long sessionId, Long agentId);

	/**
	 * Clear all sessions for an agent
	 */
	void clearSessionsByAgentId(Long agentId);

	/**
	 * Update the last activity time of a session
	 */
	void updateSessionTime(Long sessionId);

	/**
	 * 保存Data会话会话。
	 */
	void updateSessionTime(Long sessionId, Long agentId);

	/**
	 * 置顶/取消置顶会话
	 */
	void pinSession(Long sessionId, boolean isPinned);

	/**
	 * 处理Data会话会话。
	 */
	void pinSession(Long sessionId, boolean isPinned, Long agentId);

	/**
	 * Rename session
	 */
	void renameSession(Long sessionId, String newTitle);

	/**
	 * 处理Data会话会话。
	 */
	void renameSession(Long sessionId, String newTitle, Long agentId);

	/**
	 * Delete a single session
	 */
	void deleteSession(Long sessionId);

	/**
	 * 删除Data会话会话。
	 */
	void deleteSession(Long sessionId, Long agentId);

}
