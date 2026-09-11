/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.agentscope.tool.skilltool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.tool.AgentModelToolName;
import com.sn68.agent.dataagent.entity.AgentExecutionResourceVersion;
import com.sn68.agent.dataagent.entity.DataAgentSkillToolRef;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceVersionMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillToolRefMapper;
import com.sn68.agent.dataagent.tool.ToolInvocationContext;
import com.sn68.agent.dataagent.tool.ToolInvoker;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

class VersionedSkillToolCallbackFactoryTest {

	private final DataAgentSkillToolRefMapper refMapper = mock(DataAgentSkillToolRefMapper.class);

	private final AgentExecutionResourceVersionMapper versionMapper = mock(AgentExecutionResourceVersionMapper.class);

	private final ToolInvoker toolInvoker = mock(ToolInvoker.class);

	private final VersionedSkillToolCallbackFactory factory = new VersionedSkillToolCallbackFactory(refMapper,
			versionMapper, toolInvoker, new ObjectMapper());

	@Test
	void exposesOnlyReadModelToolsFromPinnedVersion() {
		when(refMapper.findBySkillVersionId(10L)).thenReturn(List.of(ref(20L), ref(21L), ref(22L)));
		when(versionMapper.findPublished(20L)).thenReturn(version(20L, "READ", "MODEL"));
		when(versionMapper.findPublished(21L)).thenReturn(version(21L, "WRITE", "FLOW_ONLY"));
		when(versionMapper.findPublished(22L)).thenReturn(version(22L, "READ", "FLOW_ONLY"));

		Map<String, ToolCallback> callbacks = factory.create("1", "waybill-query", 10L);

		assertEquals(1, callbacks.size());
		assertTrue(callbacks.keySet().stream().allMatch(AgentModelToolName::isValid));
		assertTrue(callbacks.keySet().stream().allMatch(AgentModelToolName::isSkillTool));
	}

	@Test
	void invokesPinnedToolWithRequestTenant() {
		when(refMapper.findBySkillVersionId(10L)).thenReturn(List.of(ref(20L)));
		when(versionMapper.findPublished(20L)).thenReturn(version(20L, "READ", "MODEL"));
		when(toolInvoker.invoke(any())).thenReturn(Map.of("status", "ok"));
		ToolCallback callback = factory.create("1", "waybill-query", 10L).values().iterator().next();
		AgentRequest request = AgentRequest.builder().agentId("1").tenantIdSnapshot("tenant-1")
			.threadId("thread-1").runtimeRequestId("run-1").build();
		ToolContext toolContext = new ToolContext(Map.of("graphRequest", request));

		callback.call("{\"waybillNo\":\"WB001\"}", toolContext);

		ArgumentCaptor<ToolInvocationContext> captor = ArgumentCaptor.forClass(ToolInvocationContext.class);
		verify(toolInvoker).invoke(captor.capture());
		assertEquals(1L, captor.getValue().agentId());
		assertEquals("tenant-1", captor.getValue().tenantId());
		assertEquals(10L, captor.getValue().skillVersionId());
		assertEquals(20L, captor.getValue().resourceVersionId());
		assertEquals("WB001", captor.getValue().arguments().get("waybillNo"));
	}

	private DataAgentSkillToolRef ref(Long resourceVersionId) {
		return DataAgentSkillToolRef.builder().skillVersionId(10L).resourceVersionId(resourceVersionId)
			.resourceKey("resource-" + resourceVersionId).status("enabled").build();
	}

	private AgentExecutionResourceVersion version(Long id, String accessMode, String exposureMode) {
		String resourceKey = "resource-" + id;
		return AgentExecutionResourceVersion.builder().id(id).resourceKey(resourceKey).accessMode(accessMode)
			.exposureMode(exposureMode).inputSchema("{\"type\":\"object\"}")
			.snapshot("{\"resourceKey\":\"" + resourceKey
					+ "\",\"resourceType\":\"MCP_TOOL\",\"serverCode\":\"zeus\",\"toolName\":\"query\"}")
			.build();
	}

}
