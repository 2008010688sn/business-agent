/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.capability.CapabilityApprovalRequiredException;
import com.sn68.agent.dataagent.capability.CapabilityExecutor;
import com.sn68.agent.dataagent.capability.CapabilityGateway;
import com.sn68.agent.dataagent.capability.CapabilityKind;
import com.sn68.agent.dataagent.capability.InvocationRequest;
import com.sn68.agent.dataagent.capability.ResultEnvelope;
import com.sn68.agent.dataagent.entity.AgentExecutionResourceVersion;
import com.sn68.agent.dataagent.entity.DataAgentFlowInstance;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceVersionMapper;
import com.sn68.agent.dataagent.tool.ToolInvocationContext;
import com.sn68.agent.dataagent.tool.ToolInvoker;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultFlowEngineUnknownRecoveryTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final FlowInstanceService instanceService = mock(FlowInstanceService.class);

	private final FlowEventService eventService = mock(FlowEventService.class);

	private final ToolInvoker toolInvoker = mock(ToolInvoker.class);

	private final CapabilityGateway capabilityGateway = passthroughGateway();

	private final AgentExecutionResourceVersionMapper resourceVersionMapper =
			mock(AgentExecutionResourceVersionMapper.class);

	private final DefaultFlowEngine engine = new DefaultFlowEngine(new FlowDefinitionValidator(objectMapper),
			new FlowNodeExecutorRegistry(), instanceService, eventService, new FlowContextMapper(),
			new FlowConditionEvaluator(new FlowContextMapper()), new FlowSchemaValidator(), mock(FlowFieldExtractor.class),
			toolInvoker, resourceVersionMapper, objectMapper, capabilityGateway);

	@Test
	void processingInstanceWaitsWithoutANewUserTurn() {
		DataAgentFlowInstance processing = instance(FlowInstanceStatus.PROCESSING, "extract", "{}");
		when(instanceService.loadOrCreate(any(), any(), any(), any())).thenReturn(processing);

		FlowExecutionResult result = engine.execute(AgentRequest.builder().agentId("1").threadId("thread-1")
			.runtimeRequestId("runtime-processing").build(),
			DataAgentSkill.builder().id(1L).skillCode("demand-create").build(), version(), null);

		assertEquals(FlowInstanceStatus.PROCESSING.name(), result.instance().getStatus());
		assertFalse(result.terminal());
		assertEquals("PROCESSING", result.message().payload().action());
		verify(instanceService, never()).advance(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
		verify(toolInvoker, never()).invoke(any());
		verify(capabilityGateway, never()).invoke(any(), any());
	}

	@Test
	void unknownExecuteQueriesResultWithoutRetryingWrite() {
		DataAgentFlowInstance unknown = instance(FlowInstanceStatus.UNKNOWN, "execute", "{}");
		when(resourceVersionMapper.findPublished(10L)).thenReturn(toolVersion(true));
		when(resourceVersionMapper.findPublished(20L)).thenReturn(readTool());
		when(instanceService.loadOrCreate(any(), any(), any(), any())).thenReturn(unknown);
		when(instanceService.advance(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
			.thenAnswer(invocation -> {
				DataAgentFlowInstance current = invocation.getArgument(0);
				current.setStatus(((FlowInstanceStatus) invocation.getArgument(1)).name());
				current.setCurrentNodeId(invocation.getArgument(2));
				return current;
			});
		when(toolInvoker.invoke(any())).thenReturn(Map.of("found", true, "demandNo", "D1"));

		FlowExecutionResult result = engine.execute(AgentRequest.builder().agentId("1").threadId("thread-1")
			.runtimeRequestId("runtime-2").query("查询结果").build(),
			DataAgentSkill.builder().id(1L).skillCode("demand-create").build(), version(), null);

		ArgumentCaptor<ToolInvocationContext> invocation = ArgumentCaptor.forClass(ToolInvocationContext.class);
		verify(toolInvoker, times(1)).invoke(invocation.capture());
		assertEquals("READ", invocation.getValue().expectedAccessMode());
		assertEquals(20L, invocation.getValue().resourceVersionId());
		assertEquals("flow:tenant-1:1:execute", invocation.getValue().idempotencyKey());
		ArgumentCaptor<InvocationRequest> gatewayRequest = ArgumentCaptor.forClass(InvocationRequest.class);
		verify(capabilityGateway, times(1)).invoke(gatewayRequest.capture(), any());
		assertEquals(CapabilityKind.FLOW, gatewayRequest.getValue().capabilityKind());
		assertEquals("read.tool", gatewayRequest.getValue().capabilityCode());
		assertEquals(DefaultFlowEngine.SOURCE_FLOW, gatewayRequest.getValue().source());
		assertNull(gatewayRequest.getValue().runId());
		assertEquals("D1", result.message().content().text());
		assertTrue(result.terminal());
	}

	@Test
	void idempotentWriteTimeoutEntersUnknownWithoutRetryingWrite() {
		DataAgentFlowInstance running = instance(FlowInstanceStatus.RUNNING, "execute",
				"{\"runtime\":{\"confirmed\":true,\"contextRevision\":0,\"confirmedRevision\":0}}");
		running.setIdempotencyKey(null);
		when(resourceVersionMapper.findPublished(10L)).thenReturn(toolVersion(true));
		when(instanceService.loadOrCreate(any(), any(), any(), any())).thenReturn(running);
		stubAdvance();
		when(toolInvoker.invoke(any()))
			.thenThrow(new CheckedException("MCP tool execution failed.", new TimeoutException("request timed out")));

		FlowExecutionResult result = engine.execute(AgentRequest.builder().agentId("1").threadId("thread-1")
			.runtimeRequestId("runtime-idempotent-timeout").build(),
			DataAgentSkill.builder().id(1L).skillCode("generic-write").build(), version(), null);

		ArgumentCaptor<ToolInvocationContext> invocation = ArgumentCaptor.forClass(ToolInvocationContext.class);
		verify(toolInvoker, times(1)).invoke(invocation.capture());
		assertEquals("flow:tenant-1:1:execute", invocation.getValue().idempotencyKey());
		assertEquals(FlowInstanceStatus.UNKNOWN.name(), result.instance().getStatus());
		assertFalse(result.terminal());
	}

	@Test
	void nonIdempotentWriteTimeoutFailsAndDoesNotSendIdempotencyKey() {
		DataAgentFlowInstance running = instance(FlowInstanceStatus.RUNNING, "execute",
				"{\"runtime\":{\"confirmed\":true,\"contextRevision\":0,\"confirmedRevision\":0}}");
		running.setIdempotencyKey(null);
		when(resourceVersionMapper.findPublished(10L)).thenReturn(toolVersion(false));
		when(instanceService.loadOrCreate(any(), any(), any(), any())).thenReturn(running);
		stubAdvance();
		when(toolInvoker.invoke(any()))
			.thenThrow(new CheckedException("MCP tool execution failed.", new TimeoutException("request timed out")));

		FlowExecutionResult result = engine.execute(AgentRequest.builder().agentId("1").threadId("thread-1")
			.runtimeRequestId("runtime-non-idempotent-timeout").build(),
			DataAgentSkill.builder().id(1L).skillCode("demand-create").build(), nonIdempotentVersion(), null);

		ArgumentCaptor<ToolInvocationContext> invocation = ArgumentCaptor.forClass(ToolInvocationContext.class);
		verify(toolInvoker, times(1)).invoke(invocation.capture());
		assertNull(invocation.getValue().idempotencyKey());
		assertEquals(FlowInstanceStatus.FAILED.name(), result.instance().getStatus());
		assertTrue(result.terminal());
		assertTrue(result.text().contains("人工核对"));
	}

	@Test
	void terminalResponseKeepsPresentResultPayload() {
		DataAgentFlowInstance running = instance(FlowInstanceStatus.RUNNING, "present",
				"{\"result\":{\"demandNo\":\"D1\"}}");
		when(instanceService.loadOrCreate(any(), any(), any(), any())).thenReturn(running);
		when(instanceService.advance(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
			.thenAnswer(invocation -> {
				DataAgentFlowInstance current = invocation.getArgument(0);
				current.setStatus(((FlowInstanceStatus) invocation.getArgument(1)).name());
				current.setCurrentNodeId(invocation.getArgument(2));
				return current;
			});

		FlowExecutionResult result = engine.execute(AgentRequest.builder().agentId("1").threadId("thread-1")
			.runtimeRequestId("runtime-3").build(), DataAgentSkill.builder().id(1L).skillCode("demand-create").build(),
			version(), null);

		assertEquals("D1", result.message().content().text());
		assertEquals("PRESENT", result.message().payload().action());
		assertEquals(true, result.terminal());
	}

	@Test
	void successfulWritePublishesGenericResourceSuccessOnce() {
		DataAgentFlowInstance running = instance(FlowInstanceStatus.RUNNING, "execute",
				"{\"runtime\":{\"confirmed\":true,\"contextRevision\":0,\"confirmedRevision\":0}}");
		when(resourceVersionMapper.findPublished(10L)).thenReturn(toolVersion(true));
		when(instanceService.loadOrCreate(any(), any(), any(), any())).thenReturn(running);
		stubAdvance();
		when(toolInvoker.invoke(any())).thenReturn(Map.of("demandId", "D1", "demandNo", "N1"));

		FlowExecutionResult result = engine.execute(AgentRequest.builder().agentId("1").threadId("thread-1")
				.runtimeRequestId("runtime-success").build(), DataAgentSkill.builder().id(1L).skillCode("demand-create").build(),
				version(), null);

		verify(eventService, times(1)).resourceSuccess(any(), any(), any(), any(), any(), any());
		ArgumentCaptor<InvocationRequest> gatewayRequest = ArgumentCaptor.forClass(InvocationRequest.class);
		verify(capabilityGateway, times(1)).invoke(gatewayRequest.capture(), any());
		assertEquals(CapabilityKind.FLOW, gatewayRequest.getValue().capabilityKind());
		assertEquals("write.tool", gatewayRequest.getValue().capabilityCode());
		assertEquals(DefaultFlowEngine.SOURCE_FLOW, gatewayRequest.getValue().source());
		assertEquals(1L, gatewayRequest.getValue().agentId());
		assertNull(gatewayRequest.getValue().runId());
		assertEquals(Boolean.TRUE, gatewayRequest.getValue().confirmed());
		assertNotEquals(Boolean.TRUE, gatewayRequest.getValue().requireManagerApproval());
		assertTrue(result.terminal());
	}

	@Test
	void writeGoesThroughGatewayWithOwnerReleaseAndDurableRunId() {
		DataAgentFlowInstance running = instance(FlowInstanceStatus.RUNNING, "execute",
				"{\"runtime\":{\"confirmed\":true,\"contextRevision\":0,\"confirmedRevision\":0}}");
		when(resourceVersionMapper.findPublished(10L)).thenReturn(toolVersion(true));
		when(instanceService.loadOrCreate(any(), any(), any(), any())).thenReturn(running);
		stubAdvance();
		when(toolInvoker.invoke(any())).thenReturn(Map.of("demandId", "D1", "demandNo", "N1"));

		engine.execute(AgentRequest.builder().agentId("1").threadId("thread-1").runtimeRequestId("runtime-owner")
				.tenantIdSnapshot("tenant-1").userIdSnapshot("user-1").ownerType("DIGITAL_EMPLOYEE").ownerId(88L)
				.releaseId(77L).durableRunId(99L).build(),
				DataAgentSkill.builder().id(1L).skillCode("demand-create").build(), version(), null);

		ArgumentCaptor<InvocationRequest> gatewayRequest = ArgumentCaptor.forClass(InvocationRequest.class);
		verify(capabilityGateway).invoke(gatewayRequest.capture(), any());
		assertEquals("DIGITAL_EMPLOYEE", gatewayRequest.getValue().ownerType());
		assertEquals(88L, gatewayRequest.getValue().ownerId());
		assertEquals(77L, gatewayRequest.getValue().releaseId());
		assertEquals("write.tool", gatewayRequest.getValue().capabilityCode());
		assertEquals(99L, gatewayRequest.getValue().runId());
		assertEquals("runtime-owner", gatewayRequest.getValue().stepKey());
		assertEquals("thread-1", running.getThreadId());
		assertEquals("tenant-1", gatewayRequest.getValue().tenantId());
		assertEquals("user-1", gatewayRequest.getValue().userId());
	}

	@Test
	void managerApprovalRequiredWaitsInsteadOfFailing() {
		DataAgentFlowInstance running = instance(FlowInstanceStatus.RUNNING, "execute",
				"{\"runtime\":{\"confirmed\":true,\"contextRevision\":0,\"confirmedRevision\":0}}");
		running.setId(88L);
		when(resourceVersionMapper.findPublished(10L)).thenReturn(toolVersion(false));
		when(instanceService.loadOrCreate(any(), any(), any(), any())).thenReturn(running);
		stubAdvance();
		CapabilityGateway rejecting = mock(CapabilityGateway.class);
		when(rejecting.invoke(any(), any()))
			.thenThrow(new CapabilityApprovalRequiredException(55L, "write.tool", "hash"));
		DefaultFlowEngine rejectingEngine = new DefaultFlowEngine(new FlowDefinitionValidator(objectMapper),
				new FlowNodeExecutorRegistry(), instanceService, eventService, new FlowContextMapper(),
				new FlowConditionEvaluator(new FlowContextMapper()), new FlowSchemaValidator(),
				mock(FlowFieldExtractor.class), toolInvoker, resourceVersionMapper, objectMapper, rejecting);
		DataAgentSkillVersion skillVersion = DataAgentSkillVersion.builder().id(2L).skillId(1L)
			.runtimeConfig("{\"requireManagerApproval\":true}")
			.flowDefinition(nonIdempotentVersion().getFlowDefinition()).build();

		FlowExecutionResult result = rejectingEngine.execute(AgentRequest.builder().agentId("1").threadId("thread-1")
			.runtimeRequestId("runtime-approval").tenantIdSnapshot("1").build(),
				DataAgentSkill.builder().id(1L).skillCode("demand-create").build(), skillVersion, null);

		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertEquals("APPROVAL", result.message().payload().action());
		assertFalse(result.terminal());
		verify(toolInvoker, never()).invoke(any());
	}

	@Test
	void writeRejectedByGatewayDoesNotInvokeTool() {
		DataAgentFlowInstance running = instance(FlowInstanceStatus.RUNNING, "execute",
				"{\"runtime\":{\"confirmed\":true,\"contextRevision\":0,\"confirmedRevision\":0}}");
		when(resourceVersionMapper.findPublished(10L)).thenReturn(toolVersion(true));
		when(instanceService.loadOrCreate(any(), any(), any(), any())).thenReturn(running);
		stubAdvance();
		CapabilityGateway rejecting = mock(CapabilityGateway.class);
		when(rejecting.invoke(any(), any())).thenThrow(CheckedException.fail("风险与审批检查拒绝：能力为高风险"));
		DefaultFlowEngine rejectingEngine = new DefaultFlowEngine(new FlowDefinitionValidator(objectMapper),
				new FlowNodeExecutorRegistry(), instanceService, eventService, new FlowContextMapper(),
				new FlowConditionEvaluator(new FlowContextMapper()), new FlowSchemaValidator(),
				mock(FlowFieldExtractor.class), toolInvoker, resourceVersionMapper, objectMapper, rejecting);

		FlowExecutionResult result = rejectingEngine.execute(AgentRequest.builder().agentId("1").threadId("thread-1")
			.runtimeRequestId("runtime-denied").tenantIdSnapshot("tenant-1").build(),
				DataAgentSkill.builder().id(1L).skillCode("demand-create").build(), version(), null);

		assertEquals(FlowInstanceStatus.FAILED.name(), result.instance().getStatus());
		assertTrue(result.text().contains("风险与审批检查拒绝"));
		verify(toolInvoker, never()).invoke(any());
	}

	@Test
	void resultContractFailsBusinessFailureWithoutPublishingSuccess() {
		DataAgentFlowInstance running = instance(FlowInstanceStatus.RUNNING, "execute",
				"{\"runtime\":{\"confirmed\":true,\"contextRevision\":0,\"confirmedRevision\":0}}");
		when(resourceVersionMapper.findPublished(10L)).thenReturn(toolVersion(false));
		when(instanceService.loadOrCreate(any(), any(), any(), any())).thenReturn(running);
		stubAdvance();
		when(toolInvoker.invoke(any())).thenReturn(Map.of("success", false, "message", "创建需求单失败",
				"missingFields", java.util.List.of("arrivalTime")));

		FlowExecutionResult result = engine.execute(AgentRequest.builder().agentId("1").threadId("thread-1")
				.runtimeRequestId("runtime-business-failed").build(),
				DataAgentSkill.builder().id(1L).skillCode("demand-create").build(), resultContractVersion(), null);

		assertEquals(FlowInstanceStatus.FAILED.name(), result.instance().getStatus());
		assertTrue(result.text().contains("arrivalTime"));
		verify(eventService, never()).resourceSuccess(any(), any(), any(), any(), any(), any());
	}

	@Test
	void resultContractRejectsMissingDemandNumberEvenWhenBusinessSaysSuccess() {
		DataAgentFlowInstance running = instance(FlowInstanceStatus.RUNNING, "execute",
				"{\"runtime\":{\"confirmed\":true,\"contextRevision\":0,\"confirmedRevision\":0}}");
		when(resourceVersionMapper.findPublished(10L)).thenReturn(toolVersion(false));
		when(instanceService.loadOrCreate(any(), any(), any(), any())).thenReturn(running);
		stubAdvance();
		when(toolInvoker.invoke(any())).thenReturn(Map.of("success", true, "message", "需求单已创建",
				"demandId", "1"));

		FlowExecutionResult result = engine.execute(AgentRequest.builder().agentId("1").threadId("thread-1")
				.runtimeRequestId("runtime-missing-number").build(),
				DataAgentSkill.builder().id(1L).skillCode("demand-create").build(), resultContractVersion(), null);

		assertEquals(FlowInstanceStatus.FAILED.name(), result.instance().getStatus());
		verify(eventService, never()).resourceSuccess(any(), any(), any(), any(), any(), any());
	}

	private DataAgentFlowInstance instance(FlowInstanceStatus status, String nodeId, String context) {
		return DataAgentFlowInstance.builder().id(1L).tenantId("tenant-1").agentId(1L).skillVersionId(2L)
			.skillCode("demand-create").threadId("thread-1").userId("user-1").status(status.name())
			.currentNodeId(nodeId).contextData(context).waitingPayload("{\"action\":\"POLL_RESULT\"}")
			.idempotencyKey("flow:tenant-1:1:execute").lockVersion(1).build();
	}

	private DataAgentSkillVersion version() {
		String definition = """
				{
				  "schemaVersion":"skill-flow/v1",
				  "startNode":"confirm",
				  "nodes":[
				    {"id":"confirm","type":"confirm","next":"execute"},
				    {"id":"execute","type":"execute","next":"present","config":{
				      "resourceVersionId":10,
				      "outputPath":"/result",
				      "resultQuery":{"resourceVersionId":20,"outputPath":"/result",
				        "completedCondition":{"op":"eq","path":"/result/found","value":true}}
				    }},
				    {"id":"present","type":"present","next":"end","config":{
				      "text":"{{result.demandNo}}","format":"markdown","dataPath":"/result"
				    }},
				    {"id":"end","type":"end"}
				  ]
				}
				""";
		return DataAgentSkillVersion.builder().id(2L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion nonIdempotentVersion() {
		String definition = """
				{
				  "schemaVersion":"skill-flow/v1",
				  "startNode":"confirm",
				  "nodes":[
				    {"id":"confirm","type":"confirm","next":"execute"},
				    {"id":"execute","type":"execute","next":"end","config":{
				      "resourceVersionId":10,
				      "outputPath":"/result"
				    }},
				    {"id":"end","type":"end"}
				  ]
				}
				""";
		return DataAgentSkillVersion.builder().id(2L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion resultContractVersion() {
		String definition = """
				{
				  "schemaVersion":"skill-flow/v1",
				  "startNode":"confirm",
				  "nodes":[
				    {"id":"confirm","type":"confirm","next":"execute"},
				    {"id":"execute","type":"execute","next":"present","config":{
				      "resourceVersionId":10,
				      "outputPath":"/result",
				      "successCondition":{"op":"all","conditions":[
				        {"op":"eq","path":"/result/success","value":true},
				        {"op":"notEmpty","path":"/result/demandId"},
				        {"op":"notEmpty","path":"/result/demandNo"}
				      ]}
				    }},
				    {"id":"present","type":"present","next":"end","config":{"text":"{{result.demandNo}}"}},
				    {"id":"end","type":"end"}
				  ]
				}
				""";
		return DataAgentSkillVersion.builder().id(2L).skillId(1L).flowDefinition(definition).build();
	}

	private AgentExecutionResourceVersion toolVersion(boolean idempotencyRequired) {
		return AgentExecutionResourceVersion.builder().id(10L).resourceKey("write.tool").versionNo(1)
			.status("PUBLISHED").accessMode("WRITE").exposureMode("FLOW_ONLY")
			.idempotencyRequired(idempotencyRequired).build();
	}

	private AgentExecutionResourceVersion readTool() {
		return AgentExecutionResourceVersion.builder().id(20L).resourceKey("read.tool").versionNo(1)
			.status("PUBLISHED").accessMode("READ").exposureMode("FLOW_ONLY").build();
	}

	private static CapabilityGateway passthroughGateway() {
		CapabilityGateway gateway = mock(CapabilityGateway.class);
		when(gateway.invoke(any(InvocationRequest.class), any())).thenAnswer(invocation -> {
			CapabilityExecutor executor = invocation.getArgument(1);
			return ResultEnvelope.builder()
				.status(ResultEnvelope.STATUS_SUCCESS)
				.schemaVersion(ResultEnvelope.SCHEMA_VERSION_V1)
				.data(executor.execute())
				.build();
		});
		return gateway;
	}

	private void stubAdvance() {
		when(instanceService.advance(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
			.thenAnswer(invocation -> {
				DataAgentFlowInstance current = invocation.getArgument(0);
				current.setStatus(((FlowInstanceStatus) invocation.getArgument(1)).name());
				current.setCurrentNodeId(invocation.getArgument(2));
				current.setIdempotencyKey(invocation.getArgument(5));
				return current;
			});
	}

}
