/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.constant.AgentTypeConstant;
import com.sn68.agent.dataagent.employee.dto.EmployeeReleaseSnapshot;
import com.sn68.agent.dataagent.employee.service.EmployeeReleaseSnapshotResolver;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.entity.DataAgentFlowInstance;
import com.sn68.agent.dataagent.entity.DataAgentRouteProfile;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.flow.FlowInstanceStatus;
import com.sn68.agent.dataagent.flow.FlowPersistenceService;
import com.sn68.agent.dataagent.flow.FlowTextInteractionResolver;
import com.sn68.agent.dataagent.repository.AgentOrchestrationPolicyMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.repository.DataChatTurnMapper;
import com.sn68.agent.dataagent.routing.model.ExplicitRouteTarget;
import com.sn68.agent.dataagent.routing.model.RouteCandidate;
import com.sn68.agent.dataagent.routing.model.RouteDecision;
import com.sn68.agent.dataagent.routing.model.RouteDecisionType;
import com.sn68.agent.dataagent.routing.model.RouteDegradeMode;
import com.sn68.agent.dataagent.routing.model.RoutePlan;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import com.sn68.agent.dataagent.routing.model.RouteRules;
import com.sn68.agent.dataagent.routing.model.RoutePolicy;
import com.sn68.agent.dataagent.routing.model.RouteSelection;
import com.sn68.agent.dataagent.routing.model.RouteTargetRef;
import com.sn68.agent.dataagent.routing.model.RouteTargetType;
import com.sn68.agent.dataagent.routing.model.RouteTiming;
import com.sn68.agent.dataagent.routing.shadow.ShadowRouteCompileService;
import com.sn68.agent.dataagent.service.routing.RouteProfileService;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HybridRouteCoordinatorTest {

	private HybridRouteEngine engine;

	private RouteProfileService profileService;

	private SkillRouteCandidateProvider skillProvider;

	private FlowPersistenceService flowPersistenceService;

	private DataAgentSkillMapper skillMapper;

	private DataAgentSkillVersionMapper versionMapper;

	private EmployeeReleaseSnapshotResolver employeeSnapshotResolver;

	private DataChatTurnMapper turnMapper;

	private HybridRouteCoordinator coordinator;

	private DataAgent owner;

	private DataAgentRouteProfile profile;

	private RoutePolicy policy;

	@BeforeEach
	void setUp() {
		engine = mock(HybridRouteEngine.class);
		doCallRealMethod().when(engine).selectExplicit(any(RouteCandidate.class));
		profileService = mock(RouteProfileService.class);
		skillProvider = mock(SkillRouteCandidateProvider.class);
		flowPersistenceService = mock(FlowPersistenceService.class);
		skillMapper = mock(DataAgentSkillMapper.class);
		versionMapper = mock(DataAgentSkillVersionMapper.class);
		AuthenticationContext authenticationContext = mock(AuthenticationContext.class);
		employeeSnapshotResolver = mock(EmployeeReleaseSnapshotResolver.class);
		when(engine.totalTimeout()).thenReturn(Duration.ofSeconds(10));
		turnMapper = mock(DataChatTurnMapper.class);
		coordinator = new HybridRouteCoordinator(engine, profileService, skillProvider,
				mock(CollaboratorRouteCandidateProvider.class), mock(AgentOrchestrationPolicyMapper.class),
				flowPersistenceService, mock(FlowTextInteractionResolver.class), skillMapper,
				versionMapper, turnMapper, authenticationContext,
				mock(ShadowRouteCompileService.class), employeeSnapshotResolver,
				new RouteScorer(new RouteTextNormalizer()));
		owner = DataAgent.builder().id(10L).tenantId("tenant-1").status("published")
			.agentType(AgentTypeConstant.DATA_ANALYSIS).deleted(false).build();
		profile = DataAgentRouteProfile.builder().id(1L).status("ACTIVE")
			.embeddingFingerprint("embedding-v2").deleted(false).build();
		policy = RoutePolicy.initial(1L, "embedding-v2");
		when(authenticationContext.tenantId()).thenReturn("tenant-1");
		when(authenticationContext.userId()).thenReturn("100");
		when(profileService.requireUsable(null, false)).thenReturn(profile);
		when(profileService.toPolicy(profile)).thenReturn(policy);
	}

	@Test
	void explicitTargetIsHandledBeforeCapabilityIntent() {
		RouteCandidate candidate = candidate("tenant-1");
		when(skillProvider.load(any(), any(), any(), any())).thenReturn(List.of(candidate));

		var decision = coordinator.route(request("你能做什么", explicit(1L)), owner);

		assertEquals(RouteDecisionType.SELECT, decision.decision());
		assertEquals("EXPLICIT_TARGET", decision.reasonCode());
		verify(engine, never()).route(any(), any(), any(), any());
	}

	@Test
	void explicitTargetSelectsCandidateWithoutSemanticArtifact() {
		when(skillProvider.load(any(), any(), any(), any())).thenReturn(List.of(candidateWithoutArtifact("tenant-1")));

		var decision = coordinator.route(request("查询订单", explicitWithoutArtifact(1L)), owner);

		assertEquals(RouteDecisionType.SELECT, decision.decision());
		assertEquals("EXPLICIT_TARGET", decision.reasonCode());
		verify(engine, never()).route(any(), any(), any(), any());
	}

	@Test
	void explicitWriteTargetStillRequiresTopLevelConfirmation() {
		when(skillProvider.load(any(), any(), any(), any())).thenReturn(List.of(writeCandidate("tenant-1")));

		RouteDecision decision = coordinator.route(request("update order", explicit(1L)), owner);

		assertEquals(RouteDecisionType.CONFIRM_REQUIRED, decision.decision());
		assertEquals("EXPLICIT_TARGET_CONFIRM_REQUIRED", decision.reasonCode());
	}

	@Test
	void explicitTargetRejectsMissingArtifactForCandidateWithSemanticArtifact() {
		when(skillProvider.load(any(), any(), any(), any())).thenReturn(List.of(candidate("tenant-1")));

		var decision = coordinator.route(request("查询订单", explicitWithoutArtifact(1L)), owner);

		assertEquals(RouteDecisionType.CLARIFY, decision.decision());
		assertEquals("EXPLICIT_TARGET_INVALID", decision.reasonCode());
	}

	@Test
	void explicitTargetRejectsWrongSkillVersion() {
		when(skillProvider.load(any(), any(), any(), any())).thenReturn(List.of(candidate("tenant-1")));

		var decision = coordinator.route(request("查询订单",
				new ExplicitRouteTarget(RouteTargetType.SKILL, 30L, 99L, 40L, 1L)), owner);

		assertEquals(RouteDecisionType.CLARIFY, decision.decision());
		assertEquals("EXPLICIT_TARGET_INVALID", decision.reasonCode());
	}

	@Test
	void explicitTargetRejectsIncompletePayload() {
		when(skillProvider.load(any(), any(), any(), any())).thenReturn(List.of(candidate("tenant-1")));

		List<ExplicitRouteTarget> invalidTargets = List.of(
				new ExplicitRouteTarget(RouteTargetType.SKILL, null, 31L, 40L, 1L),
				new ExplicitRouteTarget(RouteTargetType.SKILL, 30L, 31L, 40L, null),
				new ExplicitRouteTarget(RouteTargetType.SKILL, 30L, null, 40L, 1L));

		for (ExplicitRouteTarget invalidTarget : invalidTargets) {
			var decision = coordinator.route(request("查询账单", invalidTarget), owner);

			assertEquals(RouteDecisionType.CLARIFY, decision.decision());
			assertEquals("EXPLICIT_TARGET_INVALID", decision.reasonCode());
		}
	}

	@Test
	void explicitTargetRejectsCandidateOutsideRequestTenant() {
		when(skillProvider.load(any(), any(), any(), any())).thenReturn(List.of(candidate("tenant-2")));

		var decision = coordinator.route(request("查询订单", explicit(1L)), owner);

		assertEquals(RouteDecisionType.CLARIFY, decision.decision());
		assertEquals("EXPLICIT_TARGET_INVALID", decision.reasonCode());
	}

	@Test
	void explicitTargetRejectsStaleProfile() {
		when(skillProvider.load(any(), any(), any(), any())).thenReturn(List.of(candidate("tenant-1")));

		var decision = coordinator.route(request("查询订单", explicit(2L)), owner);

		assertEquals(RouteDecisionType.CLARIFY, decision.decision());
		assertEquals("EXPLICIT_TARGET_PROFILE_STALE", decision.reasonCode());
	}

	@Test
	void resolvesPolicyBeforeLoadingCandidates() {
		when(skillProvider.load(any(), any(), any(), any())).thenReturn(List.of(candidate("tenant-1")));

		coordinator.route(request("查询订单", explicit(1L)), owner);

		var ordered = inOrder(profileService, skillProvider);
		ordered.verify(profileService).toPolicy(profile);
		ordered.verify(skillProvider).load(any(), eq(profile), eq(policy), any());
	}

	@Test
	void activeFlowKeepsUsingPinnedPublishedVersionWhileNewDraftExists() {
		DataAgentFlowInstance activeFlow = DataAgentFlowInstance.builder().id(50L).tenantId("tenant-1")
			.agentId(10L).skillId(30L).skillVersionId(31L).status(FlowInstanceStatus.WAITING.name()).build();
		DataAgentSkill skill = DataAgentSkill.builder().id(30L).tenantId("tenant-1").status("PUBLISHED")
			.publishedVersionId(31L).latestDraftVersionId(32L).build();
		DataAgentSkillVersion pinnedVersion = DataAgentSkillVersion.builder().id(31L).tenantId("tenant-1")
			.skillId(30L).status("PUBLISHED").skillKind("ACTION").executionMode("FLOW").build();
		when(flowPersistenceService.findActive(any())).thenReturn(activeFlow);
		when(skillMapper.selectById(30L)).thenReturn(skill);
		when(versionMapper.selectById(31L)).thenReturn(pinnedVersion);

		RouteDecision decision = coordinator.route(request("继续", null), owner);

		assertEquals(RouteDecisionType.SELECT, decision.decision());
		assertEquals("ACTIVE_FLOW", decision.reasonCode());
		assertEquals(31L, decision.selections().get(0).target().targetVersionId());
	}

	@Test
	void activeFlowSkipsRerouteWhenQueryWouldMatchAnotherSkill() {
		DataAgentFlowInstance activeFlow = DataAgentFlowInstance.builder().id(50L).tenantId("tenant-1")
			.agentId(10L).skillId(30L).skillVersionId(31L).status(FlowInstanceStatus.WAITING.name()).build();
		DataAgentSkill skill = DataAgentSkill.builder().id(30L).tenantId("tenant-1").status("PUBLISHED")
			.publishedVersionId(31L).latestDraftVersionId(32L).build();
		DataAgentSkillVersion pinnedVersion = DataAgentSkillVersion.builder().id(31L).tenantId("tenant-1")
			.skillId(30L).status("PUBLISHED").skillKind("ACTION").executionMode("FLOW").build();
		when(flowPersistenceService.findActive(any())).thenReturn(activeFlow);
		when(skillMapper.selectById(30L)).thenReturn(skill);
		when(versionMapper.selectById(31L)).thenReturn(pinnedVersion);
		stubEngineSelect(candidate("tenant-1"));

		RouteDecision decision = coordinator.route(request("查询订单", null), owner);

		assertEquals(RouteDecisionType.SELECT, decision.decision());
		assertEquals("ACTIVE_FLOW", decision.reasonCode());
		assertEquals(31L, decision.selections().get(0).target().targetVersionId());
		verify(skillProvider, never()).load(any(), any(), any(), any());
		verify(engine, never()).route(any(), any(), any(), any());
	}

	@Test
	void confirmedRouteIsRevalidatedAndReturnedAsExecutableSelection() {
		RouteCandidate candidate = writeCandidate("tenant-1");
		RouteSelection selection = RouteSelection.from(candidate);
		RouteDecision snapshot = RouteDecision.confirmRequired(List.of(selection), null,
				RoutePlan.single(selection, "query orders", "return orders"), "WRITE_CONFIRM_REQUIRED",
				com.sn68.agent.dataagent.routing.model.RouteTiming.empty());
		when(skillProvider.load(any(), any(), any(), any())).thenReturn(List.of(candidate));
		AgentRequest request = request("query orders", null);

		RouteDecision confirmed = coordinator.validateConfirmed(request, owner, snapshot);

		assertEquals(RouteDecisionType.SELECT, confirmed.decision());
		assertEquals(List.of(selection), confirmed.selections());
		assertEquals(snapshot.plan(), confirmed.plan());
		assertTrue(request.isRouteConfirmed());
		verify(engine, never()).route(any(), any(), any(), any());
	}

	@Test
	void confirmedRouteRejectsOwnerThatIsNoLongerPublished() {
		RouteCandidate candidate = writeCandidate("tenant-1");
		RouteSelection selection = RouteSelection.from(candidate);
		RouteDecision snapshot = RouteDecision.confirmRequired(List.of(selection), null,
				RoutePlan.single(selection, "query orders", "return orders"), "WRITE_CONFIRM_REQUIRED",
				com.sn68.agent.dataagent.routing.model.RouteTiming.empty());
		when(skillProvider.load(any(), any(), any(), any())).thenReturn(List.of(candidate));
		owner.setStatus("draft");

		assertThrows(RouteUnavailableException.class,
				() -> coordinator.validateConfirmed(request("query orders", null), owner, snapshot));
	}

	@Test
	void confirmedRouteRejectsSelectionThatDoesNotRequireConfirmation() {
		RouteCandidate candidate = candidate("tenant-1");
		RouteSelection selection = RouteSelection.from(candidate);
		RouteDecision snapshot = RouteDecision.confirmRequired(List.of(selection), null,
				RoutePlan.single(selection, "query orders", "return orders"), "READ_CONFIRM_REQUIRED",
				com.sn68.agent.dataagent.routing.model.RouteTiming.empty());
		when(skillProvider.load(any(), any(), any(), any())).thenReturn(List.of(candidate));

		assertThrows(RouteUnavailableException.class,
				() -> coordinator.validateConfirmed(request("query orders", null), owner, snapshot));
	}

	@Test
	void confirmedRouteRejectsMismatchedPlanBeforeMarkingRequestConfirmed() {
		RouteCandidate candidate = writeCandidate("tenant-1");
		RouteSelection selection = RouteSelection.from(candidate);
		RouteSelection other = new RouteSelection(
				new RouteTargetRef(RouteTargetType.SKILL, 99L, 100L, 101L), 1L, 2L,
				RouteRisk.WRITE, "other-checksum");
		RouteDecision snapshot = mock(RouteDecision.class);
		when(snapshot.decision()).thenReturn(RouteDecisionType.CONFIRM_REQUIRED);
		when(snapshot.selections()).thenReturn(List.of(selection));
		when(snapshot.degradeMode()).thenReturn(com.sn68.agent.dataagent.routing.model.RouteDegradeMode.NONE);
		when(snapshot.timing()).thenReturn(com.sn68.agent.dataagent.routing.model.RouteTiming.empty());
		when(snapshot.plan()).thenReturn(RoutePlan.single(other, "other query", "other result"));
		when(skillProvider.load(any(), any(), any(), any())).thenReturn(List.of(candidate));
		AgentRequest request = request("query orders", null);

		assertThrows(RouteUnavailableException.class,
				() -> coordinator.validateConfirmed(request, owner, snapshot));
		assertFalse(request.isRouteConfirmed());
	}

	@Test
	void dataAgentRouteKeepsNullProfileAndDoesNotResolveEmployeeSnapshot() {
		stubEngineSelect(candidate("tenant-1"));

		coordinator.route(request("查询订单", null), owner);

		verify(employeeSnapshotResolver, never()).resolveById(any(), any(), any());
		verify(employeeSnapshotResolver, never()).resolveActive(anyString(), any(), anyString());
		verify(profileService).requireUsable(isNull(), eq(false));
	}

	@Test
	void digitalEmployeeRouteUsesSnapshotRouteProfileId() {
		EmployeeReleaseSnapshot snapshot = employeeSnapshot(40L);
		when(employeeSnapshotResolver.resolveById("tenant-1", 9L, 12L)).thenReturn(snapshot);
		DataAgentRouteProfile sealed = DataAgentRouteProfile.builder().id(40L).status("ACTIVE")
			.embeddingFingerprint("embedding-v2").deleted(false).build();
		when(profileService.requireUsable(40L, false)).thenReturn(sealed);
		when(profileService.toPolicy(sealed)).thenReturn(RoutePolicy.initial(40L, "embedding-v2"));
		stubEngineSelect(candidate("tenant-1"));

		coordinator.route(employeeRequest("查询订单", 12L, "DIGITAL_EMPLOYEE"), owner);

		verify(employeeSnapshotResolver).resolveById(eq("tenant-1"), eq(9L), eq(12L));
		verify(employeeSnapshotResolver, never()).resolveActive(anyString(), any(), anyString());
		verify(profileService).requireUsable(eq(40L), eq(false));
		verify(profileService, never()).requireUsable(isNull(), eq(false));
	}

	@Test
	void digitalEmployeeRouteFallsBackToNullWhenSnapshotOmitsRouteProfileId() {
		when(employeeSnapshotResolver.resolveById("tenant-1", 9L, 12L)).thenReturn(employeeSnapshot(null));
		stubEngineSelect(candidate("tenant-1"));

		coordinator.route(employeeRequest("查询订单", 12L, "DIGITAL_EMPLOYEE"), owner);

		verify(employeeSnapshotResolver).resolveById(eq("tenant-1"), eq(9L), eq(12L));
		verify(profileService).requireUsable(isNull(), eq(false));
	}

	@Test
	void digitalEmployeeWithoutReleaseIdDoesNotResolveSnapshotAndKeepsNullProfile() {
		stubEngineSelect(candidate("tenant-1"));

		coordinator.route(employeeRequest("查询订单", null, "DIGITAL_EMPLOYEE"), owner);

		verify(employeeSnapshotResolver, never()).resolveById(any(), any(), any());
		verify(employeeSnapshotResolver, never()).resolveActive(anyString(), any(), anyString());
		verify(profileService).requireUsable(isNull(), eq(false));
	}

	@Test
	void releaseIdWithoutOwnerTypeStillPinsSnapshotRouteProfileId() {
		when(employeeSnapshotResolver.resolveById("tenant-1", 9L, 12L)).thenReturn(employeeSnapshot(40L));
		DataAgentRouteProfile sealed = DataAgentRouteProfile.builder().id(40L).status("ACTIVE")
			.embeddingFingerprint("embedding-v2").deleted(false).build();
		when(profileService.requireUsable(40L, false)).thenReturn(sealed);
		when(profileService.toPolicy(sealed)).thenReturn(RoutePolicy.initial(40L, "embedding-v2"));
		stubEngineSelect(candidate("tenant-1"));

		coordinator.route(employeeRequest("查询订单", 12L, null), owner);

		verify(employeeSnapshotResolver).resolveById(eq("tenant-1"), eq(9L), eq(12L));
		verify(profileService).requireUsable(eq(40L), eq(false));
		verify(profileService, never()).requireUsable(isNull(), eq(false));
	}

	@Test
	void analysisSessionDrillPinsPreviousReadOnlySkill() {
		RouteCandidate previous = candidate("tenant-1");
		RouteCandidate other = new RouteCandidate(new RouteTargetRef(RouteTargetType.SKILL, 99L, 100L, 101L),
				"tenant-1", 10L, "项目利润", "项目利润", "QUERY", "REACT", RouteRules.empty(), RouteRisk.READ_ONLY, 0,
				1L, 50L, "checksum-other", "embedding-v2");
		when(skillProvider.load(any(), any(), any(), any())).thenReturn(List.of(other, previous));
		com.sn68.agent.dataagent.entity.DataChatTurn turn = new com.sn68.agent.dataagent.entity.DataChatTurn();
		turn.setQuestion("查询上个月各项目的账单情况");
		turn.setRouteTargetType("SKILL");
		turn.setRouteTargetId(30L);
		turn.setRouteTargetVersionId(31L);
		when(turnMapper.findRecentSuccessfulSingleSelection(any(), any(), any(), any())).thenReturn(turn);

		RouteDecision decision = coordinator.route(request(
				"请在同一分析会话中只看「太阳一号项目」的更细粒度结果，保持只读，不要办理。原问题：查询上个月各项目的账单情况",
				null), owner);

		assertEquals(RouteDecisionType.SELECT, decision.decision());
		assertEquals("ANALYSIS_SESSION_CONTINUATION", decision.reasonCode());
		assertEquals(30L, decision.selections().get(0).target().targetId());
		verify(engine, never()).route(any(), any(), any(), any());
	}

	@Test
	void noIndependentSignalFollowUpPinsPreviousReadOnlySkill() {
		RouteCandidate previous = candidate("tenant-1");
		RouteCandidate other = new RouteCandidate(new RouteTargetRef(RouteTargetType.SKILL, 99L, 100L, 101L),
				"tenant-1", 10L, "项目利润", "项目利润", "QUERY", "REACT", RouteRules.empty(), RouteRisk.READ_ONLY, 0,
				1L, 50L, "checksum-other", "embedding-v2");
		when(skillProvider.load(any(), any(), any(), any())).thenReturn(List.of(other, previous));
		when(turnMapper.findRecentSuccessfulSingleSelection(any(), any(), any(), any())).thenReturn(previousTurn());

		RouteDecision decision = coordinator.route(request("它包含哪些需求", null), owner);

		assertEquals(RouteDecisionType.SELECT, decision.decision());
		assertEquals("ANALYSIS_SESSION_CONTINUATION", decision.reasonCode());
		assertEquals(30L, decision.selections().get(0).target().targetId());
		verify(engine, never()).route(any(), any(), any(), any());
	}

	@Test
	void independentSkillSignalDoesNotPinPreviousSkill() {
		RouteCandidate previous = candidate("tenant-1");
		RouteCandidate other = new RouteCandidate(new RouteTargetRef(RouteTargetType.SKILL, 99L, 100L, 101L),
				"tenant-1", 10L, "项目利润", "项目利润", "QUERY", "REACT",
				new RouteRules(List.of(), List.of("项目利润"), List.of(), List.of(), List.of(), List.of()),
				RouteRisk.READ_ONLY, 0, 1L, 50L, "checksum-other", "embedding-v2");
		when(skillProvider.load(any(), any(), any(), any())).thenReturn(List.of(other, previous));
		when(turnMapper.findRecentSuccessfulSingleSelection(any(), any(), any(), any())).thenReturn(previousTurn());
		when(engine.route(any(), any(), any(), any())).thenReturn(RouteDecision.select(other, "LEXICAL_HIGH_CONFIDENCE",
				RouteDegradeMode.NONE, false, RouteTiming.empty()));

		RouteDecision decision = coordinator.route(request("查项目利润", null), owner);

		assertEquals(RouteDecisionType.SELECT, decision.decision());
		assertEquals("LEXICAL_HIGH_CONFIDENCE", decision.reasonCode());
		assertEquals(99L, decision.selections().get(0).target().targetId());
		verify(engine).route(any(), any(), any(), any());
	}

	@Test
	void writePreviousSkillIsNotPinned() {
		RouteCandidate previousWrite = writeCandidate("tenant-1");
		RouteCandidate other = new RouteCandidate(new RouteTargetRef(RouteTargetType.SKILL, 99L, 100L, 101L),
				"tenant-1", 10L, "项目利润", "项目利润", "QUERY", "REACT", RouteRules.empty(), RouteRisk.READ_ONLY, 0,
				1L, 50L, "checksum-other", "embedding-v2");
		when(skillProvider.load(any(), any(), any(), any())).thenReturn(List.of(other, previousWrite));
		when(turnMapper.findRecentSuccessfulSingleSelection(any(), any(), any(), any())).thenReturn(previousTurn());
		when(engine.route(any(), any(), any(), any()))
			.thenReturn(RouteDecision.noMatch(RouteTiming.empty()));

		RouteDecision decision = coordinator.route(request("它包含哪些需求", null), owner);

		assertEquals(RouteDecisionType.NO_MATCH, decision.decision());
		verify(engine).route(any(), any(), any(), any());
	}

	@Test
	void callerOwnerWithReleaseIdKeepsDataAgentNullProfile() {
		stubEngineSelect(candidate("tenant-1"));
		AgentRequest request = employeeRequest("查询订单", 12L, "CALLER");

		coordinator.route(request, owner);

		verify(employeeSnapshotResolver, never()).resolveById(any(), any(), any());
		verify(employeeSnapshotResolver, never()).resolveActive(anyString(), any(), anyString());
		verify(profileService).requireUsable(isNull(), eq(false));
	}

	private AgentRequest request(String query, ExplicitRouteTarget explicit) {
		return AgentRequest.builder()
			.query(query)
			.threadId("200")
			.runtimeRequestId("request-1")
			.tenantIdSnapshot("tenant-1")
			.userIdSnapshot("100")
			.explicitRouteTarget(explicit)
			.build();
	}

	private ExplicitRouteTarget explicit(Long profileId) {
		return new ExplicitRouteTarget(RouteTargetType.SKILL, 30L, 31L, 40L, profileId);
	}

	private ExplicitRouteTarget explicitWithoutArtifact(Long profileId) {
		return new ExplicitRouteTarget(RouteTargetType.SKILL, 30L, 31L, null, profileId);
	}

	private com.sn68.agent.dataagent.entity.DataChatTurn previousTurn() {
		com.sn68.agent.dataagent.entity.DataChatTurn turn = new com.sn68.agent.dataagent.entity.DataChatTurn();
		turn.setQuestion("查询上个月各项目的账单情况");
		turn.setRouteTargetType("SKILL");
		turn.setRouteTargetId(30L);
		turn.setRouteTargetVersionId(31L);
		return turn;
	}

	private RouteCandidate candidate(String tenantId) {
		return new RouteCandidate(new RouteTargetRef(RouteTargetType.SKILL, 30L, 31L, 32L), tenantId, 10L,
				"订单查询", "查询订单", "QUERY", "REACT", RouteRules.empty(), RouteRisk.READ_ONLY, 0, 1L,
				40L, "checksum", "embedding-v2");
	}

	private RouteCandidate candidateWithoutArtifact(String tenantId) {
		return new RouteCandidate(new RouteTargetRef(RouteTargetType.SKILL, 30L, 31L, 32L), tenantId, 10L,
				"订单查询", "查询订单", "QUERY", "REACT", RouteRules.empty(), RouteRisk.READ_ONLY, 0, 1L,
				null, null, null);
	}

	private RouteCandidate writeCandidate(String tenantId) {
		return new RouteCandidate(new RouteTargetRef(RouteTargetType.SKILL, 30L, 31L, 32L), tenantId, 10L,
				"Order update", "Update an order", "ACTION", "REACT", RouteRules.empty(), RouteRisk.WRITE, 0, 1L,
				40L, "checksum", "embedding-v2");
	}

	private AgentRequest employeeRequest(String query, Long releaseId, String ownerType) {
		return AgentRequest.builder()
			.query(query)
			.threadId("200")
			.runtimeRequestId("request-1")
			.tenantIdSnapshot("tenant-1")
			.userIdSnapshot("100")
			.ownerType(ownerType)
			.ownerId(9L)
			.releaseId(releaseId)
			.build();
	}

	private EmployeeReleaseSnapshot employeeSnapshot(Long routeProfileId) {
		return new EmployeeReleaseSnapshot(12L, 9L, 1, "1", "hash", "E-001", "员工", "岗", "提示", "你好", 1L,
				routeProfileId, 10L, "GUIDED", Map.of(), List.of(), List.of(), null);
	}

	private void stubEngineSelect(RouteCandidate candidate) {
		when(skillProvider.load(any(), any(), any(), any())).thenReturn(List.of(candidate));
		when(engine.route(any(), any(), any(), any())).thenReturn(RouteDecision.select(candidate, "LEXICAL_AUTO_SELECT",
				RouteDegradeMode.NONE, false, RouteTiming.empty()));
	}

}
