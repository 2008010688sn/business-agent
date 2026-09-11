/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.entity.DataAgentFlowInstance;
import com.sn68.agent.dataagent.im.ChannelInteractionCapability;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FlowTextInteractionResolverTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final FlowTextInteractionResolver resolver = new FlowTextInteractionResolver(objectMapper);

	@Test
	void numberAndExactBusinessNameReuseCurrentServerOptions() {
		DataAgentFlowInstance instance = waiting("select-customer", """
				{"action":"SELECT","options":[
				 {"label":"云南万绿","value":"option-1","rawData":{"companyName":"云南万绿","companyCode":"YNWL"}},
				 {"label":"云南万绿物流","value":"option-2","rawData":{"companyName":"云南万绿物流","companyCode":"YNWL-2"}}
				]}
				""");

		AgentRequest number = imRequest("第2项");
		resolver.resolve(number, instance);
		assertEquals("SELECT", number.getFlowAction().type());
		assertEquals("option-2", number.getFlowAction().value());

		AgentRequest exactName = imRequest("云南万绿");
		resolver.resolve(exactName, instance);
		assertEquals("option-1", exactName.getFlowAction().value());
	}

	@Test
	void numberTakesPriorityOverNumericCandidateLabel() {
		DataAgentFlowInstance instance = waiting("select-customer", """
				{"action":"SELECT","options":[
				 {"label":"2","value":"option-1"},
				 {"label":"客户B","value":"option-2"}
				]}
				""");

		AgentRequest request = imRequest("2");
		resolver.resolve(request, instance);

		assertEquals("option-2", request.getFlowAction().value());
	}

	@Test
	void blockingReviewDoesNotMapContinueToSubmit() {
		DataAgentFlowInstance instance = waiting("review-resolver-issues", """
				{"action":"REVIEW","blocking":true,"errors":["未匹配到网点"],"uiActions":[
				 {"actionId":"clear-history","type":"CLEAR_REFERENCE","label":"清除历史参考"}
				]}
				""");

		AgentRequest request = imRequest("继续");
		resolver.resolve(request, instance);

		assertNull(request.getFlowAction());
	}

	@Test
	void configuredActionLabelsAreResolvedFromCurrentWaitingNode() {
		DataAgentFlowInstance instance = waiting("review-history", """
				{"action":"REVIEW","uiActions":[
				 {"actionId":"continue-order","type":"SUBMIT","label":"直接使用这些信息","value":true},
				 {"actionId":"clear-history","type":"CLEAR_REFERENCE","label":"不用这张历史单"}
				]}
				""");

		AgentRequest request = imRequest("直接使用这些信息");
		resolver.resolve(request, instance);

		assertEquals("continue-order", request.getFlowAction().actionId());
		assertEquals("SUBMIT", request.getFlowAction().type());
		assertEquals(true, request.getFlowAction().value());
	}

	@Test
	void invalidIndexDoesNotBecomeAFlowActionOrSearch() {
		DataAgentFlowInstance instance = waiting("select-customer", """
				{"action":"SELECT","options":[{"label":"客户A","value":"option-1"}],
				 "textSearch":{"enabled":true,"resolverNode":"resolve-customer","argumentName":"keyword"}}
				""");

		AgentRequest request = imRequest("99");
		resolver.resolve(request, instance);

		assertNull(request.getFlowAction());
		assertNull(request.getFlowTextSearchIntent());
		org.junit.jupiter.api.Assertions.assertTrue(request.isFlowTextNoop());
	}

	@Test
	void internalTextCapabilitiesAndSearchIntentAreNotSerialized() throws Exception {
		AgentRequest request = imRequest("客户A");
		request.setFlowTextSearchIntent(new FlowTextSearchIntent("select-customer", "resolve-customer",
				"/resolved/customer", "客户A", "keyword", false));

		String json = objectMapper.writeValueAsString(request);

		org.junit.jupiter.api.Assertions.assertFalse(json.contains("interactionCapabilities"));
		org.junit.jupiter.api.Assertions.assertFalse(json.contains("flowTextSearchIntent"));
		org.junit.jupiter.api.Assertions.assertFalse(json.contains("flowTextNoop"));
	}

	@Test
	void unmatchedSelectTextCreatesRequestLocalSearchIntent() {
		DataAgentFlowInstance instance = waiting("select-project", """
				{"action":"SELECT","targetPath":"/resolved/project","options":[],"textSearch":{
				 "enabled":true,"resolverNode":"resolve-project","argumentName":"keyword",
				 "matchPaths":["/twoProjectName","/twoProjectCode"]}}
				""");
		AgentRequest request = imRequest("昆明二期");

		resolver.resolve(request, instance);

		assertNull(request.getFlowAction());
		assertEquals("resolve-project", request.getFlowTextSearchIntent().resolverNodeId());
		assertEquals("昆明二期", request.getFlowTextSearchIntent().keyword());
		assertEquals("55", request.getFlowInstanceId());
	}

	@Test
	void typedCancelBecomesCancelActionWhenCardOffersCancel() {
		DataAgentFlowInstance instance = waiting("select-project", """
				{"action":"SELECT","targetPath":"/resolved/project","options":[],"uiActions":[
				 {"type":"SELECT","label":"选择","actionId":"select-option"},
				 {"type":"CANCEL","label":"取消流程","actionId":"cancel-flow"}],
				 "textSearch":{"enabled":true,"resolverNode":"resolve-project","argumentName":"keyword"}}
				""");
		AgentRequest cancel = imRequest("取消");
		AgentRequest terminate = imRequest("终止");

		resolver.resolve(cancel, instance);
		assertEquals("CANCEL", cancel.getFlowAction().type());
		assertEquals("cancel-flow", cancel.getFlowAction().actionId());
		assertNull(cancel.getFlowTextSearchIntent());

		resolver.resolve(terminate, instance);
		assertEquals("CANCEL", terminate.getFlowAction().type());
	}

	@Test
	void typedCancelWithoutCardCancelActionFallsBackToSearchIntent() {
		DataAgentFlowInstance instance = waiting("select-project", """
				{"action":"SELECT","targetPath":"/resolved/project","options":[],"textSearch":{
				 "enabled":true,"resolverNode":"resolve-project","argumentName":"keyword"}}
				""");
		AgentRequest request = imRequest("取消");

		resolver.resolve(request, instance);

		assertNull(request.getFlowAction());
		assertEquals("取消", request.getFlowTextSearchIntent().keyword());
	}

	@Test
	void webRequestNeverEntersTextCommandParsing() {
		DataAgentFlowInstance instance = waiting("select-customer",
				"{\"action\":\"SELECT\",\"options\":[{\"label\":\"客户A\",\"value\":\"option-1\"}]}");
		AgentRequest request = AgentRequest.builder().query("1").build();

		resolver.resolve(request, instance);

		assertNull(request.getFlowAction());
		assertNull(request.getFlowTextSearchIntent());
	}

	@Test
	void confirmAndCancelConfirmationUseCurrentWaitingActionOnly() {
		AgentRequest confirm = imRequest("确认提交");
		resolver.resolve(confirm, waiting("confirm-submit", "{\"action\":\"CONFIRM\"}"));
		assertEquals("CONFIRM", confirm.getFlowAction().type());

		AgentRequest cancel = imRequest("确认取消");
		resolver.resolve(cancel, waiting("confirm-submit", "{\"action\":\"CANCEL_CONFIRM\"}"));
		assertEquals("CONFIRM_CANCEL", cancel.getFlowAction().type());
	}

	@Test
	void typedFallbackLabelBecomesFallbackActionWhenCardOffersIt() {
		DataAgentFlowInstance instance = waiting("select-project", """
				{"action":"SELECT","targetPath":"/resolved/project","options":[{"label":"客户A","value":"option-1"}],
				 "uiActions":[{"type":"SELECT","label":"选择","actionId":"select-option"},
				  {"type":"FALLBACK","label":"重新选择","actionId":"reselect"}]}
				""");
		AgentRequest request = imRequest("重新选择");

		resolver.resolve(request, instance);

		// 配置了 fallbackNode 的选择卡带 FALLBACK 动作：键入其 label 直接触发重选。
		assertEquals("FALLBACK", request.getFlowAction().type());
		assertEquals("reselect", request.getFlowAction().actionId());
		assertNull(request.getFlowTextSearchIntent());
	}

	private AgentRequest imRequest(String query) {
		return AgentRequest.builder().query(query)
				.interactionCapabilities(Set.of(ChannelInteractionCapability.TEXT_COMMANDS)).build();
	}

	private DataAgentFlowInstance waiting(String nodeId, String payload) {
		DataAgentFlowInstance instance = new DataAgentFlowInstance();
		instance.setId(55L);
		instance.setStatus(FlowInstanceStatus.WAITING.name());
		instance.setCurrentNodeId(nodeId);
		instance.setWaitingPayload(payload);
		return instance;
	}

}
