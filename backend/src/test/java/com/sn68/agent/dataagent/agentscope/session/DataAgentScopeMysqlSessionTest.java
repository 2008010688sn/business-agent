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

import com.sn68.agent.dataagent.agentscope.v2.V2AgentStateStore;
import com.sn68.agent.dataagent.constant.AgentSessionConstant;
import com.sn68.agent.dataagent.entity.AgentScopeSessionState;
import com.sn68.agent.dataagent.entity.DataChatMessage;
import com.sn68.agent.dataagent.entity.DataChatSession;
import com.sn68.agent.dataagent.repository.AgentScopeSessionStateMapper;
import com.sn68.agent.dataagent.repository.DataChatMessageMapper;
import com.sn68.agent.dataagent.repository.DataChatSessionMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataAgentScopeMysqlSessionTest {

	private final DataChatSessionMapper chatSessionMapper = mock(DataChatSessionMapper.class);

	private final AgentScopeSessionStateMapper sessionStateMapper = mock(AgentScopeSessionStateMapper.class);

	private final DataChatMessageMapper chatMessageMapper = mock(DataChatMessageMapper.class);

	private final AgentScopeMysqlSession session = new AgentScopeMysqlSession(chatSessionMapper, sessionStateMapper,
			chatMessageMapper);

	@Test
	void save_writesAgentScopeStateToDedicatedTable() {
		when(chatSessionMapper.selectBySessionId(100L)).thenReturn(DataChatSession.builder().id(100L).build());
		Msg message = Msg.builder().name("user").role(MsgRole.USER).textContent("hello").build();

		session.save(null, "100", "autoContextMemory_workingMessages", List.of(message));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Collection<AgentScopeSessionState>> captor = ArgumentCaptor.forClass(Collection.class);
		verify(sessionStateMapper).deleteBySessionIdAndModuleName(100L, "autoContextMemory_workingMessages");
		verify(sessionStateMapper).insertBatch(captor.capture());
		verify(chatMessageMapper, never()).insert(any(DataChatMessage.class));
		AgentScopeSessionState state = captor.getValue().iterator().next();
		assertEquals(100L, state.getSessionId());
		assertEquals("autoContextMemory_workingMessages", state.getModuleName());
		assertEquals(0, state.getSequence());
		assertTrue(state.getContent().contains("hello"));
	}

	@Test
	void saveEmptyList_clearsCurrentAndLegacyStateWithoutInsert() {
		when(chatSessionMapper.selectBySessionId(100L)).thenReturn(DataChatSession.builder().id(100L).build());

		session.save(null, "100", "memory_messages", List.of());

		verify(sessionStateMapper).deleteBySessionIdAndModuleName(100L, "memory_messages");
		verify(chatMessageMapper).deleteBySessionIdAndMessageType(100L,
				AgentScopeMysqlSession.legacyMessageType("memory_messages"));
		verify(sessionStateMapper, never()).insertBatch(anyCollection());
	}

	@ParameterizedTest
	@ValueSource(ints = { 10, 50, 100 })
	void saveList_usesOneProjectBatchAndPreservesOrder(int stateCount) {
		when(chatSessionMapper.selectBySessionId(100L)).thenReturn(DataChatSession.builder().id(100L).build());
		List<Msg> messages = java.util.stream.IntStream.range(0, stateCount)
			.mapToObj(index -> Msg.builder()
				.name("user")
				.role(MsgRole.USER)
				.textContent("message-" + index)
				.build())
			.toList();

		session.save(null, "100", "memory_messages", messages);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Collection<AgentScopeSessionState>> captor = ArgumentCaptor.forClass(Collection.class);
		verify(sessionStateMapper).insertBatch(captor.capture());
		List<AgentScopeSessionState> states = List.copyOf(captor.getValue());
		assertEquals(stateCount, states.size());
		for (int index = 0; index < stateCount; index++) {
			assertEquals(index, states.get(index).getSequence());
			assertTrue(states.get(index).getContent().contains("message-" + index));
		}
		verify(sessionStateMapper, never()).insertBatch(anyCollection(), anyInt());
	}

	@Test
	void saveList_propagatesBatchFailureForTransactionRollback() throws Exception {
		when(chatSessionMapper.selectBySessionId(100L)).thenReturn(DataChatSession.builder().id(100L).build());
		doThrow(new IllegalStateException("batch failed")).when(sessionStateMapper).insertBatch(anyCollection());

		assertThrows(IllegalStateException.class,
				() -> session.save(null, "100", "memory_messages",
						List.of(Msg.builder().role(MsgRole.USER).textContent("hello").build())));

		Transactional transactional = AgentScopeMysqlSession.class
			.getMethod("save", String.class, String.class, String.class, List.class)
			.getAnnotation(Transactional.class);
		assertNotNull(transactional);
		assertEquals(Exception.class, transactional.rollbackFor()[0]);
		verify(sessionStateMapper).deleteBySessionIdAndModuleName(100L, "memory_messages");
		verify(chatMessageMapper).deleteBySessionIdAndMessageType(100L,
				AgentScopeMysqlSession.legacyMessageType("memory_messages"));
	}

	@Test
	void getList_readsDedicatedTableBeforeLegacyMessageTable() {
		when(sessionStateMapper.selectBySessionIdAndModuleName(100L, "memory_messages"))
			.thenReturn(List.of(AgentScopeSessionState.builder()
				.content("""
						{"name":"user","role":"USER","content":[{"type":"text","text":"new"}],"metadata":{}}
						""")
				.build()));

		List<Msg> messages = session.getList(null, "100", "memory_messages", Msg.class);

		assertEquals(1, messages.size());
		assertEquals("new", messages.get(0).getTextContent());
		verify(chatMessageMapper, never()).selectStateBySessionIdAndMessageType(eq(100L), any());
	}

	@Test
	void get_fallsBackToLegacyAgentscopeStateMessage() {
		when(sessionStateMapper.selectBySessionIdAndModuleName(100L, "memory_messages")).thenReturn(List.of());
		when(chatMessageMapper.selectStateBySessionIdAndMessageType(100L,
				AgentScopeMysqlSession.legacyMessageType("memory_messages")))
			.thenReturn(List.of(DataChatMessage.builder()
				.content("""
						{"name":"user","role":"USER","content":[{"type":"text","text":"legacy"}],"metadata":{}}
						""")
				.build()));

		Optional<Msg> message = session.get(null, "100", "memory_messages", Msg.class);

		assertTrue(message.isPresent());
		assertEquals("legacy", message.get().getTextContent());
	}

	@Test
	void legacyMessageType_matchesExistingEncodedMessageType() {
		String messageType = AgentScopeMysqlSession.legacyMessageType("memory_messages");

		assertEquals("agentscope-state:bWVtb3J5X21lc3NhZ2Vz", messageType);
		assertFalse(messageType.equals("memory_messages"));
	}

	@Test
	void save_requiresActiveChatSession() {
		when(chatSessionMapper.selectBySessionId(100L)).thenReturn(null);

		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> session.save(null, "100", "memory_messages",
						List.of(Msg.builder().role(MsgRole.USER).textContent("hello").build())));

		assertTrue(ex.getMessage().contains("existing data_chat_session"));
		verify(sessionStateMapper, never()).deleteBySessionIdAndModuleName(any(), any());
		verify(sessionStateMapper, never()).insertBatch(anyCollection());
	}

	@Test
	void saveTombstone_writesWhenChatSessionAlreadyDeleted() {
		when(chatSessionMapper.selectBySessionId(100L)).thenReturn(null);
		when(chatSessionMapper.selectById(100L)).thenReturn(DataChatSession.builder()
			.id(100L)
			.status(AgentSessionConstant.SESSION_STATUS_DELETED)
			.build());

		session.saveTombstone(null, "100", "as2:t:u:k", new V2AgentStateStore.DeletedState());

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Collection<AgentScopeSessionState>> captor = ArgumentCaptor.forClass(Collection.class);
		verify(sessionStateMapper).deleteBySessionIdAndModuleName(100L, "as2:t:u:k");
		verify(sessionStateMapper).insertBatch(captor.capture());
		AgentScopeSessionState state = captor.getValue().iterator().next();
		assertEquals(100L, state.getSessionId());
		assertEquals("as2:t:u:k", state.getModuleName());
		assertTrue(state.getContent().contains("\"marker\""));
	}

	@Test
	void saveTombstone_isNoOpWhenChatSessionRowMissing() {
		when(chatSessionMapper.selectById(100L)).thenReturn(null);

		session.saveTombstone(null, "100", "as2:t:u:k", new V2AgentStateStore.DeletedState());

		verify(sessionStateMapper, never()).deleteBySessionIdAndModuleName(any(), any());
		verify(sessionStateMapper, never()).insertBatch(anyCollection());
	}

	@Test
	void saveAndReadSkillActivationState() {
		when(chatSessionMapper.selectBySessionId(100L)).thenReturn(DataChatSession.builder().id(100L).build());
		AgentScopeSkillActivationState activationState = new AgentScopeSkillActivationState("1",
				List.of("order-query_custom"));

		session.save(null, "100", "on_demand_skill_activation", activationState);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Collection<AgentScopeSessionState>> captor = ArgumentCaptor.forClass(Collection.class);
		verify(sessionStateMapper).insertBatch(captor.capture());
		when(sessionStateMapper.selectBySessionIdAndModuleName(100L, "on_demand_skill_activation"))
			.thenReturn(List.copyOf(captor.getValue()));
		Optional<AgentScopeSkillActivationState> restored = session.get(null, "100",
				"on_demand_skill_activation", AgentScopeSkillActivationState.class);

		assertTrue(restored.isPresent());
		assertEquals("1", restored.get().agentId());
		assertEquals(List.of("order-query_custom"), restored.get().activeSkillIds());
	}

}
