/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.agentscope.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.memory.AutoContextMemory;
import com.sn68.agent.dataagent.agentscope.template.AgentRuntimeExtensions;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.service.skill.SkillVersionService;
import com.sn68.agent.dataagent.skill.SkillExecutionMode;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

class DataAgentRuntimeExtensionFactoryTest {

	private final AgentScopeToolkitFactory toolkitFactory = mock(AgentScopeToolkitFactory.class);

	private final AgentScopeMemoryFactory memoryFactory = mock(AgentScopeMemoryFactory.class);

	private final AgentScopeHookFactory hookFactory = mock(AgentScopeHookFactory.class);

	private final SkillVersionService skillVersionService = mock(SkillVersionService.class);

	private final DataAgentProperties properties = new DataAgentProperties();

	private final AgentRuntimeExtensionFactory factory = new AgentRuntimeExtensionFactory(toolkitFactory, memoryFactory,
			hookFactory, skillVersionService, properties);

	@Test
	void createKeepsDefaultToolExecutionConfig() {
		when(toolkitFactory.buildToolkit(any())).thenReturn(new Toolkit());
		when(memoryFactory.create(any())).thenReturn(new PreparedMemory(new InMemoryMemory(), false));
		when(hookFactory.create(any(), any(), any(), anyInt())).thenReturn(List.of());

		AgentRuntimeExtensions extensions = factory.create(request(), null, Map.<String, ToolCallback>of(), null);

		assertEquals(java.time.Duration.ofSeconds(30), extensions.toolExecutionConfig().getTimeout());
		assertEquals(1, extensions.toolExecutionConfig().getMaxAttempts());
		assertEquals("", extensions.skillInstructions());
	}

	@Test
	void createUsesPinnedReactSkillInstructions() {
		when(toolkitFactory.buildToolkit(any())).thenReturn(new Toolkit());
		when(memoryFactory.create(any())).thenReturn(new PreparedMemory(new InMemoryMemory(), false));
		when(hookFactory.create(any(), any(), any(), anyInt())).thenReturn(List.of());
		when(skillVersionService.getRequired(10L))
			.thenReturn(DataAgentSkillVersion.builder().id(10L).skillMarkdown("Use the bound query tools.").build());
		AgentRequest request = request();
		request.setRoutedSkillVersionId(10L);
		request.setRoutedSkillExecutionMode(SkillExecutionMode.REACT);

		AgentRuntimeExtensions extensions = factory.create(request, null, Map.of(), null);

		assertEquals("Use the bound query tools.", extensions.skillInstructions());
	}

	@Test
	void createUsesConfiguredReactMaxIterations() {
		when(toolkitFactory.buildToolkit(any())).thenReturn(new Toolkit());
		when(memoryFactory.create(any())).thenReturn(new PreparedMemory(new InMemoryMemory(), false));
		when(hookFactory.create(any(), any(), any(), anyInt())).thenReturn(List.of());
		properties.getRuntime().setReactMaxIterations(7);
		properties.getRuntime().setMaxModelCalls(10);

		AgentRuntimeExtensions extensions = factory.create(request(), null, Map.of(), null);

		assertEquals(7, extensions.maxIterations());
	}

	@Test
	void createDoesNotRegisterContextOffloadTool() {
		Toolkit toolkit = spy(new Toolkit());
		when(toolkitFactory.buildToolkit(any())).thenReturn(toolkit);
		when(hookFactory.create(any(), any(), any(), anyInt(), any())).thenReturn(List.of());
		PreparedMemory prepared = new PreparedMemory(mock(AutoContextMemory.class), true, true);

		factory.create(request(), null, Map.of(), prepared);

		verify(toolkit, never()).registerTool(any());
	}

	@Test
	void emitSearchResultSetDelegatesToHookFactory() {
		AgentRequest request = request();
		AgentRuntimeEventPublisher publisher = mock(AgentRuntimeEventPublisher.class);

		factory.emitSearchResultSet(request, publisher);

		verify(hookFactory).emitSearchResultSet(request, publisher);
	}

	@Test
	void createReservesOneModelCallForAgentScopeSummary() {
		when(toolkitFactory.buildToolkit(any())).thenReturn(new Toolkit());
		when(memoryFactory.create(any())).thenReturn(new PreparedMemory(new InMemoryMemory(), false));
		when(hookFactory.create(any(), any(), any(), anyInt())).thenReturn(List.of());
		properties.getRuntime().setReactMaxIterations(10);
		properties.getRuntime().setMaxModelCalls(10);

		AgentRuntimeExtensions extensions = factory.create(request(), null, Map.of(), null);

		assertEquals(9, extensions.maxIterations());
	}

	@Test
	void requestCannotExpandPlatformReactMaxIterations() {
		when(toolkitFactory.buildToolkit(any())).thenReturn(new Toolkit());
		when(memoryFactory.create(any())).thenReturn(new PreparedMemory(new InMemoryMemory(), false));
		when(hookFactory.create(any(), any(), any(), anyInt())).thenReturn(List.of());
		properties.getRuntime().setReactMaxIterations(5);
		properties.getRuntime().setMaxModelCalls(10);
		AgentRequest request = request();
		request.setReactMaxIterations(9);

		AgentRuntimeExtensions extensions = factory.create(request, null, Map.of(), null);

		assertEquals(5, extensions.maxIterations());
	}

	private AgentRequest request() {
		return AgentRequest.builder().agentId("1").threadId("thread-1").runtimeRequestId("run-1").build();
	}

}
