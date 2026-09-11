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

import com.sn68.agent.dataagent.entity.AgentScopeSessionState;
import com.sn68.agent.dataagent.entity.DataChatMessage;
import com.sn68.agent.dataagent.repository.AgentScopeSessionStateMapper;
import com.sn68.agent.dataagent.repository.DataChatMessageMapper;
import com.sn68.agent.dataagent.repository.DataChatSessionMapper;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * AgentScope 原生 Session 的数据库实现：会话状态按模块持久化到独立状态表，
 * 消息走会话/消息表；兼容读取历史"消息表内嵌状态"的旧格式（LEGACY 前缀），写入只用新表。
 *
 * <p>Isolation boundary is {@code data_chat_session} PK ({@code sessionId}), not {@code userId}.
 * AgentScope 2.0 {@link AgentStateStore} keys include userId, but this store ignores it for
 * lookup and relies on stream/session access checks plus numeric session identity. Do not
 * implement an unscoped {@link #listSessionIds(String)} scan.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentScopeMysqlSession implements AgentStateStore {

	static final String LEGACY_STATE_MESSAGE_TYPE_PREFIX = "agentscope-state:";

	private final DataChatSessionMapper chatSessionMapper;

	private final AgentScopeSessionStateMapper sessionStateMapper;

	private final DataChatMessageMapper chatMessageMapper;

	private final JsonCodec jsonCodec = JsonUtils.getJsonCodec();

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void save(String userId, String sessionId, String key, State state) {
		Long numericSessionId = sessionId(sessionId);
		assertManagedChatSession(numericSessionId);
		replaceStateMessages(numericSessionId, key,
				List.of(buildState(numericSessionId, key, state.getClass().getName(), 0,
						jsonCodec.toPrettyJson(state))));
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void save(String userId, String sessionId, String key, List<? extends State> states) {
		Long numericSessionId = sessionId(sessionId);
		assertManagedChatSession(numericSessionId);
		List<AgentScopeSessionState> stateList = new ArrayList<>();
		if (states != null) {
			for (int index = 0; index < states.size(); index++) {
				State state = states.get(index);
				if (state == null) {
					continue;
				}
				stateList.add(buildState(numericSessionId, key, state.getClass().getName(), index,
						jsonCodec.toJson(state)));
			}
		}
		replaceStateMessages(numericSessionId, key, stateList);
	}

	/**
	 * Write a delete tombstone even when {@code data_chat_session.status} is already
	 * deleted. A missing row is a no-op so session delete stays idempotent.
	 */
	@Transactional(rollbackFor = Exception.class)
	public void saveTombstone(String userId, String sessionId, String key, State state) {
		Long numericSessionId = sessionId(sessionId);
		if (chatSessionMapper.selectById(numericSessionId) == null) {
			log.info("Skip AgentScope tombstone, data_chat_session row missing. sessionId={}", numericSessionId);
			return;
		}
		if (state == null) {
			replaceStateMessages(numericSessionId, key, List.of());
			return;
		}
		replaceStateMessages(numericSessionId, key,
				List.of(buildState(numericSessionId, key, state.getClass().getName(), 0,
						jsonCodec.toPrettyJson(state))));
	}

	@Override
	public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) {
		List<String> rows = selectStateContents(sessionId(sessionId), key);
		if (rows.isEmpty()) {
			return Optional.empty();
		}
		return Optional.ofNullable(jsonCodec.fromJson(rows.get(rows.size() - 1), type));
	}

	@Override
	public <T extends State> List<T> getList(String userId, String sessionId, String key, Class<T> itemType) {
		return selectStateContents(sessionId(sessionId), key).stream()
			.map(content -> jsonCodec.fromJson(content, itemType))
			.collect(Collectors.toList());
	}

	@Override
	public boolean exists(String userId, String sessionId) {
		Long numericSessionId = sessionId(sessionId);
		return sessionStateMapper.countBySessionId(numericSessionId) > 0
				|| chatMessageMapper.countAgentScopeStateBySessionId(numericSessionId) > 0;
	}

	@Override
	public void delete(String userId, String sessionId) {
		Long numericSessionId = sessionId(sessionId);
		sessionStateMapper.deleteBySessionId(numericSessionId);
		chatMessageMapper.deleteAgentScopeStateBySessionId(numericSessionId);
	}

	@Override
	public void delete(String userId, String sessionId, String key) {
		deleteStateMessages(sessionId(sessionId), key);
	}

	@Override
	public Set<String> listSessionIds(String userId) {
		throw new UnsupportedOperationException(
				"AgentScopeMysqlSession isolates by data_chat_session PK, not userId. Unscoped listSessionIds is not supported.");
	}

	public Mono<Integer> clearAllSessions() {
		return Mono
			.fromCallable(() -> sessionStateMapper.deleteAllStates()
					+ chatMessageMapper.deleteAllAgentScopeStateMessages())
			.subscribeOn(Schedulers.boundedElastic());
	}

	private void assertManagedChatSession(Long sessionId) {
		if (chatSessionMapper.selectBySessionId(sessionId) == null) {
			throw new IllegalStateException(
					"AgentScope session persistence requires an existing data_chat_session. sessionId=" + sessionId);
		}
	}

	private Long sessionId(String sessionId) {
		return parseSessionId(sessionId);
	}

	private Long parseSessionId(String sessionId) {
		try {
			return Long.valueOf(sessionId);
		}
		catch (NumberFormatException ex) {
			throw new IllegalArgumentException("AgentScope sessionId must be numeric: " + sessionId, ex);
		}
	}

	private List<String> selectStateContents(Long sessionId, String moduleName) {
		List<String> currentRows = sessionStateMapper.selectBySessionIdAndModuleName(sessionId, moduleName)
			.stream()
			.map(AgentScopeSessionState::getContent)
			.toList();
		if (!currentRows.isEmpty()) {
			return currentRows;
		}
		return chatMessageMapper.selectStateBySessionIdAndMessageType(sessionId, legacyMessageType(moduleName))
			.stream()
			.map(DataChatMessage::getContent)
			.toList();
	}

	private void deleteStateMessages(Long sessionId, String moduleName) {
		sessionStateMapper.deleteBySessionIdAndModuleName(sessionId, moduleName);
		chatMessageMapper.deleteBySessionIdAndMessageType(sessionId, legacyMessageType(moduleName));
	}

	private void replaceStateMessages(Long sessionId, String moduleName, List<AgentScopeSessionState> states) {
		deleteStateMessages(sessionId, moduleName);
		if (!states.isEmpty()) {
			sessionStateMapper.insertBatch(states);
		}
	}

	static String legacyMessageType(String moduleName) {
		String encodedModuleName = Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(moduleName.getBytes(StandardCharsets.UTF_8));
		return LEGACY_STATE_MESSAGE_TYPE_PREFIX + encodedModuleName;
	}

	private AgentScopeSessionState buildState(Long sessionId, String moduleName, String stateClass, int sequence,
			String content) {
		return AgentScopeSessionState.builder()
			.sessionId(sessionId)
			.moduleName(moduleName)
			.stateClass(stateClass)
			.sequence(sequence)
			.content(content)
			.build();
	}

}
