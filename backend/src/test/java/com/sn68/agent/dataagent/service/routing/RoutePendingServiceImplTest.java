/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.dto.ClarificationResponse;
import com.sn68.agent.dataagent.entity.DataAgentRoutePending;
import com.sn68.agent.dataagent.repository.DataAgentRoutePendingMapper;
import com.sn68.agent.dataagent.routing.model.ExplicitRouteTarget;
import com.sn68.agent.dataagent.routing.model.RouteClarification;
import com.sn68.agent.dataagent.routing.model.RouteClarificationOption;
import com.sn68.agent.dataagent.routing.model.RouteDecisionType;
import com.sn68.agent.dataagent.routing.model.RouteDegradeMode;
import com.sn68.agent.dataagent.routing.model.DelegationMode;
import com.sn68.agent.dataagent.routing.model.RouteDecision;
import com.sn68.agent.dataagent.routing.model.RoutePlan;
import com.sn68.agent.dataagent.routing.model.RoutePlanStep;
import com.sn68.agent.dataagent.routing.model.RouteDependencyValueType;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import com.sn68.agent.dataagent.routing.model.RouteSelection;
import com.sn68.agent.dataagent.routing.model.RouteStepInputMapping;
import com.sn68.agent.dataagent.routing.model.RouteStepOutputBinding;
import com.sn68.agent.dataagent.routing.model.RouteTargetRef;
import com.sn68.agent.dataagent.routing.model.RouteTargetType;
import com.sn68.agent.dataagent.routing.model.RouteTiming;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RoutePendingServiceImplTest {

	private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");

	private static final String OWN_URL = "http://10.0.0.1:31770/dual-effect/receivable/account/detail?id=2087735796997206016";

	private DataAgentRoutePendingMapper pendingMapper;

	private RoutePendingServiceImpl service;

	private AtomicReference<DataAgentRoutePending> inserted;

	@BeforeEach
	void setUp() {
		pendingMapper = mock(DataAgentRoutePendingMapper.class);
		service = new RoutePendingServiceImpl(pendingMapper, new ObjectMapper());
		inserted = new AtomicReference<>();
		when(pendingMapper.insert(any(DataAgentRoutePending.class))).thenAnswer(invocation -> {
			DataAgentRoutePending pending = invocation.getArgument(0);
			pending.setId(99L);
			inserted.set(pending);
			return 1;
		});
	}

	@Test
	void createPendingSnapshotStripsLinkKeysMarker() throws Exception {
		AgentRequest request = request("runtime-1");
		request.setEffectiveRoutingQuery("query orders\n[link-keys id=2087 trust=own_origin]");

		service.create(request, RoutePendingService.TYPE_BUSINESS_CLARIFICATION, "query orders", 1, clarification(),
				null);

		String snapshot = inserted.get().getRouteSnapshot();
		assertFalse(snapshot.contains("[link-keys"));
		assertTrue(snapshot.contains("query orders"));
	}

	@Test
	void consumeWithoutTokenAlignsOptionLabelAndRestoresOriginalQuery() {
		AgentRequest request = request("runtime-1");
		service.create(request, RoutePendingService.TYPE_BUSINESS_CLARIFICATION, "query orders " + OWN_URL, 1,
				clarification(), null);
		when(pendingMapper.findLatestPending(eq("tenant-1"), eq(10L), eq("user-1"), eq("thread-6f9f61c5"),
				any(Instant.class))).thenReturn(inserted.get());
		when(pendingMapper.consume(eq(inserted.get()), eq("runtime-2"), any(Instant.class))).thenReturn(1);
		AgentRequest followUp = request("runtime-2");
		followUp.setQuery("Current month");
		followUp.setClarificationResponse(null);

		RoutePendingService.PendingResolution resolution = service.consume(followUp);

		assertEquals("query orders " + OWN_URL, resolution.originalQuery());
		assertTrue(resolution.effectiveQuery().contains("query orders " + OWN_URL));
		assertTrue(followUp.getQuery().contains("query orders " + OWN_URL));
		verify(pendingMapper).consume(eq(inserted.get()), eq("runtime-2"), any(Instant.class));
		verify(pendingMapper, never()).cancelPending(any(), any());
	}

	@Test
	void consumeWithoutTokenCancelsPendingWhenQueryIsANewQuestion() {
		AgentRequest request = request("runtime-1");
		service.create(request, RoutePendingService.TYPE_BUSINESS_CLARIFICATION, "query orders " + OWN_URL, 1,
				clarification(), null);
		when(pendingMapper.findLatestPending(eq("tenant-1"), eq(10L), eq("user-1"), eq("thread-6f9f61c5"),
				any(Instant.class))).thenReturn(inserted.get());
		inserted.get().setId(99L);
		AgentRequest followUp = request("runtime-2");
		followUp.setQuery("分析需求和运单");

		assertNull(service.consume(followUp));

		verify(pendingMapper).cancelPending(eq(99L), any(Instant.class));
		verify(pendingMapper, never()).consume(any(DataAgentRoutePending.class), anyString(), any(Instant.class));
		assertEquals("分析需求和运单", followUp.getQuery());
	}

	@Test
	void uuidThreadIdRemainsBoundAndCanConsumeClarification() {
		AgentRequest request = request("runtime-1");
		RoutePendingService.PendingInteraction interaction = service.create(request,
				RoutePendingService.TYPE_BUSINESS_CLARIFICATION, "query orders", 1, clarification(), null);
		when(pendingMapper.findActive(anyString(), any(Instant.class))).thenReturn(inserted.get());
		when(pendingMapper.consume(eq(inserted.get()), eq("runtime-2"), any(Instant.class))).thenReturn(1);
		request.setRuntimeRequestId("runtime-2");
		request.setClarificationResponse(new ClarificationResponse("business-clarify/v1",
				interaction.clarificationId(), List.of("o1"), null));

		RoutePendingService.PendingResolution resolution = service.consume(request);

		assertEquals("query orders", resolution.originalQuery());
		assertTrue(resolution.effectiveQuery().contains("current month"));
		assertEquals(1, resolution.round());
		verify(pendingMapper).consume(eq(inserted.get()), eq("runtime-2"), any(Instant.class));
	}

	@Test
	void invalidOptionDoesNotConsumePendingInteraction() {
		AgentRequest request = request("runtime-1");
		RoutePendingService.PendingInteraction interaction = service.create(request,
				RoutePendingService.TYPE_BUSINESS_CLARIFICATION, "query orders", 1, clarification(), null);
		when(pendingMapper.findActive(anyString(), any(Instant.class))).thenReturn(inserted.get());
		when(pendingMapper.consume(any(DataAgentRoutePending.class), anyString(), any(Instant.class))).thenReturn(1);
		request.setRuntimeRequestId("runtime-2");
		request.setClarificationResponse(new ClarificationResponse("business-clarify/v1",
				interaction.clarificationId(), List.of("unknown-option"), null));

		assertThrows(CheckedException.class, () -> service.consume(request));

		verify(pendingMapper, never()).consume(any(DataAgentRoutePending.class), anyString(), any(Instant.class));
	}

	@Test
	void incompleteSecurityBindingCannotCreatePendingInteraction() {
		AgentRequest missingTenant = request("runtime-1");
		missingTenant.setTenantIdSnapshot(null);
		AgentRequest missingUser = request("runtime-1");
		missingUser.setUserIdSnapshot(null);
		AgentRequest missingAgent = request("runtime-1");
		missingAgent.setAgentId(null);
		AgentRequest missingThread = request("runtime-1");
		missingThread.setThreadId(null);
		AgentRequest missingRuntimeRequest = request(null);

		for (AgentRequest invalid : List.of(missingTenant, missingUser, missingAgent, missingThread,
				missingRuntimeRequest)) {
			assertThrows(CheckedException.class, () -> service.create(invalid,
					RoutePendingService.TYPE_BUSINESS_CLARIFICATION, "query orders", 1, clarification(), null));
		}
	}

	@Test
	void clarificationRoundMustStayWithinTwoRoundLimit() {
		AgentRequest request = request("runtime-1");

		assertThrows(CheckedException.class, () -> service.create(request,
				RoutePendingService.TYPE_BUSINESS_CLARIFICATION, "query orders", 0, clarification(), null));
		assertThrows(CheckedException.class, () -> service.create(request,
				RoutePendingService.TYPE_ROUTE_CLARIFICATION, "query orders", 3, clarification(), null));
	}

	@Test
	void orchestrationContinuationPreservesStepSnapshotAndDelegationMode() {
		AgentRequest request = request("runtime-1");
		request.setCollaboratorChild(true);
		request.setOrchestrationRunId(11L);
		request.setOrchestrationStepId(12L);
		request.setCollaboratorDelegationMode(DelegationMode.INTERACTIVE.name());
		request.setOrchestrationFailureStrategy("continue");
		request.setOrchestrationExposeTrace(true);
		request.setParentAgentId("1");
		request.setParentThreadId("parent-thread-6f9f61c5");
		RouteTargetRef sourceTarget = new RouteTargetRef(RouteTargetType.COLLABORATOR, 21L, null, 21L);
		RouteTargetRef dependentTarget = new RouteTargetRef(RouteTargetType.COLLABORATOR, 22L, null, 22L);
		RouteSelection sourceSelection = new RouteSelection(sourceTarget, 31L, 41L, RouteRisk.READ_ONLY, "checksum-1");
		RouteSelection dependentSelection = new RouteSelection(dependentTarget, 32L, 42L, RouteRisk.READ_ONLY,
				"checksum-2");
		RoutePlan plan = new RoutePlan(List.of(
				new RoutePlanStep("s1", sourceTarget, "query orders", List.of(), "order identifiers",
						List.of(new RouteStepOutputBinding("orderIds", RouteDependencyValueType.ID)), List.of(),
						DelegationMode.INTERACTIVE.name(), 101L),
				new RoutePlanStep("s2", dependentTarget, "summarize orders", List.of("s1"), "order summary",
						List.of(), List.of(new RouteStepInputMapping("s1", "orderIds", "orderIds",
								RouteDependencyValueType.ID)), DelegationMode.INTERACTIVE.name(), 102L)));
		request.setOrchestrationDependencyInputs(Map.of("orderIds", List.of(1001L, 1002L)));
		request.setOrchestrationRouteSnapshot(new RouteDecision(RouteDecisionType.MULTI_SELECT, "MODEL_MULTI_SELECTED",
				RouteDegradeMode.NONE, List.of(sourceSelection, dependentSelection), List.of(), null, true,
				RouteTiming.empty(), null, plan));
		RoutePendingService.PendingInteraction interaction = service.create(request,
				RoutePendingService.TYPE_ROUTE_CLARIFICATION, "query orders", 1, clarification(), null);
		when(pendingMapper.findActive(anyString(), any(Instant.class))).thenReturn(inserted.get());
		when(pendingMapper.consume(eq(inserted.get()), eq("runtime-2"), any(Instant.class))).thenReturn(1);
		AgentRequest parentRequest = request("runtime-2");
		parentRequest.setAgentId("1");
		parentRequest.setThreadId("parent-thread-6f9f61c5");
		parentRequest.setClarificationResponse(new ClarificationResponse("business-clarify/v1",
				interaction.clarificationId(), List.of("o1"), null));

		RoutePendingService.PendingResolution resolution = service.consume(parentRequest);

		assertTrue(resolution.hasOrchestrationContinuation());
		assertEquals(DelegationMode.INTERACTIVE.name(),
				resolution.orchestrationContinuation().childDelegationMode());
		assertEquals(DelegationMode.INTERACTIVE.name(), resolution.orchestrationContinuation()
				.orchestrationRouteSnapshot().plan().steps().get(0).delegationMode());
		assertEquals("continue", resolution.orchestrationContinuation().executionPolicy().failureStrategy());
		assertTrue(resolution.orchestrationContinuation().executionPolicy().exposeTrace());
		assertTrue(resolution.orchestrationContinuation().childQuery().startsWith("query orders\n"));
		assertEquals("query orders", resolution.orchestrationContinuation().orchestrationRouteSnapshot()
				.plan().steps().get(0).queryFragment());
		Object dependencyValue = resolution.orchestrationContinuation().childDependencyInputs().get("orderIds");
		assertTrue(dependencyValue instanceof List<?>);
		assertEquals(List.of(1001L, 1002L), ((List<?>) dependencyValue).stream()
				.map(Number.class::cast)
				.map(Number::longValue)
				.toList());
		assertEquals(101L, resolution.orchestrationContinuation().orchestrationRouteSnapshot().plan().steps().get(0)
				.collaboratorAgentId());
		assertEquals(List.of(new RouteStepOutputBinding("orderIds", RouteDependencyValueType.ID)),
				resolution.orchestrationContinuation().orchestrationRouteSnapshot().plan().steps().get(0).outputBindings());
		assertEquals(List.of(new RouteStepInputMapping("s1", "orderIds", "orderIds", RouteDependencyValueType.ID)),
				resolution.orchestrationContinuation().orchestrationRouteSnapshot().plan().steps().get(1).inputMappings());
	}

	@Test
	void routeClarificationOptionAppliesStoredSkillSelectionWithoutRewritingQuery() {
		AgentRequest request = request("runtime-1");
		request.setQuery("你有哪些商品");
		RouteTargetRef target = new RouteTargetRef(RouteTargetType.SKILL, 1L, 11L, 21L);
		RouteSelection selection = new RouteSelection(target, 40L, 1L, RouteRisk.READ_ONLY, "checksum-1");
		RouteClarification clarification = new RouteClarification("您是想办理「知识问答」，还是「创建需求」？", "请选择要办理的事项",
				List.of(new RouteClarificationOption(null, "知识问答", "知识问答", selection)), true, "medium");
		RoutePendingService.PendingInteraction interaction = service.create(request,
				RoutePendingService.TYPE_ROUTE_CLARIFICATION, "你有哪些商品", 1, clarification, null);
		when(pendingMapper.findActive(anyString(), any(Instant.class))).thenReturn(inserted.get());
		when(pendingMapper.consume(eq(inserted.get()), eq("runtime-2"), any(Instant.class))).thenReturn(1);
		request.setRuntimeRequestId("runtime-2");
		request.setClarificationResponse(new ClarificationResponse("business-clarify/v1",
				interaction.clarificationId(), List.of("o1"), null));

		RoutePendingService.PendingResolution resolution = service.consume(request);

		assertEquals("你有哪些商品", resolution.originalQuery());
		assertEquals("你有哪些商品", resolution.effectiveQuery());
		assertFalse(resolution.effectiveQuery().contains("补充业务信息"));
		assertEquals(new ExplicitRouteTarget(RouteTargetType.SKILL, 1L, 11L, 40L, 1L),
				request.getExplicitRouteTarget());
		assertEquals("你有哪些商品", request.getQuery());
	}

	@Test
	void routeClarificationWithoutSelectionStillMergesSupplementText() {
		AgentRequest request = request("runtime-1");
		RoutePendingService.PendingInteraction interaction = service.create(request,
				RoutePendingService.TYPE_ROUTE_CLARIFICATION, "你有哪些商品", 1, clarification(), null);
		when(pendingMapper.findActive(anyString(), any(Instant.class))).thenReturn(inserted.get());
		when(pendingMapper.consume(eq(inserted.get()), eq("runtime-2"), any(Instant.class))).thenReturn(1);
		request.setRuntimeRequestId("runtime-2");
		request.setClarificationResponse(new ClarificationResponse("business-clarify/v1",
				interaction.clarificationId(), List.of("o1"), null));

		RoutePendingService.PendingResolution resolution = service.consume(request);

		assertTrue(resolution.effectiveQuery().contains("你有哪些商品"));
		assertTrue(resolution.effectiveQuery().contains("补充业务信息"));
		assertNull(request.getExplicitRouteTarget());
	}

	@Test
	void businessAndRouteClarificationsExposeOnlyBusinessInteractionFields() {
		RouteClarification clarification = new RouteClarification("请选择查询期间", "补充业务信息",
				List.of(new RouteClarificationOption(null, "本月", "本月", null)), true,
				"ROUTE_MODEL_FAILURE");
		Set<String> expectedFields = Set.of("schemaVersion", "clarificationId", "title", "prompt", "options",
				"allowFreeText", "expiresAt");

		for (String interactionType : List.of(RoutePendingService.TYPE_BUSINESS_CLARIFICATION,
				RoutePendingService.TYPE_ROUTE_CLARIFICATION)) {
			RoutePendingService.PendingInteraction interaction = service.create(request("runtime-" + interactionType),
					interactionType, "查询订单", 1, clarification, null);

			assertEquals(expectedFields, interaction.metadata().keySet());
			assertFalse(interaction.metadata().containsKey("riskLevel"));
			assertFalse(interaction.metadata().toString().contains("ROUTE_MODEL_FAILURE"));
		}
	}

	@Test
	void marksExpiredPendingInteractionsInOneConditionalUpdate() {
		when(pendingMapper.expirePending(NOW)).thenReturn(3);

		assertEquals(3, service.expirePending(NOW));

		verify(pendingMapper).expirePending(NOW);
	}

	@Test
	void finishesClaimedInteractionWithTheCurrentSecurityBinding() {
		AgentRequest request = request("runtime-2");

		service.finishExecution(request, RoutePendingService.EXECUTION_SUCCEEDED, "runtime-2");

		verify(pendingMapper).finishClaimed(eq("tenant-1"), eq(10L), eq("user-1"), eq("thread-6f9f61c5"),
				eq("runtime-2"), eq(RoutePendingService.EXECUTION_SUCCEEDED), eq("runtime-2"), any(Instant.class));
	}

	@Test
	void doesNotPersistUnknownExecutionState() {
		service.finishExecution(request("runtime-2"), "RUNNING", "runtime-2");

		verify(pendingMapper, never()).finishClaimed(anyString(), any(), anyString(), anyString(), anyString(),
				anyString(), anyString(), any(Instant.class));
	}

	@Test
	@SuppressWarnings("unchecked")
	void confirmationExposesOnlyAUserVisiblePlanSummary() {
		RouteTargetRef target = new RouteTargetRef(RouteTargetType.SKILL, 21L, 31L, 41L);
		RouteSelection selection = new RouteSelection(target, 51L, 61L, RouteRisk.WRITE, "internal-checksum");
		RouteDecision decision = RouteDecision.confirmRequired(List.of(selection),
				new RouteClarification("确认执行客户更新。", "确认完整计划",
						List.of(new RouteClarificationOption("confirm", "确认继续", "confirm", null),
								new RouteClarificationOption("cancel", "取消操作", "cancel", null)),
						false, "WRITE"),
				new RoutePlan(List.of(new RoutePlanStep("s1", target, "更新客户状态", List.of(), "更新选定客户状态"))),
				"CONFIRM_REQUIRED", RouteTiming.empty());

		RoutePendingService.PendingInteraction interaction = service.create(request("runtime-1"),
				RoutePendingService.TYPE_CONFIRMATION, "更新客户", 0, decision.clarification(), decision);

		assertEquals("确认执行客户更新。", interaction.metadata().get("summary"));
		List<Map<String, Object>> planSteps = (List<Map<String, Object>>) interaction.metadata().get("planSteps");
		assertEquals(List.of(Map.of("name", "步骤 1", "description", "更新选定客户状态", "riskLevel", "WRITE")),
				planSteps);
		assertFalse(interaction.metadata().toString().contains("internal-checksum"));
		assertFalse(interaction.metadata().containsKey("routeProfileId"));
	}

	private AgentRequest request(String runtimeRequestId) {
		return AgentRequest.builder()
			.agentId("10")
			.threadId("thread-6f9f61c5")
			.runtimeRequestId(runtimeRequestId)
			.tenantIdSnapshot("tenant-1")
			.userIdSnapshot("user-1")
			.query("query orders")
			.build();
	}

	private RouteClarification clarification() {
		return new RouteClarification("Which period?", "Clarify business scope",
				List.of(new RouteClarificationOption(null, "Current month", "current month", null)), true, "medium");
	}

}
