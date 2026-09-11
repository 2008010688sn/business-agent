/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeProgressService;
import com.sn68.agent.dataagent.capability.CapabilityExecutor;
import com.sn68.agent.dataagent.capability.CapabilityGateway;
import com.sn68.agent.dataagent.capability.InvocationRequest;
import com.sn68.agent.dataagent.capability.ResultEnvelope;
import com.sn68.agent.dataagent.entity.AgentExecutionResourceVersion;
import com.sn68.agent.dataagent.entity.DataAgentFlowInstance;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceVersionMapper;
import com.sn68.agent.dataagent.tool.ToolInvocationContext;
import com.sn68.agent.dataagent.tool.ToolInvoker;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultFlowEngineParallelResolverTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final FlowInstanceService instanceService = mock(FlowInstanceService.class);

	private final FlowEventService eventService = mock(FlowEventService.class);

	private final ToolInvoker toolInvoker = mock(ToolInvoker.class);

	private final CapabilityGateway capabilityGateway = passthroughGateway();

	private final FlowFieldExtractor fieldExtractor = mock(FlowFieldExtractor.class, CALLS_REAL_METHODS);

	private final AgentExecutionResourceVersionMapper resourceVersionMapper =
			mock(AgentExecutionResourceVersionMapper.class);

	private final DefaultFlowEngine engine = new DefaultFlowEngine(new FlowDefinitionValidator(objectMapper),
			new FlowNodeExecutorRegistry(), instanceService, eventService, new FlowContextMapper(),
			new FlowConditionEvaluator(new FlowContextMapper()), new FlowSchemaValidator(), fieldExtractor,
			toolInvoker, resourceVersionMapper, objectMapper, capabilityGateway);

	@Test
	void executesIndependentReadResolversInParallelAndPersistsOneBatch() {
		DataAgentFlowInstance instance = DataAgentFlowInstance.builder().id(9L).tenantId("tenant-1").agentId(1L)
				.skillVersionId(2L).skillCode("demand-create").threadId("thread-1").userId("user-1")
				.status(FlowInstanceStatus.RUNNING.name()).currentNodeId("batch")
				.contextData("{\"input\":{\"companyId\":\"c1\"}}").waitingPayload("{}").lockVersion(0).build();
		when(instanceService.loadOrCreate(any(), any(), any(), any())).thenReturn(instance);
		when(resourceVersionMapper.findPublished(10L)).thenReturn(readTool(10L));
		when(resourceVersionMapper.findPublished(20L)).thenReturn(readTool(20L));
		when(toolInvoker.invoke(any(ToolInvocationContext.class))).thenAnswer(invocation -> {
			ToolInvocationContext context = invocation.getArgument(0);
			return Map.of("items", java.util.List.of(context.resourceVersionId()));
		});
		doAnswer(invocation -> {
			DataAgentFlowInstance current = invocation.getArgument(0);
			current.setStatus(((FlowInstanceStatus) invocation.getArgument(1)).name());
			current.setCurrentNodeId(invocation.getArgument(2));
			current.setContextData(objectMapper.writeValueAsString(invocation.getArgument(3)));
			return current;
		}).when(instanceService).advanceBatch(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
		doAnswer(invocation -> {
			DataAgentFlowInstance current = invocation.getArgument(0);
			current.setStatus(((FlowInstanceStatus) invocation.getArgument(1)).name());
			current.setCurrentNodeId(invocation.getArgument(2));
			return current;
		}).when(instanceService).advance(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

		FlowExecutionResult result = engine.execute(request(), DataAgentSkill.builder().id(1L).skillCode("demand-create").build(),
				version(), null);

		ArgumentCaptor<ToolInvocationContext> invocations = ArgumentCaptor.forClass(ToolInvocationContext.class);
		verify(toolInvoker, times(2)).invoke(invocations.capture());
		verify(instanceService, times(1)).advanceBatch(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
		verify(capabilityGateway, times(2)).invoke(any(InvocationRequest.class), any());
		assertEquals(FlowInstanceStatus.SUCCEEDED.name(), result.instance().getStatus());
	}

	@Test
	void extractionFailureReturnsWaitingFormInsteadOfTerminalFailure() {
		DataAgentFlowInstance instance = DataAgentFlowInstance.builder().id(10L).tenantId("tenant-1").agentId(1L)
				.skillVersionId(2L).skillCode("demand-create").threadId("thread-2").userId("user-1")
				.status(FlowInstanceStatus.RUNNING.name()).currentNodeId("extract").contextData("{}").waitingPayload("{}")
				.lockVersion(0).build();
		when(instanceService.loadOrCreate(any(), any(), any(), any())).thenReturn(instance);
		when(fieldExtractor.extract(any(), any(), any(), any())).thenThrow(new IllegalStateException("timeout"));
		doAnswer(invocation -> {
			DataAgentFlowInstance current = invocation.getArgument(0);
			current.setStatus(((FlowInstanceStatus) invocation.getArgument(1)).name());
			current.setCurrentNodeId(invocation.getArgument(2));
			return current;
		}).when(instanceService).advance(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
		AgentRuntimeProgressService progressService = mock(AgentRuntimeProgressService.class);
		DefaultFlowEngine failureEngine = new DefaultFlowEngine(new FlowDefinitionValidator(objectMapper),
				new FlowNodeExecutorRegistry(), instanceService, eventService, new FlowContextMapper(),
				new FlowConditionEvaluator(new FlowContextMapper()), new FlowSchemaValidator(), fieldExtractor,
				toolInvoker, resourceVersionMapper, objectMapper, Runnable::run, progressService,
				new DataAgentProperties(), capabilityGateway);

		FlowExecutionResult result = failureEngine.execute(request(), DataAgentSkill.builder().id(1L).skillCode("demand-create").build(),
				extractionVersion(), null);

		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		// 技术失败按 FAILED 上抛并推送 FLOW_INPUT_FAILED；实例仍保持 WAITING 表单可恢复。
		assertEquals(FlowTurnOutcome.FAILED, result.turnOutcome());
		assertEquals("REASK", result.message().payload().action());
		verify(progressService).emitFlow(any(), eq("10"), eq("extract"), eq(null), eq("FLOW_INPUT_FAILED"),
				eq(AgentRuntimeProgressService.STATUS_FAILED), any(), any());
	}

	@Test
	void selectUsesServerCandidateInsteadOfClientRawData() throws Exception {
		String serverCandidate = "{\"twoProjectId\":\"p2\",\"twoProjectName\":\"云南万绿\",\"oneProjectId\":\"p1\"}";
		DataAgentFlowInstance instance = DataAgentFlowInstance.builder().id(11L).tenantId("tenant-1").agentId(1L)
			.skillVersionId(2L).skillCode("demand-create").threadId("thread-1").userId("user-1")
			.status(FlowInstanceStatus.WAITING.name()).currentNodeId("select")
			.contextData("{\"resolved\":{\"projects\":{\"items\":[" + serverCandidate + "]}}}")
			.waitingPayload("{\"action\":\"SELECT\",\"targetPath\":\"/resolved/project\",\"options\":["
					+ "{\"label\":\"云南万绿\",\"value\":\"p2\",\"rawData\":" + serverCandidate + "}]}")
			.lockVersion(0).build();
		when(instanceService.loadOrCreate(any(), any(), any(), any())).thenReturn(instance);
		stubAdvanceWithContext();
		AgentRequest request = request();
		request.setFlowInstanceId("11");
		request.setFlowAction(new FlowAction("select-option", "SELECT", "p2",
				Map.of("rawData", Map.of("twoProjectId", "forged", "twoProjectName", "伪造项目"))));

		FlowExecutionResult result = engine.execute(request,
				DataAgentSkill.builder().id(1L).skillCode("demand-create").build(), selectionVersion(), null);

		Map<?, ?> context = objectMapper.readValue(result.instance().getContextData(), Map.class);
		Map<?, ?> input = (Map<?, ?>) context.get("input");
		assertEquals("p2", input.get("twoProjectId"));
		assertEquals("云南万绿", input.get("twoProjectName"));
		assertNotEquals("forged", input.get("twoProjectId"));
	}

	@Test
	void rejectsSelectionThatIsNotInServerCandidates() {
		DataAgentFlowInstance instance = DataAgentFlowInstance.builder().id(12L).tenantId("tenant-1").agentId(1L)
			.skillVersionId(2L).skillCode("demand-create").threadId("thread-1").userId("user-1")
			.status(FlowInstanceStatus.WAITING.name()).currentNodeId("select").contextData("{}")
			.waitingPayload("{\"action\":\"SELECT\",\"targetPath\":\"/resolved/project\",\"options\":["
					+ "{\"label\":\"云南万绿\",\"value\":\"p2\",\"rawData\":{\"twoProjectId\":\"p2\"}}]}")
			.lockVersion(0).build();
		when(instanceService.loadOrCreate(any(), any(), any(), any())).thenReturn(instance);
		AgentRequest request = request();
		request.setFlowInstanceId("12");
		request.setFlowAction(new FlowAction("select-option", "SELECT", "missing", Map.of()));

		assertThrows(CheckedException.class, () -> engine.execute(request,
				DataAgentSkill.builder().id(1L).skillCode("demand-create").build(), selectionVersion(), null));
	}

	@Test
	void executorRejectionReturnsRetryableWaitingState() {
		DataAgentFlowInstance instance = DataAgentFlowInstance.builder().id(13L).tenantId("tenant-1").agentId(1L)
				.skillVersionId(2L).skillCode("demand-create").threadId("thread-1").userId("user-1")
				.status(FlowInstanceStatus.RUNNING.name()).currentNodeId("batch")
				.contextData("{\"input\":{\"companyId\":\"c1\"}}").waitingPayload("{}").lockVersion(0).build();
		when(instanceService.loadOrCreate(any(), any(), any(), any())).thenReturn(instance);
		when(resourceVersionMapper.findPublished(10L)).thenReturn(readTool(10L));
		when(resourceVersionMapper.findPublished(20L)).thenReturn(readTool(20L));
		doAnswer(invocation -> {
			DataAgentFlowInstance current = invocation.getArgument(0);
			current.setStatus(((FlowInstanceStatus) invocation.getArgument(1)).name());
			current.setCurrentNodeId(invocation.getArgument(2));
			current.setContextData(objectMapper.writeValueAsString(invocation.getArgument(3)));
			return current;
		}).when(instanceService).advanceBatch(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
		AgentRuntimeProgressService progressService = mock(AgentRuntimeProgressService.class);
		DefaultFlowEngine rejectingEngine = new DefaultFlowEngine(new FlowDefinitionValidator(objectMapper),
				new FlowNodeExecutorRegistry(), instanceService, eventService, new FlowContextMapper(),
				new FlowConditionEvaluator(new FlowContextMapper()), new FlowSchemaValidator(), fieldExtractor,
				toolInvoker, resourceVersionMapper, objectMapper,
				command -> { throw new RejectedExecutionException("queue full"); }, progressService,
				new DataAgentProperties(), capabilityGateway);

		FlowExecutionResult result = rejectingEngine.execute(request(),
				DataAgentSkill.builder().id(1L).skillCode("demand-create").build(), version(), null);

		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertEquals("RESOLVER_RETRY", result.message().payload().action());
		verify(progressService).emitFlow(any(), eq("13"), eq("batch"), eq("customer"),
				eq("FLOW_RESOLVER_FINISHED"), eq(AgentRuntimeProgressService.STATUS_FAILED), any(), any());
	}

	@Test
	void failedFlowEmitsOnlyOneTerminalProgressEvent() {
		DataAgentFlowInstance instance = DataAgentFlowInstance.builder().id(14L).tenantId("tenant-1").agentId(1L)
				.skillVersionId(2L).skillCode("demand-create").threadId("thread-1").userId("user-1")
				.status(FlowInstanceStatus.RUNNING.name()).currentNodeId("error").contextData("{}")
				.waitingPayload("{}").lockVersion(0).build();
		when(instanceService.loadOrCreate(any(), any(), any(), any())).thenReturn(instance);
		stubAdvanceWithContext();
		AgentRuntimeProgressService progressService = mock(AgentRuntimeProgressService.class);
		DefaultFlowEngine progressEngine = new DefaultFlowEngine(new FlowDefinitionValidator(objectMapper),
				new FlowNodeExecutorRegistry(), instanceService, eventService, new FlowContextMapper(),
				new FlowConditionEvaluator(new FlowContextMapper()), new FlowSchemaValidator(), fieldExtractor,
				toolInvoker, resourceVersionMapper, objectMapper, Runnable::run, progressService,
				new DataAgentProperties(), capabilityGateway);

		FlowExecutionResult result = progressEngine.execute(request(),
				DataAgentSkill.builder().id(1L).skillCode("demand-create").build(), errorVersion(), null);

		assertEquals(FlowInstanceStatus.FAILED.name(), result.instance().getStatus());
		verify(progressService, times(1)).emitFlow(any(), eq("14"), eq("error"), eq(null), eq("FLOW_FAILED"),
				eq(AgentRuntimeProgressService.STATUS_FAILED), any(), any());
		verify(progressService, never()).emitFlow(any(), any(), any(), any(), eq("FLOW_FINISHED"), any(), any(), any());
	}

	private AgentRequest request() {
		return AgentRequest.builder().agentId("1").threadId("thread-1").runtimeRequestId("runtime-1")
				.tenantIdSnapshot("tenant-1").userIdSnapshot("user-1").build();
	}

	private DataAgentSkillVersion version() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"batch","nodes":[
				{"id":"batch","type":"resolve","next":"end","config":{"parallelResolvers":[
				{"id":"customer","resourceVersionId":10,"parallelSafe":true,"outputPath":"/resolved/customer",
				 "requiresAll":["/input/companyId"],"argumentMappings":{"companyId":"/input/companyId"}},
				{"id":"project","resourceVersionId":20,"parallelSafe":true,"outputPath":"/resolved/project",
				 "requiresAll":["/input/companyId"],"argumentMappings":{"companyId":"/input/companyId"}}]}},
				{"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(2L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion extractionVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"extract","nodes":[
				{"id":"extract","type":"extract","next":"end","config":{"schema":{"type":"object",
				 "properties":{"companyName":{"type":"string"}}}}},
				{"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(2L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion selectionVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"select","nodes":[
				{"id":"select","type":"select","next":"merge","config":{
				 "optionsPath":"/resolved/projects/items","targetPath":"/resolved/project",
				 "labelPath":"/twoProjectName","valuePath":"/twoProjectId"}},
				{"id":"merge","type":"merge","next":"end","config":{
				 "sourcePath":"/resolved/project","targetPath":"/input","strategy":"OVERWRITE_PRESENT"}},
				{"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(2L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion errorVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"error","nodes":[
				{"id":"error","type":"error","config":{"text":"流程执行失败"}},
				{"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(2L).skillId(1L).flowDefinition(definition).build();
	}

	private void stubAdvanceWithContext() {
		doAnswer(invocation -> {
			DataAgentFlowInstance current = invocation.getArgument(0);
			current.setStatus(((FlowInstanceStatus) invocation.getArgument(1)).name());
			current.setCurrentNodeId(invocation.getArgument(2));
			current.setContextData(objectMapper.writeValueAsString(invocation.getArgument(3)));
			return current;
		}).when(instanceService).advance(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
	}

	private AgentExecutionResourceVersion readTool(Long id) {
		return AgentExecutionResourceVersion.builder().id(id).resourceKey("read-" + id).versionNo(1)
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

}
