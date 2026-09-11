/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.capability.CapabilityGateway;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.entity.DataAgentFlowInstance;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceVersionMapper;
import com.sn68.agent.dataagent.tool.ToolInvoker;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 同回合主抽取去重回归：extract 节点（或其 EXTRACT 恢复路径）已消费整句用户输入后，
 * collect 节点不再对同一句话重复聚焦抽取；等待态用户输入的抽取链与取消后重开必须不受影响。
 */
class DefaultFlowEnginePrimaryExtractTest {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private static final String SCHEMA = """
			{"type":"object","properties":{
			 "customerName":{"type":"string","title":"客户名称"},
			 "productDescription":{"type":"string","title":"商品及数量"}}}
			""";

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final FlowFieldExtractor fieldExtractor = mock(FlowFieldExtractor.class, CALLS_REAL_METHODS);

	@Test
	void singlePackedUtteranceFillsAllFieldsWithOneExtraction() {
		when(fieldExtractor.extract(any(), any(), any(), any()))
				.thenReturn(Map.of("customerName", "张三", "productDescription", "A100 保温箱 200 个"));
		InMemoryFlowInstanceService instances = new InMemoryFlowInstanceService(runningExtractInstance());
		DefaultFlowEngine engine = engine(instances);

		FlowExecutionResult result = engine.execute(request("客户是张三，商品是A100 保温箱 200 个"), skill(), version(),
				modelConfig());

		// 一次输入带全所有字段：extract 主抽取后 collect 全部满足，全回合仅 1 次模型抽取。
		assertTrue(result.terminal());
		assertEquals("张三", value(result.instance(), "/input/customerName"));
		assertEquals("A100 保温箱 200 个", value(result.instance(), "/input/productDescription"));
		assertEquals(Boolean.TRUE, value(result.instance(), "/runtime/primaryExtractRan"));
		verify(fieldExtractor, times(1)).extract(any(), any(), any(), any(), any(), any());
	}

	@Test
	void nearlyEmptyUtteranceSkipsCollectExtractionAndAsksForCustomer() {
		when(fieldExtractor.extract(any(), any(), any(), any())).thenReturn(Map.of());
		InMemoryFlowInstanceService instances = new InMemoryFlowInstanceService(runningExtractInstance());
		DefaultFlowEngine engine = engine(instances);

		FlowExecutionResult result = engine.execute(request("帮我下一个需求单"), skill(), version(), modelConfig());

		// 主抽取没抽出任何字段：collect 跳过聚焦抽取（不再对同一句重复调用模型），缺失路径直接给等待卡。
		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertEquals("collect-customer", result.instance().getCurrentNodeId());
		assertEquals("COLLECT", result.message().payload().action());
		assertTrue(result.text().contains("请补充客户名称。"));
		assertFalse(result.text().contains("商品"));
		assertEquals(Boolean.TRUE, value(result.instance(), "/runtime/primaryExtractRan"));
		verify(fieldExtractor, times(1)).extract(any(), any(), any(), any(), any(), any());
	}

	@Test
	void waitingAnswerStillExtractsAfterPreviousTurnPrimaryExtract() {
		when(fieldExtractor.extract(any(), any(), any(), any()))
				.thenReturn(Map.of("customerName", "张三"))
				.thenReturn(Map.of("productDescription", "A100 保温箱 200 个"));
		InMemoryFlowInstanceService instances = new InMemoryFlowInstanceService(runningExtractInstance());
		DefaultFlowEngine engine = engine(instances);

		FlowExecutionResult first = engine.execute(request("客户是张三"), skill(), version(), modelConfig());

		// 第一回合主抽取已置位标记，剩余字段走 collect-product 等待卡。
		assertEquals(FlowInstanceStatus.WAITING.name(), first.instance().getStatus());
		assertEquals("collect-product", first.instance().getCurrentNodeId());
		assertEquals(Boolean.TRUE, value(first.instance(), "/runtime/primaryExtractRan"));

		// 第二回合等待态补充回答：标记每回合重置，等待态抽取不被上一回合的标记拦截。
		FlowExecutionResult second = engine.execute(request("商品是A100 保温箱 200 个"), skill(), version(), modelConfig());

		assertTrue(second.terminal());
		assertEquals("张三", value(second.instance(), "/input/customerName"));
		assertEquals("A100 保温箱 200 个", value(second.instance(), "/input/productDescription"));
		assertNull(value(second.instance(), "/runtime/primaryExtractRan"));
		verify(fieldExtractor, times(2)).extract(any(), any(), any(), any(), any(), any());
	}

	@Test
	void cancelledFlowStartsFreshInstanceWithFullExtractionOnNextOrder() {
		when(fieldExtractor.extract(any(), any(), any(), any()))
				.thenReturn(Map.of("turnAction", "cancel"))
				.thenReturn(Map.of("customerName", "张三", "productDescription", "A100 保温箱 200 个"));
		InMemoryFlowInstanceService instances = new InMemoryFlowInstanceService(runningExtractInstance());
		DefaultFlowEngine engine = engine(instances);

		// 第一回合：用户要求取消 → 取消确认等待卡。
		FlowExecutionResult confirm = engine.execute(request("暂停任务"), skill(), version(), modelConfig());
		assertEquals("CANCEL_CONFIRM", confirm.message().payload().action());
		assertFalse(confirm.terminal());

		// 第二回合：确认取消 → CANCELLED 终态。
		AgentRequest confirmed = request("确认取消");
		confirmed.setFlowAction(new FlowAction("confirm-cancel", "CONFIRM_CANCEL", true, Map.of()));
		FlowExecutionResult stopped = engine.execute(confirmed, skill(), version(), modelConfig());
		assertTrue(stopped.terminal());
		assertEquals(FlowInstanceStatus.CANCELLED.name(), stopped.instance().getStatus());

		// 第三回合：取消后再次下单，终态实例不再复用，全新实例空上下文 → extract 全量重抽不遗漏。
		FlowExecutionResult restarted = engine.execute(request("客户是张三，商品是A100 保温箱 200 个"), skill(), version(),
				modelConfig());
		assertTrue(restarted.terminal());
		assertEquals(Long.valueOf(2L), restarted.instance().getId());
		assertEquals("张三", value(restarted.instance(), "/input/customerName"));
		assertEquals("A100 保温箱 200 个", value(restarted.instance(), "/input/productDescription"));
		verify(fieldExtractor, times(2)).extract(any(), any(), any(), any(), any(), any());
	}

	@Test
	void extractRecoveryMarksPrimaryExtractAndCollectDoesNotReextract() {
		when(fieldExtractor.extract(any(), any(), any(), any())).thenReturn(Map.of("customerName", "张三"));
		InMemoryFlowInstanceService instances = new InMemoryFlowInstanceService(extractWaitingInstance());
		DefaultFlowEngine engine = engine(instances);

		FlowExecutionResult result = engine.execute(request("客户是张三"), skill(), version(), modelConfig());

		// 恢复抽取即本回合主抽取：置位标记后 EXTRACT_THEN_ADVANCE 推进到 collect 链，不再重复聚焦抽取。
		assertEquals(Boolean.TRUE, value(result.instance(), "/runtime/primaryExtractRan"));
		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertEquals("collect-product", result.instance().getCurrentNodeId());
		assertEquals("COLLECT", result.message().payload().action());
		assertEquals("张三", value(result.instance(), "/input/customerName"));
		verify(fieldExtractor, times(1)).extract(any(), any(), any(), any(), any(), any());
	}

	@Test
	void waitingStateMultiCollectChainKeepsExtractingAcrossTurns() {
		when(fieldExtractor.extract(any(), any(), any(), any()))
				.thenReturn(Map.of("customerName", "张三"))
				.thenReturn(Map.of("productDescription", "A100 保温箱 200 个"));
		InMemoryFlowInstanceService instances = new InMemoryFlowInstanceService(customerWaitingInstance());
		DefaultFlowEngine engine = engine(instances);

		// 第一回合：等待态输入抽取填客户并推进到 collect-product；COLLECT 等待态恢复不置位主抽取标记。
		FlowExecutionResult first = engine.execute(request("客户是张三"), skill(), version(), modelConfig());
		assertEquals(FlowInstanceStatus.WAITING.name(), first.instance().getStatus());
		assertEquals("collect-product", first.instance().getCurrentNodeId());
		assertEquals("张三", value(first.instance(), "/input/customerName"));
		assertNull(value(first.instance(), "/runtime/primaryExtractRan"));

		// 第二回合：同链后续 collect 的补充抽取不被误杀。
		FlowExecutionResult second = engine.execute(request("商品是A100 保温箱 200 个"), skill(), version(), modelConfig());

		assertTrue(second.terminal());
		assertEquals("A100 保温箱 200 个", value(second.instance(), "/input/productDescription"));
		assertNull(value(second.instance(), "/runtime/primaryExtractRan"));
		verify(fieldExtractor, times(2)).extract(any(), any(), any(), any(), any(), any());
	}

	private DefaultFlowEngine engine(FlowInstanceService instances) {
		FlowContextMapper contextMapper = new FlowContextMapper();
		return new DefaultFlowEngine(new FlowDefinitionValidator(objectMapper), new FlowNodeExecutorRegistry(), instances,
				mock(FlowEventService.class), contextMapper, new FlowConditionEvaluator(contextMapper),
				new FlowSchemaValidator(), fieldExtractor, mock(ToolInvoker.class),
				mock(AgentExecutionResourceVersionMapper.class), objectMapper, mock(CapabilityGateway.class));
	}

	private DataAgentFlowInstance runningExtractInstance() {
		return DataAgentFlowInstance.builder().id(1L).tenantId("tenant-1").agentId(1L)
				.skillVersionId(2L).skillCode("demand-create").threadId("thread-1").userId("user-1")
				.status(FlowInstanceStatus.RUNNING.name()).currentNodeId("extract-input")
				.contextData("{}").waitingPayload("{}").lockVersion(0).build();
	}

	private DataAgentFlowInstance extractWaitingInstance() {
		String waiting = """
				{"action":"EXTRACT","originNodeId":"extract-input","originNodeType":"extract","originAction":"EXTRACT",
				 "originOutputPath":"/input","originNextNodeId":"collect-customer","recoveryMode":"EXTRACT_THEN_ADVANCE",
				 "requiredPaths":["/input/customerName","/input/productDescription"],
				 "schema":%s,"instruction":"Extract customer and product.",
				 "extraction":{"modelPolicy":"ALWAYS","schemaMode":"FULL","failurePolicy":"WAIT_RETRY"}}
				""".formatted(SCHEMA);
		return DataAgentFlowInstance.builder().id(1L).tenantId("tenant-1").agentId(1L)
				.skillVersionId(2L).skillCode("demand-create").threadId("thread-1").userId("user-1")
				.status(FlowInstanceStatus.WAITING.name()).currentNodeId("extract-input")
				.contextData("{\"input\":{},\"runtime\":{\"contextRevision\":1}}")
				.waitingPayload(waiting).lockVersion(0).build();
	}

	private DataAgentFlowInstance customerWaitingInstance() {
		String waiting = """
				{"action":"COLLECT","requiredPaths":["/input/customerName","/input/productDescription"],
				 "schema":%s,"instruction":"Extract customer and product.",
				 "extraction":{"modelPolicy":"IF_UNRESOLVED","schemaMode":"UNRESOLVED_REQUIRED",
				 "failurePolicy":"WAIT_RETRY"}}
				""".formatted(SCHEMA);
		return DataAgentFlowInstance.builder().id(1L).tenantId("tenant-1").agentId(1L)
				.skillVersionId(2L).skillCode("demand-create").threadId("thread-1").userId("user-1")
				.status(FlowInstanceStatus.WAITING.name()).currentNodeId("collect-customer")
				.contextData("{\"input\":{},\"runtime\":{\"contextRevision\":1}}")
				.waitingPayload(waiting).lockVersion(0).build();
	}

	private DataAgentSkillVersion version() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"extract-input","nodes":[
				 {"id":"extract-input","type":"extract","next":"collect-customer","config":{
				  "outputPath":"/input","schema":%s,
				  "extraction":{"modelPolicy":"ALWAYS","schemaMode":"FULL","failurePolicy":"WAIT_RETRY"}}},
				 {"id":"collect-customer","type":"collect","next":"collect-product","config":{
				  "requiredPaths":["/input/customerName"],"schema":%s,
				  "instruction":"Extract customer and product.",
				  "extraction":{"modelPolicy":"IF_UNRESOLVED","schemaMode":"UNRESOLVED_REQUIRED",
				   "failurePolicy":"WAIT_RETRY"}}},
				 {"id":"collect-product","type":"collect","next":"end","config":{
				  "requiredPaths":["/input/productDescription"],"schema":%s,
				  "instruction":"Extract customer and product.",
				  "extraction":{"modelPolicy":"IF_UNRESOLVED","schemaMode":"UNRESOLVED_REQUIRED",
				   "failurePolicy":"WAIT_RETRY"}}},
				 {"id":"end","type":"end"}]}
				""".formatted(SCHEMA, SCHEMA, SCHEMA);
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

	private Object value(DataAgentFlowInstance instance, String path) {
		try {
			return new FlowContextMapper().get(objectMapper.readValue(instance.getContextData(), MAP_TYPE), path);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private final class InMemoryFlowInstanceService implements FlowInstanceService {

		private DataAgentFlowInstance instance;

		private InMemoryFlowInstanceService(DataAgentFlowInstance instance) {
			this.instance = instance;
		}

		@Override
		public DataAgentFlowInstance loadOrCreate(AgentRequest request, DataAgentSkill skill,
				DataAgentSkillVersion version, String startNode) {
			// 终态实例不再复用（findActive 返回空）：再次办理创建全新实例与空上下文。
			if (instance == null || Set.of(FlowInstanceStatus.CANCELLED.name(), FlowInstanceStatus.SUCCEEDED.name(),
					FlowInstanceStatus.FAILED.name(), FlowInstanceStatus.SUSPENDED.name())
					.contains(instance.getStatus())) {
				instance = DataAgentFlowInstance.builder().id(instance == null ? 1L : instance.getId() + 1)
						.tenantId("tenant-1").agentId(1L).skillVersionId(version.getId()).skillCode(skill.getSkillCode())
						.threadId("thread-1").userId("user-1").status(FlowInstanceStatus.RUNNING.name())
						.currentNodeId(startNode).contextData("{}").waitingPayload("{}").lockVersion(0).build();
			}
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
				if (status == FlowInstanceStatus.WAITING) {
					current.setResumeVersion(current.getResumeVersion() == null ? 1 : current.getResumeVersion() + 1);
				}
				return current;
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		}
	}

}
