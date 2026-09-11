/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.agentscope.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.tool.AgentModelToolName;
import com.sn68.agent.dataagent.agentscope.tool.AgentToolPolicyService;
import com.sn68.agent.dataagent.agentscope.v2.AgentScopeV2Properties;
import com.sn68.agent.dataagent.capability.CapabilityGateway;
import com.sn68.agent.dataagent.context.DataAgentAsyncContextBridge;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeArtifactMapper;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeEventService;
import com.sn68.agent.dataagent.runtime.hook.service.RuntimeHookDispatcher;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentScopeToolkitFactoryWebFetchFilterTest {

	private DataAgentProperties properties;

	private AgentScopeToolkitFactory factory;

	@BeforeEach
	void setUp() {
		properties = new DataAgentProperties();
		factory = new AgentScopeToolkitFactory(new AgentToolPolicyService(), mock(GenericApplicationContext.class),
				new ObjectMapper(), mock(AnswerTraceExplainStore.class), mock(DataAgentAsyncContextBridge.class),
				mock(RuntimeHookDispatcher.class), mock(AgentRuntimeProgressService.class),
				mock(CapabilityGateway.class), mock(RuntimeEventService.class), mock(AgentRuntimeArtifactMapper.class),
				mock(AgentScopeV2Properties.class), properties);
		Map<String, ToolCallback> snapshot = new LinkedHashMap<>();
		snapshot.put(AgentModelToolName.WEB_FETCH, callback(AgentModelToolName.WEB_FETCH));
		snapshot.put(AgentModelToolName.SQL_GUARD_CHECK, callback(AgentModelToolName.SQL_GUARD_CHECK));
		ReflectionTestUtils.setField(factory, "commonToolCallbacksSnapshot", Map.copyOf(snapshot));
	}

	@Test
	void fetchDisabledRemovesWebFetchFromRoundToolkit() {
		properties.getWebEvidence().setFetchEnabled(false);

		Map<String, ToolCallback> callbacks = factory.getToolCallbacks("1");

		assertFalse(callbacks.containsKey(AgentModelToolName.WEB_FETCH));
		assertTrue(callbacks.containsKey(AgentModelToolName.SQL_GUARD_CHECK));
	}

	@Test
	void fetchEnabledKeepsWebFetch() {
		properties.getWebEvidence().setFetchEnabled(true);

		Map<String, ToolCallback> callbacks = factory.getToolCallbacks("1");

		assertTrue(callbacks.containsKey(AgentModelToolName.WEB_FETCH));
	}

	private ToolCallback callback(String name) {
		return new ToolCallback() {
			@Override
			public ToolDefinition getToolDefinition() {
				return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
			}

			@Override
			public String call(String toolInput) {
				return "{}";
			}
		};
	}

}
