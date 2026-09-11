/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.constant.AgentTypeConstant;
import com.sn68.agent.dataagent.entity.AgentCollaborator;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.entity.DataAgentRouteArtifact;
import com.sn68.agent.dataagent.entity.DataAgentRouteProfile;
import com.sn68.agent.dataagent.repository.AgentCollaboratorMapper;
import com.sn68.agent.dataagent.repository.DataAgentMapper;
import com.sn68.agent.dataagent.repository.DataAgentRouteArtifactMapper;
import com.sn68.agent.dataagent.routing.CollaboratorCapabilityResolver.CollaboratorCapability;
import com.sn68.agent.dataagent.routing.model.DelegationMode;
import com.sn68.agent.dataagent.routing.model.RouteCandidate;
import com.sn68.agent.dataagent.routing.model.RouteContext;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import com.sn68.agent.dataagent.routing.model.RouteRules;
import com.sn68.agent.dataagent.routing.model.RoutePolicy;
import com.sn68.agent.dataagent.routing.model.RouteTargetRef;
import com.sn68.agent.dataagent.routing.model.RouteTargetType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CollaboratorRouteCandidateProviderTest {

	@Test
	void skipsArtifactsUnlessSemanticEmbeddingCapabilityIsReady() {
		AgentCollaboratorMapper collaboratorMapper = mock(AgentCollaboratorMapper.class);
		DataAgentMapper agentMapper = mock(DataAgentMapper.class);
		DataAgentRouteArtifactMapper artifactMapper = mock(DataAgentRouteArtifactMapper.class);
		RouteRulesService rulesService = mock(RouteRulesService.class);
		CollaboratorCapabilityResolver capabilityResolver = mock(CollaboratorCapabilityResolver.class);
		CollaboratorRouteCandidateProvider provider = new CollaboratorRouteCandidateProvider(collaboratorMapper,
				agentMapper, artifactMapper, rulesService, capabilityResolver, mock(RouteArtifactChecksum.class));
		AgentCollaborator relation = AgentCollaborator.builder().id(20L).agentId(10L)
			.collaboratorAgentId(30L).roleName("order analyst").capabilityDescription("query orders")
			.delegationMode(DelegationMode.AUTO_READ_ONLY.name()).routingRules(Map.of()).priority(5).enabled(true)
			.deleted(false).build();
		DataAgent collaborator = DataAgent.builder().id(30L).tenantId("tenant-1").name("order agent")
			.description("query orders").status("published").agentType(AgentTypeConstant.DATA_ANALYSIS)
			.deleted(false).build();
		DataAgentRouteProfile profile = DataAgentRouteProfile.builder().id(1L)
			.semanticRecallEnabled(true).semanticAutoSelectEnabled(false).embeddingProbeState("SUPPORTED")
			.embeddingFingerprint("embedding-v2")
			.build();
		when(collaboratorMapper.findEnabledByAgentId(10L)).thenReturn(List.of(relation));
		when(agentMapper.selectBatchIds(any())).thenReturn(List.of(collaborator));
		when(capabilityResolver.resolve(30L, "tenant-1"))
			.thenReturn(new CollaboratorCapability(RouteRisk.READ_ONLY, 1, false));
		when(rulesService.normalize(relation.getRoutingRules())).thenReturn(RouteRules.empty());

		RouteDiagnostics disabledDiagnostics = new RouteDiagnostics();
		List<RouteCandidate> candidates = provider.load(context(), profile, RoutePolicy.initial(1L, "embedding-v2"),
				disabledDiagnostics);
		RouteDiagnostics incompleteDiagnostics = new RouteDiagnostics();
		RoutePolicy incompletePolicy = new RoutePolicy(1L, true, true, false, false, 70, 15, 0.5D, 0.85D, 0.1D, 0.8D,
				null, 2L, null, false, false, null, RouteModelOutputProtocol.NONE);
		List<RouteCandidate> incompleteCapabilityCandidates = provider.load(context(), profile, incompletePolicy,
				incompleteDiagnostics);

		assertEquals(1, candidates.size());
		assertEquals(20L, candidates.get(0).target().targetId());
		assertNull(candidates.get(0).routeArtifactId());
		assertEquals(1, incompleteCapabilityCandidates.size());
		assertNull(incompleteCapabilityCandidates.get(0).routeArtifactId());
		assertEquals(RouteDiagnostics.ARTIFACT_QUERY_DISABLED,
				disabledDiagnostics.artifactStatus(candidates.get(0).target()));
		assertEquals(RouteDiagnostics.ARTIFACT_PROFILE_NOT_READY,
				incompleteDiagnostics.artifactStatus(incompleteCapabilityCandidates.get(0).target()));
		verify(artifactMapper, never()).findReady(anyLong(), anyString(), any());
	}

	@Test
	void recallWithoutAutomaticSelectionLoadsReadyArtifact() {
		AgentCollaboratorMapper collaboratorMapper = mock(AgentCollaboratorMapper.class);
		DataAgentMapper agentMapper = mock(DataAgentMapper.class);
		DataAgentRouteArtifactMapper artifactMapper = mock(DataAgentRouteArtifactMapper.class);
		RouteRulesService rulesService = mock(RouteRulesService.class);
		CollaboratorCapabilityResolver capabilityResolver = mock(CollaboratorCapabilityResolver.class);
		RouteArtifactChecksum artifactChecksum = mock(RouteArtifactChecksum.class);
		CollaboratorRouteCandidateProvider provider = new CollaboratorRouteCandidateProvider(collaboratorMapper,
				agentMapper, artifactMapper, rulesService, capabilityResolver, artifactChecksum);
		AgentCollaborator relation = AgentCollaborator.builder().id(20L).agentId(10L)
			.collaboratorAgentId(30L).roleName("order analyst").capabilityDescription("query orders")
			.delegationMode(DelegationMode.AUTO_READ_ONLY.name()).routingRules(Map.of()).priority(5).enabled(true)
			.deleted(false).build();
		DataAgent collaborator = DataAgent.builder().id(30L).tenantId("tenant-1").name("order agent")
			.description("query orders").status("published").agentType(AgentTypeConstant.DATA_ANALYSIS)
			.deleted(false).build();
		DataAgentRouteArtifact artifact = DataAgentRouteArtifact.builder().id(40L).targetKey("COLLABORATOR:20")
			.sourceChecksum("checksum").embeddingFingerprint("embedding-v2").status("READY").deleted(false).build();
		RoutePolicy policy = new RoutePolicy(1L, true, true, false, false, 70, 15, 0.5D, 0.85D, 0.1D, 0.8D,
				null, 2L, "embedding-v2", true, false, null, RouteModelOutputProtocol.NONE);
		RouteDiagnostics diagnostics = new RouteDiagnostics();
		when(collaboratorMapper.findEnabledByAgentId(10L)).thenReturn(List.of(relation));
		when(agentMapper.selectBatchIds(any())).thenReturn(List.of(collaborator));
		when(capabilityResolver.resolve(30L, "tenant-1"))
			.thenReturn(new CollaboratorCapability(RouteRisk.READ_ONLY, 1, false));
		when(rulesService.normalize(relation.getRoutingRules())).thenReturn(RouteRules.empty());
		when(artifactChecksum.calculate(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), any(),
				anyString()))
			.thenReturn("checksum");
		when(artifactMapper.findReady(1L, "tenant-1", List.of("COLLABORATOR:20"))).thenReturn(List.of(artifact));

		RouteCandidate candidate = provider.load(context(), DataAgentRouteProfile.builder().id(1L).build(), policy,
				diagnostics).get(0);

		assertEquals(40L, candidate.routeArtifactId());
		assertEquals(RouteDiagnostics.ARTIFACT_READY, diagnostics.artifactStatus(candidate.target()));
		verify(artifactMapper, times(1)).findReady(1L, "tenant-1", List.of("COLLABORATOR:20"));
	}

	@Test
	void skipsUnpublishedCollaboratorAndKeepsDelegatingToTheHealthyOne() {
		MultiFixture fixture = multiFixture(relation(20L, 30L), relation(21L, 31L));
		when(fixture.agentMapper().selectBatchIds(any()))
			.thenReturn(List.of(collaborator(30L, "draft", "tenant-1"), collaborator(31L, "published", "tenant-1")));
		RouteDiagnostics diagnostics = new RouteDiagnostics();

		List<RouteCandidate> candidates = fixture.provider().load(context(), fixture.profile(),
				RoutePolicy.initial(1L, "embedding-v2"), diagnostics);

		assertEquals(1, candidates.size());
		assertEquals(21L, candidates.get(0).target().targetId());
		assertEquals(List.of("COLLABORATOR_NOT_PUBLISHED"), diagnostics
			.ineligibleFailures(new RouteTargetRef(RouteTargetType.COLLABORATOR, 20L, null, 20L)));
	}

	@Test
	void skipsCollaboratorWhoseDelegationNoLongerMatchesItsCapabilities() {
		MultiFixture fixture = multiFixture(relation(20L, 30L), relation(21L, 31L));
		when(fixture.agentMapper().selectBatchIds(any())).thenReturn(
				List.of(collaborator(30L, "published", "tenant-1"), collaborator(31L, "published", "tenant-1")));
		when(fixture.capabilityResolver().resolve(30L, "tenant-1")).thenReturn(CollaboratorCapability.unknown());
		doThrow(new IllegalArgumentException("AUTO_READ_ONLY requires read-only capabilities"))
			.when(fixture.capabilityResolver())
			.requireSupported(DelegationMode.AUTO_READ_ONLY, CollaboratorCapability.unknown());
		RouteDiagnostics diagnostics = new RouteDiagnostics();

		List<RouteCandidate> candidates = fixture.provider().load(context(), fixture.profile(),
				RoutePolicy.initial(1L, "embedding-v2"), diagnostics);

		assertEquals(1, candidates.size());
		assertEquals(21L, candidates.get(0).target().targetId());
		assertEquals(List.of("COLLABORATOR_CAPABILITY_STALE"), diagnostics
			.ineligibleFailures(new RouteTargetRef(RouteTargetType.COLLABORATOR, 20L, null, 20L)));
	}

	@Test
	void reportsEligibilityInvalidOnlyWhenEveryCollaboratorDrifted() {
		MultiFixture fixture = multiFixture(relation(20L, 30L));
		when(fixture.agentMapper().selectBatchIds(any())).thenReturn(List.of(collaborator(30L, "draft", "tenant-1")));

		RouteStageException failure = assertThrows(RouteStageException.class, () -> fixture.provider()
			.load(context(), fixture.profile(), RoutePolicy.initial(1L, "embedding-v2"), new RouteDiagnostics()));

		assertEquals("ROUTE_ELIGIBILITY_INVALID", failure.reasonCode());
	}

	@Test
	void failsClosedOnCrossTenantCollaboratorEvenWhenAnotherIsRoutable() {
		MultiFixture fixture = multiFixture(relation(21L, 31L), relation(20L, 30L));
		when(fixture.agentMapper().selectBatchIds(any())).thenReturn(
				List.of(collaborator(30L, "published", "tenant-2"), collaborator(31L, "published", "tenant-1")));

		RouteStageException failure = assertThrows(RouteStageException.class, () -> fixture.provider()
			.load(context(), fixture.profile(), RoutePolicy.initial(1L, "embedding-v2"), new RouteDiagnostics()));

		assertEquals("ROUTE_ELIGIBILITY_INVALID", failure.reasonCode());
	}

	private MultiFixture multiFixture(AgentCollaborator... relations) {
		AgentCollaboratorMapper collaboratorMapper = mock(AgentCollaboratorMapper.class);
		DataAgentMapper agentMapper = mock(DataAgentMapper.class);
		DataAgentRouteArtifactMapper artifactMapper = mock(DataAgentRouteArtifactMapper.class);
		RouteRulesService rulesService = mock(RouteRulesService.class);
		CollaboratorCapabilityResolver capabilityResolver = mock(CollaboratorCapabilityResolver.class);
		CollaboratorRouteCandidateProvider provider = new CollaboratorRouteCandidateProvider(collaboratorMapper,
				agentMapper, artifactMapper, rulesService, capabilityResolver, mock(RouteArtifactChecksum.class));
		when(collaboratorMapper.findEnabledByAgentId(10L)).thenReturn(List.of(relations));
		when(capabilityResolver.resolve(31L, "tenant-1"))
			.thenReturn(new CollaboratorCapability(RouteRisk.READ_ONLY, 1, false));
		when(rulesService.normalize(any())).thenReturn(RouteRules.empty());
		return new MultiFixture(provider, agentMapper, capabilityResolver,
				DataAgentRouteProfile.builder().id(1L).build());
	}

	private AgentCollaborator relation(Long id, Long collaboratorAgentId) {
		return AgentCollaborator.builder().id(id).agentId(10L).collaboratorAgentId(collaboratorAgentId)
			.roleName("role-" + id).capabilityDescription("capability-" + id)
			.delegationMode(DelegationMode.AUTO_READ_ONLY.name()).routingRules(Map.of()).priority(5).enabled(true)
			.deleted(false).build();
	}

	private DataAgent collaborator(Long id, String status, String tenantId) {
		return DataAgent.builder().id(id).tenantId(tenantId).name("agent-" + id).description("description-" + id)
			.status(status).agentType(AgentTypeConstant.DATA_ANALYSIS).deleted(false).build();
	}

	private record MultiFixture(CollaboratorRouteCandidateProvider provider, DataAgentMapper agentMapper,
			CollaboratorCapabilityResolver capabilityResolver, DataAgentRouteProfile profile) {
	}

	private RouteContext context() {
		return new RouteContext("tenant-1", 10L, AgentTypeConstant.ORCHESTRATOR, null, "100", "request-1",
				"query orders", null, null, null, Instant.now().plusSeconds(5), 1);
	}

}
