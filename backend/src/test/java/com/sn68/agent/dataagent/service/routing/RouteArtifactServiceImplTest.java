/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.routing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.constant.AgentTypeConstant;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.entity.AgentCollaborator;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.entity.DataAgentRouteArtifact;
import com.sn68.agent.dataagent.entity.DataAgentRouteProfile;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillBinding;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.entity.ModelConfig;
import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.dataagent.repository.AgentCollaboratorMapper;
import com.sn68.agent.dataagent.repository.DataAgentMapper;
import com.sn68.agent.dataagent.repository.DataAgentRouteArtifactMapper;
import com.sn68.agent.dataagent.repository.DataAgentRouteProfileMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillBindingMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.repository.ModelConfigMapper;
import com.sn68.agent.dataagent.routing.RouteArtifactChecksum;
import com.sn68.agent.dataagent.routing.CollaboratorCapabilityResolver;
import com.sn68.agent.dataagent.routing.RouteEmbeddingModelResolver;
import com.sn68.agent.dataagent.routing.RouteCapabilityState;
import com.sn68.agent.dataagent.routing.RouteModelFingerprint;
import com.sn68.agent.dataagent.routing.RouteModelOutputProtocol;
import com.sn68.agent.dataagent.routing.RouteRiskResolver;
import com.sn68.agent.dataagent.routing.RouteRulesService;
import com.sn68.agent.dataagent.routing.RouteTextNormalizer;
import com.sn68.agent.dataagent.routing.CollaboratorCapabilityResolver.CollaboratorCapability;
import com.sn68.agent.dataagent.routing.model.DelegationMode;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import com.sn68.agent.dataagent.routing.model.RouteRules;
import com.sn68.agent.dataagent.service.vectorstore.AgentVectorStoreService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class RouteArtifactServiceImplTest {

	private DataAgentRouteProfileMapper profileMapper;

	private DataAgentRouteArtifactMapper artifactMapper;

	private DataAgentSkillMapper skillMapper;

	private DataAgentSkillBindingMapper bindingMapper;

	private DataAgentSkillVersionMapper versionMapper;

	private AgentCollaboratorMapper collaboratorMapper;

	private DataAgentMapper agentMapper;

	private RouteEmbeddingModelResolver embeddingModelResolver;

	private AgentVectorStoreService vectorStoreService;

	private RouteRiskResolver riskResolver;

	private ModelConfigMapper modelConfigMapper;

	private RouteModelFingerprint routeModelFingerprint;

	private CollaboratorCapabilityResolver capabilityResolver;

	private TransactionTemplate transactionTemplate;

	private DataAgentSkillBinding binding;

	private DataAgentSkill skill;

	private DataAgentSkillVersion version;

	private RouteArtifactServiceImpl service;

	private DataAgentRouteProfile profile;

	@BeforeEach
	void setUp() {
		profileMapper = mock(DataAgentRouteProfileMapper.class);
		artifactMapper = mock(DataAgentRouteArtifactMapper.class);
		skillMapper = mock(DataAgentSkillMapper.class);
		bindingMapper = mock(DataAgentSkillBindingMapper.class);
		versionMapper = mock(DataAgentSkillVersionMapper.class);
		collaboratorMapper = mock(AgentCollaboratorMapper.class);
		agentMapper = mock(DataAgentMapper.class);
		RouteRulesService rulesService = new RouteRulesService(new ObjectMapper(), new RouteTextNormalizer());
		RouteArtifactChecksum checksum = new RouteArtifactChecksum(rulesService);
		riskResolver = mock(RouteRiskResolver.class);
		modelConfigMapper = mock(ModelConfigMapper.class);
		capabilityResolver = mock(CollaboratorCapabilityResolver.class);
		routeModelFingerprint = new RouteModelFingerprint();
		transactionTemplate = mock(TransactionTemplate.class);
		doAnswer(invocation -> {
			invocation.getArgument(0, Consumer.class).accept(mock(TransactionStatus.class));
			return null;
		}).when(transactionTemplate).executeWithoutResult(any());
		binding = DataAgentSkillBinding.builder().id(5L).agentId(10L).tenantId("tenant-1")
			.skillId(30L).pinnedSkillVersionId(31L).priority(100).enabled(true).deleted(false).build();
		skill = DataAgentSkill.builder().id(30L).tenantId("tenant-1").skillName("最新草稿目录名称")
			.description("最新草稿目录说明").scope("TENANT").status("PUBLISHED").deleted(false).build();
		version = DataAgentSkillVersion.builder().id(31L).tenantId("tenant-1").skillId(30L).skillKind("QUERY")
			.skillName("订单查询").description("查询订单").executionMode("REACT").status("PUBLISHED")
			.routeRules("{\"exact\":[],\"phrases\":[\"订单\"],\"aliases\":[],\"positiveExamples\":[],\"negativeExamples\":[],\"hardExcludes\":[]}")
			.deleted(false).build();
		profile = DataAgentRouteProfile.builder().id(1L).semanticRecallEnabled(true)
			.semanticAutoSelectEnabled(false)
			.embeddingProbeState("SUPPORTED").embeddingFingerprint("embedding-v2").embeddingDimension(1536)
			.deleted(false).build();
		RouteRules rules = rulesService.parse(version.getRouteRules());
		String sourceChecksum = checksum.calculate("SKILL:30:31", "tenant-1", "订单查询", "查询订单", "QUERY",
				"REACT", rules, RouteRisk.READ_ONLY);
		DataAgentRouteArtifact artifact = DataAgentRouteArtifact.builder().id(40L).profileId(1L)
			.tenantId("tenant-1").targetType("SKILL").targetId(30L).targetVersionId(31L)
			.targetKey("SKILL:30:31").riskLevel("READ_ONLY").sourceChecksum(sourceChecksum)
			.contentChecksum("content").embeddingFingerprint("embedding-v2").embeddingDimension(1536)
			.vectorDocumentId("route-40").status("READY").deleted(false).build();
		DataAgentRouteArtifact obsolete = DataAgentRouteArtifact.builder().id(41L).profileId(1L)
			.tenantId("tenant-1").targetType("SKILL").targetId(99L).targetVersionId(100L)
			.targetKey("SKILL:99:100").riskLevel("READ_ONLY").sourceChecksum("obsolete")
			.contentChecksum("obsolete").embeddingFingerprint("embedding-v2").embeddingDimension(1536)
			.vectorDocumentId("route-41").status("READY").deleted(false).build();
		when(bindingMapper.findAllEnabled()).thenReturn(List.of(binding));
		when(skillMapper.selectBatchIds(any())).thenReturn(List.of(skill));
		when(versionMapper.selectBatchIds(any())).thenReturn(List.of(version));
		when(collaboratorMapper.findAllEnabled()).thenReturn(List.of());
		when(artifactMapper.findByProfile(1L)).thenReturn(List.of(artifact, obsolete));
		when(artifactMapper.updateById(any(DataAgentRouteArtifact.class))).thenReturn(1);
		when(riskResolver.resolveSkill(version)).thenReturn(RouteRisk.READ_ONLY);
		vectorStoreService = mock(AgentVectorStoreService.class);
		embeddingModelResolver = mock(RouteEmbeddingModelResolver.class);
		service = new RouteArtifactServiceImpl(profileMapper, artifactMapper, skillMapper, bindingMapper, versionMapper,
				collaboratorMapper, agentMapper, rulesService, vectorStoreService,
				embeddingModelResolver, checksum, riskResolver, modelConfigMapper, capabilityResolver, routeModelFingerprint,
				transactionTemplate);
	}

	@Test
	void completenessTracksCurrentSourceChecksum() {
		assertTrue(service.hasCompleteArtifacts(profile));

		version.setRouteRules("{\"exact\":[],\"phrases\":[\"运单\"],\"aliases\":[],\"positiveExamples\":[],\"negativeExamples\":[],\"hardExcludes\":[]}");

		assertFalse(service.hasCompleteArtifacts(profile));
	}

	@Test
	void hasValidRuleSourcesReturnsFalseForInvalidSource() {
		version.setStatus("DRAFT");

		assertFalse(service.hasValidRuleSources());
	}

	@Test
	void hasValidRuleSourcesReturnsFalseForInvalidRules() {
		version.setRouteRules("{");

		assertFalse(service.hasValidRuleSources());
	}

	@Test
	void hasValidRuleSourcesPropagatesSourceMapperFailure() {
		IllegalStateException mapperFailure = new IllegalStateException("route source database unavailable");
		when(bindingMapper.findAllEnabled()).thenThrow(mapperFailure);

		RuntimeException thrown = assertThrows(RuntimeException.class, service::hasValidRuleSources);

		assertSame(mapperFailure, thrown);
	}

	@Test
	void hasCompleteArtifactsReturnsFalseForInvalidSource() {
		version.setStatus("DRAFT");

		assertFalse(service.hasCompleteArtifacts(profile));
		verify(artifactMapper, never()).findByProfile(anyLong());
	}

	@Test
	void hasCompleteArtifactsReturnsFalseForInvalidRules() {
		version.setRouteRules("{");

		assertFalse(service.hasCompleteArtifacts(profile));
		verify(artifactMapper, never()).findByProfile(anyLong());
	}

	@Test
	void hasCompleteArtifactsPropagatesSourceMapperFailure() {
		IllegalStateException mapperFailure = new IllegalStateException("route source database unavailable");
		when(bindingMapper.findAllEnabled()).thenThrow(mapperFailure);

		RuntimeException thrown = assertThrows(RuntimeException.class, () -> service.hasCompleteArtifacts(profile));

		assertSame(mapperFailure, thrown);
	}

	@Test
	void hasCompleteArtifactsPropagatesArtifactMapperFailure() {
		IllegalStateException mapperFailure = new IllegalStateException("route artifact database unavailable");
		when(artifactMapper.findByProfile(1L)).thenThrow(mapperFailure);

		RuntimeException thrown = assertThrows(RuntimeException.class, () -> service.hasCompleteArtifacts(profile));

		assertSame(mapperFailure, thrown);
	}

	@Test
	void prepareSkillVersionCoversBuildableAndRecentRollbackProfiles() {
		DataAgentRouteProfile active = artifactProfile(1L, 101L, "ACTIVE", "embedding-active");
		DataAgentRouteProfile building = artifactProfile(2L, 102L, "BUILDING", "embedding-building");
		DataAgentRouteProfile ready = artifactProfile(3L, 103L, "READY", "embedding-ready");
		DataAgentRouteProfile rollback = artifactProfile(4L, 104L, "RETIRED", "embedding-rollback");
		rollback.setLastModifyTime(Instant.now());
		EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
		when(profileMapper.findArtifactProfiles()).thenReturn(List.of(active, building, ready));
		when(profileMapper.findLatestRetired(any())).thenReturn(rollback);
		when(embeddingModelResolver.resolve(anyLong(), anyString(), any())).thenReturn(embeddingModel);
		when(artifactMapper.findReusable(anyLong(), eq("tenant-1"), eq("SKILL:30:31"), anyString(), anyString()))
			.thenAnswer(invocation -> DataAgentRouteArtifact.builder()
				.id(100L + invocation.getArgument(0, Long.class))
				.vectorDocumentId("route-" + invocation.getArgument(0, Long.class))
				.status("READY")
				.deleted(false)
				.build());
		when(vectorStoreService.hasRouteDocument(anyLong(), anyLong(), anyString(), eq(embeddingModel)))
			.thenReturn(true);
		when(artifactMapper.findByTarget(anyLong(), eq("tenant-1"), eq("SKILL:30:31"))).thenReturn(List.of());

		service.prepareSkillVersion(skill, version);

		verify(embeddingModelResolver).resolve(eq(101L), eq("embedding-active"), any());
		verify(embeddingModelResolver).resolve(eq(102L), eq("embedding-building"), any());
		verify(embeddingModelResolver).resolve(eq(103L), eq("embedding-ready"), any());
		verify(embeddingModelResolver).resolve(eq(104L), eq("embedding-rollback"), any());
	}

	@Test
	void prepareSkillVersionUsesUuidVectorDocumentId() {
		DataAgentRouteProfile active = artifactProfile(1L, 101L, "ACTIVE", "embedding-active");
		EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
		when(profileMapper.findArtifactProfiles()).thenReturn(List.of(active));
		when(embeddingModelResolver.resolve(eq(101L), eq("embedding-active"), any())).thenReturn(embeddingModel);
		when(artifactMapper.findCurrent(anyLong(), anyString(), anyString(), anyString())).thenReturn(null);
		when(vectorStoreService.hasRouteDocument(anyLong(), anyLong(), anyString(), eq(embeddingModel)))
			.thenReturn(true);
		when(artifactMapper.findByTarget(anyLong(), anyString(), anyString())).thenReturn(List.of());
		when(artifactMapper.insert(any(DataAgentRouteArtifact.class))).thenAnswer(invocation -> {
			invocation.getArgument(0, DataAgentRouteArtifact.class).setId(42L);
			return 1;
		});
		ArgumentCaptor<Document> documentCaptor = ArgumentCaptor.forClass(Document.class);

		service.prepareSkillVersion(skill, version);

		verify(vectorStoreService).addRouteDocument(documentCaptor.capture(), eq(embeddingModel));
		String documentId = documentCaptor.getValue().getId();
		assertDoesNotThrow(() -> UUID.fromString(documentId));
		ArgumentCaptor<DataAgentRouteArtifact> artifactCaptor = ArgumentCaptor.forClass(DataAgentRouteArtifact.class);
		verify(artifactMapper).insert(artifactCaptor.capture());
		assertEquals(documentId, artifactCaptor.getValue().getVectorDocumentId());
	}

	@Test
	void prepareSkillVersionReplacesFailedArtifactWhenVectorCleanupFails() {
		DataAgentRouteProfile active = artifactProfile(1L, 101L, "ACTIVE", "embedding-active");
		DataAgentRouteArtifact failedArtifact = DataAgentRouteArtifact.builder().id(40L).deleted(false).build();
		EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
		when(profileMapper.findArtifactProfiles()).thenReturn(List.of(active));
		when(embeddingModelResolver.resolve(eq(101L), eq("embedding-active"), any())).thenReturn(embeddingModel);
		when(artifactMapper.findCurrent(anyLong(), anyString(), anyString(), anyString())).thenReturn(failedArtifact);
		doThrow(new IllegalStateException("Vector cleanup unavailable"))
			.when(vectorStoreService).deleteRouteDocuments(1L, 40L);
		when(vectorStoreService.hasRouteDocument(anyLong(), anyLong(), anyString(), eq(embeddingModel)))
			.thenReturn(true);
		when(artifactMapper.findByTarget(anyLong(), anyString(), anyString())).thenReturn(List.of());
		when(artifactMapper.insert(any(DataAgentRouteArtifact.class))).thenAnswer(invocation -> {
			invocation.getArgument(0, DataAgentRouteArtifact.class).setId(41L);
			return 1;
		});

		service.prepareSkillVersion(skill, version);

		verify(artifactMapper).deleteById(40L);
		verify(artifactMapper).insert(any(DataAgentRouteArtifact.class));
	}

	@Test
	void prepareSkillVersionRetiresSupersededArtifactWithLogicDelete() {
		DataAgentRouteProfile active = artifactProfile(1L, 101L, "ACTIVE", "embedding-active");
		DataAgentRouteArtifact superseded = DataAgentRouteArtifact.builder().id(40L).deleted(false).build();
		EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
		when(profileMapper.findArtifactProfiles()).thenReturn(List.of(active));
		when(embeddingModelResolver.resolve(eq(101L), eq("embedding-active"), any())).thenReturn(embeddingModel);
		when(artifactMapper.findCurrent(anyLong(), anyString(), anyString(), anyString())).thenReturn(null);
		when(vectorStoreService.hasRouteDocument(anyLong(), anyLong(), anyString(), eq(embeddingModel)))
			.thenReturn(true);
		when(artifactMapper.findByTarget(1L, "tenant-1", "SKILL:30:31")).thenReturn(List.of(superseded));
		when(artifactMapper.insert(any(DataAgentRouteArtifact.class))).thenAnswer(invocation -> {
			invocation.getArgument(0, DataAgentRouteArtifact.class).setId(41L);
			return 1;
		});

		service.prepareSkillVersion(skill, version);

		verify(artifactMapper).deleteById(40L);
		verify(vectorStoreService).deleteRouteDocuments(1L, 40L);
	}

	@Test
	void prepareSkillVersionRejectsFailedInsertBeforeVectorWrite() {
		DataAgentRouteProfile active = artifactProfile(1L, 101L, "ACTIVE", "embedding-active");
		EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
		when(profileMapper.findArtifactProfiles()).thenReturn(List.of(active));
		when(embeddingModelResolver.resolve(eq(101L), eq("embedding-active"), any())).thenReturn(embeddingModel);
		when(artifactMapper.findCurrent(anyLong(), anyString(), anyString(), anyString())).thenReturn(null);
		when(artifactMapper.insert(any(DataAgentRouteArtifact.class))).thenReturn(0);

		assertThrows(CheckedException.class, () -> service.prepareSkillVersion(skill, version));

		verify(vectorStoreService, never()).addRouteDocument(any(), any());
		verify(artifactMapper, never()).updateById(any(DataAgentRouteArtifact.class));
	}

	@Test
	void prepareSkillVersionRejectsMissingArtifactIdBeforeVectorWrite() {
		DataAgentRouteProfile active = artifactProfile(1L, 101L, "ACTIVE", "embedding-active");
		EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
		when(profileMapper.findArtifactProfiles()).thenReturn(List.of(active));
		when(embeddingModelResolver.resolve(eq(101L), eq("embedding-active"), any())).thenReturn(embeddingModel);
		when(artifactMapper.findCurrent(anyLong(), anyString(), anyString(), anyString())).thenReturn(null);
		when(artifactMapper.insert(any(DataAgentRouteArtifact.class))).thenReturn(1);

		assertThrows(CheckedException.class, () -> service.prepareSkillVersion(skill, version));

		verify(vectorStoreService, never()).addRouteDocument(any(), any());
		verify(artifactMapper, never()).updateById(any(DataAgentRouteArtifact.class));
	}

	@Test
	void readyUpdateFailureCleansNewVectorAndFailsBuildWithoutRetiringSupersededArtifact() {
		profile = buildingProfile(8L);
		EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
		DataAgentRouteArtifact superseded = DataAgentRouteArtifact.builder().id(41L).deleted(false).build();
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateBuildWithRevision(any(), eq(8L))).thenReturn(1);
		when(embeddingModelResolver.resolve(eq(22L), eq("embedding-v2"), any())).thenReturn(embeddingModel);
		when(artifactMapper.findCurrent(anyLong(), anyString(), anyString(), anyString())).thenReturn(null);
		when(artifactMapper.insert(any(DataAgentRouteArtifact.class))).thenAnswer(invocation -> {
			invocation.getArgument(0, DataAgentRouteArtifact.class).setId(42L);
			return 1;
		});
		when(vectorStoreService.hasRouteDocument(eq(1L), eq(42L), anyString(), eq(embeddingModel))).thenReturn(true);
		when(artifactMapper.updateById(any(DataAgentRouteArtifact.class))).thenReturn(0);
		when(artifactMapper.findByTarget(1L, "tenant-1", "SKILL:30:31")).thenReturn(List.of(superseded));

		service.rebuildProfile(1L, 8L);

		assertEquals("FAILED", profile.getStatus());
		assertEquals(1, profile.getBuildTotal());
		assertEquals(0, profile.getBuildReady());
		assertEquals(1, profile.getBuildFailed());
		verify(vectorStoreService).deleteRouteDocuments(1L, 42L);
		verify(artifactMapper, never()).deleteById(41L);
		verify(vectorStoreService, never()).deleteRouteDocuments(1L, 41L);
	}

	@Test
	void failedStatusUpdateCannotMaskOriginalVectorFailureOrSkipCleanup() {
		DataAgentRouteProfile active = artifactProfile(1L, 101L, "ACTIVE", "embedding-active");
		EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
		IllegalStateException vectorFailure = new IllegalStateException("vector unavailable");
		when(profileMapper.findArtifactProfiles()).thenReturn(List.of(active));
		when(embeddingModelResolver.resolve(eq(101L), eq("embedding-active"), any())).thenReturn(embeddingModel);
		when(artifactMapper.findCurrent(anyLong(), anyString(), anyString(), anyString())).thenReturn(null);
		when(artifactMapper.insert(any(DataAgentRouteArtifact.class))).thenAnswer(invocation -> {
			invocation.getArgument(0, DataAgentRouteArtifact.class).setId(42L);
			return 1;
		});
		doThrow(vectorFailure).when(vectorStoreService).addRouteDocument(any(), eq(embeddingModel));
		doThrow(new IllegalStateException("artifact status unavailable"))
			.when(artifactMapper).updateById(any(DataAgentRouteArtifact.class));

		RuntimeException thrown = assertThrows(RuntimeException.class,
				() -> service.prepareSkillVersion(skill, version));

		assertSame(vectorFailure, thrown);
		verify(vectorStoreService).deleteRouteDocuments(1L, 42L);
	}

	@Test
	void rebuildRejectsAllInvalidBindingsWithoutBuildingPartialArtifacts() {
		DataAgentSkillBinding missingVersion = DataAgentSkillBinding.builder().id(6L).agentId(10L)
			.tenantId("tenant-1").skillId(30L).pinnedSkillVersionId(999L).enabled(true).deleted(false).build();
		DataAgentSkillBinding mismatchedVersion = DataAgentSkillBinding.builder().id(7L).agentId(10L)
			.tenantId("tenant-1").skillId(30L).pinnedSkillVersionId(32L).enabled(true).deleted(false).build();
		DataAgentSkillVersion versionForAnotherSkill = DataAgentSkillVersion.builder().id(32L).tenantId("tenant-1")
			.skillId(99L).status("PUBLISHED").deleted(false).build();
		profile = buildingProfile(8L);
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateBuildWithRevision(any(), eq(8L))).thenReturn(1);
		when(bindingMapper.findAllEnabled()).thenReturn(List.of(missingVersion, mismatchedVersion));
		when(skillMapper.selectBatchIds(any())).thenReturn(List.of(skill));
		when(versionMapper.selectBatchIds(any())).thenReturn(List.of(versionForAnotherSkill));

		service.rebuildProfile(1L, 8L);

		assertEquals("FAILED", profile.getStatus());
		assertEquals("FAILED", profile.getBuildStatus());
		assertEquals(2, profile.getBuildTotal());
		assertEquals(0, profile.getBuildReady());
		assertEquals(2, profile.getBuildFailed());
		assertEquals("ROUTE_ARTIFACT_SOURCE_INVALID", profile.getLastErrorCode());
		verify(artifactMapper, never()).insert(any(DataAgentRouteArtifact.class));
		verify(vectorStoreService, never()).addRouteDocument(any(), any());
	}

	@Test
	void staleBuildRevisionCannotStartOrPersist() {
		profile = buildingProfile(9L);
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);

		service.rebuildProfile(1L, 8L);

		verify(embeddingModelResolver, never()).resolve(anyLong(), anyString(), any());
		verify(profileMapper, never()).updateBuildWithRevision(any(), anyLong());
		verify(profileMapper, never()).markBuildFailed(anyLong(), anyLong(), any(Integer.class), anyString());
	}

	@Test
	void globalEmbeddingFailurePersistsValidZeroCounts() {
		profile = buildingProfile(8L);
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateBuildWithRevision(any(), eq(8L))).thenReturn(1);
		doThrow(new IllegalStateException("embedding timeout")).when(embeddingModelResolver)
			.resolve(eq(22L), eq("embedding-v2"), any());

		service.rebuildProfile(1L, 8L);

		assertEquals("FAILED", profile.getStatus());
		assertEquals("FAILED", profile.getBuildStatus());
		assertEquals(0, profile.getBuildTotal());
		assertEquals(0, profile.getBuildReady());
		assertEquals(0, profile.getBuildFailed());
		assertTrue(profile.getBuildReady() + profile.getBuildFailed() <= profile.getBuildTotal());
		assertEquals("ROUTE_ARTIFACT_BUILD_FAILED", profile.getLastErrorCode());
	}

	@Test
	void vectorWriteFailureMarksCurrentGenerationFailed() {
		profile = buildingProfile(8L);
		EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateBuildWithRevision(any(), eq(8L))).thenReturn(1);
		when(embeddingModelResolver.resolve(eq(22L), eq("embedding-v2"), any())).thenReturn(embeddingModel);
		when(artifactMapper.findReusable(anyLong(), anyString(), anyString(), anyString(), anyString())).thenReturn(null);
		when(artifactMapper.findCurrent(anyLong(), anyString(), anyString(), anyString())).thenReturn(null);
		when(artifactMapper.insert(any(DataAgentRouteArtifact.class))).thenAnswer(invocation -> {
			invocation.getArgument(0, DataAgentRouteArtifact.class).setId(42L);
			return 1;
		});
		doThrow(new IllegalStateException("vector unavailable")).when(vectorStoreService)
			.addRouteDocument(any(), eq(embeddingModel));

		service.rebuildProfile(1L, 8L);

		assertEquals("FAILED", profile.getStatus());
		assertEquals(1, profile.getBuildTotal());
		assertEquals(0, profile.getBuildReady());
		assertEquals(1, profile.getBuildFailed());
		assertEquals("ROUTE_ARTIFACT_BUILD_FAILED", profile.getLastErrorCode());
	}

	@Test
	void finalizationFailureFallsBackToConditionalBuildFailureUpdate() {
		profile = buildingProfile(8L);
		EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
		DataAgentRouteArtifact reusable = DataAgentRouteArtifact.builder().id(42L).vectorDocumentId("route-42")
			.status("READY").deleted(false).build();
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateBuildWithRevision(any(), eq(8L))).thenThrow(new IllegalStateException("database unavailable"));
		when(profileMapper.markBuildFailed(1L, 8L, 1, "ROUTE_ARTIFACT_BUILD_FAILED")).thenReturn(1);
		when(embeddingModelResolver.resolve(eq(22L), eq("embedding-v2"), any())).thenReturn(embeddingModel);
		when(artifactMapper.findReusable(anyLong(), anyString(), anyString(), anyString(), anyString()))
			.thenReturn(reusable);
		when(vectorStoreService.hasRouteDocument(1L, 42L, "route-42", embeddingModel)).thenReturn(true);
		when(artifactMapper.findByTarget(1L, "tenant-1", "SKILL:30:31")).thenReturn(List.of());

		service.rebuildProfile(1L, 8L);

		verify(profileMapper).markBuildFailed(1L, 8L, 1, "ROUTE_ARTIFACT_BUILD_FAILED");
	}

	@Test
	void modelConfigChangeDuringBuildCannotFinalizeReady() {
		Instant probedAt = Instant.parse("2026-08-02T00:00:00Z");
		profile = buildingProfile(8L);
		enableRouteModel(probedAt, probedAt.plusSeconds(1));
		prepareSuccessfulArtifactBuild();

		service.rebuildProfile(1L, 8L);

		assertEquals("FAILED", profile.getStatus());
		assertEquals("FAILED", profile.getBuildStatus());
		assertEquals(RouteCapabilityState.STALE.name(), profile.getRouteModelProbeState());
		assertFalse(profile.getRouteModelRuntimeReady());
		assertEquals("ROUTE_MODEL_CAPABILITY_STALE", profile.getRouteModelFailureCode());
		assertEquals("ROUTE_MODEL_CAPABILITY_STALE", profile.getLastErrorCode());
		assertEquals(1, profile.getBuildTotal());
		assertEquals(1, profile.getBuildReady());
		assertEquals(0, profile.getBuildFailed());
		verify(transactionTemplate).executeWithoutResult(any());
		verify(modelConfigMapper).findByIdForUpdate(11L);
		verify(profileMapper).updateBuildWithRevision(profile, 8L);
	}

	@Test
	void unchangedModelConfigCanFinalizeReady() {
		Instant probedAt = Instant.parse("2026-08-02T00:00:00Z");
		profile = buildingProfile(8L);
		enableRouteModel(probedAt, probedAt);
		prepareSuccessfulArtifactBuild();

		service.rebuildProfile(1L, 8L);

		assertEquals("READY", profile.getStatus());
		assertEquals("READY", profile.getBuildStatus());
		assertEquals(RouteCapabilityState.SUPPORTED.name(), profile.getRouteModelProbeState());
		assertTrue(profile.getRouteModelRuntimeReady());
		assertEquals(1, profile.getBuildTotal());
		assertEquals(1, profile.getBuildReady());
		assertEquals(0, profile.getBuildFailed());
		verify(modelConfigMapper).findByIdForUpdate(11L);
		verify(profileMapper).updateBuildWithRevision(profile, 8L);
	}

	@Test
	void disabledModelDisambiguationFinalizesWithoutModelConfigLookup() {
		profile = buildingProfile(8L);
		profile.setModelDisambiguationEnabled(false);
		prepareSuccessfulArtifactBuild();

		service.rebuildProfile(1L, 8L);

		assertEquals("READY", profile.getStatus());
		assertEquals("READY", profile.getBuildStatus());
		verify(modelConfigMapper, never()).findByIdForUpdate(anyLong());
		verify(profileMapper).updateBuildWithRevision(profile, 8L);
	}

	@Test
	void refreshSkipsUnpublishedCollaboratorInsteadOfFailingTheCaller() {
		when(collaboratorMapper.findEnabledByCollaboratorAgentId(50L)).thenReturn(List.of(collaboratorRelation(20L, 10L)));
		when(agentMapper.selectById(10L)).thenReturn(agent(10L, "tenant-1", "published", AgentTypeConstant.ORCHESTRATOR));
		when(agentMapper.selectById(50L)).thenReturn(agent(50L, "tenant-1", "offline", AgentTypeConstant.DATA_ANALYSIS));
		ListAppender<ILoggingEvent> appender = attachAppender();
		try {
			assertDoesNotThrow(() -> service.refreshCollaboratorsForTargetAgent(50L));

			assertTrue(appender.list.stream()
				.filter(event -> event.getLevel() == Level.WARN)
				.anyMatch(event -> event.getFormattedMessage().contains("Collaborator route artifact skipped")
						&& event.getFormattedMessage().contains("COLLABORATOR_NOT_PUBLISHED")));
		}
		finally {
			detachAppender(appender);
		}
		verify(profileMapper, never()).findArtifactProfiles();
		verify(artifactMapper, never()).insert(any(DataAgentRouteArtifact.class));
	}

	@Test
	void refreshKeepsRebuildingRemainingRelationsAfterOneDrifted() {
		AgentCollaborator danglingOwner = collaboratorRelation(20L, 11L);
		AgentCollaborator eligible = collaboratorRelation(21L, 10L);
		when(collaboratorMapper.findEnabledByCollaboratorAgentId(50L))
			.thenReturn(List.of(danglingOwner, eligible));
		when(agentMapper.selectById(11L)).thenReturn(null);
		when(agentMapper.selectById(10L)).thenReturn(agent(10L, "tenant-1", "published", AgentTypeConstant.ORCHESTRATOR));
		when(agentMapper.selectById(50L))
			.thenReturn(agent(50L, "tenant-1", "published", AgentTypeConstant.DATA_ANALYSIS));
		when(capabilityResolver.resolve(50L, "tenant-1"))
			.thenReturn(new CollaboratorCapability(RouteRisk.READ_ONLY, 1, false));

		service.refreshCollaboratorsForTargetAgent(50L);

		verify(profileMapper, times(1)).findArtifactProfiles();
	}

	@Test
	void refreshFailsFastWhenCollaboratorTenantMismatches() {
		when(collaboratorMapper.findEnabledByCollaboratorAgentId(50L)).thenReturn(List.of(collaboratorRelation(20L, 10L)));
		when(agentMapper.selectById(10L)).thenReturn(agent(10L, "tenant-1", "published", AgentTypeConstant.ORCHESTRATOR));
		when(agentMapper.selectById(50L)).thenReturn(agent(50L, "tenant-2", "published", AgentTypeConstant.DATA_ANALYSIS));

		assertThrows(CheckedException.class, () -> service.refreshCollaboratorsForTargetAgent(50L));

		verify(profileMapper, never()).findArtifactProfiles();
	}

	@Test
	void prepareCollaboratorStillRejectsUnpublishedCollaborator() {
		AgentCollaborator relation = collaboratorRelation(20L, 10L);
		when(agentMapper.selectById(10L)).thenReturn(agent(10L, "tenant-1", "published", AgentTypeConstant.ORCHESTRATOR));
		when(agentMapper.selectById(50L)).thenReturn(agent(50L, "tenant-1", "offline", AgentTypeConstant.DATA_ANALYSIS));

		assertThrows(CheckedException.class, () -> service.prepareCollaborator(relation));

		verify(profileMapper, never()).findArtifactProfiles();
	}

	private AgentCollaborator collaboratorRelation(Long relationId, Long ownerAgentId) {
		return AgentCollaborator.builder().id(relationId).agentId(ownerAgentId).collaboratorAgentId(50L)
			.roleName("账单分析").capabilityDescription("分析账单").delegationMode(DelegationMode.INTERACTIVE.name())
			.routingRules(Map.of()).priority(0).enabled(true).deleted(false).build();
	}

	private DataAgent agent(Long agentId, String tenantId, String status, String agentType) {
		return DataAgent.builder().id(agentId).tenantId(tenantId).name("账单分析智能体").description("分析账单")
			.status(status).agentType(agentType).deleted(false).build();
	}

	private ListAppender<ILoggingEvent> attachAppender() {
		Logger logger = (Logger) LoggerFactory.getLogger(RouteArtifactServiceImpl.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private void detachAppender(ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger) LoggerFactory.getLogger(RouteArtifactServiceImpl.class);
		logger.detachAppender(appender);
		appender.stop();
	}

	private void enableRouteModel(Instant probedAt, Instant currentModifyTime) {
		ModelConfigDTO probedConfig = ModelConfigDTO.builder()
			.id(11L)
			.provider("custom")
			.baseUrl("https://route.example")
			.modelName("route-v2")
			.modelType(ModelType.CHAT.getCode())
			.lastModifyTime(probedAt)
			.build();
		profile.setModelDisambiguationEnabled(true);
		profile.setRouteModelConfigId(11L);
		profile.setRouteModelProbeState(RouteCapabilityState.SUPPORTED.name());
		profile.setRouteModelProbeVersion("route-capability-v2");
		profile.setRouteModelProtocol(RouteModelOutputProtocol.JSON_OBJECT.name());
		profile.setRouteModelRuntimeReady(true);
		profile.setRouteModelFingerprint(routeModelFingerprint.calculate(probedConfig));
		ModelConfig currentConfig = ModelConfig.builder()
			.id(11L)
			.provider("custom")
			.baseUrl("https://route.example")
			.modelName("route-v2")
			.temperature(0D)
			.maxTokens(2000L)
			.contextWindowTokens(32768L)
			.modelType(ModelType.CHAT)
			.proxyEnabled(false)
			.lastModifyTime(currentModifyTime)
			.deleted(false)
			.build();
		when(modelConfigMapper.findByIdForUpdate(11L)).thenReturn(currentConfig);
	}

	private void prepareSuccessfulArtifactBuild() {
		EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
		DataAgentRouteArtifact reusable = DataAgentRouteArtifact.builder().id(42L).vectorDocumentId("route-42")
			.status("READY").deleted(false).build();
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateBuildWithRevision(any(), eq(8L))).thenReturn(1);
		when(embeddingModelResolver.resolve(eq(22L), eq("embedding-v2"), any())).thenReturn(embeddingModel);
		when(artifactMapper.findReusable(anyLong(), anyString(), anyString(), anyString(), anyString()))
			.thenReturn(reusable);
		when(vectorStoreService.hasRouteDocument(1L, 42L, "route-42", embeddingModel)).thenReturn(true);
		when(artifactMapper.findByTarget(1L, "tenant-1", "SKILL:30:31")).thenReturn(List.of());
	}

	private DataAgentRouteProfile artifactProfile(Long id, Long modelConfigId, String status, String fingerprint) {
		return DataAgentRouteProfile.builder()
			.id(id)
			.semanticRecallEnabled(true)
			.semanticAutoSelectEnabled(false)
			.embeddingProbeState("SUPPORTED")
			.embeddingModelConfigId(modelConfigId)
			.embeddingFingerprint(fingerprint)
			.embeddingDimension(1536)
			.status(status)
			.deleted(false)
			.build();
	}

	private DataAgentRouteProfile buildingProfile(long revision) {
		return DataAgentRouteProfile.builder()
			.id(1L)
			.status("BUILDING")
			.buildStatus("RUNNING")
			.semanticRecallEnabled(true)
			.semanticAutoSelectEnabled(false)
			.embeddingProbeState("SUPPORTED")
			.embeddingModelConfigId(22L)
			.embeddingFingerprint("embedding-v2")
			.embeddingDimension(1536)
			.buildTotal(0)
			.buildReady(0)
			.buildFailed(0)
			.revision(revision)
			.deleted(false)
			.build();
	}

}
