/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
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
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SELECT 等待态通用行为回归：空选项死路卡（文案 + 退出动作）、文本搜索意图让路（关键词重查
 * 不做抽取，命中出候选/未命中出提示卡）、非空候选态抽取路径不变、自由文本抽取技术失败的
 * 恢复等待卡、协议校验异常不转换、以及 textSearchEnabled 契约透出。
 */
class DefaultFlowEngineSelectWaitingTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final FlowFieldExtractor fieldExtractor = mock(FlowFieldExtractor.class, CALLS_REAL_METHODS);

	@Test
	void emptyOptionsWaitingUsesConfiguredTextAndAddsCancelAction() {
		InMemoryFlowInstanceService instances = runningSelect(customerFilledEmptyOptionsContext());

		FlowExecutionResult result = engine(instances).execute(request("继续"), skill(), selectVersion("测试文案"),
				modelConfig());

		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertEquals("SELECT", result.message().payload().action());
		assertTrue(result.text().contains("测试文案"));
		assertHasActionType(result.message().actions(), "SELECT");
		assertHasActionType(result.message().actions(), "CANCEL");
	}

	@Test
	void emptyOptionsWaitingFallsBackToGenericTextWithoutConfig() {
		InMemoryFlowInstanceService instances = runningSelect(customerFilledEmptyOptionsContext());

		FlowExecutionResult result = engine(instances).execute(request("继续"), skill(), selectVersion(null),
				modelConfig());

		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertTrue(result.text().contains("当前没有可选项"));
		assertHasActionType(result.message().actions(), "SELECT");
		assertHasActionType(result.message().actions(), "CANCEL");
	}

	@Test
	void selectWaitingExtractionTimeoutReturnsRecoveryWaitingCard() {
		when(fieldExtractor.extract(any(), any(), any(), any()))
				.thenThrow(new FlowExtractionException(FlowExtractionException.TIMEOUT, "extraction timed out", null));
		InMemoryFlowInstanceService instances = selectWaitingInstance(null);

		FlowExecutionResult result = engine(instances).execute(request("客户是云南万绿"), skill(), selectVersion(null),
				modelConfig());

		// 技术失败不再让整个回合 failed 无卡落库：保持 SELECT 等待并返回可重试恢复卡。
		assertEquals(FlowTurnOutcome.FAILED, result.turnOutcome());
		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertTrue(result.text().contains("信息识别暂时失败"));
		assertEquals("SELECT", result.message().payload().action());
		assertEquals(FlowExtractionException.TIMEOUT, result.instance().getErrorCode());
		assertEquals(Integer.valueOf(1), result.instance().getResumeVersion());
		assertNull(result.instance().getInputHash());
	}

	@Test
	void selectWaitingStaleResumeVersionStillThrows() {
		// 协议保护不得被恢复卡吞掉：基于过期版本的选点操作必须原样拒绝。
		InMemoryFlowInstanceService instances = selectWaitingInstance(3);

		CheckedException rejected = assertThrows(CheckedException.class,
				() -> engine(instances).execute(selectRequest(Map.of("resumeVersion", 99)), skill(),
						selectVersion(null), modelConfig()));

		assertTrue(String.valueOf(rejected.getMessage()).contains("过期版本"));
	}

	@Test
	void selectWaitingExposesTextSearchEnabledValue() {
		InMemoryFlowInstanceService instances = runningSelect(customerOptionsContext());

		FlowExecutionResult result = engine(instances).execute(request("继续"), skill(), textSearchSelectVersion(),
				modelConfig());

		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertEquals("SELECT", result.message().payload().action());
		assertEquals(Boolean.TRUE, result.message().payload().values().get("textSearchEnabled"));
	}

	@Test
	void emptyOptionsTextSearchHitShowsCandidateCardWithoutExtraction() {
		InMemoryFlowInstanceService instances = textSearchWaitingInstance();
		AgentRequest request = textSearchRequest("云南万绿");

		FlowExecutionResult result = searchEngine(instances,
				gatewayReturning(customers("云南万绿", "C001", "上海箱箱物流", "C002"))).execute(request, skill(),
						textSearchSelectVersion(), modelConfig());

		// 空选项 + 文本搜索意图：跳过 LLM 抽取，关键词重查命中后直接出候选卡。
		verify(fieldExtractor, never()).extract(any(), any(), any(), any());
		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertEquals("SELECT", result.message().payload().action());
		assertEquals(2, result.message().payload().options().size());
		assertTrue(request.getFlowTextSearchIntent().consumed());
	}

	@Test
	void emptyOptionsWebKeywordWithoutPresetIntentBuildsIntentAndSearches() {
		InMemoryFlowInstanceService instances = textSearchWaitingInstance();
		// WEB 场景：无 TEXT_COMMANDS 能力、无预置意图——引擎自建搜索意图后走关键词重查。
		AgentRequest webRequest = request("云南万绿");

		FlowExecutionResult result = searchEngine(instances,
				gatewayReturning(customers("云南万绿", "C001", "上海箱箱物流", "C002"))).execute(webRequest, skill(),
						textSearchSelectVersion(), modelConfig());

		verify(fieldExtractor, never()).extract(any(), any(), any(), any());
		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertEquals(2, result.message().payload().options().size());
		assertNotNull(webRequest.getFlowTextSearchIntent());
		assertEquals("云南万绿", webRequest.getFlowTextSearchIntent().keyword());
		assertEquals("select-customer", webRequest.getFlowTextSearchIntent().waitingNodeId());
		assertTrue(webRequest.getFlowTextSearchIntent().consumed());
	}

	@Test
	void emptyOptionsWebKeywordMissShowsNotFoundCard() {
		InMemoryFlowInstanceService instances = textSearchWaitingInstance();
		AgentRequest webRequest = request("上海箱箱物流科技有限公司");

		FlowExecutionResult result = searchEngine(instances, gatewayReturning(List.of())).execute(webRequest, skill(),
				textSearchSelectVersion(), modelConfig());

		// WEB 未命中：毫秒级提示卡（带取消动作），不再出现抽取超时。
		verify(fieldExtractor, never()).extract(any(), any(), any(), any());
		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertTrue(result.text().contains("未找到匹配客户"));
		assertHasActionType(result.message().actions(), "CANCEL");
		assertTrue(webRequest.getFlowTextSearchIntent().consumed());
	}

	@Test
	void emptyOptionsTextSearchMissShowsNotFoundCardWithoutExtraction() {
		InMemoryFlowInstanceService instances = textSearchWaitingInstance();
		AgentRequest request = textSearchRequest("上海箱箱物流科技有限公司");

		FlowExecutionResult result = searchEngine(instances, gatewayReturning(List.of())).execute(request, skill(),
				textSearchSelectVersion(), modelConfig());

		// 未命中走既有 notFoundText 提示卡（毫秒级），卡片带取消流程按钮，不再出现抽取超时报错。
		verify(fieldExtractor, never()).extract(any(), any(), any(), any());
		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertTrue(result.text().contains("未找到匹配客户"));
		assertHasActionType(result.message().actions(), "CANCEL");
		assertTrue(request.getFlowTextSearchIntent().consumed());
	}

	@Test
	void nonEmptyOptionsWithSearchIntentKeepsExtractionPath() {
		InMemoryFlowInstanceService instances = customerOptionsSearchWaitingInstance();
		when(fieldExtractor.extract(any(), any(), any(), any())).thenReturn(Map.of("customerName", "云南万绿"));
		AgentRequest request = textSearchRequest("云南万绿");

		FlowExecutionResult result = searchEngine(instances,
				gatewayReturning(customers("云南万绿", "C001", "上海箱箱物流", "C002"))).execute(request, skill(),
						textSearchSelectVersion(), modelConfig());

		// 非空候选态维持现状：抽取先识别语义（键入取消等），搜索随后执行。
		verify(fieldExtractor, times(1)).extract(any(), any(), any(), any());
		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertEquals("SELECT", result.message().payload().action());
	}

	@Test
	void emptyOptionsWithoutTextSearchStillExtracts() {
		InMemoryFlowInstanceService instances = selectWaitingInstance(null);
		when(fieldExtractor.extract(any(), any(), any(), any())).thenReturn(Map.of("customerName", "云南万绿"));

		FlowExecutionResult result = engine(instances).execute(request("客户是云南万绿"), skill(),
				textSearchSelectVersion(), modelConfig());

		// 未启用 textSearch 的空选项态（无可搜索的 resolver）：保留既有抽取链路（多信息/闲聊语义识别）。
		verify(fieldExtractor, times(1)).extract(any(), any(), any(), any());
		assertEquals(FlowInstanceStatus.SUCCEEDED.name(), result.instance().getStatus());
	}

	private DefaultFlowEngine engine(FlowInstanceService instances) {
		FlowContextMapper contextMapper = new FlowContextMapper();
		return new DefaultFlowEngine(new FlowDefinitionValidator(objectMapper), new FlowNodeExecutorRegistry(),
				instances, mock(FlowEventService.class), contextMapper, new FlowConditionEvaluator(contextMapper),
				new FlowSchemaValidator(), fieldExtractor, mock(ToolInvoker.class),
				mock(AgentExecutionResourceVersionMapper.class), objectMapper, mock(CapabilityGateway.class));
	}

	private void assertHasActionType(List<AgentUiMessage.Action> actions, String expectedType) {
		assertTrue(actions.stream().anyMatch(action -> expectedType.equalsIgnoreCase(action.type())),
				() -> "expected action type " + expectedType + " but got " + actions);
	}

	private InMemoryFlowInstanceService runningSelect(String context) {
		DataAgentFlowInstance instance = DataAgentFlowInstance.builder().id(1L).tenantId("tenant-1").agentId(1L)
				.skillVersionId(2L).skillCode("demand-create").threadId("thread-1").userId("user-1")
				.status(FlowInstanceStatus.RUNNING.name()).currentNodeId("select-customer")
				.contextData(context).waitingPayload("{}").lockVersion(0).build();
		return new InMemoryFlowInstanceService(instance);
	}

	private InMemoryFlowInstanceService selectWaitingInstance(Integer resumeVersion) {
		String waiting = """
				{"action":"SELECT","targetPath":"/resolved/customer","optionsPath":"/resolved/customers/items",
				 "options":[],
				 "schema":{"type":"object","properties":{"customerName":{"type":"string"}}},
				 "instruction":"Extract customer name."}
				""";
		DataAgentFlowInstance instance = DataAgentFlowInstance.builder().id(1L).tenantId("tenant-1").agentId(1L)
				.skillVersionId(2L).skillCode("demand-create").threadId("thread-1").userId("user-1")
				.status(FlowInstanceStatus.WAITING.name()).currentNodeId("select-customer")
				.contextData("{\"input\":{},\"runtime\":{\"contextRevision\":1}}")
				.waitingPayload(waiting).lockVersion(0).resumeVersion(resumeVersion).build();
		return new InMemoryFlowInstanceService(instance);
	}

	private String customerFilledEmptyOptionsContext() {
		return """
				{"input":{"companyId":"CN102101","companyName":"云南万绿"},
				 "resolved":{"customers":{"items":[]}},
				 "runtime":{"contextRevision":1}}
				""";
	}

	private String customerOptionsContext() {
		return """
				{"resolved":{"customers":{"items":[
				 {"customerName":"云南万绿","customerId":"C001"},
				 {"customerName":"上海箱箱物流","customerId":"C002"}]}},
				 "runtime":{"contextRevision":1}}
				""";
	}

	private DataAgentSkillVersion selectVersion(String emptyOptionsText) {
		String config = emptyOptionsText == null ? "" : (",\"emptyOptionsText\":\"" + emptyOptionsText + "\"");
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"select-customer","nodes":[
				 {"id":"select-customer","type":"select","next":"end","config":{
				  "optionsPath":"/resolved/customers/items","targetPath":"/resolved/customer",
				  "emptyNext":"collect-customer"%s}},
				 {"id":"collect-customer","type":"collect","next":"end","config":{
				  "requiredPaths":["/input/companyName"]}},
				 {"id":"end","type":"end"}]}
				""".formatted(config);
		return DataAgentSkillVersion.builder().id(2L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion textSearchSelectVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"select-customer","nodes":[
				 {"id":"select-customer","type":"select","next":"end","config":{
				  "optionsPath":"/resolved/customers/items","targetPath":"/resolved/customer",
				  "textSearch":{"enabled":true,"matchPaths":["/customerName"],"argumentName":"keyword",
				   "notFoundText":"未找到匹配客户，请换一个名称或编码。","resolverNode":"resolve-customer"}}},
				 {"id":"resolve-customer","type":"resolve","next":"select-customer","config":{
				  "resourceVersionId":3,"resourceKey":"demo.echo.customerOptions",
				  "outputPath":"/resolved/customers"}},
				 {"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(2L).skillId(1L).flowDefinition(definition).build();
	}

	private InMemoryFlowInstanceService textSearchWaitingInstance() {
		String waiting = """
				{"action":"SELECT","targetPath":"/resolved/customer","optionsPath":"/resolved/customers/items",
				 "options":[],
				 "schema":{"type":"object","properties":{"customerName":{"type":"string"}}},
				 "instruction":"Extract customer name.",
				 "textSearch":{"enabled":true,"matchPaths":["/customerName"],"argumentName":"keyword",
				  "notFoundText":"未找到匹配客户，请换一个名称或编码。","resolverNode":"resolve-customer"},
				 "uiActions":[{"type":"SELECT","label":"选择","actionId":"select-option"},
				  {"type":"CANCEL","label":"取消流程","actionId":"cancel-flow"}]}
				""";
		DataAgentFlowInstance instance = DataAgentFlowInstance.builder().id(1L).tenantId("tenant-1").agentId(1L)
				.skillVersionId(2L).skillCode("demand-create").threadId("thread-1").userId("user-1")
				.status(FlowInstanceStatus.WAITING.name()).currentNodeId("select-customer")
				.contextData("{\"runtime\":{\"contextRevision\":1}}")
				.waitingPayload(waiting).lockVersion(0).resumeVersion(1).build();
		return new InMemoryFlowInstanceService(instance);
	}

	private InMemoryFlowInstanceService customerOptionsSearchWaitingInstance() {
		String waiting = """
				{"action":"SELECT","targetPath":"/resolved/customer","optionsPath":"/resolved/customers/items",
				 "options":[{"label":"客户A","value":"option-1"},{"label":"客户B","value":"option-2"}],
				 "schema":{"type":"object","properties":{"customerName":{"type":"string"}}},
				 "instruction":"Extract customer name.",
				 "textSearch":{"enabled":true,"matchPaths":["/customerName"],"argumentName":"keyword",
				  "notFoundText":"未找到匹配客户，请换一个名称或编码。","resolverNode":"resolve-customer"},
				 "uiActions":[{"type":"SELECT","label":"选择","actionId":"select-option"}]}
				""";
		DataAgentFlowInstance instance = DataAgentFlowInstance.builder().id(1L).tenantId("tenant-1").agentId(1L)
				.skillVersionId(2L).skillCode("demand-create").threadId("thread-1").userId("user-1")
				.status(FlowInstanceStatus.WAITING.name()).currentNodeId("select-customer")
				.contextData("{\"runtime\":{\"contextRevision\":1}}")
				.waitingPayload(waiting).lockVersion(0).resumeVersion(1).build();
		return new InMemoryFlowInstanceService(instance);
	}

	private AgentRequest textSearchRequest(String keyword) {
		AgentRequest request = request(keyword);
		request.setFlowTextSearchIntent(new FlowTextSearchIntent("select-customer", "resolve-customer",
				"/resolved/customer", keyword, "keyword", false));
		return request;
	}

	private DefaultFlowEngine searchEngine(FlowInstanceService instances, CapabilityGateway gateway) {
		FlowContextMapper contextMapper = new FlowContextMapper();
		AgentExecutionResourceVersionMapper resourceVersionMapper = mock(AgentExecutionResourceVersionMapper.class);
		when(resourceVersionMapper.findPublished(3L)).thenReturn(publishedCustomerOptionsVersion());
		return new DefaultFlowEngine(new FlowDefinitionValidator(objectMapper), new FlowNodeExecutorRegistry(),
				instances, mock(FlowEventService.class), contextMapper, new FlowConditionEvaluator(contextMapper),
				new FlowSchemaValidator(), fieldExtractor, mock(ToolInvoker.class), resourceVersionMapper,
				objectMapper, gateway);
	}

	private CapabilityGateway gatewayReturning(List<Map<String, Object>> items) {
		CapabilityGateway gateway = mock(CapabilityGateway.class);
		when(gateway.invoke(any(), any())).thenReturn(ResultEnvelope.builder()
			.status(ResultEnvelope.STATUS_SUCCESS)
			.schemaVersion(ResultEnvelope.SCHEMA_VERSION_V1)
			.data(Map.of("items", items))
			.build());
		return gateway;
	}

	private AgentExecutionResourceVersion publishedCustomerOptionsVersion() {
		String snapshot = """
				{"resourceKey":"demo.echo.customerOptions","toolName":"customerOptions"}
				""";
		return AgentExecutionResourceVersion.builder().id(3L).resourceKey("demo.echo.customerOptions")
			.status("PUBLISHED").snapshot(snapshot).build();
	}

	private List<Map<String, Object>> customers(String... nameAndIdPairs) {
		java.util.ArrayList<Map<String, Object>> items = new java.util.ArrayList<>();
		for (int index = 0; index + 1 < nameAndIdPairs.length; index += 2) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("customerName", nameAndIdPairs[index]);
			item.put("customerId", nameAndIdPairs[index + 1]);
			items.add(item);
		}
		return items;
	}

	private DataAgentSkill skill() {
		return DataAgentSkill.builder().id(1L).skillCode("demand-create").build();
	}

	private AgentRequest request(String query) {
		return AgentRequest.builder().agentId("1").threadId("thread-1").runtimeRequestId("runtime-1")
				.tenantIdSnapshot("tenant-1").userIdSnapshot("user-1").flowInstanceId("1").query(query).build();
	}

	private AgentRequest selectRequest(Map<String, Object> actionPayload) {
		AgentRequest request = request(null);
		request.setFlowAction(new FlowAction("select-1", "SELECT", "option-1", actionPayload));
		return request;
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
