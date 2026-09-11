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

import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.State;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentScopeNativeSessionServiceTest {

	@Test
	void saveMemory_normalizesPersistedSnapshotWithoutMutatingRuntimeMemory() {
		AgentScopeMysqlSession session = mock(AgentScopeMysqlSession.class);
		AgentScopeNativeSessionService service = new AgentScopeNativeSessionService(session);
		InMemoryMemory memory = new InMemoryMemory();
		Msg runtimeMessage = Msg.builder().name("user").role(MsgRole.USER).textContent("runtime prompt").build();
		memory.addMessage(runtimeMessage);

		service.saveMemory(memory, "100",
				messages -> List.of(Msg.builder().name("user").role(MsgRole.USER).textContent("original query").build()));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<? extends State>> captor = ArgumentCaptor.forClass(List.class);
		verify(session).save(eq(null), eq("100"), eq("memory_messages"), captor.capture());
		assertEquals("original query", ((Msg) captor.getValue().get(0)).getTextContent());
		assertSame(runtimeMessage, memory.getMessages().get(0));
		assertEquals("runtime prompt", memory.getMessages().get(0).getTextContent());
	}

}
