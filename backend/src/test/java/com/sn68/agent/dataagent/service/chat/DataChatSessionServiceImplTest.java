/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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

import com.sn68.agent.dataagent.agentscope.session.AgentScopeNativeSessionService;
import com.sn68.agent.dataagent.agentscope.v2.V2AgentStateStore;
import com.sn68.agent.dataagent.agentscope.v2.V2RuntimeSnapshot;
import com.sn68.agent.dataagent.entity.DataChatSession;
import com.sn68.agent.dataagent.enums.ChatSessionChannelDict;
import com.sn68.agent.dataagent.repository.DataChatSessionMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataChatSessionServiceImplTest {

	private DataChatSessionMapper mapper;

	private AgentScopeNativeSessionService nativeSessionService;

	private V2AgentStateStore rootStore;

	private V2AgentStateStore boundStore;

	private DataChatSessionServiceImpl service;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		mapper = mock(DataChatSessionMapper.class);
		nativeSessionService = mock(AgentScopeNativeSessionService.class);
		rootStore = mock(V2AgentStateStore.class);
		boundStore = mock(V2AgentStateStore.class);
		when(rootStore.bind(any())).thenReturn(boundStore);
		ObjectProvider<V2AgentStateStore> storeProvider = mock(ObjectProvider.class);
		when(storeProvider.getIfAvailable()).thenReturn(rootStore);
		service = new DataChatSessionServiceImpl(nativeSessionService, storeProvider);
		ReflectionTestUtils.setField(service, "baseMapper", mapper);
	}

	@Test
	void deleteSessionTombstonesAs2State() {
		DataChatSession session = session(100L, "tenant-1", 7L);
		when(mapper.selectBySessionId(100L)).thenReturn(session);

		service.deleteSession(100L);

		ArgumentCaptor<V2RuntimeSnapshot> snapshot = ArgumentCaptor.forClass(V2RuntimeSnapshot.class);
		verify(rootStore).bind(snapshot.capture());
		assertEquals("tenant-1", snapshot.getValue().tenantId());
		assertEquals("7", snapshot.getValue().userId());
		assertEquals("100", snapshot.getValue().sessionId());
		org.mockito.InOrder order = inOrder(boundStore, nativeSessionService, mapper);
		order.verify(boundStore).delete("7", "100");
		order.verify(nativeSessionService).deleteSessionState("100");
		order.verify(mapper).softDeleteById(eq(100L), any());
	}

	@Test
	void deleteSessionSkipsAs2WhenTenantMissing() {
		DataChatSession session = session(100L, null, 7L);
		when(mapper.selectBySessionId(100L)).thenReturn(session);

		service.deleteSession(100L);

		verify(nativeSessionService).deleteSessionState("100");
		verify(rootStore, never()).bind(any());
		verify(boundStore, never()).delete(any(), any());
	}

	@Test
	void deleteSessionPropagatesAs2WriteFailure() {
		DataChatSession session = session(100L, "tenant-1", 7L);
		when(mapper.selectBySessionId(100L)).thenReturn(session);
		when(rootStore.bind(any())).thenReturn(boundStore);
		org.mockito.Mockito.doThrow(CheckedException.fail("v2 AgentStateStore Redis 写入失败: down"))
			.when(boundStore)
			.delete(eq("7"), eq("100"));

		assertThrows(CheckedException.class, () -> service.deleteSession(100L));
		verify(nativeSessionService, never()).deleteSessionState(any());
		verify(mapper, never()).softDeleteById(any(), any());
	}

	@Test
	void clearSessionsByAgentIdTombstonesEachAs2Session() {
		DataChatSession first = session(100L, "tenant-1", 7L);
		DataChatSession second = session(101L, "tenant-1", 7L);
		when(mapper.selectByAgentId(eq(1L), eq(ChatSessionChannelDict.WEB.getValue())))
			.thenReturn(List.of(first, second));

		service.clearSessionsByAgentId(1L);

		org.mockito.InOrder order = inOrder(boundStore, nativeSessionService, mapper);
		order.verify(boundStore).delete("7", "100");
		order.verify(boundStore).delete("7", "101");
		order.verify(nativeSessionService).deleteSessionStates(List.of("100", "101"));
		order.verify(mapper).softDeleteByAgentId(eq(1L), any());
	}

	@Test
	void deleteSessionSkipsAs2WhenStoreMissing() {
		@SuppressWarnings("unchecked")
		ObjectProvider<V2AgentStateStore> empty = mock(ObjectProvider.class);
		when(empty.getIfAvailable()).thenReturn(null);
		DataChatSessionServiceImpl noStore = new DataChatSessionServiceImpl(nativeSessionService, empty);
		ReflectionTestUtils.setField(noStore, "baseMapper", mapper);
		when(mapper.selectBySessionId(100L)).thenReturn(session(100L, "tenant-1", 7L));

		noStore.deleteSession(100L);

		verify(nativeSessionService).deleteSessionState("100");
		verify(rootStore, never()).bind(any());
	}

	private static DataChatSession session(Long id, String tenantId, Long userId) {
		DataChatSession session = new DataChatSession(1L, "web会话", "active", userId);
		session.setId(id);
		session.setTenantId(tenantId);
		session.setChannelType(ChatSessionChannelDict.WEB.getValue());
		return session;
	}

}
