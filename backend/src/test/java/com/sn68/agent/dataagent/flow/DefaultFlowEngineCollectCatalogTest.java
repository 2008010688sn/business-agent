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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
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
import com.sn68.agent.dataagent.temporal.TemporalInterval;
import com.sn68.agent.dataagent.tool.ToolInvocationContext;
import com.sn68.agent.dataagent.tool.ToolInvoker;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultFlowEngineCollectCatalogTest {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private static final String INPUT_SCHEMA = """
			{"type":"object","required":["productList","addressList"],"properties":{
			 "productList":{"type":"array","minItems":1,"items":{"type":"object",
			  "required":["productId","productName","num"],
			  "properties":{"productId":{"type":"string"},"productNo":{"type":"string"},
			   "productName":{"type":"string"},"num":{"type":"integer"}}}},
			 "addressList":{"type":"array","minItems":2,"items":{"type":"object",
			  "required":["type","siteName"],
			  "properties":{"type":{"type":"string"},"siteName":{"type":"string"}}}}}}
			""";

	private static final String PAIR_EXTRACTIONS = """
			[{"field":"addressList","nameField":"siteName","typeField":"type",
			  "fromValue":"0","toValue":"1","separators":["到","至","->","→"]}]
			""";

	private static final String EXTRACT_SCHEMA = """
			{"type":"object","properties":{
			 "type":{"type":"string","enum":["0","1","3","5"]},
			 "businessType":{"type":"string","enum":["1","2"]},
			 "companyName":{"type":"string"},
			 "arrivalTime":{"type":"string","x-temporal":{"kind":"DATE_OR_DATE_TIME","targetType":"INSTANT","dateLanding":"START_OF_DAY"}},
			 "productList":{"type":"array","items":{"type":"object",
			  "properties":{"productId":{"type":"string"},"productName":{"type":"string"},"num":{"type":"integer"}}}},
			 "addressList":{"type":"array","items":{"type":"object",
			  "properties":{"type":{"type":"string"},"siteName":{"type":"string"}}}}}}
			""";

	private static final String LITERAL_MAPPINGS = """
			{"type":{"3":["送箱","送箱需求"]},
			 "businessType":{"2":["趟租","趟租业务"]}}
			""";

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final FlowFieldExtractor fieldExtractor = mock(FlowFieldExtractor.class);

	private final ToolInvoker toolInvoker = mock(ToolInvoker.class);

	private final AgentExecutionResourceVersionMapper resourceVersionMapper =
			mock(AgentExecutionResourceVersionMapper.class);

	@Test
	void listingProductsDuringCollectListsCatalogForTyping() {
		when(resourceVersionMapper.findPublished(10L)).thenReturn(readTool());
		when(toolInvoker.invoke(any())).thenReturn(Map.of("items", List.of(
				Map.of("productId", "A-1", "productNo", "330", "productName", "绿箱"),
				Map.of("productId", "B-1", "productNo", "331", "productName", "灰箱"))));
		InMemoryFlowInstanceService instances = waitingCollect(missingProductContext());

		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any())).thenReturn(listTurn("商品"));
		FlowExecutionResult result = engine(instances).execute(request("有哪些商品呀"), skill(), catalogVersion(),
				modelConfig());

		assertEquals("COLLECT", result.message().payload().action());
		assertTrue(result.message().payload().options() == null || result.message().payload().options().isEmpty());
		assertTrue(result.text().contains("绿箱"));
		assertTrue(result.text().contains("灰箱"));
		assertTrue(result.instance().getWaitingPayload().contains("\"schema\""));
		assertTrue(result.message().actions() == null || result.message().actions().isEmpty());
		ArgumentCaptor<ToolInvocationContext> invocation = ArgumentCaptor.forClass(ToolInvocationContext.class);
		verify(toolInvoker).invoke(invocation.capture());
		assertEquals("resolve-products", invocation.getValue().flowReference().invokingNodeId());
		verify(fieldExtractor).extract(any(), any(), any(), any(), any(), any());
	}

	@Test
	void informalProductListingCueStillListsCatalog() {
		when(resourceVersionMapper.findPublished(10L)).thenReturn(readTool());
		when(toolInvoker.invoke(any())).thenReturn(Map.of("items", List.of(
				Map.of("productId", "A-1", "productNo", "330", "productName", "绿箱"))));
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any())).thenReturn(listTurn("商品"));
		InMemoryFlowInstanceService instances = waitingCollect(missingProductContext());

		FlowExecutionResult result = engine(instances).execute(request("有啥商品"), skill(), catalogVersion(),
				modelConfig());

		assertTrue(result.text().contains("绿箱"));
		verify(toolInvoker).invoke(any());
	}

	@Test
	void modelOmittingIntegerStillFillsFromUtteranceDigits() {
		when(resourceVersionMapper.findPublished(10L)).thenReturn(readTool());
		when(toolInvoker.invoke(any())).thenReturn(Map.of("items",
				List.of(Map.of("productId", "A-1", "productNo", "OF330", "productName", "全灰OF330"))));
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any()))
				.thenReturn(Map.of("productList", List.of(Map.of("productName", "全灰OF330", "productNo", "OF330"))));
		InMemoryFlowInstanceService instances = waitingCollect(addressFilledContext());

		FlowExecutionResult result = engine(instances).execute(request("全灰OF330 10个"), skill(), catalogVersion(),
				modelConfig());

		assertEquals("A-1", value(result.instance(), "/input/productList/0/productId"));
		assertEquals("OF330", value(result.instance(), "/input/productList/0/productNo"));
		assertEquals(10L, ((Number) value(result.instance(), "/input/productList/0/num")).longValue());
		assertFalse(result.text().contains("请补充数量"));
		assertFalse(result.text().contains("请补充商品及数量"));
	}

	@Test
	void trailingPunctuationOnIdentityStillResolves() {
		when(resourceVersionMapper.findPublished(10L)).thenReturn(readTool());
		when(toolInvoker.invoke(any())).thenReturn(Map.of("items",
				List.of(Map.of("productId", "A-1", "productNo", "OF330", "productName", "全灰OF330"))));
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any()))
				.thenReturn(Map.of("productList", List.of(
						Map.of("productName", "全灰OF330", "productNo", "OF330,", "num", 10))));
		InMemoryFlowInstanceService instances = waitingCollect(addressFilledContext());

		FlowExecutionResult result = engine(instances).execute(request("全灰OF330, 10"), skill(), catalogVersion(),
				modelConfig());

		assertEquals("A-1", value(result.instance(), "/input/productList/0/productId"));
		assertEquals("OF330", value(result.instance(), "/input/productList/0/productNo"));
	}

	@Test
	void listingSitesDuringProductCollectDoesNotRequireSiteSlotEmpty() {
		when(resourceVersionMapper.findPublished(10L)).thenReturn(readTool());
		when(toolInvoker.invoke(any())).thenReturn(Map.of("items", List.of(
				Map.of("siteId", "S-1", "siteCode", "QJ", "siteName", "杭州前进仓"),
				Map.of("siteId", "S-2", "siteCode", "XS", "siteName", "杭州萧山仓"))));
		InMemoryFlowInstanceService instances = waitingCollect(missingProductContext());

		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any())).thenReturn(listTurn("网点"));
		FlowExecutionResult result = engine(instances).execute(request("网点有哪些"), skill(), listingVersion(),
				modelConfig());

		assertEquals("COLLECT", result.message().payload().action());
		assertTrue(result.text().contains("杭州前进仓"));
		assertTrue(result.text().contains("杭州萧山仓"));
		verify(fieldExtractor).extract(any(), any(), any(), any(), any(), any());
	}

	@Test
	void listingCustomersUsesDisplayFieldsWhenCatalogQueriesAbsent() {
		when(resourceVersionMapper.findPublished(11L)).thenReturn(readTool());
		when(toolInvoker.invoke(any())).thenReturn(Map.of("items",
				List.of(Map.of("companyId", "C-1", "companyName", "箱箱科技"))));
		InMemoryFlowInstanceService instances = waitingCollect(missingProductContext());

		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any())).thenReturn(listTurn("客户"));
		FlowExecutionResult result = engine(instances).execute(request("客户有哪些"), skill(), listingVersion(),
				modelConfig());

		assertEquals("COLLECT", result.message().payload().action());
		assertTrue(result.text().contains("箱箱科技"));
		assertEquals("杭州前进仓", value(result.instance(), "/input/addressList/0/siteName"));
		verify(fieldExtractor).extract(any(), any(), any(), any(), any(), any());
		ArgumentCaptor<ToolInvocationContext> invocation = ArgumentCaptor.forClass(ToolInvocationContext.class);
		verify(toolInvoker).invoke(invocation.capture());
		assertEquals("resolve-customers", invocation.getValue().flowReference().invokingNodeId());
	}

	@Test
	void listingResolverMissingPrerequisiteDoesNotExtract() {
		InMemoryFlowInstanceService instances = waitingCollect(missingProductContext());

		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any())).thenReturn(listTurn("网点"));
		FlowExecutionResult result = engine(instances).execute(request("网点有哪些"), skill(),
				sitePrerequisiteVersion(), modelConfig());

		assertEquals("COLLECT", result.message().payload().action());
		assertTrue(result.text().contains("请先补充查询所需的信息"));
		verify(toolInvoker, never()).invoke(any());
		verify(fieldExtractor).extract(any(), any(), any(), any(), any(), any());
	}

	@Test
	void blockingReviewProceedCommandDoesNotExtractOrChangeModel() {
		when(resourceVersionMapper.findPublished(10L)).thenReturn(readTool());
		when(toolInvoker.invoke(any())).thenReturn(Map.of("items", List.of(
				Map.of("productId", "A-1", "productNo", "SFPP01020002", "productName", "全灰OF330"))));
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any()))
				.thenReturn(Map.of("turnAction", "proceed"));
		InMemoryFlowInstanceService instances = waitingProductIssueReview();

		FlowExecutionResult result = engine(instances).execute(request("不需要修改 继续"), skill(), reviewRetryVersion(),
				modelConfig());

		assertEquals("SFPP01020002", value(result.instance(), "/input/productList/0/productNo"));
		assertEquals("A-1", value(result.instance(), "/input/productList/0/productId"));
		assertFalse(result.text().contains("请切换"));
		verify(fieldExtractor).extract(any(), any(), any(), any(), any(), any());
	}

	@Test
	void nonBlockingReviewContinueSubmitsWithoutExtraction() {
		InMemoryFlowInstanceService instances = waitingOpenReview();

		FlowExecutionResult result = engine(instances).execute(request("使用当前信息继续下单"), skill(),
				openReviewVersion(), modelConfig());

		assertTrue(result.terminal());
		verify(fieldExtractor, never()).extract(any(), any(), any(), any(), any(), any());
	}

	@Test
	void typedCatalogLineExtractsNameAndQuantityTogether() {
		when(resourceVersionMapper.findPublished(10L)).thenReturn(readTool());
		when(toolInvoker.invoke(any())).thenReturn(Map.of("items", List.of(
				Map.of("productId", "A-1", "productNo", "330", "productName", "绿箱"))));
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any()))
				.thenReturn(listTurn("商品"))
				.thenReturn(Map.of("productList", List.of(Map.of("productName", "绿箱", "productNo", "330", "num", 10))));
		InMemoryFlowInstanceService instances = waitingCollect(missingProductContext());
		DefaultFlowEngine flowEngine = engine(instances);
		flowEngine.execute(request("有哪些商品"), skill(), catalogVersion(), modelConfig());

		FlowExecutionResult result = flowEngine.execute(request("绿箱 10个"), skill(), catalogVersion(),
				modelConfig());

		assertEquals("A-1", value(result.instance(), "/input/productList/0/productId"));
		assertEquals("绿箱", value(result.instance(), "/input/productList/0/productName"));
		assertEquals(10, value(result.instance(), "/input/productList/0/num"));
		assertFalse(result.text().contains("请补充商品及数量"));
		assertFalse(result.text().contains("请从可选商品中选择"));
	}

	@Test
	void listedNameBindsCatalogCodeNotNameSubstring() {
		when(resourceVersionMapper.findPublished(10L)).thenReturn(readTool());
		when(toolInvoker.invoke(any())).thenReturn(Map.of("items", List.of(
				Map.of("productId", "A-1", "productNo", "SFPP01020002", "productName", "全灰OF330"))));
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any()))
				.thenReturn(listTurn("商品"))
				.thenReturn(Map.of("productList", List.of(
						Map.of("productName", "全灰OF330", "productNo", "OF330", "num", 10))));
		InMemoryFlowInstanceService instances = waitingCollect(addressFilledContext());
		DefaultFlowEngine flowEngine = engine(instances);
		flowEngine.execute(request("有哪些商品"), skill(), catalogVersion(), modelConfig());

		FlowExecutionResult result = flowEngine.execute(request("全灰OF330 10个"), skill(), catalogVersion(),
				modelConfig());

		assertEquals("A-1", value(result.instance(), "/input/productList/0/productId"));
		assertEquals("SFPP01020002", value(result.instance(), "/input/productList/0/productNo"));
		assertEquals("全灰OF330", value(result.instance(), "/input/productList/0/productName"));
		assertEquals(10L, ((Number) value(result.instance(), "/input/productList/0/num")).longValue());
		assertFalse(result.text().contains("请修改"));
		assertFalse(result.text().contains("未匹配"));
	}

	@Test
	void extractionFailureAfterListedBindAsksRemainingMissingNotHardcodedSlots() {
		when(resourceVersionMapper.findPublished(10L)).thenReturn(readTool());
		when(toolInvoker.invoke(any())).thenReturn(Map.of("items", List.of(
				Map.of("productId", "A-1", "productNo", "SFPP01020002", "productName", "全灰OF330"))));
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any()))
				.thenReturn(listTurn("商品"))
				.thenThrow(new FlowExtractionException(FlowExtractionException.INVALID_RESPONSE, "empty", null));
		InMemoryFlowInstanceService instances = waitingCollect(customerFilledMissingItemsContext());
		DefaultFlowEngine flowEngine = engine(instances);
		flowEngine.execute(request("能看下商品吗"), skill(), catalogVersion(), modelConfig());

		FlowExecutionResult result = flowEngine.execute(request("全灰OF330 10个"), skill(), catalogVersion(),
				modelConfig());

		assertEquals("SFPP01020002", value(result.instance(), "/input/productList/0/productNo"));
		assertEquals(10L, ((Number) value(result.instance(), "/input/productList/0/num")).longValue());
		assertEquals("上海箱箱", value(result.instance(), "/input/companyName"));
		assertTrue(result.text().contains("发货"));
		assertFalse(result.text().contains("客户、到货时间"));
	}

	@Test
	void alreadyCollectedCustomerIsNotReaskedOnEmptyExtraction() {
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any())).thenReturn(Map.of());
		InMemoryFlowInstanceService instances = waitingCollect(customerFilledMissingItemsContext());

		FlowExecutionResult result = engine(instances).execute(request("客户给过了呀"), skill(), catalogVersion(),
				modelConfig());

		assertEquals("上海箱箱", value(result.instance(), "/input/companyName"));
		assertEquals("2026-09-03", value(result.instance(), "/input/arrivalTime"));
		assertFalse(result.text().contains("客户、到货时间"));
		assertTrue(result.text().contains("商品") || result.text().contains("网点") || result.text().contains("发货"));
		assertEquals(List.of("productList", "addressList"), missingNames(result));
		assertFalse(missingNames(result).contains("companyName"));
	}

	@Test
	void questionDuringCollectAnswersThenKeepsMissingBusinessPrompts() {
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any()))
				.thenReturn(Map.of("turnAction", "ask", "turnReply", "我不能唱歌。若要继续办理，请补充下面的信息。"));
		InMemoryFlowInstanceService instances = waitingCollect(customerFilledMissingItemsContext());

		FlowExecutionResult result = engine(instances).execute(request("你能唱歌吗"), skill(), catalogVersion(),
				modelConfig());

		assertEquals("COLLECT", result.message().payload().action());
		assertTrue(result.text().contains("我不能唱歌"));
		assertTrue(result.text().contains("商品") || result.text().contains("网点") || result.text().contains("发货"));
		assertFalse(result.text().trim().equals("请补充必填信息。"));
		assertEquals("上海箱箱", value(result.instance(), "/input/companyName"));
		verify(toolInvoker, never()).invoke(any());
		assertEquals(List.of("productList", "addressList"), missingNames(result));
	}

	@Test
	void askDoesNotMergeHallucinatedSlots() {
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any()))
				.thenReturn(Map.of("turnAction", "ask", "turnReply", "我不能唱歌。请先补充下面的信息。",
						"companyName", "胡说客户", "productList",
						List.of(Map.of("productName", "绿箱", "num", 10))));
		InMemoryFlowInstanceService instances = waitingCollect(customerFilledMissingItemsContext());

		FlowExecutionResult result = engine(instances).execute(request("你能唱歌吗"), skill(), catalogVersion(),
				modelConfig());

		assertEquals("COLLECT", result.message().payload().action());
		assertEquals("上海箱箱", value(result.instance(), "/input/companyName"));
		assertNull(value(result.instance(), "/input/productList"));
		assertFalse(result.text().contains("胡说客户"));
		assertEquals(List.of("productList", "addressList"), missingNames(result));
		verify(toolInvoker, never()).invoke(any());
	}

	@Test
	void chitchatDoesNotMergeHallucinatedSlots() {
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any()))
				.thenReturn(Map.of("turnAction", "chitchat", "turnReply", "天气不错。咱们先把当前这单办完。",
						"companyName", "天气客户", "productList",
						List.of(Map.of("productName", "绿箱", "num", 8))));
		InMemoryFlowInstanceService instances = waitingCollect(customerFilledMissingItemsContext());

		FlowExecutionResult result = engine(instances).execute(request("今天天气真好"), skill(), catalogVersion(),
				modelConfig());

		assertEquals("COLLECT", result.message().payload().action());
		assertEquals("上海箱箱", value(result.instance(), "/input/companyName"));
		assertNull(value(result.instance(), "/input/productList"));
		assertTrue(result.text().contains("天气不错") || result.text().contains("办完"));
		assertTrue(result.text().contains("商品") || result.text().contains("网点") || result.text().contains("发货"));
		assertEquals(List.of("productList", "addressList"), missingNames(result));
		assertFalse(missingNames(result).contains("companyName"));
		verify(toolInvoker, never()).invoke(any());
	}

	@Test
	void askDoesNotKeepListedOptionBind() {
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any()))
				.thenReturn(Map.of("turnAction", "ask", "turnReply", "绿箱是可循环包装。请先补充下面的信息。"));
		InMemoryFlowInstanceService instances = waitingCollectWithListedProduct(customerFilledMissingItemsContext());

		FlowExecutionResult result = engine(instances).execute(request("绿箱OF330是什么"), skill(), catalogVersion(),
				modelConfig());

		assertEquals("COLLECT", result.message().payload().action());
		assertNull(value(result.instance(), "/input/productList"));
		assertEquals("上海箱箱", value(result.instance(), "/input/companyName"));
		verify(toolInvoker, never()).invoke(any());
	}

	@Test
	void collectMissingPayloadContainsOnlyEmptyRequiredSlots() {
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any())).thenReturn(Map.of());
		InMemoryFlowInstanceService instances = waitingCollect(customerFilledMissingItemsContext());

		FlowExecutionResult result = engine(instances).execute(request("客户给过了呀"), skill(), catalogVersion(),
				modelConfig());

		assertEquals("COLLECT", result.message().payload().action());
		assertEquals(List.of("productList", "addressList"), missingNames(result));
		assertFalse(missingNames(result).contains("companyName"));
		assertFalse(missingNames(result).contains("arrivalTime"));
		for (Map<String, Object> slot : missingSlots(result)) {
			assertTrue(text(slot.get("prompt")).contains("请"));
			assertFalse(text(slot.get("prompt")).contains("productList"));
			assertFalse(text(slot.get("prompt")).contains("addressList"));
		}
	}

	@Test
	void capabilityQuestionOnFreshCollectAnswersBeforeSlotDump() {
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any()))
				.thenReturn(Map.of("turnAction", "ask", "turnReply", "可以，我可以帮您办理需求单。"));
		InMemoryFlowInstanceService instances = waitingCollect(emptyInputContext());

		FlowExecutionResult result = engine(instances).execute(request("可以提供下单吗"), skill(), catalogVersion(),
				modelConfig());

		assertEquals("COLLECT", result.message().payload().action());
		assertTrue(result.text().contains("可以"));
		assertTrue(result.text().contains("商品") || result.text().contains("网点") || result.text().contains("发货"));
		assertFalse(result.text().trim().equals("请补充必填信息。"));
		verify(toolInvoker, never()).invoke(any());
	}

	@Test
	void firstExtractQuestionAnswersThenCollectsMissingFields() {
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any()))
				.thenReturn(Map.of("turnAction", "ask", "turnReply", "可以，我可以帮您办理需求单。"));
		InMemoryFlowInstanceService instances = runningExtract();

		FlowExecutionResult result = engine(instances).execute(request("可以提供下单吗"), skill(), extractVersion(),
				modelConfig());

		assertEquals("COLLECT", result.message().payload().action());
		assertTrue(result.text().contains("可以"));
		assertTrue(result.text().contains("商品") || result.text().contains("到货") || result.text().contains("网点")
				|| result.text().contains("发货"));
		assertFalse(result.text().trim().equals("请补充必填信息。"));
	}

	@Test
	void pauseTaskEntersCancelConfirmationAndStopsFlow() {
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any()))
				.thenReturn(Map.of("turnAction", "cancel"));
		InMemoryFlowInstanceService instances = waitingCollect(customerFilledMissingItemsContext());
		DefaultFlowEngine flowEngine = engine(instances);

		FlowExecutionResult confirm = flowEngine.execute(request("暂停任务"), skill(), catalogVersion(), modelConfig());
		assertEquals("CANCEL_CONFIRM", confirm.message().payload().action());
		assertFalse(confirm.terminal());

		AgentRequest confirmed = request("确认取消");
		confirmed.setFlowAction(new FlowAction("confirm-cancel", "CONFIRM_CANCEL", true, Map.of()));
		FlowExecutionResult stopped = flowEngine.execute(confirmed, skill(), catalogVersion(), modelConfig());
		assertTrue(stopped.terminal());
		assertEquals(FlowInstanceStatus.CANCELLED.name(), stopped.instance().getStatus());
	}

	@Test
	void stringQuantityPassesValidationInsteadOfReaskingProduct() {
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any())).thenReturn(Map.of());
		InMemoryFlowInstanceService instances = runningValidate(completeProductWithStringNum());

		FlowExecutionResult result = engine(instances).execute(request("继续"), skill(), validateVersion(),
				modelConfig());

		assertTrue(result.terminal());
		assertEquals(10L, ((Number) value(result.instance(), "/input/productList/0/num")).longValue());
		assertFalse(result.text().contains("请从可选商品中选择"));
	}

	@Test
	void namedSitesWithoutTypeUseConfiguredPairRoles() {
		when(resourceVersionMapper.findPublished(10L)).thenReturn(readTool());
		when(toolInvoker.invoke(any())).thenReturn(Map.of("items",
				List.of(Map.of("productId", "A-1", "productNo", "330", "productName", "绿箱"))));
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any()))
				.thenReturn(Map.of("addressList", List.of(
						Map.of("siteName", "杭州前进仓"), Map.of("siteName", "杭州萧山仓"))));
		InMemoryFlowInstanceService instances = waitingCollect(missingAddressContext());

		FlowExecutionResult result = engine(instances).execute(request("杭州前进仓到杭州萧山仓"), skill(),
				catalogVersion(), modelConfig());

		assertEquals("杭州前进仓", value(result.instance(), "/input/addressList/0/siteName"));
		assertEquals("0", value(result.instance(), "/input/addressList/0/type"));
		assertEquals("杭州萧山仓", value(result.instance(), "/input/addressList/1/siteName"));
		assertEquals("1", value(result.instance(), "/input/addressList/1/type"));
		assertFalse(result.text().contains("请补充type"));
	}

	@Test
	void sitePairUtteranceFillsAddressesInsteadOfListingCatalog() {
		when(resourceVersionMapper.findPublished(10L)).thenReturn(readTool());
		when(toolInvoker.invoke(any())).thenReturn(Map.of("items", List.of(
				Map.of("siteId", "S-1", "siteCode", "QJ", "siteName", "杭州前进仓"),
				Map.of("siteId", "S-2", "siteCode", "XS", "siteName", "杭州萧山仓"))));
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any()))
				.thenReturn(Map.of("turnAction", "list", "listLabel", "网点"));
		InMemoryFlowInstanceService instances = waitingCollect(missingAddressContext());

		FlowExecutionResult result = engine(instances).execute(request("杭州前进仓到杭州萧山仓"), skill(),
				listingVersion(), modelConfig());

		assertEquals("杭州前进仓", value(result.instance(), "/input/addressList/0/siteName"));
		assertEquals("0", value(result.instance(), "/input/addressList/0/type"));
		assertEquals("杭州萧山仓", value(result.instance(), "/input/addressList/1/siteName"));
		assertEquals("1", value(result.instance(), "/input/addressList/1/type"));
		assertFalse(result.text().contains("当前可选网点"));
		assertFalse(result.text().contains("请补充发货和收货网点"));
	}

	@Test
	void extractionShapeErrorCollectsInsteadOfChangingModel() {
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any()))
				.thenThrow(new FlowExtractionException(FlowExtractionException.INVALID_RESPONSE, "extra field", null));
		InMemoryFlowInstanceService instances = waitingCollect(missingAddressContext());

		FlowExecutionResult first = engine(instances).execute(request("发杭州前进仓 收杭州萧山仓"), skill(),
				catalogVersion(), modelConfig());
		FlowExecutionResult second = engine(instances).execute(request("发杭州前进仓 收杭州萧山仓"), skill(),
				catalogVersion(), modelConfig());

		assertEquals("COLLECT", first.message().payload().action());
		assertEquals("COLLECT", second.message().payload().action());
		assertFalse(second.text().contains("请切换"));
	}

	@Test
	void arrivalTimeUtteranceDoesNotInventAddressPair() {
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any()))
				.thenReturn(Map.of("companyName", "箱箱"));
		InMemoryFlowInstanceService instances = runningExtract();
		AgentRequest query = request("下单 送箱 趟租 客户是箱箱 到货时间今天");
		query.setTemporalInterval(new TemporalInterval(LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 4),
				Instant.parse("2026-09-02T16:00:00Z"), Instant.parse("2026-09-03T16:00:00Z"), false));

		FlowExecutionResult result = engine(instances).execute(query, skill(), extractVersion(), modelConfig());

		assertEquals("3", value(result.instance(), "/input/type"));
		assertEquals("2", value(result.instance(), "/input/businessType"));
		assertEquals("2026-09-02T16:00:00Z", String.valueOf(value(result.instance(), "/input/arrivalTime")));
		assertNull(value(result.instance(), "/input/addressList"));
		assertFalse(result.instance().getContextData().contains("货时间今天"));
		verify(fieldExtractor).extract(any(), any(), any(), any(), any(), any());
	}

	@Test
	void openLanguageAddressGoesToModel() {
		assertAddressExtractedByModel("杭州前进仓到杭州萧山仓", "杭州前进仓", "杭州萧山仓");
		assertAddressExtractedByModel("发杭州前进仓 收杭州萧山仓", "杭州前进仓", "杭州萧山仓");
		assertAddressExtractedByModel("从客户工厂送到萧山码头", "客户工厂", "萧山码头");
	}

	@Test
	void reviewOverwritesAddressNamesViaModel() {
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any()))
				.thenReturn(Map.of("addressList", List.of(
						Map.of("siteName", "客户工厂", "type", "0"),
						Map.of("siteName", "萧山码头", "type", "1"))));
		InMemoryFlowInstanceService instances = waitingReview(poisonAddressContext());

		FlowExecutionResult result = engine(instances).execute(request("从客户工厂送到萧山码头"), skill(),
				reviewVersion(), modelConfig());

		assertEquals("客户工厂", value(result.instance(), "/input/addressList/0/siteName"));
		assertEquals("萧山码头", value(result.instance(), "/input/addressList/1/siteName"));
		assertFalse(result.instance().getContextData().contains("货时间今天"));
		verify(fieldExtractor).extract(any(), any(), any(), any(), any(), any());
	}

	@Test
	void namedProductResolvesIdBeforeValidate() {
		when(resourceVersionMapper.findPublished(10L)).thenReturn(readTool());
		when(toolInvoker.invoke(any())).thenReturn(Map.of("items",
				List.of(Map.of("productId", "A-1", "productNo", "330", "productName", "绿箱"))));
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any()))
				.thenReturn(Map.of("productList", List.of(Map.of("productNo", "330", "productName", "绿箱", "num", 30))));
		InMemoryFlowInstanceService instances = waitingCollect(addressFilledContext());

		FlowExecutionResult result = engine(instances).execute(request("下单of330绿箱30件"), skill(), catalogVersion(),
				modelConfig());

		assertEquals("A-1", value(result.instance(), "/input/productList/0/productId"));
		assertEquals(30, value(result.instance(), "/input/productList/0/num"));
		assertFalse(result.text().contains("/productList/0/productId is required"));
	}

	@Test
	void listTurnDoesNotRewriteFilledSlots() {
		when(resourceVersionMapper.findPublished(10L)).thenReturn(readTool());
		when(toolInvoker.invoke(any())).thenReturn(Map.of("items",
				List.of(Map.of("productId", "A-1", "productNo", "330", "productName", "绿箱"))));
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any()))
				.thenReturn(Map.of("turnAction", "list", "listLabel", "商品", "type", "3"));
		InMemoryFlowInstanceService instances = waitingCollect("""
				{"input":{"twoProjectId":"P-1","companyName":"上海箱箱","arrivalTime":"2026-09-03","type":"3"},
				 "runtime":{"contextRevision":1}}
				""");

		FlowExecutionResult result = engine(instances).execute(request("有哪些商品"), skill(), catalogVersion(),
				modelConfig());

		assertEquals("3", value(result.instance(), "/input/type"));
		Object filled = value(result.instance(), "/runtime/filledThisTurn");
		assertFalse(String.valueOf(filled).contains("/input/type"));
		assertTrue(result.text().contains("绿箱"));
	}

	@Test
	void soleListedOptionBindsWithoutIdentityToken() {
		when(resourceVersionMapper.findPublished(10L)).thenReturn(readTool());
		when(toolInvoker.invoke(any())).thenReturn(Map.of("items",
				List.of(Map.of("productId", "A-1", "productNo", "330", "productName", "绿箱"))));
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any()))
				.thenReturn(listTurn("商品"))
				.thenReturn(Map.of());
		InMemoryFlowInstanceService instances = waitingCollect(addressFilledContext());
		DefaultFlowEngine flowEngine = engine(instances);
		flowEngine.execute(request("有哪些商品"), skill(), catalogVersion(), modelConfig());

		FlowExecutionResult result = flowEngine.execute(request("这个商品 10个"), skill(), catalogVersion(),
				modelConfig());

		assertEquals("A-1", value(result.instance(), "/input/productList/0/productId"));
		assertEquals("绿箱", value(result.instance(), "/input/productList/0/productName"));
		assertEquals(10L, ((Number) value(result.instance(), "/input/productList/0/num")).longValue());
		assertFalse(result.text().contains("请补充商品及数量"));
	}

	@Test
	void unmatchedAmongSeveralListedOptionsKeepsListedLabels() {
		when(resourceVersionMapper.findPublished(10L)).thenReturn(readTool());
		when(toolInvoker.invoke(any())).thenReturn(Map.of("items", List.of(
				Map.of("productId", "A-1", "productNo", "330", "productName", "绿箱"),
				Map.of("productId", "B-1", "productNo", "331", "productName", "灰箱"))));
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any()))
				.thenReturn(listTurn("商品"))
				.thenReturn(Map.of());
		InMemoryFlowInstanceService instances = waitingCollect(addressFilledContext());
		DefaultFlowEngine flowEngine = engine(instances);
		flowEngine.execute(request("有哪些商品"), skill(), catalogVersion(), modelConfig());

		FlowExecutionResult result = flowEngine.execute(request("这个 10个"), skill(), catalogVersion(),
				modelConfig());

		assertNull(value(result.instance(), "/input/productList"));
		assertEquals("COLLECT", result.message().payload().action());
		assertTrue(result.text().contains("绿箱"));
		assertTrue(result.text().contains("灰箱"));
		assertFalse(result.text().contains("请补充商品及数量"));
	}

	@Test
	void validateErrorsUseConfiguredPromptsInsteadOfJsonPointers() {
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any())).thenReturn(Map.of());
		InMemoryFlowInstanceService instances = waitingValidate();

		FlowExecutionResult result = engine(instances).execute(request("继续"), skill(), validateVersion(),
				modelConfig());

		assertEquals("VALIDATION", result.message().payload().action());
		assertFalse(result.text().contains("is required"));
		assertTrue(result.text().contains("发货"));
	}

	@Test
	void pairExtractionKeepsSitesWhenModelTimesOut() {
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any()))
				.thenThrow(new FlowExtractionException(FlowExtractionException.TIMEOUT, "timed out", null));
		InMemoryFlowInstanceService instances = waitingCollect(customerFilledMissingItemsContext());

		FlowExecutionResult result = engine(instances).execute(request("杭州前进仓到杭州萧山仓"), skill(),
				catalogVersion(), modelConfig());

		assertEquals("COLLECT", result.message().payload().action());
		assertEquals("杭州前进仓", value(result.instance(), "/input/addressList/0/siteName"));
		assertEquals("0", value(result.instance(), "/input/addressList/0/type"));
		assertEquals("杭州萧山仓", value(result.instance(), "/input/addressList/1/siteName"));
		assertEquals("1", value(result.instance(), "/input/addressList/1/type"));
		assertTrue(result.text().contains("商品"));
		assertFalse(result.text().contains("is required"));
		assertFalse(result.text().contains("addressList"));
		assertFalse(result.text().contains("REASK"));
	}

	@Test
	void rolePrefixedSitesAreCollectedOnFirstUtterance() {
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any()))
				.thenThrow(new FlowExtractionException(FlowExtractionException.TIMEOUT, "timed out", null));
		InMemoryFlowInstanceService instances = waitingCollect(customerFilledMissingItemsContext());

		FlowExecutionResult result = engine(instances).execute(request("发货网点是上海仓，收货网点是杭州仓"), skill(),
				catalogVersion(), modelConfig());

		assertEquals("COLLECT", result.message().payload().action());
		assertEquals("上海仓", value(result.instance(), "/input/addressList/0/siteName"));
		assertEquals("杭州仓", value(result.instance(), "/input/addressList/1/siteName"));
		assertTrue(result.text().contains("商品"));
	}

	@Test
	void turnReplyDropsSchemaFieldNamesAndJsonPointers() {
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any()))
				.thenReturn(Map.of("turnAction", "ask", "turnReply",
						"可以办理。还需要 addressList, arrivalTime, businessType, companyName, productList, remark, type。"
								+ " /addressList/0/siteName is required"));
		InMemoryFlowInstanceService instances = waitingCollect(emptyInputContext());

		FlowExecutionResult result = engine(instances).execute(request("能下单吗"), skill(), catalogVersion(),
				modelConfig());

		assertEquals("COLLECT", result.message().payload().action());
		assertTrue(result.text().contains("可以"));
		assertFalse(result.text().contains("addressList"));
		assertFalse(result.text().contains("arrivalTime"));
		assertFalse(result.text().contains("is required"));
		assertFalse(result.text().contains("/addressList"));
		assertTrue(result.text().contains("商品") || result.text().contains("网点") || result.text().contains("发货"));
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

	private Map<String, Object> listTurn(String label) {
		return Map.of("turnAction", "list", "listLabel", label);
	}

	private ModelConfigDTO modelConfig() {
		return ModelConfigDTO.builder().id(1L).modelName("test-model").build();
	}

	private DataAgentSkill skill() {
		return DataAgentSkill.builder().id(1L).skillCode("demand-create").build();
	}

	private void assertAddressExtractedByModel(String query, String from, String to) {
		when(resourceVersionMapper.findPublished(10L)).thenReturn(readTool());
		when(toolInvoker.invoke(any())).thenReturn(Map.of("items",
				List.of(Map.of("productId", "A-1", "productNo", "330", "productName", "绿箱"))));
		when(fieldExtractor.extract(any(), any(), any(), any(), any(), any()))
				.thenReturn(Map.of("addressList", List.of(
						Map.of("siteName", from, "type", "0"),
						Map.of("siteName", to, "type", "1"))));
		InMemoryFlowInstanceService instances = waitingCollect(missingAddressContext());

		FlowExecutionResult result = engine(instances).execute(request(query), skill(), catalogVersion(),
				modelConfig());

		assertEquals(from, value(result.instance(), "/input/addressList/0/siteName"));
		assertEquals("0", value(result.instance(), "/input/addressList/0/type"));
		assertEquals(to, value(result.instance(), "/input/addressList/1/siteName"));
		assertEquals("1", value(result.instance(), "/input/addressList/1/type"));
		assertFalse(result.text().contains("is required"));
	}

	private DataAgentSkillVersion listingVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"collect-required","nodes":[
				 {"id":"collect-required","type":"collect","next":"resolve-products","config":{
				  "requiredPaths":["/input/productList","/input/addressList"],
				  "schema":%s,
				  "extraction":{"modelPolicy":"IF_UNRESOLVED","schemaMode":"UNRESOLVED_REQUIRED",
				   "failurePolicy":"WAIT_RETRY","pairExtractions":%s},
				  "catalogQueries":[
				    {"whenMissing":"/input/productList","intents":["商品","产品"],
				     "resolverNode":"resolve-products","prompt":"请选择商品。"},
				    {"whenMissing":"/input/addressList","intents":["网点","仓库","仓"],
				     "resolverNode":"resolve-sites","prompt":"请选择网点。"}],
				  "collectionPresentation":{"mode":"MISSING_ONLY","fieldPrompts":{
				   "/input/productList":"请补充商品及数量。","/input/addressList":"请补充发货和收货网点或地址。"}}}},
				 {"id":"resolve-products","type":"resolve","next":"validate-input","config":{
				  "resourceVersionId":10,"forEach":"/input/productList","candidatesPath":"/items",
				  "itemLabel":"商品","displayName":"查询商品",
				  "identityPaths":["/productId","/productNo","/productName"],
				  "argumentMappings":{"twoProjectId":"/input/twoProjectId"},
				  "itemArgumentMappings":{"productId":"/productId","productNo":"/productNo","keyword":"/productName"},
				  "labelPath":"/productName","valuePath":"/productId","preserveUserFields":["num"]}},
				 {"id":"resolve-sites","type":"resolve","next":"validate-input","config":{
				  "resourceVersionId":10,"forEach":"/input/addressList","candidatesPath":"/items",
				  "itemLabel":"网点","displayName":"查询网点",
				  "identityPaths":["/siteId","/siteCode","/siteName"],
				  "argumentMappings":{"companyId":"/input/companyId"},
				  "itemArgumentMappings":{"siteId":"/siteId","siteCode":"/siteCode","keyword":"/siteName"},
				  "labelPath":"/siteName","valuePath":"/siteId"}},
				 {"id":"resolve-customers","type":"resolve","next":"validate-input","config":{
				  "resourceVersionId":11,"outputPath":"/resolved/customers","candidatesPath":"/items",
				  "identityPaths":["/companyId","/companyName"],
				  "argumentMappings":{"keyword":"/input/companyName","companyId":"/input/companyId"},
				  "labelPath":"/companyName","valuePath":"/companyId"}},
				 {"id":"review-history","type":"review","next":"collect-required","config":{
				  "schema":%s,
				  "displayFields":[{"label":"客户","path":"/input/companyName"}],
				  "refreshRoutes":[{"whenAny":["/input/companyId","/input/companyName"],
				    "next":"resolve-customers"}]}},
				 {"id":"validate-input","type":"validate","next":"end","config":{
				  "targetPath":"/input","schema":%s}},
				 {"id":"end","type":"end"}]}
				""".formatted(INPUT_SCHEMA, PAIR_EXTRACTIONS, INPUT_SCHEMA, INPUT_SCHEMA);
		return DataAgentSkillVersion.builder().id(6L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion sitePrerequisiteVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"collect-required","nodes":[
				 {"id":"collect-required","type":"collect","next":"end","config":{
				  "requiredPaths":["/input/productList","/input/addressList"],
				  "schema":%s,
				  "extraction":{"modelPolicy":"IF_UNRESOLVED","schemaMode":"UNRESOLVED_REQUIRED",
				   "failurePolicy":"WAIT_RETRY"},
				  "catalogQueries":[{"whenMissing":"/input/addressList","intents":["网点","仓库","仓"],
				    "resolverNode":"resolve-sites","prompt":"请选择网点。"}]}},
				 {"id":"resolve-sites","type":"resolve","next":"end","config":{
				  "resourceVersionId":10,"forEach":"/input/addressList","candidatesPath":"/items",
				  "requiresAll":["/input/companyId"],"itemLabel":"网点",
				  "identityPaths":["/siteId","/siteCode","/siteName"],
				  "argumentMappings":{"companyId":"/input/companyId"},
				  "labelPath":"/siteName","valuePath":"/siteId"}},
				 {"id":"end","type":"end"}]}
				""".formatted(INPUT_SCHEMA);
		return DataAgentSkillVersion.builder().id(7L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion openReviewVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"review-history","nodes":[
				 {"id":"review-history","type":"review","next":"end","config":{
				  "schema":%s,
				  "uiActions":[{"actionId":"review-submit","type":"SUBMIT","label":"使用当前信息继续下单","value":true}],
				  "extraction":{"modelPolicy":"ALWAYS","schemaMode":"FULL","failurePolicy":"WAIT_RETRY"}}},
				 {"id":"end","type":"end"}]}
				""".formatted(INPUT_SCHEMA);
		return DataAgentSkillVersion.builder().id(8L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion catalogVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"collect-required","nodes":[
				 {"id":"collect-required","type":"collect","next":"resolve-products","config":{
				  "requiredPaths":["/input/productList","/input/addressList"],
				  "schema":%s,
				  "extraction":{"modelPolicy":"IF_UNRESOLVED","schemaMode":"UNRESOLVED_REQUIRED",
				   "failurePolicy":"WAIT_RETRY","pairExtractions":%s},
				  "catalogQueries":[{"whenMissing":"/input/productList","intents":["商品","产品"],
				    "resolverNode":"resolve-products","prompt":"请选择要下单的商品。"}],
				  "collectionPresentation":{"mode":"MISSING_ONLY","fieldPrompts":{
				   "/input/productList":"请补充商品及数量。","/input/addressList":"请补充发货和收货网点或地址。"}}}},
				 {"id":"resolve-products","type":"resolve","next":"validate-input","config":{
				  "resourceVersionId":10,"forEach":"/input/productList","candidatesPath":"/items",
				  "identityPaths":["/productId","/productNo","/productName"],
				  "argumentMappings":{"twoProjectId":"/input/twoProjectId"},
				  "itemArgumentMappings":{"productId":"/productId","productNo":"/productNo","keyword":"/productName"},
				  "labelPath":"/productName","valuePath":"/productId","preserveUserFields":["num"]}},
				 {"id":"validate-input","type":"validate","next":"end","config":{
				  "targetPath":"/input","schema":%s,
				  "errorPrompts":{"/addressList":"请说明哪个是发货网点、哪个是收货网点。",
				   "/productList":"请从可选商品中选择，或补充商品编码/名称。"}}},
				 {"id":"end","type":"end"}]}
				""".formatted(INPUT_SCHEMA, PAIR_EXTRACTIONS, INPUT_SCHEMA);
		return DataAgentSkillVersion.builder().id(2L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion extractVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"extract-input","nodes":[
				 {"id":"extract-input","type":"extract","next":"collect-required","config":{
				  "outputPath":"/input","schema":%s,
				  "literalMappings":%s,
				  "extraction":{"modelPolicy":"ALWAYS","schemaMode":"FULL","failurePolicy":"WAIT_RETRY",
				   "pairExtractions":%s}}},
				 {"id":"collect-required","type":"collect","next":"end","config":{
				  "requiredPaths":["/input/arrivalTime","/input/productList","/input/addressList"],
				  "schema":%s,
				  "extraction":{"modelPolicy":"IF_UNRESOLVED","schemaMode":"UNRESOLVED_REQUIRED",
				   "failurePolicy":"WAIT_RETRY"},
				  "collectionPresentation":{"mode":"MISSING_ONLY","fieldPrompts":{
				   "/input/arrivalTime":"请补充到货时间。","/input/productList":"请补充商品及数量。",
				   "/input/addressList":"请补充发货和收货网点或地址。"}}}},
				 {"id":"end","type":"end"}]}
				""".formatted(EXTRACT_SCHEMA, LITERAL_MAPPINGS, PAIR_EXTRACTIONS, EXTRACT_SCHEMA);
		return DataAgentSkillVersion.builder().id(4L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion reviewVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"review-resolver-issues","nodes":[
				 {"id":"review-resolver-issues","type":"review","next":"end","config":{
				  "schema":%s,
				  "issuesPaths":["/runtime/resolverIssues/sites"],
				  "extraction":{"modelPolicy":"ALWAYS","schemaMode":"FULL","failurePolicy":"WAIT_RETRY"},
				  "prompt":"部分历史明细未匹配到当前可用主数据，请一次说明需要修改的商品或网点。"}},
				 {"id":"end","type":"end"}]}
				""".formatted(INPUT_SCHEMA);
		return DataAgentSkillVersion.builder().id(5L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion reviewRetryVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"review-resolver-issues","nodes":[
				 {"id":"review-resolver-issues","type":"review","next":"validate-input","config":{
				  "schema":%s,
				  "issuesPaths":["/runtime/resolverIssues/products"],
				  "refreshRoutes":[{"whenAny":["/input/productList"],"next":"resolve-products"}],
				  "extraction":{"modelPolicy":"ALWAYS","schemaMode":"FULL","failurePolicy":"WAIT_RETRY"},
				  "prompt":"部分历史明细未匹配到当前可用主数据，请一次说明需要修改的商品或网点。"}},
				 {"id":"resolve-products","type":"resolve","next":"validate-input","config":{
				  "resourceVersionId":10,"forEach":"/input/productList","candidatesPath":"/items",
				  "identityPaths":["/productId","/productNo","/productName"],
				  "argumentMappings":{"twoProjectId":"/input/twoProjectId"},
				  "itemArgumentMappings":{"productId":"/productId","productNo":"/productNo","keyword":"/productName"},
				  "labelPath":"/productName","valuePath":"/productId","preserveUserFields":["num"]}},
				 {"id":"validate-input","type":"validate","next":"end","config":{
				  "targetPath":"/input","schema":%s}},
				 {"id":"end","type":"end"}]}
				""".formatted(INPUT_SCHEMA, INPUT_SCHEMA);
		return DataAgentSkillVersion.builder().id(9L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion validateVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"validate-input","nodes":[
				 {"id":"validate-input","type":"validate","next":"end","config":{
				  "targetPath":"/input","schema":%s,
				  "extraction":{"modelPolicy":"ALWAYS","schemaMode":"FULL","failurePolicy":"WAIT_RETRY"},
				  "errorPrompts":{"/addressList":"请说明哪个是发货网点、哪个是收货网点。",
				   "/productList":"请从可选商品中选择，或补充商品编码/名称。"}}},
				 {"id":"end","type":"end"}]}
				""".formatted(INPUT_SCHEMA);
		return DataAgentSkillVersion.builder().id(3L).skillId(1L).flowDefinition(definition).build();
	}

	private InMemoryFlowInstanceService runningExtract() {
		DataAgentFlowInstance current = instance("extract-input", "{}", "{}");
		current.setStatus(FlowInstanceStatus.RUNNING.name());
		return new InMemoryFlowInstanceService(current);
	}

	private InMemoryFlowInstanceService waitingBlockingReview() {
		String waiting = """
				{"action":"REVIEW","blocking":true,"schema":%s,
				 "errors":["部分历史明细未匹配到当前可用主数据"],
				 "extraction":{"modelPolicy":"ALWAYS","schemaMode":"FULL","failurePolicy":"WAIT_RETRY"},
				 "issuesPaths":["/runtime/resolverIssues/sites"]}
				""".formatted(INPUT_SCHEMA);
		return new InMemoryFlowInstanceService(instance("review-resolver-issues", poisonAddressContext(), waiting));
	}

	private InMemoryFlowInstanceService waitingProductIssueReview() {
		String context = """
				{"input":{"twoProjectId":"P-1","type":"3","businessType":"2","arrivalTime":"2026-09-04",
				 "productList":[{"productName":"全灰OF330","productNo":"OF330","num":10}],
				 "addressList":[{"type":"0","siteName":"杭州前进仓"},{"type":"1","siteName":"杭州萧山仓"}]},
				 "runtime":{"contextRevision":1,"resolverIssues":{"products":["部分历史明细未匹配到当前可用主数据"]}}}
				""";
		String waiting = """
				{"action":"REVIEW","blocking":true,"schema":%s,
				 "errors":["部分历史明细未匹配到当前可用主数据"],
				 "extraction":{"modelPolicy":"ALWAYS","schemaMode":"FULL","failurePolicy":"WAIT_RETRY"},
				 "issuesPaths":["/runtime/resolverIssues/products"],
				 "refreshRoutes":[{"whenAny":["/input/productList"],"next":"resolve-products"}]}
				""".formatted(INPUT_SCHEMA);
		return new InMemoryFlowInstanceService(instance("review-resolver-issues", context, waiting));
	}

	private InMemoryFlowInstanceService waitingOpenReview() {
		String context = """
				{"input":{"type":"3","businessType":"2","arrivalTime":"2026-09-04","companyName":"箱箱",
				 "productList":[{"productId":"A-1","productNo":"330","productName":"绿箱","num":30}],
				 "addressList":[{"type":"0","siteName":"杭州前进仓"},{"type":"1","siteName":"杭州萧山仓"}]},
				 "runtime":{"contextRevision":1}}
				""";
		String waiting = """
				{"action":"REVIEW","schema":%s,
				 "uiActions":[{"actionId":"review-submit","type":"SUBMIT","label":"使用当前信息继续下单","value":true}],
				 "extraction":{"modelPolicy":"ALWAYS","schemaMode":"FULL","failurePolicy":"WAIT_RETRY"}}
				""".formatted(INPUT_SCHEMA);
		return new InMemoryFlowInstanceService(instance("review-history", context, waiting));
	}

	private InMemoryFlowInstanceService waitingReview(String context) {
		String waiting = """
				{"action":"REVIEW","schema":%s,
				 "extraction":{"modelPolicy":"ALWAYS","schemaMode":"FULL","failurePolicy":"WAIT_RETRY"},
				 "issuesPaths":["/runtime/resolverIssues/sites"]}
				""".formatted(INPUT_SCHEMA);
		return new InMemoryFlowInstanceService(instance("review-resolver-issues", context, waiting));
	}

	private InMemoryFlowInstanceService waitingCollect(String context) {
		String waiting = """
				{"action":"COLLECT","requiredPaths":["/input/productList","/input/addressList"],
				 "schema":%s,
				 "extraction":{"modelPolicy":"IF_UNRESOLVED","schemaMode":"UNRESOLVED_REQUIRED",
				  "failurePolicy":"WAIT_RETRY","pairExtractions":%s},
				 "catalogQueries":[{"whenMissing":"/input/productList","intents":["商品","产品"],
				   "resolverNode":"resolve-products","prompt":"请选择要下单的商品。"}]}
				""".formatted(INPUT_SCHEMA, PAIR_EXTRACTIONS);
		return new InMemoryFlowInstanceService(instance("collect-required", context, waiting));
	}

	private InMemoryFlowInstanceService waitingCollectWithListedProduct(String context) {
		String waiting = """
				{"action":"COLLECT","requiredPaths":["/input/productList","/input/addressList"],
				 "schema":%s,
				 "extraction":{"modelPolicy":"IF_UNRESOLVED","schemaMode":"UNRESOLVED_REQUIRED",
				  "failurePolicy":"WAIT_RETRY","pairExtractions":%s},
				 "catalogTargetPath":"/input/productList",
				 "identityPaths":["/productId","/productNo","/productName"],
				 "listedOptions":[{"label":"绿箱OF330","value":"A-1",
				   "rawData":{"productId":"A-1","productNo":"330","productName":"绿箱OF330"}}]}
				""".formatted(INPUT_SCHEMA, PAIR_EXTRACTIONS);
		return new InMemoryFlowInstanceService(instance("collect-required", context, waiting));
	}

	private InMemoryFlowInstanceService runningValidate(String context) {
		DataAgentFlowInstance current = instance("validate-input", context, "{}");
		current.setStatus(FlowInstanceStatus.RUNNING.name());
		return new InMemoryFlowInstanceService(current);
	}

	private String completeProductWithStringNum() {
		return """
				{"input":{"twoProjectId":"P-1",
				 "productList":[{"productId":"A-1","productNo":"330","productName":"绿箱","num":"10"}],
				 "addressList":[{"type":"0","siteName":"杭州前进仓"},{"type":"1","siteName":"杭州萧山仓"}]},
				 "runtime":{"contextRevision":1}}
				""";
	}

	private InMemoryFlowInstanceService waitingValidate() {
		String context = """
				{"input":{"twoProjectId":"P-1","productList":[{"productName":"绿箱","productNo":"330","num":30}],
				 "addressList":[{"siteName":"杭州前进仓"},{"siteName":"杭州萧山仓"}]},
				 "runtime":{"contextRevision":1}}
				""";
		String waiting = """
				{"action":"VALIDATION","targetPath":"/input","schema":%s,
				 "errorPrompts":{"/addressList":"请说明哪个是发货网点、哪个是收货网点。",
				  "/productList":"请从可选商品中选择，或补充商品编码/名称。"}}
				""".formatted(INPUT_SCHEMA);
		return new InMemoryFlowInstanceService(instance("validate-input", context, waiting));
	}

	private DataAgentFlowInstance instance(String nodeId, String context, String waiting) {
		return DataAgentFlowInstance.builder().id(20L).tenantId("tenant-1").agentId(1L).skillVersionId(2L)
				.skillCode("demand-create").threadId("thread-1").userId("user-1")
				.status(FlowInstanceStatus.WAITING.name()).currentNodeId(nodeId)
				.contextData(context).waitingPayload(waiting).lockVersion(0).build();
	}

	private String poisonAddressContext() {
		return """
				{"input":{"type":"3","businessType":"2","arrivalTime":"2026-09-04","companyName":"箱箱",
				 "productList":[{"productId":"A-1","productNo":"330","productName":"绿箱","num":30}],
				 "addressList":[{"type":"0","siteName":"下单 送箱 趟租 客户是箱箱"},
				  {"type":"1","siteName":"货时间今天"}]},
				 "runtime":{"contextRevision":1,"resolverIssues":{"sites":["未匹配到网点"]}}}
				""";
	}

	private String missingProductContext() {
		return """
				{"input":{"twoProjectId":"P-1","addressList":[
				 {"siteName":"杭州前进仓","type":"0"},{"siteName":"杭州萧山仓","type":"1"}]},
				 "runtime":{"contextRevision":1}}
				""";
	}

	private String missingAddressContext() {
		return """
				{"input":{"twoProjectId":"P-1","productList":[
				 {"productId":"A-1","productNo":"330","productName":"绿箱","num":30}]},
				 "runtime":{"contextRevision":1}}
				""";
	}

	private String addressFilledContext() {
		return """
				{"input":{"twoProjectId":"P-1","addressList":[
				 {"siteName":"杭州前进仓","type":"0"},{"siteName":"杭州萧山仓","type":"1"}]},
				 "runtime":{"contextRevision":1}}
				""";
	}

	private String emptyInputContext() {
		return """
				{"input":{},"runtime":{"contextRevision":1}}
				""";
	}

	private String customerFilledMissingItemsContext() {
		return """
				{"input":{"twoProjectId":"P-1","companyName":"上海箱箱","arrivalTime":"2026-09-03"},
				 "runtime":{"contextRevision":1,"lastChangedPaths":["/input/companyName","/input/arrivalTime"]}}
				""";
	}

	private AgentExecutionResourceVersion readTool() {
		return AgentExecutionResourceVersion.builder().id(10L).resourceKey("products").versionNo(1)
				.status("PUBLISHED").accessMode("READ").exposureMode("FLOW_ONLY").build();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> map(Object value) {
		return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
	}

	private List<String> missingNames(FlowExecutionResult result) {
		return missingSlots(result).stream().map(slot -> text(slot.get("name"))).toList();
	}

	private List<Map<String, Object>> missingSlots(FlowExecutionResult result) {
		Object missing = result.message().payload().values().get("missing");
		if (!(missing instanceof List<?> items)) {
			return List.of();
		}
		return items.stream().map(this::map).toList();
	}

	private String text(Object value) {
		return value == null ? "" : String.valueOf(value);
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
