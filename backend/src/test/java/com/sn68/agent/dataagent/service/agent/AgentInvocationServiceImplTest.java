/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.service.DataAgentService;
import com.sn68.agent.dataagent.skill.SkillExecutionMode;
import com.sn68.agent.dataagent.ui.AgentUiMessage;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class AgentInvocationServiceImplTest {

	@Test
	void detailedInvocationCapturesOnlyCurrentFlowUiMessage() {
		DataAgentService dataAgentService = mock(DataAgentService.class);
		AgentInvocationServiceImpl service = new AgentInvocationServiceImpl(dataAgentService);
		AgentRequest request = AgentRequest.builder().runtimeRequestId("req-1").build();
		AgentUiMessage message = new AgentUiMessage("agent-ui/v2", "skill-flow", "req-1", null,
				new AgentUiMessage.Content("markdown", "请选择"),
				new AgentUiMessage.Payload("SELECT", Map.of(), List.of()), List.of(), null);
		doAnswer(invocation -> {
			@SuppressWarnings("unchecked")
			Consumer<AgentUiMessage> collector = invocation.getArgument(1);
			collector.accept(new AgentUiMessage("agent-ui/v2", "skill-flow", "other", null, null, null, List.of(), null));
			collector.accept(message);
			request.setRoutedSkillExecutionMode(SkillExecutionMode.FLOW);
			return "请选择";
		}).when(dataAgentService).executeAgentOnce(eq(request), any());

		AgentInvocationResult result = service.invokeDetailed(request);

		assertEquals("请选择", result.answer());
		assertEquals(message, result.uiMessage());
	}

	@Test
	void handledFlowWithoutUiMessageFailsExplicitly() {
		DataAgentService dataAgentService = mock(DataAgentService.class);
		AgentInvocationServiceImpl service = new AgentInvocationServiceImpl(dataAgentService);
		AgentRequest request = AgentRequest.builder().runtimeRequestId("req-1").build();
		doAnswer(invocation -> {
			request.setRoutedSkillExecutionMode(SkillExecutionMode.FLOW);
			return "等待选择";
		}).when(dataAgentService).executeAgentOnce(eq(request), any());

		assertThrows(IllegalStateException.class, () -> service.invokeDetailed(request));
	}

}
