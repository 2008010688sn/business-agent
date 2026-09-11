/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-G：进程内工具网关 runId 必须是权威 agent_runtime_run.id，不能用编排遥测 id。
 */
class CapabilityGatewayToolCallbackTest {

	@Test
	void runIdUsesDurableRunIdAndIgnoresOrchestrationRunId() {
		CapabilityGateway gateway = mock(CapabilityGateway.class);
		when(gateway.invoke(any(), any())).thenReturn(ResultEnvelope.builder()
			.status(ResultEnvelope.STATUS_SUCCESS)
			.data("ok")
			.build());
		ToolCallback delegate = mock(ToolCallback.class);
		when(delegate.getToolDefinition()).thenReturn(ToolDefinition.builder().name("crm_update").description("update")
			.inputSchema("{}").build());
		when(delegate.call(any(), any())).thenReturn("ok");
		CapabilityGatewayToolCallback callback = new CapabilityGatewayToolCallback(delegate, gateway, new ObjectMapper());
		AgentRequest agentRequest = AgentRequest.builder()
			.tenantIdSnapshot("7")
			.orchestrationRunId(999L)
			.durableRunId(88L)
			.runtimeRequestId("rr-1")
			.agentId("9")
			.build();

		callback.call("{}", new ToolContext(Map.of("graphRequest", agentRequest)));

		ArgumentCaptor<InvocationRequest> captor = ArgumentCaptor.forClass(InvocationRequest.class);
		verify(gateway).invoke(captor.capture(), any());
		assertEquals(88L, captor.getValue().runId());
		assertEquals("rr-1", captor.getValue().stepKey());
	}

	@Test
	void runIdStaysNullWhenDurableRunIdMissing() {
		CapabilityGateway gateway = mock(CapabilityGateway.class);
		when(gateway.invoke(any(), any())).thenReturn(ResultEnvelope.builder()
			.status(ResultEnvelope.STATUS_SUCCESS)
			.data("ok")
			.build());
		ToolCallback delegate = mock(ToolCallback.class);
		when(delegate.getToolDefinition()).thenReturn(ToolDefinition.builder().name("datasource_skill_search")
			.description("search").inputSchema("{}").build());
		CapabilityGatewayToolCallback callback = new CapabilityGatewayToolCallback(delegate, gateway, new ObjectMapper());
		AgentRequest agentRequest = AgentRequest.builder().orchestrationRunId(999L).runtimeRequestId("rr-2").build();

		callback.call("{}", new ToolContext(Map.of("graphRequest", agentRequest)));

		ArgumentCaptor<InvocationRequest> captor = ArgumentCaptor.forClass(InvocationRequest.class);
		verify(gateway).invoke(captor.capture(), any());
		assertNull(captor.getValue().runId());
	}

}
