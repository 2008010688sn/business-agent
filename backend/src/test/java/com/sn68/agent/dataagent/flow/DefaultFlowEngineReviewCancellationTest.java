/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.im.ChannelInteractionCapability;
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
import com.sn68.agent.dataagent.ui.AgentUiMessage;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DefaultFlowEngineReviewCancellationTest {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final ToolInvoker toolInvoker = mock(ToolInvoker.class);

	private final CapabilityGateway capabilityGateway = passthroughGateway();

	private final FlowFieldExtractor fieldExtractor = mock(FlowFieldExtractor.class, CALLS_REAL_METHODS);

	private final AgentExecutionResourceVersionMapper resourceVersionMapper =
			mock(AgentExecutionResourceVersionMapper.class);

	@Test
	void editReturnsToReviewAndReviewSubmitDoesNotExecuteWrite() {
		InMemoryFlowInstanceService instances = new InMemoryFlowInstanceService(waitingInstance("confirm-submit",
				"{\"input\":{\"companyName\":\"客户A\"},\"runtime\":{\"contextRevision\":1,\"reviewedRevision\":1}}",
				"{\"action\":\"CONFIRM\",\"editNode\":\"review-history\"}"));
		DefaultFlowEngine engine = engine(instances);

		FlowExecutionResult review = engine.execute(actionRequest("EDIT", false), skill(), version(), null);

		assertEquals("REVIEW", review.message().payload().action());
		assertEquals("客户A", map(review.instance().getContextData(), "/input/companyName"));
		verify(toolInvoker, never()).invoke(any());

		FlowExecutionResult confirm = engine.execute(actionRequest("SUBMIT", true), skill(), version(), null);

		assertEquals("CONFIRM", confirm.message().payload().action());
		verify(toolInvoker, never()).invoke(any());
	}

	@Test
	void finalConfirmExecutesOnlyAfterCurrentRevisionReview() {
		InMemoryFlowInstanceService instances = new InMemoryFlowInstanceService(waitingInstance("review-history",
				"{\"input\":{\"companyName\":\"客户A\"},\"runtime\":{\"contextRevision\":2}}",
				"{\"action\":\"REVIEW\"}"));
		DefaultFlowEngine engine = engine(instances);
		when(resourceVersionMapper.findPublished(10L)).thenReturn(AgentExecutionResourceVersion.builder().id(10L)
				.resourceKey("write.tool").status("PUBLISHED").accessMode("WRITE").exposureMode("FLOW_ONLY")
				.confirmRequired(true).build());
		when(toolInvoker.invoke(any())).thenReturn(Map.of("demandNo", "D-1"));

		FlowExecutionResult confirm = engine.execute(actionRequest("SUBMIT", true), skill(), version(), null);
		assertEquals("CONFIRM", confirm.message().payload().action());

		FlowExecutionResult completed = engine.execute(actionRequest("CONFIRM", true), skill(), version(), null);

		assertTrue(completed.terminal());
		assertEquals(FlowInstanceStatus.SUCCEEDED.name(), completed.instance().getStatus());
		verify(toolInvoker).invoke(any());
		verify(capabilityGateway).invoke(any(InvocationRequest.class), any());
	}

	@Test
	void cancelRequiresConfirmationAndKeepFlowRestoresCheckpoint() {
		InMemoryFlowInstanceService instances = new InMemoryFlowInstanceService(waitingInstance("review-history",
				"{\"input\":{\"companyName\":\"客户A\"},\"runtime\":{\"contextRevision\":1}}",
				"{\"action\":\"REVIEW\",\"currentValues\":{\"companyName\":\"客户A\"},"
						+ "\"cancelPolicy\":{\"question\":\"是否终止客服下单？\",\"confirmLabel\":\"终止\","
						+ "\"keepLabel\":\"继续填写\",\"continueText\":\"已继续填写。\",\"successText\":\"已终止。\"}}"));
		DefaultFlowEngine engine = engine(instances);

		FlowExecutionResult confirmation = engine.execute(actionRequest("CANCEL", true), skill(), version(), null);
		assertEquals("CANCEL_CONFIRM", confirmation.message().payload().action());
		assertEquals("是否终止客服下单？", confirmation.text());
		assertEquals(List.of("终止", "继续填写"),
				confirmation.message().actions().stream().map(AgentUiMessage.Action::label).toList());
		assertEquals(FlowInstanceStatus.WAITING.name(), confirmation.instance().getStatus());

		FlowExecutionResult restored = engine.execute(actionRequest("KEEP_FLOW", true), skill(), version(), null);
		assertEquals("REVIEW", restored.message().payload().action());
		assertEquals("已继续填写。", restored.text());
		assertEquals("客户A", map(restored.instance().getContextData(), "/input/companyName"));
	}

	@Test
	void cancelDuringResolverReturnsToLastInteractionCheckpoint() {
		String context = """
				{"input":{"companyName":"客户A"},
				 "runtime":{"contextRevision":2,"lastInteractionCheckpoint":{
				   "nodeId":"review-history","waitingPayload":{"action":"REVIEW",
				   "currentValues":{"companyName":"客户A"}}}}}
				""";
		DataAgentFlowInstance running = waitingInstance("resolve-products", context, "{}");
		running.setStatus(FlowInstanceStatus.RUNNING.name());
		InMemoryFlowInstanceService instances = new InMemoryFlowInstanceService(running);
		DefaultFlowEngine engine = engine(instances);

		FlowExecutionResult confirmation = engine.execute(actionRequest("CANCEL", true), skill(), version(), null);
		assertEquals("CANCEL_CONFIRM", confirmation.message().payload().action());
		assertEquals(3L,
				((Number) map(confirmation.instance().getContextData(), "/runtime/contextRevision")).longValue());

		FlowExecutionResult restored = engine.execute(actionRequest("KEEP_FLOW", true), skill(), version(), null);
		assertEquals("review-history", restored.instance().getCurrentNodeId());
		assertEquals("REVIEW", restored.message().payload().action());
	}

	@Test
	void confirmCancelTerminatesDraftAndRejectsExecutionStates() {
		InMemoryFlowInstanceService instances = new InMemoryFlowInstanceService(waitingInstance("review-history", "{}",
				"{\"action\":\"REVIEW\"}"));
		DefaultFlowEngine engine = engine(instances);
		engine.execute(actionRequest("CANCEL", true), skill(), version(), null);

		FlowExecutionResult cancelled = engine.execute(actionRequest("CONFIRM_CANCEL", true), skill(), version(), null);

		assertTrue(cancelled.terminal());
		assertEquals(FlowInstanceStatus.CANCELLED.name(), cancelled.instance().getStatus());
		assertEquals("已取消当前流程。", cancelled.text());
		verify(toolInvoker, never()).invoke(any());

		for (FlowInstanceStatus status : new FlowInstanceStatus[] { FlowInstanceStatus.EXECUTING,
				FlowInstanceStatus.UNKNOWN }) {
			instances.instance.setStatus(status.name());
			assertThrows(CheckedException.class,
					() -> engine.execute(actionRequest("CANCEL", true), skill(), version(), null));
		}
	}

	@Test
	void clearReferenceRemovesOnlyFieldsStillOwnedByHistory() {
		String context = """
				{"input":{"arrivalTime":"2026-08-01","remark":"用户备注"},
				 "slotMeta":{"/input/arrivalTime":{"source":"HISTORY","status":"PROVIDED"},
				             "/input/remark":{"source":"USER","status":"PROVIDED"}},
				 "runtime":{"contextRevision":1}}
				""";
		InMemoryFlowInstanceService instances = new InMemoryFlowInstanceService(waitingInstance("review-history", context,
				"{\"action\":\"REVIEW\"}"));
		DefaultFlowEngine engine = engine(instances);

		FlowExecutionResult result = engine.execute(actionRequest("CLEAR_REFERENCE", true), skill(), version(), null);

		assertNull(map(result.instance().getContextData(), "/input/arrivalTime"));
		assertEquals("用户备注", map(result.instance().getContextData(), "/input/remark"));
		assertEquals("SKIP_CURRENT_FLOW", map(result.instance().getContextData(), "/runtime/referenceDecision"));
	}

	@Test
	void explicitNoHistoryMessageSkipsHistorySelection() {
		String context = """
				{"resolved":{"history":{"records":[{"label":"D-1","value":"1","rawData":{"remark":"历史"}}]}},
				 "runtime":{"contextRevision":1}}
				""";
		DataAgentFlowInstance instance = waitingInstance("select-history", context, "{}");
		instance.setStatus(FlowInstanceStatus.RUNNING.name());
		InMemoryFlowInstanceService instances = new InMemoryFlowInstanceService(instance);
		DefaultFlowEngine engine = engine(instances);
		FlowExecutionResult selection = engine.execute(queryRequest(null), skill(), historySelectionVersion(), null);
		assertEquals("SELECT", selection.message().payload().action());
		assertEquals(1, ((java.util.List<?>) selection.message().payload().values().get("skipCommands")).size());

		FlowExecutionResult result = engine.execute(queryRequest("不用历史数据"), skill(), historySelectionVersion(), null);

		assertEquals("REVIEW", result.message().payload().action());
		assertEquals("SKIP_CURRENT_FLOW", map(result.instance().getContextData(), "/runtime/referenceDecision"));
		assertNull(map(result.instance().getContextData(), "/resolved/historySelection"));
		verify(toolInvoker, never()).invoke(any());
	}

	@Test
	void skipHistoryButtonSetsDecisionForTheRestOfTheFlow() {
		String context = """
				{"resolved":{"history":{"records":[{"label":"D-1","value":"1","rawData":{"remark":"历史"}}]}},
				 "runtime":{"contextRevision":1}}
				""";
		DataAgentFlowInstance instance = waitingInstance("select-history", context, "{}");
		instance.setStatus(FlowInstanceStatus.RUNNING.name());
		InMemoryFlowInstanceService instances = new InMemoryFlowInstanceService(instance);
		DefaultFlowEngine engine = engine(instances);
		engine.execute(queryRequest(null), skill(), historySelectionVersion(), null);

		FlowExecutionResult result = engine.execute(actionRequest("SKIP", true), skill(), historySelectionVersion(), null);

		assertEquals("REVIEW", result.message().payload().action());
		assertEquals("SKIP_CURRENT_FLOW", map(result.instance().getContextData(), "/runtime/referenceDecision"));
	}

	@Test
	void reviewCustomerChangeUsesConfiguredResolverRoute() {
		DataAgentFlowInstance instance = waitingInstance("review-history",
				"{\"input\":{\"companyName\":\"旧客户\"},\"runtime\":{\"contextRevision\":1}}", "{}");
		instance.setStatus(FlowInstanceStatus.RUNNING.name());
		InMemoryFlowInstanceService instances = new InMemoryFlowInstanceService(instance);
		DefaultFlowEngine engine = engine(instances);
		ModelConfigDTO modelConfig = mock(ModelConfigDTO.class);
		FlowExecutionResult review = engine.execute(queryRequest(null), skill(), refreshRoutingVersion(), modelConfig);
		assertEquals("REVIEW", review.message().payload().action());
		when(fieldExtractor.extract(any(), any(), any(), any())).thenReturn(Map.of("companyName", "新客户"));

		FlowExecutionResult result = engine.execute(queryRequest("客户改成新客户"), skill(), refreshRoutingVersion(),
				modelConfig);

		assertEquals("CUSTOMER_REFRESH", result.message().payload().action());
		assertEquals("新客户", map(result.instance().getContextData(), "/input/companyName"));
	}

	@Test
	void reviewUnroutedArrivalChangeStaysOnUpdatedSummary() {
		DataAgentFlowInstance instance = waitingInstance("review-history",
				"{\"input\":{\"arrivalTime\":\"2026-08-01\"},\"runtime\":{\"contextRevision\":1}}", "{}");
		instance.setStatus(FlowInstanceStatus.RUNNING.name());
		InMemoryFlowInstanceService instances = new InMemoryFlowInstanceService(instance);
		DefaultFlowEngine engine = engine(instances);
		ModelConfigDTO modelConfig = mock(ModelConfigDTO.class);
		engine.execute(queryRequest(null), skill(), arrivalReviewVersion(), modelConfig);
		when(fieldExtractor.extract(any(), any(), any(), any())).thenReturn(Map.of("arrivalTime", "2026-08-03"));

		FlowExecutionResult result = engine.execute(queryRequest("到货时间改成8月3日"), skill(),
				arrivalReviewVersion(), modelConfig);

		assertEquals("REVIEW", result.message().payload().action());
		assertEquals("review-history", result.instance().getCurrentNodeId());
		assertTrue(result.text().contains("2026-08-03"));
		verify(toolInvoker, never()).invoke(any());
	}

	@Test
	void invalidTextSelectionReturnsCurrentWaitingStateWithoutChangingRevision() {
		InMemoryFlowInstanceService instances = new InMemoryFlowInstanceService(waitingInstance("review-history",
				"{\"runtime\":{\"contextRevision\":7}}",
				"{\"action\":\"SELECT\",\"options\":[{\"label\":\"客户A\",\"value\":\"option-1\"}]}"));
		DefaultFlowEngine engine = engine(instances);
		AgentRequest request = queryRequest("99");
		request.setFlowTextNoop(true);

		FlowExecutionResult result = engine.execute(request, skill(), version(), null);

		assertEquals("SELECT", result.message().payload().action());
		assertEquals(7L, ((Number) map(result.instance().getContextData(), "/runtime/contextRevision")).longValue());
		verify(toolInvoker, never()).invoke(any());
	}

	@Test
	void askDuringReviewPendingEditKeepsReviewWait() {
		when(fieldExtractor.extract(any(), any(), any(), any()))
				.thenReturn(Map.of("turnAction", "ask", "turnReply", "先核对当前信息。", "companyName", "胡说客户"));
		DataAgentFlowInstance instance = waitingInstance("review-history",
				"{\"input\":{\"companyName\":\"客户A\"},\"runtime\":{\"contextRevision\":1,\"pendingEditQuery\":\"客户改成B\"}}",
				"{}");
		instance.setStatus(FlowInstanceStatus.RUNNING.name());

		FlowExecutionResult result = engine(new InMemoryFlowInstanceService(instance))
				.execute(queryRequest("你能唱歌吗"), skill(), refreshRoutingVersion(), mock(ModelConfigDTO.class));

		assertEquals("REVIEW", result.message().payload().action());
		assertEquals("review-history", result.instance().getCurrentNodeId());
		assertEquals("客户A", map(result.instance().getContextData(), "/input/companyName"));
		assertEquals("客户改成B", map(result.instance().getContextData(), "/runtime/pendingEditQuery"));
	}

	@Test
	void confirmDirectEditUsesReviewExtractionAndReturnsUpdatedSummary() {
		InMemoryFlowInstanceService instances = new InMemoryFlowInstanceService(waitingInstance("confirm-submit",
				"{\"input\":{\"arrivalTime\":\"2026-08-01\"},\"runtime\":{\"contextRevision\":1}}",
				"{\"action\":\"CONFIRM\",\"editNode\":\"review-history\"}"));
		DefaultFlowEngine engine = engine(instances);
		ModelConfigDTO modelConfig = mock(ModelConfigDTO.class);
		when(fieldExtractor.extract(any(), any(), any(), any())).thenReturn(Map.of("arrivalTime", "2026-08-03"));
		AgentRequest request = actionRequest("EDIT", null);
		request.setQuery("到货时间改成8月3日");
		request.setFlowTextDirectEdit(true);

		FlowExecutionResult result = engine.execute(request, skill(), confirmDirectEditVersion(), modelConfig);

		assertEquals("REVIEW", result.message().payload().action());
		assertEquals("2026-08-03", map(result.instance().getContextData(), "/input/arrivalTime"));
		assertNull(map(result.instance().getContextData(), "/runtime/pendingEditQuery"));
		assertTrue(result.text().contains("2026-08-03"));
		verify(toolInvoker, never()).invoke(any());
	}

	@Test
	void issueReviewAdvancesOnlyAfterAllResolverIssuesAreCleared() {
		DataAgentFlowInstance clean = waitingInstance("review-issues",
				"{\"runtime\":{\"resolverIssues\":{\"products\":[],\"sites\":[]}}}", "{}");
		clean.setStatus(FlowInstanceStatus.RUNNING.name());
		FlowExecutionResult completed = engine(new InMemoryFlowInstanceService(clean))
				.execute(queryRequest(null), skill(), issueReviewVersion(), null);
		assertTrue(completed.terminal());

		DataAgentFlowInstance blocked = waitingInstance("review-issues",
				"{\"runtime\":{\"resolverIssues\":{\"products\":[\"商品 1 未匹配\"],\"sites\":[]}}}", "{}");
		blocked.setStatus(FlowInstanceStatus.RUNNING.name());
		FlowExecutionResult review = engine(new InMemoryFlowInstanceService(blocked))
				.execute(queryRequest(null), skill(), issueReviewVersion(), null);
		assertEquals("REVIEW", review.message().payload().action());
		assertTrue(Boolean.TRUE.equals(review.message().payload().values().get("blocking")));
	}

	@Test
	void defaultChatHidesInternalValuesButKeepsWaitingState() {
		DataAgentFlowInstance instance = waitingInstance("review-history",
				"{\"input\":{\"companyId\":\"company-1\",\"companyName\":\"客户A\"},\"runtime\":{\"contextRevision\":1}}",
				"{}");
		instance.setStatus(FlowInstanceStatus.RUNNING.name());
		FlowExecutionResult result = engine(new InMemoryFlowInstanceService(instance))
				.execute(queryRequest(null), skill(), version(), null);

		assertEquals("CHAT", result.message().payload().values().get("uiMode"));
		assertFalse(result.message().payload().values().containsKey("schema"));
		assertFalse(result.message().payload().values().containsKey("currentValues"));
		assertFalse(result.message().payload().values().toString().contains("company-1"));
		assertTrue(result.instance().getWaitingPayload().contains("company-1"));
	}

	@Test
	void summarySkipsBackendIdPathsEvenForUnvalidatedLegacyDefinition() {
		DataAgentFlowInstance instance = waitingInstance("review-history", """
				{"input":{"companyId":"company-1","companyName":"客户A",
				 "productList":[{"productId":"product-1","productName":"折叠箱"}]},
				 "runtime":{"contextRevision":2}}
				""", "{}");
		instance.setStatus(FlowInstanceStatus.RUNNING.name());
		FlowDefinitionValidator validator = mock(FlowDefinitionValidator.class);
		when(validator.validate(any())).thenReturn(List.of());
		FlowExecutionResult result = engine(new InMemoryFlowInstanceService(instance), validator)
				.execute(queryRequest(null), skill(), unsafeSummaryVersion(), null);

		assertTrue(result.text().contains("客户A"));
		assertTrue(result.text().contains("折叠箱"));
		assertFalse(result.text().contains("company-1"));
		assertFalse(result.text().contains("product-1"));
		assertTrue(result.instance().getWaitingPayload().contains("company-1"));
	}

	@Test
	void summaryDisplaysBusinessLabelsWithoutBackendIds() {
		DataAgentFlowInstance instance = waitingInstance("review-history", """
				{"input":{"demandNo":"D-1","companyId":"company-1","companyName":"客户A","type":"3",
				 "targetTwoProjectId":"target-1","targetTwoProjectName":"目标项目",
				 "productList":[{"productId":"product-1","productName":"折叠箱","productPrice":12.5,"num":20}],
				 "addressList":[{"siteId":"site-1","siteName":"收货点","districtId":"district-1","districtName":"浦东新区"}]},
				 "runtime":{"contextRevision":2}}
				""", "{}");
		instance.setStatus(FlowInstanceStatus.RUNNING.name());
		FlowExecutionResult result = engine(new InMemoryFlowInstanceService(instance))
				.execute(queryRequest(null), skill(), summaryVersion(), null);

		assertEquals("SUMMARY", result.message().payload().values().get("uiMode"));
		assertTrue(result.text().contains("客户A"));
		assertTrue(result.text().contains("送箱"));
		assertTrue(result.text().contains("D-1"));
		assertTrue(result.text().contains("目标项目"));
		assertTrue(result.text().contains("折叠箱"));
		assertTrue(result.text().contains("12.5"));
		assertTrue(result.text().contains("浦东新区"));
		assertFalse(result.text().contains("company-1"));
		assertFalse(result.text().contains("product-1"));
		assertFalse(result.text().contains("target-1"));
		assertFalse(result.text().contains("district-1"));
		assertTrue(result.instance().getWaitingPayload().contains("company-1"));
	}

	@Test
	void skippedReferenceInvalidationDoesNotFailConfirm() {
		DataAgentFlowInstance instance = waitingInstance("confirm-submit", """
				{"input":{"companyName":"客户A"},"runtime":{"contextRevision":2,
				 "referenceDecision":"SKIP_CURRENT_FLOW"},
				 "resolverState":{"lookup-ref":{"status":"INVALIDATED"}}}
				""", "{}");
		instance.setStatus(FlowInstanceStatus.RUNNING.name());
		FlowExecutionResult result = engine(new InMemoryFlowInstanceService(instance))
				.execute(queryRequest(null), skill(), skippedReferenceConfirmVersion(), null);

		assertEquals("CONFIRM", result.message().payload().action());
		assertFalse(result.terminal());
		assertFalse(result.text().contains("lookup-ref"));
	}

	@Test
	void confirmFailureHidesResolverIds() {
		DataAgentFlowInstance instance = waitingInstance("confirm-submit", """
				{"input":{"companyName":"客户A"},"runtime":{"contextRevision":2},
				 "resolverState":{"lookup-items":{"status":"INVALIDATED"}}}
				""", "{}");
		instance.setStatus(FlowInstanceStatus.RUNNING.name());
		FlowExecutionResult result = engine(new InMemoryFlowInstanceService(instance))
				.execute(queryRequest(null), skill(), blockingResolverConfirmVersion(), null);

		assertTrue(result.terminal());
		assertFalse(result.text().contains("lookup-items"));
		assertTrue(result.text().contains("主数据") || result.text().contains("解析"));
	}

	@Test
	void summaryCompactsTechnicalDumpAndTitlesSites() {
		DataAgentFlowInstance instance = waitingInstance("review-history", """
				{"input":{"companyName":"客户A","arrivalTime":"2026-09-03T16:00:00Z",
				 "productList":[{"productName":"折叠箱","productPrice":12.5,"num":20,"erpCode":"ERP-1",
				  "sizeLength":1200,"lng":121.1}],
				 "addressList":[{"type":"0","siteName":"杭州前进仓","lng":120.1,"lat":30.2,"siteCode":"HZ-1",
				  "tel":"13800000000"},{"type":"1","siteName":"杭州萧山仓","districtName":"萧山区",
				  "fullAddress":"杭州市萧山区"}]},
				 "runtime":{"contextRevision":2,"temporalPolicy":{"zoneId":"Asia/Shanghai"}}}
				""", "{}");
		instance.setStatus(FlowInstanceStatus.RUNNING.name());
		FlowExecutionResult result = engine(new InMemoryFlowInstanceService(instance))
				.execute(queryRequest(null), skill(), dumpedSummaryVersion(), null);

		assertTrue(result.text().contains("发货网点"));
		assertTrue(result.text().contains("收货网点"));
		assertTrue(result.text().contains("杭州前进仓"));
		assertTrue(result.text().contains("杭州萧山仓"));
		assertTrue(result.text().contains("折叠箱"));
		assertTrue(result.text().contains("12.5"));
		assertTrue(result.text().contains("萧山区"));
		assertFalse(result.text().contains("经度"));
		assertFalse(result.text().contains("120.1"));
		assertFalse(result.text().contains("ERP-1"));
		assertFalse(result.text().contains("13800000000"));
		assertFalse(result.text().contains("2026-09-03T16:00:00Z"));
		assertTrue(result.text().contains("2026-09-04"));
	}

	@Test
	void selectionPublishesOpaqueKeyAndKeepsRawDataForBackendMerge() {
		String context = """
				{"resolved":{"history":{"records":[{"label":"D-1","value":"demand-1",
				 "rawData":{"companyId":"company-1","companyName":"客户A"}}]}},
				 "runtime":{"contextRevision":1}}
				""";
		DataAgentFlowInstance instance = waitingInstance("select-history", context, "{}");
		instance.setStatus(FlowInstanceStatus.RUNNING.name());
		InMemoryFlowInstanceService instances = new InMemoryFlowInstanceService(instance);
		DefaultFlowEngine engine = engine(instances);

		FlowExecutionResult selection = engine.execute(queryRequest(null), skill(), historySelectionVersion(), null);
		Map<String, Object> option = selection.message().payload().options().get(0);
		assertEquals("option-1", option.get("value"));
		assertFalse(option.toString().contains("company-1"));
		assertTrue(selection.instance().getWaitingPayload().contains("company-1"));

		FlowExecutionResult selected = engine.execute(actionRequest("SELECT", "option-1"), skill(),
				historySelectionVersion(), null);
		assertEquals("REVIEW", selected.message().payload().action());
		assertEquals("company-1", map(selected.instance().getContextData(),
				"/resolved/historySelection/rawData/companyId"));
	}

	@Test
	void textCommandSelectListsNumberedBusinessOptionsInWaitingText() {
		String context = """
				{"resolved":{"history":{"records":[{"label":"云南万绿","value":"demand-1",
				 "summary":"客户编码 YNWL","rawData":{"companyId":"company-1"}}]}},
				 "runtime":{"contextRevision":1}}
				""";
		DataAgentFlowInstance instance = waitingInstance("select-history", context, "{}");
		instance.setStatus(FlowInstanceStatus.RUNNING.name());
		DefaultFlowEngine engine = engine(new InMemoryFlowInstanceService(instance));
		AgentRequest request = AgentRequest.builder().agentId("1").threadId("thread-1")
				.runtimeRequestId("runtime-im-select").tenantIdSnapshot("tenant-1").userIdSnapshot("user-1")
				.flowInstanceId("1")
				.interactionCapabilities(Set.of(ChannelInteractionCapability.TEXT_COMMANDS)).build();

		FlowExecutionResult selection = engine.execute(request, skill(), historySelectionVersion(), null);

		assertEquals("SELECT", selection.message().payload().action());
		assertTrue(selection.text().contains("1. 云南万绿 - 客户编码 YNWL"));
		assertTrue(selection.text().contains("请回复序号、候选名称或业务编码。"));
		assertTrue(selection.text().contains("不需要时可回复：不用历史数据"));
		assertFalse(selection.text().contains("company-1"));
		assertFalse(selection.text().contains("客服下单"));
	}

	@Test
	void formPatchPreservesHiddenBackendIds() {
		String uiSchema = "{\"type\":\"object\",\"properties\":{\"companyName\":{\"type\":\"string\"}}}";
		DataAgentFlowInstance instance = waitingInstance("collect", """
				{"input":{"companyId":"company-1","companyName":"旧客户"},"runtime":{"contextRevision":1}}
				""", "{\"action\":\"COLLECT\",\"uiMode\":\"FORM\",\"uiSchema\":" + uiSchema
				+ ",\"schema\":" + uiSchema + "}");
		FlowExecutionResult result = engine(new InMemoryFlowInstanceService(instance)).execute(
				formActionRequest(Map.of("companyName", "新客户", "companyId", "malicious-id")), skill(), formVersion(), null);

		assertEquals("company-1", map(result.instance().getContextData(), "/input/companyId"));
		assertEquals("新客户", map(result.instance().getContextData(), "/input/companyName"));
	}

	@Test
	void defaultActionsStayGenericAndExplicitActionsControlButtons() {
		DefaultFlowEngine engine = engine(new InMemoryFlowInstanceService(waitingInstance("review-history", "{}", "{}")));

		List<AgentUiMessage.Action> collectActions = ReflectionTestUtils.invokeMethod(engine, "actions", "COLLECT",
				Map.of("uiMode", "CHAT"));
		List<AgentUiMessage.Action> validationActions = ReflectionTestUtils.invokeMethod(engine, "actions", "VALIDATION",
				Map.of("uiMode", "CHAT"));
		List<AgentUiMessage.Action> formActions = ReflectionTestUtils.invokeMethod(engine, "actions", "COLLECT",
				Map.of("uiMode", "FORM"));
		List<AgentUiMessage.Action> reviewActions = ReflectionTestUtils.invokeMethod(engine, "actions", "REVIEW", Map.of());
		List<AgentUiMessage.Action> confirmActions = ReflectionTestUtils.invokeMethod(engine, "actions", "CONFIRM", Map.of());
		List<AgentUiMessage.Action> selectActions = ReflectionTestUtils.invokeMethod(engine, "actions", "SELECT", Map.of());
		List<AgentUiMessage.Action> explicitActions = ReflectionTestUtils.invokeMethod(engine, "actions", "CONFIRM",
				Map.of("uiActions", List.of(
						Map.of("actionId", "continue", "type", "CONFIRM", "label", "立即执行", "value", true),
						Map.of("actionId", "cancel", "type", "CANCEL", "label", "终止本流程", "value", false))));
		List<AgentUiMessage.Action> explicitEmpty = ReflectionTestUtils.invokeMethod(engine, "actions", "CONFIRM",
				Map.of("uiActions", List.of()));

		assertTrue(collectActions.isEmpty());
		assertTrue(validationActions.isEmpty());
		assertEquals(List.of("SUBMIT"), formActions.stream().map(AgentUiMessage.Action::type).toList());
		assertEquals(List.of("SUBMIT"), reviewActions.stream().map(AgentUiMessage.Action::type).toList());
		assertEquals(List.of("CONFIRM", "EDIT"),
				confirmActions.stream().map(AgentUiMessage.Action::type).toList());
		assertEquals(List.of("SELECT"), selectActions.stream().map(AgentUiMessage.Action::type).toList());
		assertEquals(List.of("立即执行", "终止本流程"),
				explicitActions.stream().map(AgentUiMessage.Action::label).toList());
		assertTrue(explicitEmpty.isEmpty());
	}

	private DefaultFlowEngine engine(FlowInstanceService instanceService) {
		return engine(instanceService, new FlowDefinitionValidator(objectMapper));
	}

	private DefaultFlowEngine engine(FlowInstanceService instanceService, FlowDefinitionValidator validator) {
		FlowContextMapper contextMapper = new FlowContextMapper();
		return new DefaultFlowEngine(validator, new FlowNodeExecutorRegistry(), instanceService,
				mock(FlowEventService.class), contextMapper, new FlowConditionEvaluator(contextMapper),
				new FlowSchemaValidator(), fieldExtractor, toolInvoker, resourceVersionMapper, objectMapper,
				capabilityGateway);
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

	private DataAgentSkill skill() {
		return DataAgentSkill.builder().id(1L).skillCode("demand-create").build();
	}

	private DataAgentSkillVersion version() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"review-history","nodes":[
				 {"id":"review-history","type":"review","next":"confirm-submit","config":{"editNode":"review-history"}},
				 {"id":"confirm-submit","type":"confirm","next":"execute-demand","config":{"editNode":"review-history"}},
				 {"id":"execute-demand","type":"execute","next":"end","config":{"resourceVersionId":10}},
				 {"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(2L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion historySelectionVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"select-history","nodes":[
				 {"id":"select-history","type":"select","next":"review-history","config":{
				  "optionsPath":"/resolved/history/records","targetPath":"/resolved/historySelection",
				  "autoSelectSingle":false,"allowSkip":true,
				  "skipCommands":["不用历史数据"],"skipDecisionPath":"/runtime/referenceDecision",
				  "skipDecisionValue":"SKIP_CURRENT_FLOW"}},
				 {"id":"review-history","type":"review","next":"end","config":{}},
				 {"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(3L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion refreshRoutingVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"review-history","nodes":[
				 {"id":"review-history","type":"review","next":"end","config":{"schema":{"type":"object",
				  "properties":{"companyName":{"type":"string"}}},
				  "refreshNext":"local-refresh","refreshRoutes":[
				   {"whenAny":["/input/companyId","/input/companyName"],"next":"customer-refresh"}]}},
				 {"id":"customer-refresh","type":"present","config":{"waitForAction":true,"action":"CUSTOMER_REFRESH"}},
				 {"id":"local-refresh","type":"present","config":{"waitForAction":true,"action":"LOCAL_REFRESH"}},
				 {"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(4L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion arrivalReviewVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"review-history","nodes":[
				 {"id":"review-history","type":"review","next":"end","config":{
				  "schema":{"type":"object","properties":{"arrivalTime":{"type":"string"}}},
				  "uiMode":"SUMMARY","displayFields":[{"label":"到货时间","path":"/input/arrivalTime"}]}},
				 {"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(8L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion confirmDirectEditVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"confirm-submit","nodes":[
				 {"id":"confirm-submit","type":"confirm","next":"end","config":{"editNode":"review-history"}},
				 {"id":"review-history","type":"review","next":"confirm-submit","config":{
				  "schema":{"type":"object","properties":{"arrivalTime":{"type":"string"}}},
				  "uiMode":"SUMMARY","displayFields":[{"label":"到货时间","path":"/input/arrivalTime"}]}},
				 {"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(10L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion issueReviewVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"review-issues","nodes":[
				 {"id":"review-issues","type":"review","next":"end","config":{"issuesPaths":[
				  "/runtime/resolverIssues/products","/runtime/resolverIssues/sites"]}},
				 {"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(9L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion skippedReferenceConfirmVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"confirm-submit","nodes":[
				 {"id":"lookup-ref","type":"resolve","next":"confirm-submit","config":{
				  "reference":true,"referencePolicyCode":"recent-reference",
				  "invalidateOn":["/input/type"]}},
				 {"id":"confirm-submit","type":"confirm","next":"end","config":{
				  "summary":"请确认是否提交。"}},
				 {"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(12L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion blockingResolverConfirmVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"confirm-submit","nodes":[
				 {"id":"lookup-items","type":"resolve","next":"confirm-submit","config":{
				  "forEach":"/input/productList"}},
				 {"id":"confirm-submit","type":"confirm","next":"end","config":{
				  "summary":"请确认是否提交。"}},
				 {"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(13L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion summaryVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"review-history","nodes":[
				 {"id":"review-history","type":"review","next":"end","config":{"uiMode":"SUMMARY",
				  "displayFields":[{"label":"参考需求单号","path":"/input/demandNo"},
				   {"label":"客户","path":"/input/companyName"},
				   {"label":"目标二级项目","path":"/input/targetTwoProjectName"},
				   {"label":"需求类型","path":"/input/type","valueLabels":{"3":"送箱"}}],
				  "displayCollections":[{"label":"商品","path":"/input/productList","itemFields":[
				   {"label":"名称","path":"/productName"},{"label":"单价","path":"/productPrice"},
				   {"label":"数量","path":"/num"}]},{"label":"地址","path":"/input/addressList","itemFields":[
				   {"label":"网点","path":"/siteName"},{"label":"区县","path":"/districtName"}]}]}},
				 {"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(5L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion dumpedSummaryVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"review-history","nodes":[
				 {"id":"review-history","type":"review","next":"end","config":{"uiMode":"SUMMARY",
				  "displayFields":[{"label":"客户","path":"/input/companyName"},
				   {"label":"到货时间","path":"/input/arrivalTime"}],
				  "displayCollections":[{"label":"商品","itemLabel":"商品","path":"/input/productList","itemFields":[
				   {"label":"ERP编码","path":"/erpCode"},{"label":"名称","path":"/productName"},
				   {"label":"长度","path":"/sizeLength"},{"label":"单价","path":"/productPrice"},
				   {"label":"数量","path":"/num"}]},
				   {"label":"网点地址","itemLabel":"地址","path":"/input/addressList","itemFields":[
				    {"label":"类型","path":"/type","valueLabels":{"0":"发货/提货","1":"收货/到达"}},
				    {"label":"网点","path":"/siteName"},{"label":"网点编码","path":"/siteCode"},
				    {"label":"经度","path":"/lng"},{"label":"纬度","path":"/lat"},
				    {"label":"联系电话","path":"/tel"},{"label":"完整地址","path":"/fullAddress"},
				    {"label":"区县","path":"/districtName"}]}]}},
				 {"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(11L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion formVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"collect","nodes":[
				 {"id":"collect","type":"collect","next":"end","config":{"uiMode":"FORM",
				  "requiredPaths":["/input/companyName"],"uiSchema":{"type":"object","properties":{
				   "companyName":{"type":"string"}}}}},
				 {"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(6L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion unsafeSummaryVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"review-history","nodes":[
				 {"id":"review-history","type":"review","next":"end","config":{"uiMode":"SUMMARY",
				  "displayFields":[{"label":"客户","path":"/input/companyName"},
				   {"label":"客户后台值","path":"/input/companyId"}],
				  "displayCollections":[{"label":"商品","path":"/input/productList","itemFields":[
				   {"label":"名称","path":"/productName"},{"label":"后台值","path":"/productId"}]}]}},
				 {"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(7L).skillId(1L).flowDefinition(definition).build();
	}

	private AgentRequest actionRequest(String action, Object value) {
		return AgentRequest.builder().agentId("1").threadId("thread-1").runtimeRequestId("runtime-" + action)
				.tenantIdSnapshot("tenant-1").userIdSnapshot("user-1").flowInstanceId("1")
				.flowAction(new FlowAction(action.toLowerCase(), action, value, Map.of())).build();
	}

	private AgentRequest queryRequest(String query) {
		return AgentRequest.builder().agentId("1").threadId("thread-1").runtimeRequestId("runtime-query")
				.tenantIdSnapshot("tenant-1").userIdSnapshot("user-1").flowInstanceId("1").query(query).build();
	}

	private AgentRequest formActionRequest(Map<String, Object> payload) {
		return AgentRequest.builder().agentId("1").threadId("thread-1").runtimeRequestId("runtime-form")
				.tenantIdSnapshot("tenant-1").userIdSnapshot("user-1").flowInstanceId("1")
				.flowAction(new FlowAction("submit-fields", "SUBMIT", true, payload)).build();
	}

	private DataAgentFlowInstance waitingInstance(String nodeId, String context, String waitingPayload) {
		return DataAgentFlowInstance.builder().id(1L).tenantId("tenant-1").agentId(1L).skillVersionId(2L)
				.skillCode("demand-create").threadId("thread-1").userId("user-1")
				.status(FlowInstanceStatus.WAITING.name()).currentNodeId(nodeId).contextData(context)
				.waitingPayload(waitingPayload).lockVersion(0).build();
	}

	private Object map(String json, String path) {
		try {
			return new FlowContextMapper().get(objectMapper.readValue(json, MAP_TYPE), path);
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
				current.setIdempotencyKey(idempotencyKey);
				current.setLockVersion(current.getLockVersion() + 1);
				current.setFinishedAt(finishedAt);
				return current;
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		}
	}

}
