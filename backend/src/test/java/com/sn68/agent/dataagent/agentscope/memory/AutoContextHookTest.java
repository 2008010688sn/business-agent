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
package com.sn68.agent.dataagent.agentscope.memory;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import java.util.List;
import org.junit.jupiter.api.Test;

class AutoContextHookTest {

	@Test
	void preReasoningKeepsCurrentUserTurnWhenMemoryOnlyHasHistory() {
		AutoContextMemory memory = new AutoContextMemory(AutoContextConfig.builder().msgThreshold(100).lastKeep(50)
			.maxToken(128_000).build(), mock(Model.class));
		Msg priorUser = Msg.builder().name("user").role(MsgRole.USER).textContent("昨天的问题").build();
		Msg priorAssistant = Msg.builder().name("assistant").role(MsgRole.ASSISTANT).textContent("昨天的答案").build();
		memory.addMessage(priorUser);
		memory.addMessage(priorAssistant);

		Msg currentUser = Msg.builder().name("user").role(MsgRole.USER).textContent("本轮最新问题").build();
		PreReasoningEvent event = new PreReasoningEvent(mock(Agent.class), "test-model", mock(GenerateOptions.class),
				List.of(Msg.builder().name("system").role(MsgRole.SYSTEM).textContent("系统提示").build(), priorUser,
						priorAssistant, currentUser));

		new AutoContextHook(memory).onEvent(event).block();

		assertTrue(event.getInputMessages().stream()
			.anyMatch(msg -> msg.getRole() == MsgRole.USER && "本轮最新问题".equals(msg.getTextContent())),
				"压缩后的推理输入必须保留本轮用户话");
		assertTrue(memory.getMessages().stream()
			.anyMatch(msg -> msg.getRole() == MsgRole.USER && "本轮最新问题".equals(msg.getTextContent())),
				"当前用户回合应先写入 AutoContextMemory 再压缩");
	}

}
