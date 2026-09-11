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
package com.sn68.agent.dataagent.agentscope.session;

import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * AgentScope 原生会话状态的读写门面：校验会话 ID 合法性，按模块加载/保存记忆与工作消息状态，
 * 屏蔽底层 Session 实现细节供运行时装配使用。
 */
@Slf4j
@Component
public class AgentScopeNativeSessionService {

	private static final Pattern SESSION_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");

	private static final Set<String> MESSAGE_STATE_MODULES = Set.of("memory_messages",
			"autoContextMemory_workingMessages", "autoContextMemory_originalMessages");

	private final AgentStateStore session;

	public AgentScopeNativeSessionService(AgentScopeMysqlSession session) {
		this.session = session;
	}

	public boolean loadMemoryIfExists(Memory memory, String sessionId) {
		return loadMemoryIfExists(memory, sessionId, null);
	}

	public boolean loadMemoryIfExists(Memory memory, String sessionId, String userId) {
		Objects.requireNonNull(memory, "memory must not be null");
		if (!StringUtils.hasText(sessionId)) {
			return false;
		}
		if (!isNumericSessionId(sessionId)) {
			return false;
		}
		try {
			if (!session.exists(userId, normalizeSessionId(sessionId))) {
				return false;
			}
			memory.loadFrom(session, userId, normalizeSessionId(sessionId));
			return true;
		}
		catch (RuntimeException ex) {
			log.warn("Failed to load AgentScope native session state from MySQL. sessionId={}", sessionId, ex);
			return false;
		}
	}

	public boolean loadStateIfExists(Memory memory, String sessionId) {
		return loadStateIfExists(memory, sessionId, null);
	}

	public boolean loadStateIfExists(Memory memory, String sessionId, String userId) {
		Objects.requireNonNull(memory, "memory must not be null");
		if (!StringUtils.hasText(sessionId) || !isNumericSessionId(sessionId)) {
			return false;
		}
		try {
			String normalized = normalizeSessionId(sessionId);
			if (!session.exists(userId, normalized)) {
				return false;
			}
			memory.loadFrom(session, userId, normalized);
			return !memory.getMessages().isEmpty();
		}
		catch (RuntimeException ex) {
			log.warn("Failed to load AgentScope native memory state. sessionId={}", sessionId, ex);
			return false;
		}
	}

	public void saveMemory(Memory memory, String sessionId) {
		saveMemory(memory, sessionId, (String) null);
	}

	public void saveMemory(Memory memory, String sessionId, String userId) {
		Objects.requireNonNull(memory, "memory must not be null");
		if (!StringUtils.hasText(sessionId) || !isNumericSessionId(sessionId)) {
			return;
		}
		memory.saveTo(session, userId, normalizeSessionId(sessionId));
	}

	public void saveMemory(Memory memory, String sessionId, UnaryOperator<List<Msg>> messageNormalizer) {
		saveMemory(memory, sessionId, null, messageNormalizer);
	}

	public void saveMemory(Memory memory, String sessionId, String userId, UnaryOperator<List<Msg>> messageNormalizer) {
		Objects.requireNonNull(memory, "memory must not be null");
		Objects.requireNonNull(messageNormalizer, "messageNormalizer must not be null");
		if (!StringUtils.hasText(sessionId) || !isNumericSessionId(sessionId)) {
			return;
		}
		memory.saveTo(new MessageNormalizingStore(session, messageNormalizer), userId, normalizeSessionId(sessionId));
	}

	public String toSessionKey(String sessionId) {
		if (!StringUtils.hasText(sessionId) || !isNumericSessionId(sessionId)) {
			throw new IllegalArgumentException("sessionId 必须为数字");
		}
		return normalizeSessionId(sessionId);
	}

	public boolean supportsSessionId(String sessionId) {
		return StringUtils.hasText(sessionId) && isNumericSessionId(sessionId);
	}

	public <T extends State> Optional<T> readState(String sessionId, String moduleName, Class<T> stateClass) {
		Objects.requireNonNull(stateClass, "stateClass must not be null");
		return session.get(null, toSessionKey(sessionId), requireModuleName(moduleName), stateClass);
	}

	public void saveState(String sessionId, String moduleName, State state) {
		Objects.requireNonNull(state, "state must not be null");
		session.save(null, toSessionKey(sessionId), requireModuleName(moduleName), state);
	}

	public void deleteStateModule(String sessionId, String moduleName) {
		session.delete(null, toSessionKey(sessionId), requireModuleName(moduleName));
	}

	public void deleteSessionState(String sessionId) {
		if (!StringUtils.hasText(sessionId) || !isNumericSessionId(sessionId)) {
			return;
		}
		try {
			String normalized = normalizeSessionId(sessionId);
			if (session.exists(null, normalized)) {
				session.delete(null, normalized);
			}
		}
		catch (RuntimeException ex) {
			throw new IllegalStateException("Failed to delete AgentScope session state: " + sessionId, ex);
		}
	}

	public void deleteSessionStates(Collection<String> sessionIds) {
		if (sessionIds == null || sessionIds.isEmpty()) {
			return;
		}
		for (String sessionId : new LinkedHashSet<>(sessionIds)) {
			deleteSessionState(sessionId);
		}
	}

	private String requireModuleName(String moduleName) {
		if (!StringUtils.hasText(moduleName)) {
			throw new IllegalArgumentException("moduleName must not be empty");
		}
		return moduleName.trim();
	}

	private String normalizeSessionId(String sessionId) {
		if (!StringUtils.hasText(sessionId)) {
			throw new IllegalArgumentException("sessionId 不能为空");
		}
		String normalized = sessionId.trim();
		if (!SESSION_ID_PATTERN.matcher(normalized).matches()) {
			throw new IllegalArgumentException("sessionId 包含非法字符: " + sessionId);
		}
		return normalized;
	}

	private boolean isNumericSessionId(String sessionId) {
		if (!StringUtils.hasText(sessionId)) {
			return false;
		}
		String normalized = sessionId.trim();
		for (int i = 0; i < normalized.length(); i++) {
			if (!Character.isDigit(normalized.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	private static final class MessageNormalizingStore implements AgentStateStore {

		private final AgentStateStore delegate;

		private final UnaryOperator<List<Msg>> messageNormalizer;

		private MessageNormalizingStore(AgentStateStore delegate, UnaryOperator<List<Msg>> messageNormalizer) {
			this.delegate = delegate;
			this.messageNormalizer = messageNormalizer;
		}

		@Override
		public void save(String userId, String sessionId, String key, State value) {
			delegate.save(userId, sessionId, key, value);
		}

		@Override
		public void save(String userId, String sessionId, String key, List<? extends State> values) {
			delegate.save(userId, sessionId, key, normalizeMessages(key, values));
		}

		private List<? extends State> normalizeMessages(String key, List<? extends State> values) {
			if (!MESSAGE_STATE_MODULES.contains(key) || values == null || values.isEmpty()
					|| values.stream().anyMatch(value -> !(value instanceof Msg))) {
				return values;
			}
			List<Msg> messages = values.stream().map(Msg.class::cast).toList();
			return Objects.requireNonNull(messageNormalizer.apply(messages),
					"messageNormalizer result must not be null");
		}

		@Override
		public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) {
			return delegate.get(userId, sessionId, key, type);
		}

		@Override
		public <T extends State> List<T> getList(String userId, String sessionId, String key, Class<T> itemType) {
			return delegate.getList(userId, sessionId, key, itemType);
		}

		@Override
		public boolean exists(String userId, String sessionId) {
			return delegate.exists(userId, sessionId);
		}

		@Override
		public void delete(String userId, String sessionId) {
			delegate.delete(userId, sessionId);
		}

		@Override
		public void delete(String userId, String sessionId, String key) {
			delegate.delete(userId, sessionId, key);
		}

		@Override
		public Set<String> listSessionIds(String userId) {
			return delegate.listSessionIds(userId);
		}
	}

}
