/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.optimization.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.context.DataAgentAsyncContextBridge;
import com.sn68.agent.dataagent.entity.DataChatSession;
import com.sn68.agent.dataagent.optimization.entity.DataAgentOptCandidate;
import com.sn68.agent.dataagent.optimization.entity.DataAgentOptExperiment;
import com.sn68.agent.dataagent.optimization.repository.DataAgentOptCandidateMapper;
import com.sn68.agent.dataagent.optimization.repository.DataAgentOptExperimentMapper;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.runtime.hook.dto.RuntimeHookEvent;
import com.sn68.agent.dataagent.service.agent.AgentInvocationService;
import com.sn68.agent.dataagent.service.chat.DataChatSessionService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataAgentEvolutionShadowServiceTest {

	private final DataAgentProperties properties = new DataAgentProperties();

	private final DataAgentOptExperimentMapper experimentMapper = mock(DataAgentOptExperimentMapper.class);

	private final DataAgentOptCandidateMapper candidateMapper = mock(DataAgentOptCandidateMapper.class);

	private final DataChatSessionService chatSessionService = mock(DataChatSessionService.class);

	private final AgentInvocationService invocationService = mock(AgentInvocationService.class);

	@SuppressWarnings("unchecked")
	private final ObjectProvider<AgentInvocationService> invocationProvider = mock(ObjectProvider.class);

	private final DataAgentAsyncContextBridge asyncContextBridge = mock(DataAgentAsyncContextBridge.class);

	private DataAgentEvolutionShadowService service() {
		when(invocationProvider.getIfAvailable()).thenReturn(invocationService);
		when(asyncContextBridge.capture()).thenReturn(DataAgentAsyncContextBridge.Snapshot.empty());
		return new DataAgentEvolutionShadowService(properties, experimentMapper, candidateMapper, chatSessionService,
				invocationProvider, asyncContextBridge, new ObjectMapper(), Runnable::run);
	}

	@Test
	void shadowDisabledByDefault() {
		assertFalse(service().shadowEnabledForTenant("tenant-1"));
	}

	@Test
	void skipsDigitalEmployee() {
		enableShadow("tenant-1");
		DataAgentEvolutionShadowService shadow = service();

		assertFalse(shadow.shouldMirror(event("tenant-1", "WEB", "DIGITAL_EMPLOYEE", null)));
		verify(invocationService, never()).invoke(any());
	}

	@Test
	void skipsRuntimeTask() {
		enableShadow("tenant-1");
		assertFalse(service().shouldMirror(event("tenant-1", "RUNTIME_TASK", "DATA_AGENT", null)));
	}

	@Test
	void skipsEvalAndDryRun() {
		enableShadow("tenant-1");
		DataAgentEvolutionShadowService shadow = service();
		assertFalse(shadow.shouldMirror(event("tenant-1", "EVAL", null, null)));
		RuntimeHookEvent dryRun = event("tenant-1", "WEB", null, "DRY_RUN");
		assertFalse(shadow.shouldMirror(dryRun));
	}

	@Test
	void skipsTenantOutsideWhitelist() {
		enableShadow("other-tenant");
		assertFalse(service().shouldMirror(event("tenant-1", "WEB", null, null)));
	}

	@Test
	void mirrorsDataAgentPromptCandidateWithDryRun() {
		enableShadow("tenant-1");
		DataAgentOptExperiment experiment = new DataAgentOptExperiment();
		experiment.setId(8L);
		experiment.setTenantId("tenant-1");
		experiment.setOwnerType("DATA_AGENT");
		experiment.setOwnerId("7");
		when(experimentMapper.findActiveByOwner("tenant-1", "DATA_AGENT", "7")).thenReturn(List.of(experiment));
		DataAgentOptCandidate candidate = new DataAgentOptCandidate();
		candidate.setId(22L);
		candidate.setTargetType("PROMPT");
		candidate.setStatus("evaluated");
		candidate.setPatchJson("{\"snapshotOverlay\":{\"promptSnapshot\":{\"prompt\":\"候选提示\"}}}");
		when(candidateMapper.findByExperimentId(8L)).thenReturn(List.of(candidate));
		DataChatSession session = new DataChatSession();
		session.setId(90L);
		when(chatSessionService.createSession(anyLong(), any(), any())).thenReturn(session);
		when(invocationService.invoke(any(AgentRequest.class))).thenReturn("shadow-answer");
		when(asyncContextBridge.capture()).thenReturn(DataAgentAsyncContextBridge.Snapshot.empty());
		org.mockito.Mockito.doAnswer(invocation -> {
			((Runnable) invocation.getArgument(1)).run();
			return null;
		}).when(asyncContextBridge).runWith(any(), any(Runnable.class));
		DataAgentEvolutionShadowService shadow = service();

		shadow.scheduleIfEligible(event("tenant-1", "WEB", null, null));

		ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);
		verify(invocationService).invoke(captor.capture());
		AgentRequest request = captor.getValue();
		assertEquals("SHADOW", request.getRequestSource());
		assertEquals("DRY_RUN", request.getExecutionIntent());
		assertEquals("候选提示", request.getEvalSystemInstructionOverride());
		assertTrue(request.isIsolatedMemory());
		assertEquals("hello", request.getQuery());
	}

	private void enableShadow(String tenantId) {
		properties.getOptimization().setShadowEnabled(true);
		properties.getOptimization().setShadowTenantIds(List.of(tenantId));
	}

	private RuntimeHookEvent event(String tenantId, String source, String ownerType, String executionIntent) {
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("query", "hello");
		input.put("requestSource", source);
		input.put("ownerType", ownerType);
		input.put("executionIntent", executionIntent);
		input.put("tenantIdSnapshot", tenantId);
		input.put("userIdSnapshot", "100");
		return new RuntimeHookEvent("AFTER_AGENT_SUCCESS", 7L, null, null, null, "55", "rt-1", "rt-1", input, Map.of());
	}

}
