/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeProgressService;
import com.sn68.agent.dataagent.capability.CapabilityGateway;
import com.sn68.agent.dataagent.capability.ResultEnvelope;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.entity.AgentExecutionResourceVersion;
import com.sn68.agent.dataagent.entity.DataAgentFlowInstance;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceVersionMapper;
import com.sn68.agent.dataagent.tool.ToolInvoker;
import com.sn68.agent.dataagent.ui.AgentUiMessage;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Sinks;

/**
 * skill-flow 卡片 steps 契约回归：并行 Resolver 批次执行后，最终卡片应携带
 * kind=tool（已发布快照工具标题）、kind=node（节点类型中文标签）步骤，且末条恒为 kind=state 终态。
 */
class DefaultFlowEngineCardStepsTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final AgentExecutionResourceVersionMapper resourceVersionMapper = mock(
			AgentExecutionResourceVersionMapper.class);

	private final CapabilityGateway capabilityGateway = mock(CapabilityGateway.class);

	@Test
	void waitingCardExposesToolNodeAndStateSteps() {
		when(resourceVersionMapper.findPublished(3L)).thenReturn(publishedProjectOptionsVersion());
		when(capabilityGateway.invoke(any(), any())).thenReturn(ResultEnvelope.builder()
			.status(ResultEnvelope.STATUS_SUCCESS)
			.schemaVersion(ResultEnvelope.SCHEMA_VERSION_V1)
			.data(Map.of("items", List.of()))
			.build());
		AgentRuntimeProgressService progress = new AgentRuntimeProgressService();
		AgentRequest request = request("继续");
		Sinks.Many<ServerSentEvent<com.sn68.agent.dataagent.agentscope.vo.AgentResponse>> sink = Sinks.many()
			.unicast()
			.onBackpressureBuffer();
		sink.asFlux().subscribe(event -> {
		});
		progress.register(request, sink);

		FlowExecutionResult result = engine(progress).execute(request, skill(), parallelResolverVersion(),
				modelConfig());

		assertEquals(FlowInstanceStatus.SUCCEEDED.name(), result.instance().getStatus());
		List<AgentUiMessage.Step> steps = result.message().steps();
		assertEquals(3, steps.size());
		assertEquals("tool", steps.get(0).kind());
		// 工具标题来自已发布快照的 toolName=projectOptions 的中文映射
		assertEquals("查询下单项目候选", steps.get(0).label());
		assertEquals(AgentRuntimeProgressService.STATUS_SUCCESS, steps.get(0).status());
		assertNotNull(steps.get(0).durationMs());
		assertEquals("node", steps.get(1).kind());
		assertEquals("查询主数据", steps.get(1).label());
		assertEquals("state", steps.get(2).kind());
		assertEquals(AgentRuntimeProgressService.STATUS_SUCCESS, steps.get(2).status());
	}

	@Test
	void cardWithoutProgressHandleKeepsEmptySteps() {
		when(resourceVersionMapper.findPublished(3L)).thenReturn(publishedProjectOptionsVersion());
		when(capabilityGateway.invoke(any(), any())).thenReturn(ResultEnvelope.builder()
			.status(ResultEnvelope.STATUS_SUCCESS)
			.schemaVersion(ResultEnvelope.SCHEMA_VERSION_V1)
			.data(Map.of("items", List.of()))
			.build());

		FlowExecutionResult result = engine(null).execute(request("继续"), skill(), parallelResolverVersion(),
				modelConfig());

		// 未注册进度服务（runtimeProgressService == null）时卡片不携带 steps，结构保持既有契约。
		assertEquals(FlowInstanceStatus.SUCCEEDED.name(), result.instance().getStatus());
		assertEquals(0, result.message().steps().size());
	}

	private DefaultFlowEngine engine(AgentRuntimeProgressService progress) {
		FlowContextMapper contextMapper = new FlowContextMapper();
		return new DefaultFlowEngine(new FlowDefinitionValidator(objectMapper), new FlowNodeExecutorRegistry(),
				new InMemoryFlowInstanceService(runningResolveInstance()), mock(FlowEventService.class), contextMapper,
				new FlowConditionEvaluator(contextMapper), new FlowSchemaValidator(), mock(FlowFieldExtractor.class),
				mock(ToolInvoker.class), resourceVersionMapper, objectMapper, Runnable::run, progress,
				new com.sn68.agent.dataagent.properties.DataAgentProperties(), capabilityGateway);
	}

	private AgentExecutionResourceVersion publishedProjectOptionsVersion() {
		String snapshot = """
				{"resourceKey":"demo.echo.projectOptions","toolName":"projectOptions"}
				""";
		return AgentExecutionResourceVersion.builder().id(3L).resourceKey("demo.echo.projectOptions")
			.status("PUBLISHED").snapshot(snapshot).build();
	}

	private DataAgentFlowInstance runningResolveInstance() {
		return DataAgentFlowInstance.builder().id(1L).tenantId("tenant-1").agentId(1L).skillVersionId(2L)
			.skillCode("demand-create").threadId("thread-1").userId("user-1")
			.status(FlowInstanceStatus.RUNNING.name()).currentNodeId("resolve-project")
			.contextData("{\"runtime\":{\"contextRevision\":1}}").waitingPayload("{}").lockVersion(0).build();
	}

	private DataAgentSkillVersion parallelResolverVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"resolve-project","nodes":[
				 {"id":"resolve-project","type":"resolve","next":"end","config":{
				  "parallelResolvers":[{"id":"project-options","resourceVersionId":3,
				   "resourceKey":"demo.echo.projectOptions","parallelSafe":true}]}},
				 {"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(2L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkill skill() {
		return DataAgentSkill.builder().id(1L).skillCode("demand-create").build();
	}

	private AgentRequest request(String query) {
		return AgentRequest.builder().agentId("1").threadId("thread-1").runtimeRequestId("runtime-1")
			.tenantIdSnapshot("tenant-1").userIdSnapshot("user-1").flowInstanceId("1").query(query).build();
	}

	private ModelConfigDTO modelConfig() {
		return ModelConfigDTO.builder().id(1L).modelName("test-model").maxTokens(1024L).build();
	}

	private final class InMemoryFlowInstanceService implements FlowInstanceService {

		private final DataAgentFlowInstance instance;

		private InMemoryFlowInstanceService(DataAgentFlowInstance instance) {
			this.instance = instance;
		}

		@Override
		public DataAgentFlowInstance loadOrCreate(AgentRequest request, DataAgentSkill skill,
				DataAgentSkillVersion version, String startNode) {
			return instance;
		}

		@Override
		public DataAgentFlowInstance findActive(AgentRequest request) {
			return instance;
		}

		@Override
		public DataAgentFlowInstance advance(DataAgentFlowInstance current, FlowInstanceStatus status, String nodeId,
				Map<String, Object> context, Map<String, Object> waitingPayload, String idempotencyKey,
				String runtimeRequestId, String errorCode, String errorMessage, Instant finishedAt) {
			try {
				current.setStatus(status.name());
				current.setCurrentNodeId(nodeId);
				current.setContextData(objectMapper.writeValueAsString(new LinkedHashMap<>(context)));
				current.setWaitingPayload(objectMapper.writeValueAsString(new LinkedHashMap<>(waitingPayload)));
				current.setLockVersion(current.getLockVersion() + 1);
				current.setFinishedAt(finishedAt);
				current.setErrorCode(errorCode);
				current.setErrorMessage(errorMessage);
				return current;
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		}
	}

}
