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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.constant.AgentTypeConstant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.entity.DataAgentRouteProfile;
import com.sn68.agent.dataagent.entity.DataAgentRouteArtifact;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillBinding;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.repository.DataAgentRouteArtifactMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillBindingMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.routing.model.RouteCandidate;
import com.sn68.agent.dataagent.routing.model.RouteContext;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import com.sn68.agent.dataagent.routing.model.RouteRules;
import com.sn68.agent.dataagent.routing.model.RoutePolicy;
import com.sn68.agent.dataagent.routing.model.RouteTargetRef;
import com.sn68.agent.dataagent.routing.model.RouteTargetType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkillRouteCandidateProviderTest {

	@Test
	void loadsPinnedPublishedVersionWhileNewDraftExistsWithoutQueryingArtifacts() {
		TestFixture fixture = fixture();
		RouteDiagnostics diagnostics = new RouteDiagnostics();

		List<RouteCandidate> candidates = fixture.provider().load(context(), fixture.profile(),
				RoutePolicy.initial(1L, "embedding-v2"), diagnostics);

		assertEquals(1, candidates.size());
		assertEquals(30L, candidates.get(0).target().targetId());
		assertEquals(31L, candidates.get(0).target().targetVersionId());
		assertEquals("published order query", candidates.get(0).name());
		assertEquals("query orders with the pinned version", candidates.get(0).description());
		assertNull(candidates.get(0).routeArtifactId());
		assertEquals(RouteDiagnostics.ARTIFACT_QUERY_DISABLED,
				diagnostics.artifactStatus(candidates.get(0).target()));
		verify(fixture.artifactMapper(), never()).findReady(anyLong(), anyString(), any());
	}

	@Test
	void pinnedEmptySkillVersionsDoesNotQueryLiveBindings() {
		TestFixture fixture = fixture();
		List<RouteCandidate> candidates = fixture.provider().load(pinnedContext(List.of()), fixture.profile(),
				RoutePolicy.initial(1L, "embedding-v2"), new RouteDiagnostics());

		assertEquals(List.of(), candidates);
		verify(fixture.bindingMapper(), never()).findEnabledByAgentId(any(), any());
	}

	@Test
	void pinnedSkillVersionsLoadWithoutLiveAgentBindings() {
		TestFixture fixture = fixture();
		List<RouteCandidate> candidates = fixture.provider().load(pinnedContext(List.of(31L)), fixture.profile(),
				RoutePolicy.initial(1L, "embedding-v2"), new RouteDiagnostics());

		assertEquals(1, candidates.size());
		assertEquals(30L, candidates.get(0).target().targetId());
		assertEquals(31L, candidates.get(0).target().targetVersionId());
		verify(fixture.bindingMapper(), never()).findEnabledByAgentId(any(), any());
	}

	@Test
	void recallWithoutAutomaticSelectionDiagnosesReadyMissingChecksumAndFingerprintArtifacts() {
		TestFixture fixture = fixture();
		RoutePolicy policy = semanticPolicy();
		DataAgentRouteArtifact ready = artifact(40L, "checksum", "embedding-v2");
		when(fixture.artifactMapper().findReady(anyLong(), anyString(), any())).thenReturn(List.of(ready));
		RouteDiagnostics readyDiagnostics = new RouteDiagnostics();

		RouteCandidate readyCandidate = fixture.provider().load(context(), fixture.profile(), policy, readyDiagnostics)
			.get(0);
		assertEquals(40L, readyCandidate.routeArtifactId());
		assertEquals(RouteDiagnostics.ARTIFACT_READY, readyDiagnostics.artifactStatus(readyCandidate.target()));

		when(fixture.artifactMapper().findReady(anyLong(), anyString(), any()))
			.thenReturn(List.of(artifact(41L, "other-checksum", "embedding-v2")));
		RouteDiagnostics checksumDiagnostics = new RouteDiagnostics();
		RouteCandidate checksumCandidate = fixture.provider()
			.load(context(), fixture.profile(), policy, checksumDiagnostics)
			.get(0);
		assertNull(checksumCandidate.routeArtifactId());
		assertEquals(RouteDiagnostics.ARTIFACT_CHECKSUM_MISMATCH,
				checksumDiagnostics.artifactStatus(checksumCandidate.target()));

		when(fixture.artifactMapper().findReady(anyLong(), anyString(), any()))
			.thenReturn(List.of(artifact(42L, "checksum", "other-fingerprint")));
		RouteDiagnostics fingerprintDiagnostics = new RouteDiagnostics();
		RouteCandidate fingerprintCandidate = fixture.provider()
			.load(context(), fixture.profile(), policy, fingerprintDiagnostics)
			.get(0);
		assertNull(fingerprintCandidate.routeArtifactId());
		assertEquals(RouteDiagnostics.ARTIFACT_FINGERPRINT_MISMATCH,
				fingerprintDiagnostics.artifactStatus(fingerprintCandidate.target()));

		when(fixture.artifactMapper().findReady(anyLong(), anyString(), any())).thenReturn(List.of());
		RouteDiagnostics missingDiagnostics = new RouteDiagnostics();
		RouteCandidate missingCandidate = fixture.provider()
			.load(context(), fixture.profile(), policy, missingDiagnostics)
			.get(0);
		assertNull(missingCandidate.routeArtifactId());
		assertEquals(RouteDiagnostics.ARTIFACT_NOT_READY,
				missingDiagnostics.artifactStatus(missingCandidate.target()));
		verify(fixture.artifactMapper(), times(4)).findReady(1L, "tenant-1", List.of("SKILL:30:31"));
	}

	@Test
	void doesNotQueryArtifactsWhenSemanticProfileIsNotRuntimeReady() {
		TestFixture fixture = fixture();
		RoutePolicy policy = new RoutePolicy(1L, true, true, false, false, 70, 15, 0.5D, 0.85D, 0.1D, 0.8D,
				null, 2L, "embedding-v2", false, false, null, RouteModelOutputProtocol.NONE);
		RouteDiagnostics diagnostics = new RouteDiagnostics();

		RouteCandidate candidate = fixture.provider().load(context(), fixture.profile(), policy, diagnostics).get(0);

		assertNull(candidate.routeArtifactId());
		assertEquals(RouteDiagnostics.ARTIFACT_PROFILE_NOT_READY,
				diagnostics.artifactStatus(candidate.target()));
		verify(fixture.artifactMapper(), never()).findReady(anyLong(), anyString(), any());
	}

	@Test
	void onlyKeepsFlowAutoSelectForPublishedFlowWithConfirmSubmit() {
		TestFixture fixture = fixture();
		RouteRules optIn = new RouteRules(List.of(), List.of(), List.of(), List.of(), List.of("*下单*"), List.of(),
				List.of(), List.of(), true);
		fixture.version().setSkillKind("ACTION");
		fixture.version().setExecutionMode("FLOW");
		fixture.version().setFlowDefinition("{\"schemaVersion\":\"skill-flow/v2\",\"nodes\":[{\"id\":\"confirm-submit\",\"type\":\"confirm\"}]}");
		when(fixture.rulesService().parse("{}")).thenReturn(optIn);
		when(fixture.riskResolver().resolveSkill(fixture.version())).thenReturn(RouteRisk.FLOW);

		assertEquals(true, fixture.provider().load(context(), fixture.profile(), RoutePolicy.initial(1L, "embedding-v2"),
				new RouteDiagnostics()).get(0).rules().allowFlowAutoSelect());

		fixture.version().setFlowDefinition("{\"schemaVersion\":\"skill-flow/v2\",\"nodes\":[]}");

		assertEquals(false, fixture.provider().load(context(), fixture.profile(), RoutePolicy.initial(1L, "embedding-v2"),
				new RouteDiagnostics()).get(0).rules().allowFlowAutoSelect());
	}

	@Test
	void skipsBindingWhoseVersionVanishedAndKeepsRoutingTheHealthySkill() {
		MultiFixture fixture = multiFixture(brokenBinding(), healthyBinding());
		RouteDiagnostics diagnostics = new RouteDiagnostics();

		List<RouteCandidate> candidates = fixture.provider().load(context(), fixture.profile(),
				RoutePolicy.initial(1L, "embedding-v2"), diagnostics);

		assertEquals(1, candidates.size());
		assertEquals(60L, candidates.get(0).target().targetId());
		assertEquals(61L, candidates.get(0).target().targetVersionId());
		assertEquals(List.of("VERSION_MISSING_OR_DELETED"), diagnostics
			.ineligibleFailures(new RouteTargetRef(RouteTargetType.SKILL, 50L, 51L, 5L)));
		assertEquals(1, diagnostics.ineligibleTargets().size());
	}

	@Test
	void skipsUnpinnedBindingWithoutLookingUpANullVersionId() {
		MultiFixture fixture = multiFixture(unpinnedBinding(), healthyBinding());
		RouteDiagnostics diagnostics = new RouteDiagnostics();

		List<RouteCandidate> candidates = fixture.provider().load(context(), fixture.profile(),
				RoutePolicy.initial(1L, "embedding-v2"), diagnostics);

		assertEquals(1, candidates.size());
		assertEquals(61L, candidates.get(0).target().targetVersionId());
		assertEquals(List.of("VERSION_NOT_PINNED"), diagnostics
			.ineligibleFailures(new RouteTargetRef(RouteTargetType.SKILL, 70L, null, 7L)));
		verify(fixture.versionMapper()).selectBatchIds(List.of(61L));
	}

	@Test
	void reportsEligibilityInvalidOnlyWhenEveryBindingDrifted() {
		MultiFixture fixture = multiFixture(brokenBinding(), unpinnedBinding());

		RouteStageException failure = assertThrows(RouteStageException.class, () -> fixture.provider()
			.load(context(), fixture.profile(), RoutePolicy.initial(1L, "embedding-v2"), new RouteDiagnostics()));

		assertEquals("ROUTE_ELIGIBILITY_INVALID", failure.reasonCode());
	}

	@Test
	void failsClosedOnCrossTenantBindingEvenWhenAnotherSkillIsRoutable() {
		MultiFixture fixture = multiFixture(healthyBinding(), foreignTenantBinding());

		RouteStageException failure = assertThrows(RouteStageException.class, () -> fixture.provider()
			.load(context(), fixture.profile(), RoutePolicy.initial(1L, "embedding-v2"), new RouteDiagnostics()));

		assertEquals("ROUTE_ELIGIBILITY_INVALID", failure.reasonCode());
	}

	/** 版本行取不到（被逻辑删除或从未落库），命中 VERSION_MISSING_OR_DELETED。 */
	private DataAgentSkillBinding brokenBinding() {
		return DataAgentSkillBinding.builder().id(5L).agentId(10L).tenantId("tenant-1").skillId(50L)
			.pinnedSkillVersionId(51L).priority(9).enabled(true).deleted(false).build();
	}

	private DataAgentSkillBinding healthyBinding() {
		return DataAgentSkillBinding.builder().id(6L).agentId(10L).tenantId("tenant-1").skillId(60L)
			.pinnedSkillVersionId(61L).priority(5).enabled(true).deleted(false).build();
	}

	private DataAgentSkillBinding unpinnedBinding() {
		return DataAgentSkillBinding.builder().id(7L).agentId(10L).tenantId("tenant-1").skillId(70L)
			.pinnedSkillVersionId(null).priority(1).enabled(true).deleted(false).build();
	}

	private DataAgentSkillBinding foreignTenantBinding() {
		return DataAgentSkillBinding.builder().id(8L).agentId(10L).tenantId("tenant-1").skillId(80L)
			.pinnedSkillVersionId(81L).priority(1).enabled(true).deleted(false).build();
	}

	/**
	 * 只有 60/61 是完全健康的：50 缺版本行、70 未锁版本、80 属于别的租户。
	 */
	private MultiFixture multiFixture(DataAgentSkillBinding... bindings) {
		DataAgentSkillBindingMapper bindingMapper = mock(DataAgentSkillBindingMapper.class);
		DataAgentSkillMapper skillMapper = mock(DataAgentSkillMapper.class);
		DataAgentSkillVersionMapper versionMapper = mock(DataAgentSkillVersionMapper.class);
		DataAgentRouteArtifactMapper artifactMapper = mock(DataAgentRouteArtifactMapper.class);
		RouteRulesService rulesService = mock(RouteRulesService.class);
		RouteRiskResolver riskResolver = mock(RouteRiskResolver.class);
		RouteArtifactChecksum artifactChecksum = mock(RouteArtifactChecksum.class);
		SkillRouteCandidateProvider provider = new SkillRouteCandidateProvider(bindingMapper, skillMapper, versionMapper,
				artifactMapper, rulesService, riskResolver, artifactChecksum, new ObjectMapper());
		DataAgentSkillVersion healthyVersion = version(61L, 60L, "tenant-1");
		when(bindingMapper.findEnabledByAgentId(10L, "tenant-1")).thenReturn(List.of(bindings));
		when(skillMapper.selectBatchIds(any())).thenReturn(List.of(skill(50L, 51L, "tenant-1"),
				skill(60L, 61L, "tenant-1"), skill(70L, 71L, "tenant-1"), skill(80L, 81L, "tenant-2")));
		when(versionMapper.selectBatchIds(any())).thenReturn(List.of(healthyVersion, version(81L, 80L, "tenant-2")));
		when(rulesService.parse("{}")).thenReturn(RouteRules.empty());
		when(riskResolver.resolveSkill(healthyVersion)).thenReturn(RouteRisk.READ_ONLY);
		when(artifactChecksum.calculate(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
				any(), any())).thenReturn("checksum");
		return new MultiFixture(provider, versionMapper, DataAgentRouteProfile.builder().id(1L).build());
	}

	private DataAgentSkill skill(Long id, Long publishedVersionId, String tenantId) {
		return DataAgentSkill.builder().id(id).tenantId(tenantId).skillName("skill-" + id).scope("TENANT")
			.status("PUBLISHED").publishedVersionId(publishedVersionId).deleted(false).build();
	}

	private DataAgentSkillVersion version(Long id, Long skillId, String tenantId) {
		return DataAgentSkillVersion.builder().id(id).tenantId(tenantId).skillId(skillId)
			.skillName("version-" + id).description("description-" + id).skillKind("QUERY")
			.executionMode("DETERMINISTIC").status("PUBLISHED").routeRules("{}").deleted(false).build();
	}

	private record MultiFixture(SkillRouteCandidateProvider provider, DataAgentSkillVersionMapper versionMapper,
			DataAgentRouteProfile profile) {
	}

	private TestFixture fixture() {
		DataAgentSkillBindingMapper bindingMapper = mock(DataAgentSkillBindingMapper.class);
		DataAgentSkillMapper skillMapper = mock(DataAgentSkillMapper.class);
		DataAgentSkillVersionMapper versionMapper = mock(DataAgentSkillVersionMapper.class);
		DataAgentRouteArtifactMapper artifactMapper = mock(DataAgentRouteArtifactMapper.class);
		RouteRulesService rulesService = mock(RouteRulesService.class);
		RouteRiskResolver riskResolver = mock(RouteRiskResolver.class);
		RouteArtifactChecksum artifactChecksum = mock(RouteArtifactChecksum.class);
		SkillRouteCandidateProvider provider = new SkillRouteCandidateProvider(bindingMapper, skillMapper, versionMapper,
				artifactMapper, rulesService, riskResolver, artifactChecksum, new ObjectMapper());
		DataAgentSkillBinding binding = DataAgentSkillBinding.builder().id(5L).agentId(10L).tenantId("tenant-1")
			.skillId(30L).pinnedSkillVersionId(31L).priority(5).enabled(true).deleted(false).build();
		DataAgentSkill skill = DataAgentSkill.builder().id(30L).tenantId("tenant-1").skillName("latest catalog name")
			.description("latest editable description").scope("TENANT").status("PUBLISHED").publishedVersionId(31L)
			.latestDraftVersionId(32L).deleted(false).build();
		DataAgentSkillVersion version = DataAgentSkillVersion.builder().id(31L).tenantId("tenant-1").skillId(30L)
			.skillName("published order query").description("query orders with the pinned version")
			.skillKind("QUERY").executionMode("DETERMINISTIC").status("PUBLISHED").routeRules("{}")
			.deleted(false).build();
		DataAgentRouteProfile profile = DataAgentRouteProfile.builder().id(1L).build();
		when(bindingMapper.findEnabledByAgentId(10L, "tenant-1")).thenReturn(List.of(binding));
		when(skillMapper.selectBatchIds(any())).thenReturn(List.of(skill));
		when(versionMapper.selectBatchIds(any())).thenReturn(List.of(version));
		when(rulesService.parse("{}")).thenReturn(RouteRules.empty());
		when(riskResolver.resolveSkill(version)).thenReturn(RouteRisk.READ_ONLY);
		when(artifactChecksum.calculate(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
				any(), any())).thenReturn("checksum");
		return new TestFixture(provider, bindingMapper, artifactMapper, profile, version, rulesService, riskResolver);
	}

	private RoutePolicy semanticPolicy() {
		return new RoutePolicy(1L, true, true, false, false, 70, 15, 0.5D, 0.85D, 0.1D, 0.8D,
				null, 2L, "embedding-v2", true, false, null, RouteModelOutputProtocol.NONE);
	}

	private DataAgentRouteArtifact artifact(Long id, String checksum, String fingerprint) {
		return DataAgentRouteArtifact.builder().id(id).targetKey("SKILL:30:31").sourceChecksum(checksum)
			.embeddingFingerprint(fingerprint).status("READY").deleted(false).build();
	}

	private RouteContext context() {
		return new RouteContext("tenant-1", 10L, AgentTypeConstant.DATA_ANALYSIS, null, "100", "request-1",
				"query orders", null, null, null, Instant.now().plusSeconds(5), 1);
	}

	private RouteContext pinnedContext(List<Long> pinnedSkillVersionIds) {
		return new RouteContext("tenant-1", 88L, AgentTypeConstant.DATA_ANALYSIS, null, "100", "request-1",
				"query orders", null, null, null, Instant.now().plusSeconds(5), 1,
				com.sn68.agent.dataagent.routing.model.RouteScope.forOwnerType(AgentTypeConstant.DATA_ANALYSIS),
				pinnedSkillVersionIds);
	}

	private record TestFixture(SkillRouteCandidateProvider provider, DataAgentSkillBindingMapper bindingMapper,
			DataAgentRouteArtifactMapper artifactMapper, DataAgentRouteProfile profile, DataAgentSkillVersion version,
			RouteRulesService rulesService, RouteRiskResolver riskResolver) {
	}

}
