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

import com.sn68.agent.dataagent.entity.DataChatMessage;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperService;

import java.util.List;

/**
 * 会话消息服务契约。
 */
public interface ChatMessageService extends SuperService<DataChatMessage> {

	/**
	 * Get message list by session ID
	 */
	List<DataChatMessage> findBySessionId(Long sessionId);

	/**
	 * Get visible messages by session ID for UI rendering.
	 */
	List<DataChatMessage> findVisibleBySessionId(Long sessionId);

	/**
	 * 查询会话消息。
	 */
	List<DataChatMessage> findVisibleBySessionId(Long sessionId, Long agentId);

	/**
	 * Get messages by session ID and message type.
	 */
	List<DataChatMessage> findBySessionIdAndMessageType(Long sessionId, String messageType);

	/**
	 * 查询会话消息。
	 */
	List<DataChatMessage> findBySessionIdAndMessageType(Long sessionId, String messageType, Long agentId);

	/**
	 * Save message
	 */
	DataChatMessage saveMessage(DataChatMessage message);

	/**
	 * 保存会话消息。
	 */
	DataChatMessage saveMessage(DataChatMessage message, Long agentId);

}
