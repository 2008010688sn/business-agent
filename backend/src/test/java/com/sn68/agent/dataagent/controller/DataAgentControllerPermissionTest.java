/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.controller;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.service.DataAgentService;
import com.sn68.agent.dataagent.service.chat.DataChatTurnService;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class DataAgentControllerPermissionTest {

	@Test
	void stopStreamAuthorizesBeforeCancel() {
		DataAgentService agentService = mock(DataAgentService.class);
		DataChatTurnService chatTurnService = mock(DataChatTurnService.class);
		AuthenticationContext authenticationContext = mock(AuthenticationContext.class);
		when(authenticationContext.userId()).thenReturn("7");
		when(agentService.stopStreamProcessing("100", "run-1")).thenReturn(true);
		DataAgentController controller = new DataAgentController(agentService, chatTurnService, authenticationContext);
		AgentRequest request = AgentRequest.builder().threadId("100").runtimeRequestId("run-1").build();

		controller.stopStream(request);

		InOrder order = inOrder(agentService);
		order.verify(agentService).authorizeRuntimeControl(request);
		order.verify(agentService).stopStreamProcessing("100", "run-1");
	}

	@Test
	void activeRuntimeAuthorizesBeforeLookup() {
		DataAgentService agentService = mock(DataAgentService.class);
		DataChatTurnService chatTurnService = mock(DataChatTurnService.class);
		AuthenticationContext authenticationContext = mock(AuthenticationContext.class);
		when(authenticationContext.userId()).thenReturn("7");
		DataAgentController controller = new DataAgentController(agentService, chatTurnService, authenticationContext);
		AgentRequest request = AgentRequest.builder().threadId("100").build();

		controller.activeRuntime(request);

		InOrder order = inOrder(agentService);
		order.verify(agentService).authorizeRuntimeControl(request);
		order.verify(agentService).activeRuntime("100");
	}

}
