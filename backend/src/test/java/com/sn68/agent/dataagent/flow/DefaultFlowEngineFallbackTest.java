/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
import com.sn68.agent.dataagent.ui.AgentUiMessage;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * select 死路自动回退 + FALLBACK 重选动作回归：空候选且配置 fallbackNode 时自动清声明路径
 * 跳回重选点（每回合至多一次、跨回合可再次回退）、同回合二次死路落死路卡、FALLBACK 点击
 * 清路径改道、过期版本拒绝、未配置 fallbackNode 时拒绝重选。
 */
class DefaultFlowEngineFallbackTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final FlowFieldExtractor fieldExtractor = mock(FlowFieldExtractor.class, CALLS_REAL_METHODS);

	@Test
	void emptyOptionsAutoBouncesToFallbackNodeAndNotifies() {
		InMemoryFlowInstanceService instances = runningAt("select-project", bounceContext());

		FlowExecutionResult result = engine(instances).execute(request(null), skill(), fallbackVersion(),
				modelConfig());

		// 死路自动回退：清声明的选择路径后跳回重选链，用户直接面对下一个候选卡，全程不做抽取。
		verify(fieldExtractor, never()).extract(any(), any(), any(), any());
		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertEquals("select-customer", result.instance().getCurrentNodeId());
		assertEquals("SELECT", result.message().payload().action());
		assertTrue(result.text().contains("该客户没有可下单项目"));
		assertEquals(2, result.message().payload().options().size());
		Map<String, Object> context = readContext(result.instance().getContextData());
		Map<?, ?> resolved = (Map<?, ?>) context.get("resolved");
		assertNull(resolved.get("customer"));
		assertNull(resolved.get("project"));
	}

	@Test
	void fallbackRepeatsAcrossTurnsForUserDrivenReselect() {
		String context = """
				{"input":{"companyName":"箱箱智能科技"},
				 "resolved":{"customer":{"companyId":"C1"},"projects":{"items":[]}},
				 "runtime":{"contextRevision":1,"fallbackApplied":true}}
				""";
		InMemoryFlowInstanceService instances = runningAt("select-project", context);

		FlowExecutionResult result = engine(instances).execute(request(null), skill(), fallbackVersion(),
				modelConfig());

		// 回退标记按回合重置：换到客户 B 仍无项目时，下一回合依旧自动回退重选，不落死路卡。
		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertEquals("select-customer", result.instance().getCurrentNodeId());
		assertTrue(result.text().contains("该客户没有可下单项目"));
	}

	@Test
	void secondDeadEndInSameTurnFallsBackToDeadEndCard() {
		String context = """
				{"input":{"companyName":"箱箱智能科技"},
				 "resolved":{"customer":{"companyId":"C1"},"projects":{"items":[]},"customers":{"items":[]}},
				 "runtime":{"contextRevision":1}}
				""";
		InMemoryFlowInstanceService instances = runningAt("select-project", context);

		FlowExecutionResult result = engine(instances).execute(request(null), skill(), fallbackVersion(),
				modelConfig());

		// 同回合链上第二次死路（select-project 回退后 select-customer 仍空）：
		// fallbackApplied 已置位 → 落死路卡兜底，不循环弹跳。
		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertEquals("select-customer", result.instance().getCurrentNodeId());
		assertTrue(result.text().contains("请重新选择客户"));
		assertHasActionType(result.message().actions(), "CANCEL");
	}

	@Test
	void noFallbackConfigKeepsLegacyDeadEndCardWithoutBounce() {
		InMemoryFlowInstanceService instances = runningAt("select-customer", """
				{"input":{"companyName":"箱箱智能科技"},
				 "resolved":{"customers":{"items":[]}},
				 "runtime":{"contextRevision":1}}
				""");

		FlowExecutionResult result = engine(instances).execute(request(null), skill(), plainVersion(), modelConfig());

		// 未配置 fallbackNode：保持既有死路卡行为，不发生回退。
		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertEquals("select-customer", result.instance().getCurrentNodeId());
		assertHasActionType(result.message().actions(), "CANCEL");
	}

	@Test
	void fallbackClickClearsSelectionAndRebuildsCandidateCard() {
		InMemoryFlowInstanceService instances = fallbackWaitingInstance(1);

		FlowExecutionResult result = engine(instances).execute(fallbackRequest(Map.of("resumeVersion", 1)), skill(),
				fallbackVersion(), modelConfig());

		// FALLBACK 点击：清声明的选择路径并改道重选点，最终落在下一个候选卡，全程不抽取。
		verify(fieldExtractor, never()).extract(any(), any(), any(), any());
		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertEquals("select-customer", result.instance().getCurrentNodeId());
		assertEquals(2, result.message().payload().options().size());
		Map<String, Object> context = readContext(result.instance().getContextData());
		assertNull(((Map<?, ?>) context.get("resolved")).get("customer"));
	}

	@Test
	void fallbackClickWithStaleResumeVersionRejected() {
		InMemoryFlowInstanceService instances = fallbackWaitingInstance(1);

		CheckedException rejected = assertThrows(CheckedException.class,
				() -> engine(instances).execute(fallbackRequest(Map.of("resumeVersion", 99)), skill(),
						fallbackVersion(), modelConfig()));

		// 协议保护：FALLBACK 携带过期版本同样被 applyWaitingInput 的既有版本校验拒绝。
		assertTrue(String.valueOf(rejected.getMessage()).contains("过期版本"));
	}

	@Test
	void fallbackClickWithoutFallbackConfigRejected() {
		DataAgentFlowInstance instance = DataAgentFlowInstance.builder().id(1L).tenantId("tenant-1").agentId(1L)
				.skillVersionId(2L).skillCode("demand-create").threadId("thread-1").userId("user-1")
				.status(FlowInstanceStatus.WAITING.name()).currentNodeId("select-customer")
				.contextData("""
						{"input":{"companyName":"箱箱智能科技"},"resolved":{"customers":{"items":[]}},
						 "runtime":{"contextRevision":1}}
						""")
				.waitingPayload("""
						{"action":"SELECT","targetPath":"/resolved/customer",
						 "optionsPath":"/resolved/customers/items","options":[]}
						""")
				.lockVersion(0).resumeVersion(1).build();
		InMemoryFlowInstanceService instances = new InMemoryFlowInstanceService(instance);

		CheckedException rejected = assertThrows(CheckedException.class,
				() -> engine(instances).execute(fallbackRequest(Map.of("resumeVersion", 1)), skill(), plainVersion(),
						modelConfig()));

		assertTrue(String.valueOf(rejected.getMessage()).contains("不支持重新选择"));
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

	private Map<String, Object> readContext(String contextData) {
		try {
			return objectMapper.readValue(contextData, new TypeReference<>() {
			});
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private InMemoryFlowInstanceService runningAt(String currentNodeId, String context) {
		DataAgentFlowInstance instance = DataAgentFlowInstance.builder().id(1L).tenantId("tenant-1").agentId(1L)
				.skillVersionId(2L).skillCode("demand-create").threadId("thread-1").userId("user-1")
				.status(FlowInstanceStatus.RUNNING.name()).currentNodeId(currentNodeId)
				.contextData(context).waitingPayload("{}").lockVersion(0).build();
		return new InMemoryFlowInstanceService(instance);
	}

	private InMemoryFlowInstanceService fallbackWaitingInstance(Integer resumeVersion) {
		String waiting = """
				{"action":"SELECT","targetPath":"/resolved/project","optionsPath":"/resolved/projects/items",
				 "options":[{"label":"白糖项目","value":"option-1"},{"label":"鲜花项目","value":"option-2"}],
				 "uiActions":[{"type":"SELECT","label":"选择","actionId":"select-option"},
				  {"type":"FALLBACK","label":"重新选择","actionId":"reselect"}]}
				""";
		DataAgentFlowInstance instance = DataAgentFlowInstance.builder().id(1L).tenantId("tenant-1").agentId(1L)
				.skillVersionId(2L).skillCode("demand-create").threadId("thread-1").userId("user-1")
				.status(FlowInstanceStatus.WAITING.name()).currentNodeId("select-project")
				.contextData(bounceContext())
				.waitingPayload(waiting).lockVersion(0).resumeVersion(resumeVersion).build();
		return new InMemoryFlowInstanceService(instance);
	}

	private String bounceContext() {
		return """
				{"input":{"companyName":"箱箱智能科技"},
				 "resolved":{"customer":{"companyId":"C1"},
				  "projects":{"items":[]},
				  "customers":{"items":[
				   {"customerName":"云南万绿","customerId":"C001"},
				   {"customerName":"上海箱箱物流","customerId":"C002"}]}},
				 "runtime":{"contextRevision":1}}
				""";
	}

	private DataAgentSkillVersion fallbackVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"select-project","nodes":[
				 {"id":"select-project","type":"select","next":"end","config":{
				  "optionsPath":"/resolved/projects/items","targetPath":"/resolved/project",
				  "emptyNext":"collect-customer","fallbackNode":"collect-customer",
				  "fallbackClears":["/resolved/project","/resolved/customer"],
				  "emptyOptionsText":"该客户没有可下单项目，请换个客户。"}},
				 {"id":"collect-customer","type":"collect","next":"select-customer","config":{
				  "requiredPaths":["/input/companyName"]}},
				 {"id":"select-customer","type":"select","next":"end","config":{
				  "optionsPath":"/resolved/customers/items","targetPath":"/resolved/customer",
				  "emptyNext":"collect-customer","fallbackNode":"collect-customer",
				  "fallbackClears":["/resolved/customer"],
				  "emptyOptionsText":"请重新选择客户。"}},
				 {"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(2L).skillId(1L).flowDefinition(definition).build();
	}

	@Test
	void fallbackClearingIdentityFieldsMakesCollectAskInsteadOfReselectingSameCustomer() {
		String context = """
				{"input":{"companyName":"箱箱智能科技","companyId":"455405ba","companyCode":"KH001",
				 "industryName":"食品饮料","salesTeamName":"鲜展团队一组"},
				 "resolved":{"customer":{"companyId":"455405ba"},"projects":{"items":[]},"customers":{"items":[
				  {"customerName":"箱箱智能科技","customerId":"455405ba"}]}},
				 "runtime":{"contextRevision":1}}
				""";
		InMemoryFlowInstanceService instances = runningAt("select-project", context);

		FlowExecutionResult result = engine(instances).execute(request(null), skill(), identityClearingVersion(),
				modelConfig());

		// fallbackClears 含 /input 身份与派生字段：回退后 collect 不再满足，改为向用户要客户，
		// 而不是按旧名重查后 autoSelectSingle 自动选定同一客户（旧客户身份残留陷阱）。
		verify(fieldExtractor, never()).extract(any(), any(), any(), any());
		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertEquals("collect-customer", result.instance().getCurrentNodeId());
		assertTrue(result.text().contains("该客户没有可下单项目"));
		assertTrue(result.text().contains("请提供客户名称"));
		Map<String, Object> persisted = readContext(result.instance().getContextData());
		Map<?, ?> input = (Map<?, ?>) persisted.get("input");
		assertNull(input.get("companyName"));
		assertNull(input.get("companyId"));
		assertNull(input.get("industryName"));
		assertNull(((Map<?, ?>) persisted.get("resolved")).get("customer"));
		// 通知只出现一次（flowNotice 与正文相同不重复拼接）。
		int count = result.text().split("该客户没有可下单项目", -1).length - 1;
		assertEquals(1, count);
	}

	private DataAgentSkillVersion identityClearingVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"select-project","nodes":[
				 {"id":"select-project","type":"select","next":"end","config":{
				  "optionsPath":"/resolved/projects/items","targetPath":"/resolved/project",
				  "emptyNext":"collect-customer","fallbackNode":"collect-customer",
				  "fallbackClears":["/resolved/project","/resolved/customer",
				   "/input/companyName","/input/companyId","/input/companyCode",
				   "/input/industryName","/input/salesTeamName"],
				  "emptyOptionsText":"该客户没有可下单项目，请换个客户。"}},
				 {"id":"collect-customer","type":"collect","next":"select-customer","config":{
				  "prompt":"请提供客户名称或客户编码。",
				  "requiredPaths":["/input/companyName"]}},
				 {"id":"select-customer","type":"select","next":"end","config":{
				  "optionsPath":"/resolved/customers/items","targetPath":"/resolved/customer"}},
				 {"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(2L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion plainVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"select-customer","nodes":[
				 {"id":"select-customer","type":"select","next":"end","config":{
				  "optionsPath":"/resolved/customers/items","targetPath":"/resolved/customer",
				  "emptyNext":"collect-customer"}},
				 {"id":"collect-customer","type":"collect","next":"end","config":{
				  "requiredPaths":["/input/companyName"]}},
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

	private AgentRequest fallbackRequest(Map<String, Object> actionPayload) {
		AgentRequest request = request(null);
		request.setFlowAction(new FlowAction("reselect", "FALLBACK", null, actionPayload));
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
