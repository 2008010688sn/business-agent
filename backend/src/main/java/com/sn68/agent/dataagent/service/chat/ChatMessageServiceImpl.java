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
import com.sn68.agent.dataagent.repository.DataChatMessageMapper;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Chat Message Service Class
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageServiceImpl extends SuperServiceImpl<DataChatMessageMapper, DataChatMessage>
		implements ChatMessageService {

	private final DataChatSessionService chatSessionService;

	@Override
	public List<DataChatMessage> findBySessionId(Long sessionId) {
		return baseMapper.selectBySessionId(sessionId);
	}

	@Override
	public List<DataChatMessage> findVisibleBySessionId(Long sessionId) {
		return baseMapper.selectVisibleBySessionId(sessionId);
	}

	@Override
	public List<DataChatMessage> findVisibleBySessionId(Long sessionId, Long agentId) {
		chatSessionService.requireSessionForAgent(sessionId, agentId);
		return findVisibleBySessionId(sessionId);
	}

	@Override
	public List<DataChatMessage> findBySessionIdAndMessageType(Long sessionId, String messageType) {
		return baseMapper.selectBySessionIdAndMessageType(sessionId, messageType);
	}

	@Override
	public List<DataChatMessage> findBySessionIdAndMessageType(Long sessionId, String messageType, Long agentId) {
		chatSessionService.requireSessionForAgent(sessionId, agentId);
		return findBySessionIdAndMessageType(sessionId, messageType);
	}

	@Override
	public DataChatMessage saveMessage(DataChatMessage message) {
		baseMapper.insert(message);
		log.info("Saved message: {} for session: {}", message.getId(), message.getSessionId());
		return message;
	}

	@Override
	public DataChatMessage saveMessage(DataChatMessage message, Long agentId) {
		if (message != null) {
			chatSessionService.requireSessionForAgent(message.getSessionId(), agentId);
		}
		return saveMessage(message);
	}

}
