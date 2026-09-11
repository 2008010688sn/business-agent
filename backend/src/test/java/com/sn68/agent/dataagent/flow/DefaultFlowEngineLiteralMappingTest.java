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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.mockito.ArgumentCaptor;

class DefaultFlowEngineLiteralMappingTest {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private static final String SCHEMA = """
			{"type":"object","properties":{
			 "type":{"type":"string","enum":["0","1","3","5"]},
			 "businessType":{"type":"string","enum":["1","2"]}}}
			""";

	private static final String LITERAL_MAPPINGS = """
			{"type":{"0":["收箱","收箱需求","RECEIVER_BOX"],
			          "1":["调拨","调拨需求","ALLOT"],
			          "3":["送箱","送箱需求","送箱需求单","SEND_BOX"],
			          "5":["模组调拨","模组调拨需求","MODULE_ALLOT"]},
			 "businessType":{"1":["年租","年租业务","BUSSINESS_TYPE_YEAR"],
			                 "2":["趟租","趟租业务","BUSSINESS_TYPE_SALE"]}}
			""";

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final FlowFieldExtractor fieldExtractor = mock(FlowFieldExtractor.class, CALLS_REAL_METHODS);

	@Test
	void explicitChineseTypesAdvanceWithoutCallingModel() {
		InMemoryFlowInstanceService instances = instances();
		DefaultFlowEngine engine = engine(instances);

		FlowExecutionResult result = engine.execute(request("送箱需求 趟租"), skill(), version(), modelConfig());

		assertTrue(result.terminal());
		assertEquals("3", value(result.instance(), "/input/type"));
		assertEquals("2", value(result.instance(), "/input/businessType"));
		verifyNoInteractions(fieldExtractor);
	}

	@Test
	void explicitEnglishEnumsAdvanceWithoutCallingModel() {
		InMemoryFlowInstanceService instances = instances();

		FlowExecutionResult result = engine(instances).execute(request("SEND_BOX BUSSINESS_TYPE_SALE"), skill(),
				version(), modelConfig());

		assertTrue(result.terminal());
		assertEquals("3", value(result.instance(), "/input/type"));
		assertEquals("2", value(result.instance(), "/input/businessType"));
		verifyNoInteractions(fieldExtractor);
	}

	@Test
	void longerCompositeAliasWinsOverContainedAlias() {
		InMemoryFlowInstanceService instances = instances();

		FlowExecutionResult result = engine(instances).execute(request("模组调拨 趟租"), skill(), version(),
				modelConfig());

		assertTrue(result.terminal());
		assertEquals("5", value(result.instance(), "/input/type"));
		assertEquals("2", value(result.instance(), "/input/businessType"));
		verifyNoInteractions(fieldExtractor);
	}

	@Test
	void conflictingTypeFallsBackToModelAndLiteralBusinessWins() {
		when(fieldExtractor.extract(any(), any(), any(), any()))
				.thenReturn(Map.of("type", "收箱"));
		InMemoryFlowInstanceService instances = instances();

		FlowExecutionResult result = engine(instances).execute(request("送箱改收箱，趟租"), skill(), version(),
				modelConfig());

		assertTrue(result.terminal());
		assertEquals("0", value(result.instance(), "/input/type"));
		assertEquals("2", value(result.instance(), "/input/businessType"));
		verify(fieldExtractor).extract(any(), any(), any(), any());
	}

	@Test
	void negatedTypeDoesNotBecomeProvidedWhenModelReturnsNothing() {
		when(fieldExtractor.extract(any(), any(), any(), any())).thenReturn(Map.of());
		InMemoryFlowInstanceService instances = instances();

		FlowExecutionResult result = engine(instances).execute(request("不要送箱，趟租"), skill(), version(),
				modelConfig());

		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertNull(value(result.instance(), "/input/type"));
		assertEquals("2", value(result.instance(), "/input/businessType"));
		assertTrue(result.message().actions().isEmpty());
		verify(fieldExtractor).extract(any(), any(), any(), any());
	}

	@Test
	void modelLabelsAreNormalizedAndUnknownMappedValuesAreDiscarded() {
		when(fieldExtractor.extract(any(), any(), any(), any()))
				.thenReturn(Map.of("type", "送箱需求", "businessType", "趟租"));
		InMemoryFlowInstanceService normalizedInstances = instances();

		FlowExecutionResult normalized = engine(normalizedInstances).execute(request("按常用方案"), skill(), version(),
				modelConfig());

		assertTrue(normalized.terminal());
		assertEquals("3", value(normalized.instance(), "/input/type"));
		assertEquals("2", value(normalized.instance(), "/input/businessType"));

		when(fieldExtractor.extract(any(), any(), any(), any()))
				.thenReturn(Map.of("type", "未知类型", "businessType", "趟租"));
		InMemoryFlowInstanceService invalidInstances = instances();

		FlowExecutionResult invalid = engine(invalidInstances).execute(request("使用未配置类型"), skill(), version(),
				modelConfig());

		assertEquals(FlowInstanceStatus.WAITING.name(), invalid.instance().getStatus());
		assertNull(value(invalid.instance(), "/input/type"));
		assertEquals("2", value(invalid.instance(), "/input/businessType"));
	}

	@Test
	void extractionFailureKeepsLiteralMappingsForRetry() {
		when(fieldExtractor.extract(any(), any(), any(), any()))
				.thenThrow(new FlowExtractionException(FlowExtractionException.TIMEOUT, "timeout", null));
		InMemoryFlowInstanceService instances = instances();

		FlowExecutionResult result = engine(instances).execute(request("暂未识别的表达"), skill(), version(),
				modelConfig());

		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		// 技术失败按 FAILED 上抛，与业务字段缺失的 WAITING 澄清区分；实例仍保持 WAITING 可恢复。
		assertEquals(FlowTurnOutcome.FAILED, result.turnOutcome());
		assertEquals(FlowExtractionException.TIMEOUT, result.instance().getErrorCode());
		assertTrue(result.instance().getWaitingPayload().contains("literalMappings"));
		assertFalse(result.instance().getWaitingPayload().contains("extraction"));
		assertEquals("REASK", result.message().payload().action());
		assertTrue(Boolean.TRUE.equals(result.message().payload().values().get("retryable")));
		assertFalse(result.message().payload().values().containsKey("literalMappings"));
		assertFalse(result.message().payload().values().containsKey("schema"));
		assertFalse(result.message().payload().values().containsKey("extraction"));
		assertTrue(result.message().actions().isEmpty());
	}

	@Test
	void explicitTypesLimitModelSchemaToUnresolvedCustomerFields() {
		when(fieldExtractor.extract(any(), any(), any(), any())).thenReturn(Map.of("companyName", "云南万绿"));
		InMemoryFlowInstanceService instances = runningInstance("extract-input");

		FlowExecutionResult result = engine(instances).execute(
				request("给云南万绿客户下送箱需求单 趟租业务"), skill(), extractInputVersion("WAIT_RETRY"), modelConfig());

		ArgumentCaptor<Map<String, Object>> schemaCaptor = ArgumentCaptor.forClass(Map.class);
		ArgumentCaptor<Map<String, Object>> currentValuesCaptor = ArgumentCaptor.forClass(Map.class);
		verify(fieldExtractor).extract(any(), any(), schemaCaptor.capture(), any(), any(), currentValuesCaptor.capture());
		Set<String> modelFields = new java.util.LinkedHashSet<>(
				Map.copyOf((Map<String, Object>) schemaCaptor.getValue().get("properties")).keySet());
		assertTrue(modelFields.contains("companyName"));
		modelFields.removeAll(Set.of("turnAction", "turnReply", "listLabel"));
		assertEquals(Set.of("companyName"), modelFields);
		assertEquals("3", currentValuesCaptor.getValue().get("type"));
		assertEquals("2", currentValuesCaptor.getValue().get("businessType"));
		assertFalse(currentValuesCaptor.getValue().containsKey("companyId"));
		assertTrue(result.terminal());
		assertEquals("3", value(result.instance(), "/input/type"));
		assertEquals("2", value(result.instance(), "/input/businessType"));
		assertEquals("云南万绿", value(result.instance(), "/input/companyName"));
	}

	@Test
	void requiredAnyPathAdvancesWhenCompanyNameIsResolved() {
		when(fieldExtractor.extract(any(), any(), any(), any())).thenReturn(Map.of("companyName", "云南万绿"));
		InMemoryFlowInstanceService instances = customerWaitingInstance();

		FlowExecutionResult result = engine(instances).execute(request("云南万绿客户"), skill(),
				collectCustomerVersion(), modelConfig());

		assertTrue(result.terminal());
		assertEquals("云南万绿", value(result.instance(), "/input/companyName"));
		assertNull(value(result.instance(), "/input/companyId"));
	}

	@Test
	void firstOrderMessageKeepsCustomerAndArrivalTimeThenOnlyAsksForMissingBusinessField() {
		when(fieldExtractor.extract(any(), any(), any(), any()))
			.thenReturn(Map.of("companyName", "云南万绿", "arrivalTime", "2026-08-04"));
		InMemoryFlowInstanceService instances = runningInstance("extract-input");

		FlowExecutionResult result = engine(instances).execute(
				request("下单：送箱 趟租 客户是云南万绿 到货时间今天"), skill(), firstOrderTurnVersion(), modelConfig());

		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertEquals("云南万绿", value(result.instance(), "/input/companyName"));
		assertEquals("2026-08-04", value(result.instance(), "/input/arrivalTime"));
		assertEquals("3", value(result.instance(), "/input/type"));
		assertEquals("2", value(result.instance(), "/input/businessType"));
		assertEquals("COLLECT", result.message().payload().action());
		assertTrue(result.text().contains("商品及数量"));
		assertFalse(result.text().contains("客户"));
		verify(fieldExtractor).extract(any(), any(), any(), any(), any(), any());
	}

	@Test
	void extractTimeoutPreservesLiteralsAndContinuesWhenRequiredPathsFilled() {
		when(fieldExtractor.extract(any(), any(), any(), any()))
				.thenThrow(new FlowExtractionException(FlowExtractionException.TIMEOUT, "timeout", null));
		InMemoryFlowInstanceService instances = runningInstance("extract-input");

		FlowExecutionResult result = engine(instances).execute(request("送箱需求 趟租业务"), skill(),
				extractInputVersion("WAIT_RETRY"), modelConfig());

		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertEquals("collect-customer", result.instance().getCurrentNodeId());
		assertEquals("3", value(result.instance(), "/input/type"));
		assertEquals("2", value(result.instance(), "/input/businessType"));
		assertEquals("COLLECT", result.message().payload().action());
		assertFalse(result.text().contains("客户、到货时间"));
	}

	@Test
	void secondExtractionNodeDoesNotExceedTheSingleExtractorInvocationBudget() {
		when(fieldExtractor.extract(any(), any(), any(), any())).thenReturn(Map.of("companyName", "云南万绿"));
		InMemoryFlowInstanceService instances = runningInstance("extract-one");

		FlowExecutionResult result = engine(instances).execute(request("客户是云南万绿"), skill(), twoExtractVersion(),
				modelConfig());

		assertTrue(result.terminal());
		verify(fieldExtractor, times(1)).extract(any(), any(), any(), any(), any(), any());
	}

	@Test
	void repeatedExtractionFailuresEscalateToChangeModelWithoutLeakingInternalPayload() {
		when(fieldExtractor.extract(any(), any(), any(), any()))
				.thenThrow(new FlowExtractionException(FlowExtractionException.TIMEOUT, "timeout", null));
		InMemoryFlowInstanceService instances = runningInstance("extract-input");

		FlowExecutionResult first = engine(instances).execute(request("客户是云南万绿"), skill(),
				extractInputVersion("WAIT_RETRY"), modelConfig());
		FlowExecutionResult second = engine(instances).execute(request("客户是云南万绿"), skill(),
				extractInputVersion("WAIT_RETRY"), modelConfig());

		assertEquals("REASK", first.message().payload().action());
		assertEquals("CHANGE_MODEL", second.message().payload().action());
		assertEquals(FlowTurnOutcome.FAILED, second.turnOutcome());
		assertTrue(second.instance().getWaitingPayload().contains("blockedModelConfigId"));
		assertFalse(second.message().payload().values().containsKey("blockedModelConfigId"));
		assertFalse(second.message().payload().values().containsKey("requiredPaths"));
		verify(fieldExtractor, times(2)).extract(any(), any(), any(), any(), any(), any());
	}

	@Test
	void unchangedBlockedModelDoesNotCallExtractorAndChangedModelResumesTheOriginalExtract() {
		when(fieldExtractor.extract(any(), any(), any(), any())).thenReturn(Map.of("companyName", "云南万绿"));
		InMemoryFlowInstanceService instances = changeModelWaitingInstance();

		FlowExecutionResult blocked = engine(instances).execute(request("客户是云南万绿"), skill(),
				extractInputVersion("WAIT_RETRY"), modelConfig());

		assertEquals("CHANGE_MODEL", blocked.message().payload().action());
		assertEquals(FlowTurnOutcome.WAITING, blocked.turnOutcome());
		verifyNoInteractions(fieldExtractor);

		FlowExecutionResult resumed = engine(instances).execute(request("客户是云南万绿"), skill(),
				extractInputVersion("WAIT_RETRY"), ModelConfigDTO.builder().id(2L).modelName("replacement").build());

		assertTrue(resumed.terminal());
		assertEquals("云南万绿", value(resumed.instance(), "/input/companyName"));
		verify(fieldExtractor).extract(any(), any(), any(), any(), any(), any());
	}

	@Test
	void collectCustomerKeepsLaterSlotsFromPackedUtterance() {
		when(fieldExtractor.extract(any(), any(), any(), any())).thenReturn(Map.of("companyName", "箱箱"));
		InMemoryFlowInstanceService instances = customerWaitingInstance();

		FlowExecutionResult result = engine(instances).execute(request("送箱 趟租 客户是箱箱 到货时间今天"), skill(),
				extractInputVersion("WAIT_RETRY"), modelConfig());

		assertEquals("3", value(result.instance(), "/input/type"));
		assertEquals("2", value(result.instance(), "/input/businessType"));
		assertEquals("箱箱", value(result.instance(), "/input/companyName"));
		assertFalse(result.text().contains("需求类型"));
		assertFalse(result.text().contains("业务类型"));
		assertTrue(result.terminal());
	}

	@Test
	void collectCustomerKeepsLaterSlotsWhenModelMarksAsk() {
		when(fieldExtractor.extract(any(), any(), any(), any()))
				.thenReturn(Map.of("turnAction", "ask", "turnReply", "可以办理。", "companyName", "箱箱"));
		InMemoryFlowInstanceService instances = customerWaitingInstance();

		FlowExecutionResult result = engine(instances).execute(request("能下单吗，送箱 趟租 客户是箱箱"), skill(),
				extractInputVersion("WAIT_RETRY"), modelConfig());

		assertEquals("3", value(result.instance(), "/input/type"));
		assertEquals("2", value(result.instance(), "/input/businessType"));
		assertEquals("箱箱", value(result.instance(), "/input/companyName"));
		assertTrue(result.terminal());
	}

	@Test
	void collectCustomerKeepsPartialLaterSlotsAndStillWaitsForName() {
		when(fieldExtractor.extract(any(), any(), any(), any())).thenReturn(Map.of());
		InMemoryFlowInstanceService instances = customerWaitingInstance();

		FlowExecutionResult result = engine(instances).execute(request("送箱 趟租"), skill(),
				extractInputVersion("WAIT_RETRY"), modelConfig());

		assertEquals("3", value(result.instance(), "/input/type"));
		assertEquals("2", value(result.instance(), "/input/businessType"));
		assertNull(value(result.instance(), "/input/companyName"));
		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertEquals("collect-customer", result.instance().getCurrentNodeId());
	}

	@Test
	void oralParaphraseFillsRemainingSlotsThroughWidenedModelSchema() {
		when(fieldExtractor.extract(any(), any(), any(), any()))
				.thenReturn(Map.of("companyName", "箱箱", "type", "送箱", "businessType", "趟租"));
		InMemoryFlowInstanceService instances = customerWaitingInstance();

		FlowExecutionResult result = engine(instances).execute(request("给箱箱送一趟，今天到"), skill(),
				extractInputVersion("WAIT_RETRY"), modelConfig());

		assertEquals("箱箱", value(result.instance(), "/input/companyName"));
		ArgumentCaptor<Map<String, Object>> schemaCaptor = ArgumentCaptor.forClass(Map.class);
		verify(fieldExtractor).extract(any(), any(), schemaCaptor.capture(), any(), any(), any());
		Set<String> modelFields = new java.util.LinkedHashSet<>(
				Map.copyOf((Map<String, Object>) schemaCaptor.getValue().get("properties")).keySet());
		assertTrue(modelFields.contains("companyName"));
	}

	@Test
	void configuredPathsDoesNotSendUnlistedFlowFieldsToTheModel() {
		when(fieldExtractor.extract(any(), any(), any(), any())).thenReturn(Map.of("name", "张三"));
		InMemoryFlowInstanceService instances = runningInstance("extract-input");

		engine(instances).execute(request("帮张三办，类型甲"), skill(), configuredPathsWithLaterSlotsVersion(),
				modelConfig());

		ArgumentCaptor<Map<String, Object>> schemaCaptor = ArgumentCaptor.forClass(Map.class);
		verify(fieldExtractor).extract(any(), any(), schemaCaptor.capture(), any(), any(), any());
		Set<String> modelFields = new java.util.LinkedHashSet<>(
				Map.copyOf((Map<String, Object>) schemaCaptor.getValue().get("properties")).keySet());
		modelFields.removeAll(Set.of("turnAction", "turnReply", "listLabel"));
		assertTrue(modelFields.contains("name"));
		assertFalse(modelFields.contains("qty"));
		assertFalse(modelFields.contains("note"));
	}

	@Test
	void failedExtractFallsThroughToCollectWaitingWithoutSecondExtraction() {
		// 新语义：extract 节点（含模型失败降级继续）已消费整句输入，collect 不再对同一句重复抽取，
		// 缺失字段由 collect 等待卡向用户追问（原先“预算不烧、collect 补抽”的路径被主抽取标记卫语句取代）。
		when(fieldExtractor.extract(any(), any(), any(), any()))
				.thenThrow(new FlowExtractionException(FlowExtractionException.TRUNCATED, "truncated", null));
		InMemoryFlowInstanceService instances = runningInstance("extract-input");

		FlowExecutionResult result = engine(instances).execute(request("帮张三办，类型甲，数量2"), skill(),
				genericStaggeredVersion(), modelConfig());

		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertEquals("collect-name", result.instance().getCurrentNodeId());
		assertEquals("COLLECT", result.message().payload().action());
		assertNull(value(result.instance(), "/input/name"));
		assertEquals(Boolean.TRUE, value(result.instance(), "/runtime/primaryExtractRan"));
		verify(fieldExtractor, times(1)).extract(any(), any(), any(), any(), any(), any());
	}

	@Test
	void overlappingRequiredPathsAreDedupedInMissingSlots() {
		InMemoryFlowInstanceService instances = runningInstance("collect-name");

		FlowExecutionResult result = engine(instances).execute(request(null), skill(), overlappingRequiredVersion(),
				modelConfig());

		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		List<Map<String, Object>> missing = missingSlots(result.instance());
		assertEquals(List.of("/input/name", "/input/code"),
				missing.stream().map(slot -> String.valueOf(slot.get("path"))).toList());
	}

	@Test
	void genericFlowCollectsAllSlotsFromFirstPackedUtterance() {
		when(fieldExtractor.extract(any(), any(), any(), any()))
				.thenReturn(Map.of("name", "张三", "kind", "甲", "qty", 2));
		InMemoryFlowInstanceService instances = runningInstance("extract-input");

		FlowExecutionResult result = engine(instances).execute(request("帮张三办，类型甲，数量2"), skill(),
				genericStaggeredVersion(), modelConfig());

		assertEquals("张三", value(result.instance(), "/input/name"));
		assertEquals("A", value(result.instance(), "/input/kind"));
		assertEquals(2, value(result.instance(), "/input/qty"));
		assertTrue(result.terminal());
	}

	@Test
	void genericFlowCollectsLaterSlotsWhileWaitingOnName() {
		when(fieldExtractor.extract(any(), any(), any(), any())).thenReturn(Map.of("name", "张三", "qty", 2));
		InMemoryFlowInstanceService instances = genericNameWaitingInstance();

		FlowExecutionResult result = engine(instances).execute(request("数量2，类型就甲吧，人是张三"), skill(),
				genericStaggeredVersion(), modelConfig());

		assertEquals("张三", value(result.instance(), "/input/name"));
		assertEquals("A", value(result.instance(), "/input/kind"));
		assertEquals(2, value(result.instance(), "/input/qty"));
		assertTrue(result.terminal());
	}

	@Test
	void dynamicCollectionPromptOnlyShowsActuallyMissingField() {
		DataAgentFlowInstance instance = DataAgentFlowInstance.builder().id(1L).tenantId("tenant-1").agentId(1L)
				.skillVersionId(2L).skillCode("demand-create").threadId("thread-1").userId("user-1")
				.status(FlowInstanceStatus.RUNNING.name()).currentNodeId("collect-history-key")
				.contextData("{\"input\":{\"type\":\"3\"},\"runtime\":{\"contextRevision\":1}}")
				.waitingPayload("{}").lockVersion(0).build();

		FlowExecutionResult result = engine(new InMemoryFlowInstanceService(instance)).execute(request(null), skill(),
				dynamicPromptVersion(), modelConfig());

		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertEquals("请补充业务类型，例如趟租或年租。", result.message().content().text());
		assertFalse(result.message().content().text().contains("需求类型"));
		assertFalse(result.instance().getWaitingPayload().contains("collectionPresentation"));
	}

	@Test
	void dynamicCollectionPromptKeepsRequiredPathOrder() {
		InMemoryFlowInstanceService instances = runningInstance("collect-history-key");

		FlowExecutionResult result = engine(instances).execute(request(null), skill(), dynamicPromptVersion(), modelConfig());

		assertEquals("请补充需求类型，例如送箱或收箱。\n请补充业务类型，例如趟租或年租。",
				result.message().content().text());
	}

	private DefaultFlowEngine engine(FlowInstanceService instances) {
		FlowContextMapper contextMapper = new FlowContextMapper();
		return new DefaultFlowEngine(new FlowDefinitionValidator(objectMapper), new FlowNodeExecutorRegistry(), instances,
				mock(FlowEventService.class), contextMapper, new FlowConditionEvaluator(contextMapper),
				new FlowSchemaValidator(), fieldExtractor, mock(ToolInvoker.class),
				mock(AgentExecutionResourceVersionMapper.class), objectMapper, mock(CapabilityGateway.class));
	}

	private InMemoryFlowInstanceService instances() {
		String waiting = """
				{"action":"COLLECT","requiredPaths":["/input/type","/input/businessType"],
				 "schema":%s,"literalMappings":%s,
				 "instruction":"Extract demand type and business type."}
				""".formatted(SCHEMA, LITERAL_MAPPINGS);
		DataAgentFlowInstance instance = DataAgentFlowInstance.builder().id(1L).tenantId("tenant-1").agentId(1L)
				.skillVersionId(2L).skillCode("demand-create").threadId("thread-1").userId("user-1")
				.status(FlowInstanceStatus.WAITING.name()).currentNodeId("collect-history-key")
				.contextData("{\"input\":{},\"runtime\":{\"contextRevision\":1}}")
				.waitingPayload(waiting).lockVersion(0).build();
		return new InMemoryFlowInstanceService(instance);
	}

	private InMemoryFlowInstanceService runningInstance(String nodeId) {
		DataAgentFlowInstance instance = DataAgentFlowInstance.builder().id(1L).tenantId("tenant-1").agentId(1L)
				.skillVersionId(2L).skillCode("demand-create").threadId("thread-1").userId("user-1")
				.status(FlowInstanceStatus.RUNNING.name()).currentNodeId(nodeId)
				.contextData("{\"input\":{},\"runtime\":{\"contextRevision\":1}}")
				.waitingPayload("{}").lockVersion(0).build();
		return new InMemoryFlowInstanceService(instance);
	}

	private InMemoryFlowInstanceService genericNameWaitingInstance() {
		String waiting = """
				{"action":"COLLECT","requiredPaths":["/input/name"],
				 "schema":{"type":"object","properties":{
				  "name":{"type":"string"},"kind":{"type":"string"},"qty":{"type":"integer"}}},
				 "instruction":"Extract remaining fields from the utterance.",
				 "extraction":{"modelPolicy":"IF_UNRESOLVED","schemaMode":"FULL",
				 "failurePolicy":"WAIT_RETRY"}}
				""";
		DataAgentFlowInstance instance = DataAgentFlowInstance.builder().id(1L).tenantId("tenant-1").agentId(1L)
				.skillVersionId(2L).skillCode("generic-flow").threadId("thread-1").userId("user-1")
				.status(FlowInstanceStatus.WAITING.name()).currentNodeId("collect-name")
				.contextData("{\"input\":{},\"runtime\":{\"contextRevision\":1}}")
				.waitingPayload(waiting).lockVersion(0).build();
		return new InMemoryFlowInstanceService(instance);
	}

	private DataAgentSkillVersion configuredPathsWithLaterSlotsVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"extract-input","nodes":[
				 {"id":"extract-input","type":"extract","next":"collect-name","config":{
				  "outputPath":"/input",
				  "schema":{"type":"object","properties":{
				   "name":{"type":"string"},"kind":{"type":"string"}}},
				  "literalMappings":{"kind":{"A":["甲"],"B":["乙"]}},
				  "extraction":{"modelPolicy":"ALWAYS","schemaMode":"CONFIGURED_PATHS",
				   "paths":["/input/name","/input/kind"],"failurePolicy":"WAIT_RETRY"}}},
				 {"id":"collect-name","type":"collect","next":"collect-qty","config":{
				  "requiredPaths":["/input/name"],
				  "schema":{"type":"object","properties":{"name":{"type":"string"}}},
				  "extraction":{"modelPolicy":"IF_UNRESOLVED","schemaMode":"UNRESOLVED_REQUIRED",
				   "failurePolicy":"WAIT_RETRY"}}},
				 {"id":"collect-qty","type":"collect","next":"end","config":{
				  "requiredPaths":["/input/qty"],
				  "schema":{"type":"object","properties":{
				   "qty":{"type":"integer"},"note":{"type":"string"}}},
				  "extraction":{"modelPolicy":"IF_UNRESOLVED","schemaMode":"UNRESOLVED_REQUIRED",
				   "failurePolicy":"WAIT_RETRY"}}},
				 {"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(5L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion overlappingRequiredVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"collect-name","nodes":[
				 {"id":"collect-name","type":"collect","next":"end","config":{
				  "requiredPaths":["/input/name"],
				  "requiredAnyPaths":["/input/name","/input/code"],
				  "schema":{"type":"object","properties":{
				   "name":{"type":"string"},"code":{"type":"string"}}},
				  "collectionPresentation":{"mode":"MISSING_ONLY","fieldPrompts":{
				   "/input/name":"请提供名称。","/input/code":"请提供编码。"}}}},
				 {"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(6L).skillId(1L).flowDefinition(definition).build();
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> missingSlots(DataAgentFlowInstance instance) {
		try {
			Map<String, Object> waiting = objectMapper.readValue(instance.getWaitingPayload(), MAP_TYPE);
			Object missing = waiting.get("missing");
			return missing instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private DataAgentSkillVersion genericStaggeredVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"extract-input","nodes":[
				 {"id":"extract-input","type":"extract","next":"collect-name","config":{
				  "outputPath":"/input",
				  "schema":{"type":"object","properties":{
				   "name":{"type":"string"},"kind":{"type":"string"},"qty":{"type":"integer"}}},
				  "literalMappings":{"kind":{"A":["甲"],"B":["乙"]}},
				  "extraction":{"modelPolicy":"ALWAYS","schemaMode":"FULL","failurePolicy":"WAIT_RETRY"}}},
				 {"id":"collect-name","type":"collect","next":"collect-kind-qty","config":{
				  "requiredPaths":["/input/name"],
				  "schema":{"type":"object","properties":{
				   "name":{"type":"string"},"kind":{"type":"string"},"qty":{"type":"integer"}}},
				  "instruction":"Extract remaining fields from the utterance.",
				  "extraction":{"modelPolicy":"IF_UNRESOLVED","schemaMode":"FULL",
				   "failurePolicy":"WAIT_RETRY"}}},
				 {"id":"collect-kind-qty","type":"collect","next":"end","config":{
				  "requiredPaths":["/input/kind","/input/qty"],
				  "schema":{"type":"object","properties":{
				   "kind":{"type":"string"},"qty":{"type":"integer"}}},
				  "literalMappings":{"kind":{"A":["甲"],"B":["乙"]}},
				  "extraction":{"modelPolicy":"IF_UNRESOLVED","schemaMode":"UNRESOLVED_REQUIRED",
				   "failurePolicy":"WAIT_RETRY"}}},
				 {"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(2L).skillId(1L).flowDefinition(definition).build();
	}

	private InMemoryFlowInstanceService customerWaitingInstance() {
		String waiting = """
				{"action":"COLLECT","requiredPaths":[],
				 "requiredAnyPaths":["/input/companyId","/input/companyName"],
				 "schema":{"type":"object","properties":{"companyId":{"type":"string"},
				 "companyName":{"type":"string"}}},
				 "extraction":{"modelPolicy":"IF_UNRESOLVED","schemaMode":"UNRESOLVED_REQUIRED",
				 "failurePolicy":"WAIT_RETRY"}}
				""";
		DataAgentFlowInstance instance = DataAgentFlowInstance.builder().id(1L).tenantId("tenant-1").agentId(1L)
				.skillVersionId(2L).skillCode("demand-create").threadId("thread-1").userId("user-1")
				.status(FlowInstanceStatus.WAITING.name()).currentNodeId("collect-customer")
				.contextData("{\"input\":{},\"runtime\":{\"contextRevision\":1}}")
				.waitingPayload(waiting).lockVersion(0).build();
		return new InMemoryFlowInstanceService(instance);
	}

	private InMemoryFlowInstanceService changeModelWaitingInstance() {
		String waiting = """
				{"action":"CHANGE_MODEL","schema":{"type":"object","properties":{
				 "companyName":{"type":"string"}}},"requiredAnyPaths":["/input/companyName"],
				 "extraction":{"modelPolicy":"ALWAYS","schemaMode":"FULL","failurePolicy":"WAIT_RETRY"},
				 "instruction":"Extract customer name.","originNodeId":"extract-input","originNodeType":"extract",
				 "originAction":"EXTRACT","originOutputPath":"/input","originNextNodeId":"collect-customer",
				 "recoveryMode":"EXTRACT_THEN_ADVANCE","reaskFailures":2,"blockedModelConfigId":"1"}
				""";
		DataAgentFlowInstance instance = DataAgentFlowInstance.builder().id(1L).tenantId("tenant-1").agentId(1L)
				.skillVersionId(2L).skillCode("demand-create").threadId("thread-1").userId("user-1")
				.status(FlowInstanceStatus.WAITING.name()).currentNodeId("extract-input")
				.contextData("{\"input\":{},\"runtime\":{\"contextRevision\":1}}")
				.waitingPayload(waiting).lockVersion(0).build();
		return new InMemoryFlowInstanceService(instance);
	}

	private DataAgentSkill skill() {
		return DataAgentSkill.builder().id(1L).skillCode("demand-create").build();
	}

	private DataAgentSkillVersion version() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"collect-history-key","nodes":[
				 {"id":"collect-history-key","type":"collect","next":"end","config":{
				  "requiredPaths":["/input/type","/input/businessType"],
				  "prompt":"请补充需求类型和业务类型。","schema":%s,"literalMappings":%s,
				  "instruction":"Extract demand type and business type."}},
				 {"id":"end","type":"end"}]}
				""".formatted(SCHEMA, LITERAL_MAPPINGS);
		return DataAgentSkillVersion.builder().id(2L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion extractInputVersion(String failurePolicy) {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"extract-input","nodes":[
				 {"id":"extract-input","type":"extract","next":"collect-customer","config":{
				  "outputPath":"/input",
				  "requiredPaths":["/input/type","/input/businessType"],
				  "requiredAnyPaths":["/input/companyId","/input/companyName"],
				  "schema":{"type":"object","properties":{
				   "companyId":{"type":"string"},"companyName":{"type":"string"},
				   "type":{"type":"string"},"businessType":{"type":"string"}}},
				  "literalMappings":%s,
				  "extraction":{"modelPolicy":"IF_UNRESOLVED","schemaMode":"CONFIGURED_PATHS",
				   "paths":["/input/companyId","/input/companyName","/input/type","/input/businessType"],
				   "failurePolicy":"%s"}}},
				 {"id":"collect-customer","type":"collect","next":"end","config":{
				  "requiredAnyPaths":["/input/companyId","/input/companyName"],
				  "schema":{"type":"object","properties":{"companyId":{"type":"string"},
				   "companyName":{"type":"string"}}},
				  "extraction":{"modelPolicy":"IF_UNRESOLVED","schemaMode":"UNRESOLVED_REQUIRED",
				   "failurePolicy":"WAIT_RETRY"}}},
				 {"id":"end","type":"end"}]}
				""".formatted(LITERAL_MAPPINGS, failurePolicy);
		return DataAgentSkillVersion.builder().id(2L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion collectCustomerVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"collect-customer","nodes":[
				 {"id":"collect-customer","type":"collect","next":"end","config":{
				  "requiredAnyPaths":["/input/companyId","/input/companyName"],
				  "schema":{"type":"object","properties":{"companyId":{"type":"string"},
				   "companyName":{"type":"string"}}},
				  "extraction":{"modelPolicy":"IF_UNRESOLVED","schemaMode":"UNRESOLVED_REQUIRED",
				   "failurePolicy":"WAIT_RETRY"}}},
				 {"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(2L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion firstOrderTurnVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"extract-input","nodes":[
				 {"id":"extract-input","type":"extract","next":"collect-required","config":{
				  "outputPath":"/input","requiredPaths":["/input/type","/input/businessType","/input/arrivalTime"],
				  "requiredAnyPaths":["/input/companyId","/input/companyName"],
				  "schema":{"type":"object","properties":{"companyId":{"type":"string"},
				   "companyName":{"type":"string"},"type":{"type":"string"},
				   "businessType":{"type":"string"},"arrivalTime":{"type":"string"}}},
				  "literalMappings":%s,
				  "extraction":{"modelPolicy":"ALWAYS","schemaMode":"FULL","failurePolicy":"WAIT_RETRY"}}},
				 {"id":"collect-required","type":"collect","next":"collect-product","config":{
				  "requiredPaths":["/input/type","/input/businessType","/input/arrivalTime"],
				  "requiredAnyPaths":["/input/companyId","/input/companyName"],
				  "schema":{"type":"object","properties":{"companyName":{"type":"string"},
				   "type":{"type":"string"},"businessType":{"type":"string"},"arrivalTime":{"type":"string"}}},
				  "extraction":{"modelPolicy":"IF_UNRESOLVED","schemaMode":"UNRESOLVED_REQUIRED",
				   "failurePolicy":"WAIT_RETRY"}}},
				 {"id":"collect-product","type":"collect","next":"end","config":{
				  "requiredPaths":["/input/productDescription"],
				  "schema":{"type":"object","properties":{"productDescription":{"type":"string"}}},
				  "collectionPresentation":{"mode":"MISSING_ONLY","fieldPrompts":{
				   "/input/productDescription":"请补充商品及数量。"}},
				  "extraction":{"modelPolicy":"IF_UNRESOLVED","schemaMode":"UNRESOLVED_REQUIRED",
				   "failurePolicy":"WAIT_RETRY"}}},
				 {"id":"end","type":"end"}]}
				""".formatted(LITERAL_MAPPINGS);
		return DataAgentSkillVersion.builder().id(4L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion twoExtractVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"extract-one","nodes":[
				 {"id":"extract-one","type":"extract","next":"extract-two","config":{
				  "schema":{"type":"object","properties":{"companyName":{"type":"string"}}},
				  "extraction":{"modelPolicy":"ALWAYS","schemaMode":"FULL","failurePolicy":"WAIT_RETRY"}}},
				 {"id":"extract-two","type":"extract","next":"end","config":{
				  "schema":{"type":"object","properties":{"arrivalTime":{"type":"string"}}},
				  "extraction":{"modelPolicy":"ALWAYS","schemaMode":"FULL","failurePolicy":"WAIT_RETRY"}}},
				 {"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(3L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion dynamicPromptVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"collect-history-key","nodes":[
				 {"id":"collect-history-key","type":"collect","next":"end","config":{
				  "requiredPaths":["/input/type","/input/businessType"],
				  "prompt":"请补充需求类型和业务类型。",
				  "collectionPresentation":{"mode":"MISSING_ONLY","fieldPrompts":{
				   "/input/type":"请补充需求类型，例如送箱或收箱。",
				   "/input/businessType":"请补充业务类型，例如趟租或年租。"}},
				  "schema":%s}},
				 {"id":"end","type":"end"}]}
				""".formatted(SCHEMA);
		return DataAgentSkillVersion.builder().id(2L).skillId(1L).flowDefinition(definition).build();
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
