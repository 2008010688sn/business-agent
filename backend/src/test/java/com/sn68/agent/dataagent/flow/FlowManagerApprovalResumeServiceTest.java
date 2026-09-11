/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.entity.DataAgentFlowInstance;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.repository.DataAgentFlowInstanceMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.runtime.durable.event.RuntimeOutboxEvent;
import com.sn68.agent.dataagent.service.chat.ChatMessageService;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FlowManagerApprovalResumeServiceTest {

	private FlowEngine flowEngine;

	private FlowInstanceService instanceService;

	private DataAgentFlowInstanceMapper instanceMapper;

	private DataAgentSkillMapper skillMapper;

	private DataAgentSkillVersionMapper skillVersionMapper;

	private FlowManagerApprovalIdentityRestorer identityRestorer;

	private FlowManagerApprovalResumeService service;

	@BeforeEach
	void setUp() {
		flowEngine = mock(FlowEngine.class);
		instanceService = mock(FlowInstanceService.class);
		instanceMapper = mock(DataAgentFlowInstanceMapper.class);
		skillMapper = mock(DataAgentSkillMapper.class);
		skillVersionMapper = mock(DataAgentSkillVersionMapper.class);
		identityRestorer = mock(FlowManagerApprovalIdentityRestorer.class);
		service = new FlowManagerApprovalResumeService(flowEngine, instanceService, instanceMapper, skillMapper,
				skillVersionMapper, identityRestorer, mock(ChatMessageService.class), new ObjectMapper());
	}

	@Test
	void taskRunEventsAreIgnored() {
		service.onApprovalDecided(new RuntimeOutboxEvent("1", 2L, "3", "APPROVAL_DECIDED", "k",
				"{\"approvalId\":9,\"state\":\"APPROVED\",\"source\":\"TASK_RUN\",\"sourceRefId\":\"5\"}"));
		verify(instanceMapper, never()).selectById(any());
		verify(flowEngine, never()).execute(any(), any(), any(), any());
	}

	@Test
	void approvedGatewayEventResumesMatchingWaitingInstance() {
		DataAgentFlowInstance instance = DataAgentFlowInstance.builder().id(88L).tenantId("7").agentId(1L)
			.skillId(4L).skillVersionId(5L).threadId("t1").userId("u1").sessionId(11L)
			.status(FlowInstanceStatus.WAITING.name())
			.waitingPayload("{\"action\":\"APPROVAL\",\"approvalId\":9}")
			.contextData("{\"runtime\":{\"callerSnapshot\":{\"userId\":\"u1\",\"tenantId\":\"7\"}}}")
			.build();
		when(instanceMapper.selectById(88L)).thenReturn(instance);
		when(skillMapper.selectById(4L)).thenReturn(DataAgentSkill.builder().id(4L).skillCode("demand-create").build());
		when(skillVersionMapper.selectById(5L)).thenReturn(DataAgentSkillVersion.builder().id(5L).build());
		when(identityRestorer.runAsCaller(eq(instance), any(), any())).thenAnswer(invocation -> {
			Supplier<?> supplier = invocation.getArgument(2);
			return supplier.get();
		});
		when(flowEngine.execute(any(), any(), any(), any()))
			.thenReturn(new FlowExecutionResult(instance, null, "已创建 D1", true, FlowTurnOutcome.SUCCEEDED));

		service.onApprovalDecided(new RuntimeOutboxEvent("7", null, null, "APPROVAL_DECIDED", "approval-decided:9",
				"{\"approvalId\":9,\"state\":\"APPROVED\",\"source\":\"CAPABILITY_GATEWAY\",\"sourceRefId\":\"88\"}"));

		verify(identityRestorer).runAsCaller(eq(instance), any(), any());
		verify(flowEngine).execute(any(), any(), any(), any());
	}

	@Test
	void rejectedGatewayEventFailsInstance() {
		DataAgentFlowInstance instance = DataAgentFlowInstance.builder().id(88L).tenantId("7").agentId(1L)
			.status(FlowInstanceStatus.WAITING.name())
			.waitingPayload("{\"action\":\"APPROVAL\",\"approvalId\":9}").contextData("{}").build();
		when(instanceMapper.selectById(88L)).thenReturn(instance);

		service.onApprovalDecided(new RuntimeOutboxEvent("7", null, null, "APPROVAL_DECIDED", "approval-decided:9",
				"{\"approvalId\":9,\"state\":\"REJECTED\",\"source\":\"CAPABILITY_GATEWAY\",\"sourceRefId\":\"88\",\"comment\":\"no\"}"));

		verify(instanceService).advance(eq(instance), eq(FlowInstanceStatus.FAILED), any(), any(), any(), any(), any(),
				eq("APPROVAL_REJECTED"), any(), any());
		verify(flowEngine, never()).execute(any(), any(), any(), any());
	}

	@Test
	void mismatchedApprovalIdDoesNotWrite() {
		DataAgentFlowInstance instance = DataAgentFlowInstance.builder().id(88L).tenantId("7")
			.status(FlowInstanceStatus.WAITING.name())
			.waitingPayload("{\"action\":\"APPROVAL\",\"approvalId\":1}").build();
		when(instanceMapper.selectById(88L)).thenReturn(instance);

		service.onApprovalDecided(new RuntimeOutboxEvent("7", null, null, "APPROVAL_DECIDED", "approval-decided:9",
				"{\"approvalId\":9,\"state\":\"APPROVED\",\"source\":\"CAPABILITY_GATEWAY\",\"sourceRefId\":\"88\"}"));

		verify(flowEngine, never()).execute(any(), any(), any(), any());
		verify(instanceService, never()).advance(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
	}

}
