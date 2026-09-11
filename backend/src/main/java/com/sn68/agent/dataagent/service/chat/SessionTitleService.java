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

import com.sn68.agent.dataagent.constant.AgentSessionConstant;
import com.sn68.agent.dataagent.entity.DataChatSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Generate deterministic session titles asynchronously and push results to frontend.
 */
@Slf4j
@Service
public class SessionTitleService {

	private final DataChatSessionService chatSessionService;

	private final SessionEventPublisher sessionEventPublisher;

	private final ExecutorService executorService;

	private final Set<Long> runningTasks = ConcurrentHashMap.newKeySet();

	public SessionTitleService(DataChatSessionService chatSessionService, SessionEventPublisher sessionEventPublisher,
			@Qualifier("dbOperationExecutor") ExecutorService executorService) {
		this.chatSessionService = chatSessionService;
		this.sessionEventPublisher = sessionEventPublisher;
		this.executorService = executorService;
	}

	public void scheduleTitleGeneration(Long sessionId, String userMessage) {
		if (sessionId == null || !StringUtils.hasText(userMessage)) {
			return;
		}
		if (!runningTasks.add(sessionId)) {
			return;
		}
		CompletableFuture.runAsync(() -> generateAndPersist(sessionId, userMessage), executorService)
			.whenComplete((unused, throwable) -> runningTasks.remove(sessionId));
	}

	private void generateAndPersist(Long sessionId, String userMessage) {
		long totalStart = System.nanoTime();
		try {
			DataChatSession session = chatSessionService.findBySessionId(sessionId);
			if (session == null) {
				log.warn("Session {} not found when generating title", sessionId);
				return;
			}
			if (hasCustomTitle(session)) {
				log.debug("Session {} already has custom title, skip generating", sessionId);
				return;
			}

			long localTitleStart = System.nanoTime();
			String title = normalizeTitle(fallbackTitle(userMessage));
			long localTitleMs = elapsedMs(localTitleStart);
			if (!StringUtils.hasText(title)) {
				log.warn("Local title generation returned empty title for session {}", sessionId);
				return;
			}

			chatSessionService.renameSession(sessionId, title);
			sessionEventPublisher.publishTitleUpdated(session.getAgentId(), String.valueOf(sessionId), title);
			log.info("Generated local session title '{}' for session {}, localTitleMs={}, totalMs={}", title,
					sessionId, localTitleMs, elapsedMs(totalStart));
		}
		catch (Exception ex) {
			log.error("Failed to generate session title for session {}, totalMs={}", sessionId, elapsedMs(totalStart), ex);
		}
	}

	private long elapsedMs(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000L;
	}

	private boolean hasCustomTitle(DataChatSession session) {
		return StringUtils.hasText(session.getTitle())
				&& !AgentSessionConstant.DEFAULT_SESSION_TITLE.equals(session.getTitle());
	}

	private String normalizeTitle(String raw) {
		if (!StringUtils.hasText(raw)) {
			return null;
		}
		String sanitized = raw.replaceAll("[\\r\\n]+", " ").replaceAll("[\"“”]+", "").trim();
		if (sanitized.length() > AgentSessionConstant.SESSION_TITLE_MAX_LENGTH) {
			sanitized = sanitized.substring(0, AgentSessionConstant.SESSION_TITLE_MAX_LENGTH);
		}
		return sanitized;
	}

	private String fallbackTitle(String userMessage) {
		String text = userMessage.replaceAll("\\s+", " ").trim();
		if (text.length() > AgentSessionConstant.SESSION_TITLE_MAX_LENGTH) {
			text = text.substring(0, AgentSessionConstant.SESSION_TITLE_MAX_LENGTH);
		}
		return StringUtils.hasText(text) ? text : AgentSessionConstant.DEFAULT_SESSION_TITLE;
	}

}
