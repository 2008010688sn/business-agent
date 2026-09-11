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
package com.sn68.agent.dataagent.agentscope.runtime;

import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.enums.SessionContextCompressionStatus;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.agentscope.memory.AutoContextConfig;
import com.sn68.agent.dataagent.agentscope.memory.AutoContextMemory;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import com.sn68.agent.dataagent.agentscope.session.AgentScopeNativeSessionService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ContextCompressionServiceTest {

	@Test
	void autoContextTypesLiveOutsideAgentscopeCorePackage() {
		assertEquals("com.sn68.agent.dataagent.agentscope.memory.AutoContextMemory",
				AutoContextMemory.class.getName());
		assertEquals("com.sn68.agent.dataagent.agentscope.memory.AutoContextConfig",
				AutoContextConfig.class.getName());
	}

	@Test
	void buildConfig_usesAvailableContextBudgetAndConfiguredRules() {
		DataAgentProperties properties = new DataAgentProperties();
		properties.getMemory().getAutoContext().setReserveTokens(2_000);
		properties.getMemory().getAutoContext().setTokenRatio(0.72);
		properties.getMemory().getAutoContext().setMsgThreshold(66);
		properties.getMemory().getAutoContext().setLastKeep(24);
		properties.getMemory().getAutoContext().setLargePayloadThreshold(6_000);
		properties.getMemory().getAutoContext().setOffloadSinglePreview(180);
		properties.getMemory().getAutoContext().setMinConsecutiveToolMessages(3);
		properties.getMemory().getAutoContext().setMinCompressionTokenThreshold(2_500);
		properties.getMemory().getAutoContext().setCurrentRoundCompressionRatio(0.35);
		ContextCompressionService service = new ContextCompressionService(properties);

		AutoContextConfig config = service.buildConfig(ModelConfigDTO.builder()
			.contextWindowTokens(32_000L)
			.maxTokens(4_000L)
			.build());

		assertEquals(26_000L, config.getMaxToken());
		assertEquals(0.72, config.getTokenRatio());
		assertEquals(66, config.getMsgThreshold());
		assertEquals(24, config.getLastKeep());
		assertEquals(6_000, config.getLargePayloadThreshold());
		assertEquals(180, config.getOffloadSinglePreview());
		assertEquals(3, config.getMinConsecutiveToolMessages());
		assertEquals(2_500, config.getMinCompressionTokenThreshold());
		assertEquals(0.35, config.getCurrentRoundCompressionRatio());
	}

	@Test
	void buildManualConfig_usesGentleForceThresholdsAndKeepsConfiguredRules() {
		DataAgentProperties properties = new DataAgentProperties();
		properties.getMemory().getAutoContext().setLastKeep(24);
		properties.getMemory().getAutoContext().setManualLastKeep(8);
		properties.getMemory().getAutoContext().setLargePayloadThreshold(6_000);
		properties.getMemory().getAutoContext().setOffloadSinglePreview(180);
		properties.getMemory().getAutoContext().setMinConsecutiveToolMessages(3);
		properties.getMemory().getAutoContext().setCurrentRoundCompressionRatio(0.35);
		ContextCompressionService service = new ContextCompressionService(properties);

		AutoContextConfig config = service.buildManualConfig(ModelConfigDTO.builder()
			.contextWindowTokens(32_000L)
			.maxTokens(4_000L)
			.build());

		assertEquals(8, config.getLastKeep());
		assertEquals(10, config.getMsgThreshold());
		assertEquals(0.01, config.getTokenRatio());
		assertEquals(0, config.getMinCompressionTokenThreshold());
		assertEquals(6_000, config.getLargePayloadThreshold());
		assertEquals(180, config.getOffloadSinglePreview());
		assertEquals(3, config.getMinConsecutiveToolMessages());
		assertEquals(0.35, config.getCurrentRoundCompressionRatio());
	}

	@Test
	void prepareMemory_keepsOriginalMemoryWhenAutoContextDisabled() {
		DataAgentProperties properties = new DataAgentProperties();
		properties.getMemory().getAutoContext().setEnabled(false);
		ContextCompressionService service = new ContextCompressionService(properties);
		PreparedMemory original = new PreparedMemory(new InMemoryMemory(), true, false);

		PreparedMemory prepared = service.prepareMemory(original, ModelConfigDTO.builder().build(), mock(Model.class));

		assertSame(original, prepared);
	}

	@Test
	void prepareMemory_upgradesToAutoContextMemoryAndCopiesExistingMessages() {
		DataAgentProperties properties = new DataAgentProperties();
		properties.getMemory().getAutoContext().setEnabled(true);
		ContextCompressionService service = new ContextCompressionService(properties);
		InMemoryMemory source = new InMemoryMemory();
		source.addMessage(Msg.builder().name("user").role(MsgRole.USER).textContent("hello").build());

		PreparedMemory prepared = service.prepareMemory(new PreparedMemory(source, true, false),
				ModelConfigDTO.builder().contextWindowTokens(16_000L).maxTokens(1_000L).build(), mock(Model.class));

		assertInstanceOf(AutoContextMemory.class, prepared.memory());
		assertEquals(1, prepared.memory().getMessages().size());
		assertEquals("hello", prepared.memory().getMessages().get(0).getTextContent());
	}

	@Test
	void prepareMemory_loadsPersistedAutoContextStateBeforeFallbackMemory() {
		DataAgentProperties properties = new DataAgentProperties();
		properties.getMemory().getAutoContext().setEnabled(true);
		AgentScopeNativeSessionService nativeSessionService = mock(AgentScopeNativeSessionService.class);
		doAnswer(invocation -> {
			AutoContextMemory memory = invocation.getArgument(0);
			memory.addMessage(Msg.builder().name("system").role(MsgRole.SYSTEM).textContent("compressed").build());
			return true;
		}).when(nativeSessionService).loadStateIfExists(any(AutoContextMemory.class), eq("100"));
		ContextCompressionService service = new ContextCompressionService(properties, nativeSessionService);
		InMemoryMemory staleMemory = new InMemoryMemory();
		staleMemory.addMessage(Msg.builder().name("user").role(MsgRole.USER).textContent("stale").build());

		PreparedMemory prepared = service.prepareMemory(new PreparedMemory(staleMemory, true, false),
				ModelConfigDTO.builder().build(), mock(Model.class), "100");

		assertInstanceOf(AutoContextMemory.class, prepared.memory());
		assertTrue(prepared.autoContextEnabled());
		assertEquals(1, prepared.memory().getMessages().size());
		assertEquals("compressed", prepared.memory().getMessages().get(0).getTextContent());
		verify(nativeSessionService).loadStateIfExists(any(AutoContextMemory.class), eq("100"));
	}

	@Test
	void compressMemoryManually_remainsAvailableWhenOnlyAutoContextDisabled() {
		DataAgentProperties properties = new DataAgentProperties();
		properties.getMemory().getAutoContext().setEnabled(false);
		properties.getMemory().getAutoContext().setManualEnabled(true);
		ContextCompressionService service = new ContextCompressionService(properties);
		InMemoryMemory memory = new InMemoryMemory();
		memory.addMessage(Msg.builder().name("user").role(MsgRole.USER).textContent("hello").build());

		ContextCompressionService.CompressionResult result = service.compressMemoryManually(memory,
				ModelConfigDTO.builder().build(), mock(Model.class));

		assertFalse(service.isAutoContextEnabled());
		assertTrue(service.isManualContextEnabled());
		assertTrue(service.isContextCompressionEnabled());
		assertEquals(SessionContextCompressionStatus.NO_COMPRESSIBLE_CONTENT, result.status());
		assertFalse(result.compressed());
		assertEquals(1, result.beforeRuntimeMessageCount());
		assertEquals(1, result.afterRuntimeMessageCount());
	}

	@Test
	void compressMemoryManually_returnsDisabledWhenManualContextDisabled() {
		DataAgentProperties properties = new DataAgentProperties();
		properties.getMemory().getAutoContext().setEnabled(true);
		properties.getMemory().getAutoContext().setManualEnabled(false);
		ContextCompressionService service = new ContextCompressionService(properties);
		InMemoryMemory memory = new InMemoryMemory();
		memory.addMessage(Msg.builder().name("user").role(MsgRole.USER).textContent("hello").build());

		ContextCompressionService.CompressionResult result = service.compressMemoryManually(memory,
				ModelConfigDTO.builder().build(), mock(Model.class));

		assertTrue(service.isAutoContextEnabled());
		assertFalse(service.isManualContextEnabled());
		assertTrue(service.isContextCompressionEnabled());
		assertEquals(SessionContextCompressionStatus.DISABLED, result.status());
		assertFalse(result.compressed());
		assertEquals(1, result.beforeRuntimeMessageCount());
		assertEquals(1, result.afterRuntimeMessageCount());
	}

	@Test
	void contextCompressionDisabledOnlyWhenBothAutoAndManualAreDisabled() {
		DataAgentProperties properties = new DataAgentProperties();
		properties.getMemory().getAutoContext().setEnabled(false);
		properties.getMemory().getAutoContext().setManualEnabled(false);
		ContextCompressionService service = new ContextCompressionService(properties);
		PreparedMemory original = new PreparedMemory(new InMemoryMemory(), true, false);

		PreparedMemory prepared = service.prepareMemory(original, ModelConfigDTO.builder().build(), mock(Model.class));
		ContextCompressionService.CompressionResult result = service.compressMemoryManually(original.memory(),
				ModelConfigDTO.builder().build(), mock(Model.class));

		assertSame(original, prepared);
		assertFalse(service.isAutoContextEnabled());
		assertFalse(service.isManualContextEnabled());
		assertFalse(service.isContextCompressionEnabled());
		assertEquals(SessionContextCompressionStatus.DISABLED, result.status());
	}

	@Test
	void compressMemoryManually_skipsWhenMessagesAreInsideLastKeepWindow() {
		DataAgentProperties properties = new DataAgentProperties();
		properties.getMemory().getAutoContext().setEnabled(true);
		properties.getMemory().getAutoContext().setLastKeep(3);
		ContextCompressionService service = new ContextCompressionService(properties);
		InMemoryMemory memory = new InMemoryMemory();
		memory.addMessage(Msg.builder().name("user").role(MsgRole.USER).textContent("hello").build());
		memory.addMessage(Msg.builder().name("assistant").role(MsgRole.ASSISTANT).textContent("world").build());

		ContextCompressionService.CompressionResult result = service.compressMemoryManually(memory,
				ModelConfigDTO.builder().build(), mock(Model.class));

		assertEquals(SessionContextCompressionStatus.NO_COMPRESSIBLE_CONTENT, result.status());
		assertFalse(result.compressed());
		assertEquals(2, result.beforeRuntimeMessageCount());
		assertEquals(2, result.afterRuntimeMessageCount());
	}

	@Test
	void compressMemoryManually_usesManualLastKeepInsteadOfAutoLastKeep() {
		DataAgentProperties properties = new DataAgentProperties();
		properties.getMemory().getAutoContext().setEnabled(true);
		properties.getMemory().getAutoContext().setLastKeep(30);
		properties.getMemory().getAutoContext().setLargePayloadThreshold(100);
		properties.getMemory().getAutoContext().setOffloadSinglePreview(20);
		ContextCompressionService service = new ContextCompressionService(properties);
		InMemoryMemory memory = new InMemoryMemory();
		for (int i = 0; i < 20; i++) {
			boolean user = i % 2 == 0;
			String text = i == 1 ? "large payload ".repeat(300) : "message " + i;
			memory.addMessage(Msg.builder()
				.name(user ? "user" : "assistant")
				.role(user ? MsgRole.USER : MsgRole.ASSISTANT)
				.textContent(text)
				.build());
		}

		ContextCompressionService.CompressionResult result = service.compressMemoryManually(memory,
				ModelConfigDTO.builder().contextWindowTokens(32_000L).maxTokens(2_000L).build(), mock(Model.class));

		assertEquals(SessionContextCompressionStatus.COMPRESSED, result.status());
		assertTrue(result.compressed());
		assertTrue(result.afterRuntimeTokens() < result.beforeRuntimeTokens());
		assertEquals(20, result.beforeRuntimeMessageCount());
		assertEquals(20, result.afterRuntimeMessageCount());
	}

	@Test
	void buildConfig_supportsLargeContextWindowWithLongBudget() {
		DataAgentProperties properties = new DataAgentProperties();
		properties.getMemory().getAutoContext().setReserveTokens(8_000);
		ContextCompressionService service = new ContextCompressionService(properties);

		AutoContextConfig config = service.buildConfig(ModelConfigDTO.builder()
			.contextWindowTokens(1_000_000L)
			.maxTokens(20_000L)
			.build());

		assertEquals(972_000L, config.getMaxToken());
	}

}
