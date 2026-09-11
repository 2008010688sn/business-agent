/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.capability.CapabilityExecutor;
import com.sn68.agent.dataagent.capability.CapabilityGateway;
import com.sn68.agent.dataagent.capability.InvocationRequest;
import com.sn68.agent.dataagent.capability.ResultEnvelope;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.entity.AgentExecutionResourceVersion;
import com.sn68.agent.dataagent.entity.DataAgentFlowInstance;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceVersionMapper;
import com.sn68.agent.dataagent.tool.ToolInvoker;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultFlowEngineSelectEmptyLoopTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final FlowFieldExtractor fieldExtractor = mock(FlowFieldExtractor.class);

	private final ToolInvoker toolInvoker = mock(ToolInvoker.class);

	private final AgentExecutionResourceVersionMapper resourceVersionMapper =
			mock(AgentExecutionResourceVersionMapper.class);

	@Test
	void projectToolErrorWaitsOnSelectInsteadOfLooping() {
		InMemoryFlowInstanceService instances = runningSelect(customerFilledContext(true));

		FlowExecutionResult result = engine(instances).execute(request("上海箱箱物流科技有限公司"), skill(),
				customerProjectVersion(), modelConfig());

		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertEquals("select-project", result.instance().getCurrentNodeId());
		assertEquals("SELECT", result.message().payload().action());
		assertTrue(result.text().contains("查询失败"));
		assertFalse(result.text().contains("安全上限"));
		assertFalse(result.text().contains("DataColumn"));
		assertNotEquals("FLOW_NODE_LIMIT", result.instance().getErrorCode());
	}

	@Test
	void emptyProjectWithCustomerFilledWaitsInsteadOfCollectBounce() {
		InMemoryFlowInstanceService instances = runningSelect(customerFilledContext(false));

		FlowExecutionResult result = engine(instances).execute(request("继续"), skill(), customerProjectVersion(),
				modelConfig());

		// 空选项 + emptyNext 已满足是死路：换关键词也无济于事，文案改为死路兜底而非 notFoundText，
		// 且卡片必须带 CANCEL 退出动作。
		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertEquals("select-project", result.instance().getCurrentNodeId());
		assertEquals("SELECT", result.message().payload().action());
		assertTrue(result.text().contains("当前没有可选项"));
		assertFalse(result.text().contains("安全上限"));
		assertTrue(result.message().actions().stream().anyMatch(action -> "CANCEL".equalsIgnoreCase(action.type())));
	}

	@Test
	void emptyNextLoopStopsBeforeNodeLimit() {
		when(resourceVersionMapper.findPublished(3L)).thenReturn(readTool());
		when(toolInvoker.invoke(any())).thenReturn(Map.of("items", List.of()));
		InMemoryFlowInstanceService instances = runningSelect("""
				{"input":{"companyId":"CN102101","companyName":"上海箱箱物流科技有限公司"},
				 "runtime":{"contextRevision":2}}
				""");

		FlowExecutionResult result = engine(instances).execute(request("继续"), skill(), cyclingVersion(),
				modelConfig());

		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertFalse(result.text().contains("安全上限"));
		assertNotEquals("FLOW_NODE_LIMIT", result.instance().getErrorCode());
		assertTrue(result.text().contains("未能取得新结果") || "SELECT".equals(result.message().payload().action()));
	}

	private DefaultFlowEngine engine(FlowInstanceService instances) {
		FlowContextMapper contextMapper = new FlowContextMapper();
		return new DefaultFlowEngine(new FlowDefinitionValidator(objectMapper), new FlowNodeExecutorRegistry(),
				instances, mock(FlowEventService.class), contextMapper, new FlowConditionEvaluator(contextMapper),
				new FlowSchemaValidator(), fieldExtractor, toolInvoker, resourceVersionMapper, objectMapper,
				passthroughGateway());
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

	private AgentRequest request(String query) {
		return AgentRequest.builder().agentId("1").threadId("thread-1").runtimeRequestId("runtime-1")
				.tenantIdSnapshot("tenant-1").userIdSnapshot("user-1").query(query).build();
	}

	private ModelConfigDTO modelConfig() {
		return ModelConfigDTO.builder().id(1L).modelName("test-model").build();
	}

	private DataAgentSkill skill() {
		return DataAgentSkill.builder().id(1L).skillCode("demand-create").build();
	}

	private DataAgentSkillVersion customerProjectVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"collect-customer","nodes":[
				 {"id":"collect-customer","type":"collect","next":"resolve-project","config":{
				  "requiredPaths":["/input/companyName"],
				  "requiredAnyPaths":["/input/companyId","/input/companyName"],
				  "schema":{"type":"object","properties":{"companyId":{"type":"string"},"companyName":{"type":"string"}}},
				  "collectionPresentation":{"mode":"MISSING_ONLY","fieldPrompts":{
				   "/input/companyId":"请提供客户名称或客户编码。",
				   "/input/companyName":"请提供客户名称或客户编码。"}}}},
				 {"id":"resolve-project","type":"resolve","next":"select-project","config":{
				  "resourceVersionId":3,"resourceKey":"demo.echo.projectOptions",
				  "outputPath":"/resolved/projects",
				  "argumentMappings":{"companyId":"/input/companyId","companyName":"/input/companyName"}}},
				 {"id":"select-project","type":"select","next":"end","config":{
				  "emptyNext":"collect-customer","optionsPath":"/resolved/projects/items",
				  "labelPath":"/twoProjectName","valuePath":"/twoProjectId","targetPath":"/resolved/project",
				  "textSearch":{"enabled":true,"matchPaths":["/twoProjectName"],"argumentName":"keyword",
				   "notFoundText":"未找到匹配项目，请换一个名称或编码。","resolverNode":"resolve-project"}}},
				 {"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(2L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion cyclingVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"select-project","nodes":[
				 {"id":"resolve-project","type":"resolve","next":"select-project","config":{
				  "resourceVersionId":3,"resourceKey":"demo.echo.projectOptions",
				  "outputPath":"/resolved/projects"}},
				 {"id":"select-project","type":"select","next":"end","config":{
				  "emptyNext":"resolve-project","optionsPath":"/resolved/projects/items",
				  "labelPath":"/twoProjectName","valuePath":"/twoProjectId","targetPath":"/resolved/project"}},
				 {"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(3L).skillId(1L).flowDefinition(definition).build();
	}

	private InMemoryFlowInstanceService runningSelect(String context) {
		return new InMemoryFlowInstanceService(DataAgentFlowInstance.builder().id(20L).tenantId("tenant-1").agentId(1L)
				.skillVersionId(2L).skillCode("demand-create").threadId("thread-1").userId("user-1")
				.status(FlowInstanceStatus.RUNNING.name()).currentNodeId("select-project").contextData(context)
				.waitingPayload("{}").lockVersion(0).build());
	}

	private String customerFilledContext(boolean toolError) {
		String projectBlock = toolError
				? """
						{"items":[],"isError":true,"toolError":true,"success":false,
						 "message":"java.lang.String[] com.sn68.agent.framework.db.mybatisplus.datascope.annotation.DataColumn.names()"}
						"""
				: """
						{"items":[]}
						""";
		return """
				{"input":{"companyId":"CN102101","companyName":"上海箱箱物流科技有限公司"},
				 "resolved":{"projects":%s},
				 "runtime":{"contextRevision":2}}
				""".formatted(projectBlock);
	}

	private AgentExecutionResourceVersion readTool() {
		return AgentExecutionResourceVersion.builder().id(3L).resourceKey("demo.echo.projectOptions")
				.versionNo(1).status("PUBLISHED").accessMode("READ").exposureMode("FLOW_ONLY").build();
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
