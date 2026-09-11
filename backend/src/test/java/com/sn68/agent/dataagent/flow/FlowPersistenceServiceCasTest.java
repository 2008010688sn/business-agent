/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.entity.AgentExecutionResourceVersion;
import com.sn68.agent.dataagent.entity.DataAgentFlowInstance;
import com.sn68.agent.dataagent.flow.definition.FlowNode;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceVersionMapper;
import com.sn68.agent.dataagent.repository.DataAgentFlowEventMapper;
import com.sn68.agent.dataagent.repository.DataAgentFlowInstanceMapper;
import com.sn68.agent.dataagent.runtime.hook.service.RuntimeHookDispatcher;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FlowPersistenceServiceCasTest {

	private final DataAgentFlowInstanceMapper instanceMapper = mock(DataAgentFlowInstanceMapper.class);

	private final AgentExecutionResourceVersionMapper resourceVersionMapper = mock(AgentExecutionResourceVersionMapper.class);

	private final RuntimeHookDispatcher runtimeHookDispatcher = mock(RuntimeHookDispatcher.class);

	private final FlowPersistenceService service = new FlowPersistenceService(instanceMapper,
			mock(DataAgentFlowEventMapper.class), mock(AuthenticationContext.class), new ObjectMapper(),
			resourceVersionMapper, runtimeHookDispatcher);

	@Test
	void staleTransitionFailsInsteadOfOverwritingConcurrentState() {
		DataAgentFlowInstance instance = DataAgentFlowInstance.builder().id(1L).tenantId("tenant-1")
				.lockVersion(5).build();
		when(instanceMapper.advance(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
				any(), any(), any())).thenReturn(0);

		assertThrows(CheckedException.class, () -> service.advance(instance, FlowInstanceStatus.EXECUTING,
				"execute-demand", Map.of(), Map.of(), "flow:1", "runtime-1", null, null, null));

		verify(instanceMapper, never()).selectById(any());
	}

	@Test
	void confirmAndCancelFromTheSameRevisionCannotBothWin() {
		DataAgentFlowInstance confirmCopy = DataAgentFlowInstance.builder().id(1L).tenantId("tenant-1")
			.lockVersion(5).build();
		DataAgentFlowInstance cancelCopy = DataAgentFlowInstance.builder().id(1L).tenantId("tenant-1")
			.lockVersion(5).build();
		DataAgentFlowInstance executing = DataAgentFlowInstance.builder().id(1L).tenantId("tenant-1")
			.status(FlowInstanceStatus.EXECUTING.name()).lockVersion(6).build();
		AtomicInteger attempts = new AtomicInteger();
		when(instanceMapper.advance(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
				any(), any(), any())).thenAnswer(invocation -> attempts.getAndIncrement() == 0 ? 1 : 0);
		when(instanceMapper.selectById(1L)).thenReturn(executing);

		DataAgentFlowInstance confirmed = service.advance(confirmCopy, FlowInstanceStatus.EXECUTING,
				"execute-demand", Map.of(), Map.of(), "flow:1", "runtime-confirm", null, null, null);
		assertEquals(FlowInstanceStatus.EXECUTING.name(), confirmed.getStatus());

		assertThrows(CheckedException.class, () -> service.advance(cancelCopy, FlowInstanceStatus.CANCELLED,
				"confirm-submit", Map.of(), Map.of(), null, "runtime-cancel", null, null, null));
		assertEquals(2, attempts.get());
	}

	@Test
	@SuppressWarnings("unchecked")
	void resourceSuccessAddsBackwardCompatibleOutputData() {
		DataAgentFlowInstance instance = DataAgentFlowInstance.builder().id(1L).agentId(8L).tenantId("tenant-1")
				.threadId("session-1").skillCode("demand-create").skillVersionId(11L).build();
		FlowNode node = new FlowNode("execute-demand", "execute", "present-result",
				Map.of("resourceVersionId", 21L), List.of());
		when(resourceVersionMapper.findPublished(21L)).thenReturn(AgentExecutionResourceVersion.builder()
				.id(21L).resourceKey("demo.echo.execute").build());

		service.resourceSuccess(instance, node, "runtime-1", "flow:1", Map.of("type", "3"),
				Map.of("success", true, "demandId", "demand-1", "demandNo", "XQ-001"));

		ArgumentCaptor<Map<String, Object>> output = ArgumentCaptor.forClass(Map.class);
		verify(runtimeHookDispatcher).dispatchAfterResourceSuccess(eq(8L), eq("demand-create"), eq(11L),
				eq("demo.echo.execute"), eq("session-1"), eq("runtime-1"), eq("flow:1"),
				eq(Map.of("type", "3")), output.capture());
		assertEquals("demand-1", output.getValue().get("demandId"));
		Map<String, Object> data = (Map<String, Object>) output.getValue().get("data");
		assertEquals("demand-1", data.get("demandId"));
		assertEquals("XQ-001", data.get("demandNo"));
	}
}
