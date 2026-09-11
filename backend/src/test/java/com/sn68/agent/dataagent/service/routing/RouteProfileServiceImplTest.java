/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.context.DataAgentAsyncContextBridge;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.dto.routing.RouteProfileCreateReq;
import com.sn68.agent.dataagent.dto.routing.RouteProfileResp;
import com.sn68.agent.dataagent.dto.routing.RouteProfileModifyReq;
import com.sn68.agent.dataagent.entity.DataAgentRouteProfile;
import com.sn68.agent.dataagent.entity.ModelConfig;
import com.sn68.agent.dataagent.event.ModelConfigChangedEvent;
import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.repository.DataAgentRouteProfileMapper;
import com.sn68.agent.dataagent.repository.ModelConfigMapper;
import com.sn68.agent.dataagent.routing.RouteCapabilityState;
import com.sn68.agent.dataagent.routing.RouteEmbeddingFingerprint;
import com.sn68.agent.dataagent.routing.RouteModelAdapter;
import com.sn68.agent.dataagent.routing.RouteModelFingerprint;
import com.sn68.agent.dataagent.routing.RouteModelOutputProtocol;
import com.sn68.agent.dataagent.routing.RouteModelProbeResult;
import com.sn68.agent.dataagent.service.aimodelconfig.DynamicModelFactory;
import com.sn68.agent.dataagent.service.aimodelconfig.ModelConfigDataService;
import com.sn68.agent.dataagent.service.permission.PlatformScopePermissionService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.redis.plus.exception.RedisLockException;
import com.sn68.agent.framework.redis.plus.lock.RedisLockHelper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class RouteProfileServiceImplTest {

	/**
	 * 与 {@code RouteProfileServiceImpl.ROUTE_MODEL_PROBE_VERSION} 保持一致。探测协议升版时这里要同步，
	 * 否则快照校验会先因版本不符判定 STALE，用例便再也走不到后面的模型锁定/指纹比对分支。
	 */
	private static final String ROUTE_MODEL_PROBE_VERSION = "route-model-capability-v3";

	private DataAgentRouteProfileMapper profileMapper;

	private ModelConfigDataService modelConfigDataService;

	private ModelConfigMapper modelConfigMapper;

	private DynamicModelFactory modelFactory;

	private RouteModelAdapter routeModelAdapter;

	private RouteArtifactService artifactService;

	private RouteEmbeddingFingerprint embeddingFingerprint;

	private RouteModelFingerprint modelFingerprint;

	private ExecutorService routeRetrievalExecutor;

	private DataAgentAsyncContextBridge asyncContextBridge;

	private TransactionTemplate transactionTemplate;

	private RedisLockHelper redisLockHelper;

	private PlatformScopePermissionService platformScopePermissionService;

	private RouteProfileServiceImpl service;

	private DataAgentProperties properties;

	private SimpleMeterRegistry meterRegistry;

	@BeforeEach
	void setUp() {
		profileMapper = mock(DataAgentRouteProfileMapper.class);
		modelConfigDataService = mock(ModelConfigDataService.class);
		modelConfigMapper = mock(ModelConfigMapper.class);
		modelFactory = mock(DynamicModelFactory.class);
		routeModelAdapter = mock(RouteModelAdapter.class);
		artifactService = mock(RouteArtifactService.class);
		embeddingFingerprint = new RouteEmbeddingFingerprint();
		modelFingerprint = new RouteModelFingerprint();
		routeRetrievalExecutor = mock(ExecutorService.class);
		asyncContextBridge = mock(DataAgentAsyncContextBridge.class);
		when(asyncContextBridge.capture()).thenReturn(DataAgentAsyncContextBridge.Snapshot.empty());
		transactionTemplate = mock(TransactionTemplate.class);
		doAnswer(invocation -> {
			TransactionCallback<?> callback = invocation.getArgument(0);
			return callback.doInTransaction(mock(TransactionStatus.class));
		}).when(transactionTemplate).execute(any());
		properties = new DataAgentProperties();
		meterRegistry = new SimpleMeterRegistry();
		redisLockHelper = mock(RedisLockHelper.class);
		platformScopePermissionService = mock(PlatformScopePermissionService.class);
		when(platformScopePermissionService.requireCurrentTenantId(anyString())).thenReturn("tenant-1");
		// 默认「总能抢到锁」，让用例覆盖的仍是原有业务路径；抢不到锁的跳过行为由专门的用例断言。
		when(redisLockHelper.execute(anyString(), anyLong(), any(TimeUnit.class), any()))
			.thenAnswer(invocation -> invocation.getArgument(3, Supplier.class).get());
		service = new RouteProfileServiceImpl(profileMapper, modelConfigDataService, modelConfigMapper, modelFactory,
				routeModelAdapter, modelFingerprint, artifactService, routeRetrievalExecutor, asyncContextBridge,
				embeddingFingerprint, properties, meterRegistry, transactionTemplate, redisLockHelper,
				platformScopePermissionService);
	}

	@Test
	void createDefaultsToSemanticRecallWithoutAutomaticSelection() {
		ModelConfigDTO embeddingConfig = embeddingConfig(22L);
		when(modelConfigDataService.getConfigById(22L, ModelType.EMBEDDING)).thenReturn(embeddingConfig);

		service.create(new RouteProfileCreateReq("semantic-recall", null, 22L, null, null, null, null,
				null, null, null, null, null, null));

		var captor = ArgumentCaptor.forClass(DataAgentRouteProfile.class);
		verify(profileMapper).insert(captor.capture());
		DataAgentRouteProfile profile = captor.getValue();
		assertEquals("tenant-1", profile.getTenantId());
		assertTrue(profile.getLexicalAutoSelectEnabled());
		assertTrue(profile.getSemanticRecallEnabled());
		assertFalse(profile.getSemanticAutoSelectEnabled());
		assertFalse(profile.getModelDisambiguationEnabled());
		assertEquals("NOT_BUILT", profile.getBuildStatus());
		verify(modelConfigDataService).getConfigById(22L, ModelType.EMBEDDING);
	}

	@Test
	void createRecallOnlyProfileUsesNotBuiltStatus() {
		ModelConfigDTO embeddingConfig = embeddingConfig(22L);
		when(modelConfigDataService.getConfigById(22L, ModelType.EMBEDDING)).thenReturn(embeddingConfig);

		service.create(new RouteProfileCreateReq("semantic", null, 22L, true, true, false, false,
				null, null, null, null, null, null));

		var captor = ArgumentCaptor.forClass(DataAgentRouteProfile.class);
		verify(profileMapper).insert(captor.capture());
		assertEquals("NOT_BUILT", captor.getValue().getBuildStatus());
	}

	@Test
	void createRejectsSemanticAutoSelectWithoutRecall() {
		CheckedException exception = assertThrows(CheckedException.class,
				() -> service.create(new RouteProfileCreateReq("invalid", null, null, true, false, true, false,
						null, null, null, null, null, null)));

		assertEquals("语义自动选择开启时必须同时开启语义召回", exception.getMessage());
		verify(modelConfigDataService, never()).getConfigById(any(), any());
	}

	@Test
	void modifyRejectsSemanticAutoSelectWithoutRecall() {
		DataAgentRouteProfile profile = probeableProfile();
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);

		CheckedException exception = assertThrows(CheckedException.class,
				() -> service.modify(1L, new RouteProfileModifyReq(3L, "invalid", 11L, 22L, true, false,
						true, true, 70, 15, new BigDecimal("0.50"), new BigDecimal("0.85"),
						new BigDecimal("0.10"), new BigDecimal("0.80"))));

		assertEquals("语义自动选择开启时必须同时开启语义召回", exception.getMessage());
		verify(profileMapper, never()).updateWithRevision(any(), anyLong());
	}

	@Test
	void modifyNullSemanticRecallPreservesExistingValue() {
		DataAgentRouteProfile profile = probeableProfile();
		ModelConfigDTO embeddingConfig = embeddingConfig(22L);
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(any(), eq(3L))).thenReturn(1);
		when(modelConfigDataService.getConfigById(22L, ModelType.EMBEDDING)).thenReturn(embeddingConfig);

		service.modify(1L, new RouteProfileModifyReq(3L, "preserve-recall", null, 22L, true, null,
				false, false, 70, 15, new BigDecimal("0.50"), new BigDecimal("0.85"),
				new BigDecimal("0.10"), new BigDecimal("0.80")));

		assertTrue(profile.getSemanticRecallEnabled());
		assertFalse(profile.getSemanticAutoSelectEnabled());
		verify(profileMapper).updateWithRevision(profile, 3L);
	}

	@Test
	void modifyAppliesExplicitSemanticRecallChange() {
		DataAgentRouteProfile profile = lexicalProfile("DRAFT");
		ModelConfigDTO embeddingConfig = embeddingConfig(22L);
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(any(), eq(3L))).thenReturn(1);
		when(modelConfigDataService.getConfigById(22L, ModelType.EMBEDDING)).thenReturn(embeddingConfig);

		service.modify(1L, new RouteProfileModifyReq(3L, "enable-recall", null, 22L, true, true,
				false, false, 70, 15, new BigDecimal("0.50"), new BigDecimal("0.85"),
				new BigDecimal("0.10"), new BigDecimal("0.80")));

		assertTrue(profile.getSemanticRecallEnabled());
		assertFalse(profile.getSemanticAutoSelectEnabled());
		verify(profileMapper).updateWithRevision(profile, 3L);
	}

	@Test
	void probeReturnsIndependentSupportedCapabilities() {
		DataAgentRouteProfile profile = probeableProfile();
		ModelConfigDTO routeConfig = routeConfig(11L, "https://route.example");
		ModelConfigDTO embeddingConfig = embeddingConfig(22L);
		EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(any(), eq(3L))).thenReturn(1);
		when(modelConfigDataService.getRuntimeConfigById(11L, ModelType.CHAT, "tenant-1")).thenReturn(routeConfig);
		when(modelConfigDataService.getRuntimeConfigById(22L, ModelType.EMBEDDING, "tenant-1")).thenReturn(embeddingConfig);
		when(modelConfigDataService.getConfigById(11L, ModelType.CHAT, "tenant-1")).thenReturn(routeConfig);
		when(modelConfigMapper.findByIdForUpdate(11L))
			.thenReturn(routeModelConfig(11L, "https://route.example", null, ModelType.CHAT, false));
		when(routeModelAdapter.probe(routeConfig)).thenReturn(new RouteModelProbeResult(RouteCapabilityState.SUPPORTED,
				RouteModelOutputProtocol.JSON_OBJECT, 200L, null));
		when(modelFactory.createRouteEmbeddingModel(embeddingConfig, java.time.Duration.ofSeconds(30)))
			.thenReturn(embeddingModel);
		when(embeddingModel.embed("hybrid route profile probe")).thenReturn(new float[] { 0.1F, 0.2F });

		var result = service.probe(1L);

		assertEquals(RouteCapabilityState.SUPPORTED.name(), result.modelCapability().state());
		assertEquals(RouteModelOutputProtocol.JSON_OBJECT.name(), result.modelCapability().protocol());
		assertTrue(result.modelCapability().runtimeReady());
		assertEquals(RouteCapabilityState.SUPPORTED.name(), result.embeddingCapability().state());
		assertEquals(1L, result.id());
		assertEquals(4L, result.revision());
		assertEquals(2, profile.getEmbeddingDimension());
		assertEquals("NOT_BUILT", profile.getBuildStatus());
		assertEquals(1D, meterRegistry.get("data.agent.routing.probe.results")
			.tag("capability", "model")
			.tag("protocol", RouteModelOutputProtocol.JSON_OBJECT.name())
			.counter()
			.count());
		assertEquals(1D, meterRegistry.get("data.agent.routing.probe.results")
			.tag("capability", "embedding")
			.tag("protocol", RouteModelOutputProtocol.NONE.name())
			.counter()
			.count());
		verify(embeddingModel).embed("hybrid route profile probe");
	}

	@Test
	void probeReadinessUsesConfiguredOnlineModelBudget() {
		properties.getRuntime().getRouting().setModelTimeout(java.time.Duration.ofMillis(150));
		DataAgentRouteProfile profile = probeableProfile();
		profile.setSemanticRecallEnabled(false);
		profile.setSemanticAutoSelectEnabled(false);
		ModelConfigDTO routeConfig = routeConfig(11L, "https://route.example");
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(any(), eq(3L))).thenReturn(1);
		when(modelConfigDataService.getRuntimeConfigById(11L, ModelType.CHAT, "tenant-1")).thenReturn(routeConfig);
		when(routeModelAdapter.probe(routeConfig)).thenReturn(new RouteModelProbeResult(RouteCapabilityState.SUPPORTED,
				RouteModelOutputProtocol.JSON_OBJECT, 200L, null));

		var result = service.probe(1L);

		assertFalse(result.modelCapability().runtimeReady());
		assertEquals("ROUTE_MODEL_RUNTIME_BUDGET_EXCEEDED", result.modelCapability().failureCode());
		assertEquals(1D, meterRegistry.get("data.agent.routing.probe.budget.exceeded")
			.tag("capability", "model")
			.counter()
			.count());
	}

	@Test
	void modelConfigChangeDuringProbeCannotPersistReadyWithStaleCapability() {
		Instant probedAt = Instant.parse("2026-08-02T00:00:00Z");
		ModelConfigDTO probedConfig = routeConfig(11L, "https://route.example", probedAt);
		DataAgentRouteProfile profile = probeableProfile();
		AtomicBoolean transactionOpen = new AtomicBoolean();
		profile.setSemanticRecallEnabled(false);
		profile.setSemanticAutoSelectEnabled(false);
		profile.setEmbeddingModelConfigId(null);
		doAnswer(invocation -> {
			TransactionCallback<?> callback = invocation.getArgument(0);
			transactionOpen.set(true);
			try {
				return callback.doInTransaction(mock(TransactionStatus.class));
			}
			finally {
				transactionOpen.set(false);
			}
		}).when(transactionTemplate).execute(any());
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(any(), eq(3L))).thenReturn(1);
		when(modelConfigDataService.getRuntimeConfigById(11L, ModelType.CHAT, "tenant-1")).thenReturn(probedConfig);
		when(routeModelAdapter.probe(probedConfig)).thenAnswer(invocation -> {
			assertFalse(transactionOpen.get(), "外部模型 probe 必须在事务外执行");
			return new RouteModelProbeResult(RouteCapabilityState.SUPPORTED,
					RouteModelOutputProtocol.JSON_OBJECT, 100L, null);
		});
		when(modelConfigMapper.findByIdForUpdate(11L))
			.thenReturn(routeModelConfig(11L, "https://route.example", probedAt.plusSeconds(1), ModelType.CHAT, false));

		var result = service.probe(1L);

		assertEquals("DRAFT", result.status());
		assertEquals(RouteCapabilityState.STALE.name(), result.modelCapability().state());
		assertFalse(result.modelCapability().runtimeReady());
		assertEquals("ROUTE_MODEL_CAPABILITY_STALE", result.lastErrorCode());
		InOrder inOrder = inOrder(profileMapper, modelConfigDataService, routeModelAdapter, modelConfigMapper);
		inOrder.verify(profileMapper).findAvailableById(1L);
		inOrder.verify(modelConfigDataService).getRuntimeConfigById(11L, ModelType.CHAT, "tenant-1");
		inOrder.verify(routeModelAdapter).probe(probedConfig);
		inOrder.verify(profileMapper).findAvailableById(1L);
		inOrder.verify(modelConfigMapper).findByIdForUpdate(11L);
		inOrder.verify(profileMapper).updateWithRevision(profile, 3L);
		verify(modelConfigDataService, never()).getConfigById(11L, ModelType.CHAT, "tenant-1");
	}

	@Test
	void probeRejectsProfileRevisionChangedDuringExternalProbeBeforeCas() {
		ModelConfigDTO routeConfig = routeConfig(11L, "https://route.example");
		DataAgentRouteProfile probedProfile = probeableProfile();
		DataAgentRouteProfile currentProfile = probeableProfile();
		probedProfile.setSemanticRecallEnabled(false);
		probedProfile.setSemanticAutoSelectEnabled(false);
		probedProfile.setEmbeddingModelConfigId(null);
		currentProfile.setSemanticRecallEnabled(false);
		currentProfile.setSemanticAutoSelectEnabled(false);
		currentProfile.setEmbeddingModelConfigId(null);
		currentProfile.setRevision(4L);
		when(profileMapper.findAvailableById(1L)).thenReturn(probedProfile, currentProfile);
		when(profileMapper.updateWithRevision(any(), eq(3L))).thenReturn(1);
		when(modelConfigDataService.getRuntimeConfigById(11L, ModelType.CHAT, "tenant-1")).thenReturn(routeConfig);
		when(modelConfigDataService.getConfigById(11L, ModelType.CHAT, "tenant-1")).thenReturn(routeConfig);
		when(routeModelAdapter.probe(routeConfig)).thenReturn(new RouteModelProbeResult(RouteCapabilityState.SUPPORTED,
				RouteModelOutputProtocol.JSON_OBJECT, 100L, null));

		assertThrows(CheckedException.class, () -> service.probe(1L));

		verify(profileMapper, never()).updateWithRevision(any(), anyLong());
	}

	@Test
	void currentIncludesReadOnlyDefaultsConstraintsAndBudgets() {
		var result = service.current();

		assertTrue(result.configuration().defaults().semanticRecallEnabled());
		assertFalse(result.configuration().defaults().semanticAutoSelectEnabled());
		assertEquals(70, result.configuration().defaults().lexicalMinScore());
		assertEquals(128, result.configuration().constraints().profileNameMaxLength());
		assertEquals(1800L, result.configuration().runtimeBudget().totalTimeoutMs());
		assertEquals(1100L, result.configuration().runtimeBudget().modelTimeoutMs());
		assertEquals(30000L, result.configuration().probeTimeouts().modelTimeoutMs());
	}

	@Test
	void probeStillChecksEmbeddingWhenModelIsUnavailable() {
		DataAgentRouteProfile profile = probeableProfile();
		ModelConfigDTO routeConfig = routeConfig(11L, "https://route.example");
		ModelConfigDTO embeddingConfig = embeddingConfig(22L);
		EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(any(), eq(3L))).thenReturn(1);
		when(modelConfigDataService.getRuntimeConfigById(11L, ModelType.CHAT, "tenant-1")).thenReturn(routeConfig);
		when(modelConfigDataService.getRuntimeConfigById(22L, ModelType.EMBEDDING, "tenant-1")).thenReturn(embeddingConfig);
		when(routeModelAdapter.probe(routeConfig)).thenReturn(new RouteModelProbeResult(RouteCapabilityState.UNAVAILABLE,
				RouteModelOutputProtocol.NONE, 0L, "ROUTE_MODEL_UNAVAILABLE"));
		when(modelFactory.createRouteEmbeddingModel(embeddingConfig, java.time.Duration.ofSeconds(30)))
			.thenReturn(embeddingModel);
		when(embeddingModel.embed("hybrid route profile probe")).thenReturn(new float[] { 0.1F, 0.2F });

		var result = service.probe(1L);

		assertEquals(RouteCapabilityState.UNAVAILABLE.name(), result.modelCapability().state());
		assertEquals(RouteCapabilityState.SUPPORTED.name(), result.embeddingCapability().state());
		verify(embeddingModel).embed("hybrid route profile probe");
	}

	@Test
	void probeChecksEmbeddingWhenRecallIsEnabledWithoutAutomaticSelection() {
		DataAgentRouteProfile profile = probeableProfile();
		profile.setSemanticAutoSelectEnabled(false);
		profile.setModelDisambiguationEnabled(false);
		ModelConfigDTO embeddingConfig = embeddingConfig(22L);
		EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(any(), eq(3L))).thenReturn(1);
		when(modelConfigDataService.getRuntimeConfigById(22L, ModelType.EMBEDDING, "tenant-1")).thenReturn(embeddingConfig);
		when(modelFactory.createRouteEmbeddingModel(embeddingConfig, java.time.Duration.ofSeconds(30)))
			.thenReturn(embeddingModel);
		when(embeddingModel.embed("hybrid route profile probe")).thenReturn(new float[] { 0.1F, 0.2F });

		var result = service.probe(1L);

		assertEquals(RouteCapabilityState.SUPPORTED.name(), result.embeddingCapability().state());
		assertEquals("NOT_BUILT", result.buildStatus());
		verify(embeddingModel).embed("hybrid route profile probe");
	}

	@Test
	void rebuildSemanticProfileUsesRunningStatus() {
		DataAgentRouteProfile profile = probeableProfile();
		profile.setSemanticAutoSelectEnabled(false);
		profile.setModelDisambiguationEnabled(false);
		ModelConfigDTO embeddingConfig = embeddingConfig(22L);
		profile.setEmbeddingProbeState(RouteCapabilityState.SUPPORTED.name());
		profile.setEmbeddingProbeVersion("route-capability-v2");
		profile.setEmbeddingFingerprint(embeddingFingerprint.calculate(embeddingConfig));
		profile.setEmbeddingDimension(2);
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(any(), eq(3L))).thenReturn(1);
		when(modelConfigDataService.getConfigById(22L, ModelType.EMBEDDING, "tenant-1")).thenReturn(embeddingConfig);

		var result = service.rebuild(1L);

		assertEquals("BUILDING", result.status());
		assertEquals("RUNNING", result.buildStatus());
	}

	@Test
	void semanticProfileCannotRebuildWhenRouteModelCapabilityIsUnavailable() {
		DataAgentRouteProfile profile = rebuildableProfile();
		profile.setModelDisambiguationEnabled(true);
		profile.setRouteModelProbeState(RouteCapabilityState.UNAVAILABLE.name());
		profile.setRouteModelProtocol(RouteModelOutputProtocol.NONE.name());
		profile.setRouteModelRuntimeReady(false);
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);

		assertThrows(CheckedException.class, () -> service.rebuild(1L));

		assertEquals("DRAFT", profile.getStatus());
		verify(routeRetrievalExecutor, never()).execute(any(Runnable.class));
	}

	@Test
	void semanticProfileCannotRebuildWhenRouteModelFingerprintIsStale() {
		Instant probedAt = Instant.parse("2026-08-02T00:00:00Z");
		ModelConfigDTO probedConfig = routeConfig(11L, "https://route.example", probedAt);
		DataAgentRouteProfile profile = routeModelReady(rebuildableProfile(), probedConfig,
				RouteModelOutputProtocol.JSON_OBJECT);
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(any(), eq(3L))).thenReturn(1);
		when(modelConfigDataService.getConfigById(11L, ModelType.CHAT, "tenant-1"))
			.thenReturn(routeConfig(11L, "https://route.example", probedAt.plusSeconds(1)));
		when(modelConfigMapper.findByIdForUpdate(11L))
			.thenReturn(routeModelConfig(11L, "https://route.example", probedAt.plusSeconds(1), ModelType.CHAT, false));

		assertThrows(CheckedException.class, () -> service.rebuild(1L));

		assertEquals("DRAFT", profile.getStatus());
		assertEquals(RouteCapabilityState.STALE.name(), profile.getRouteModelProbeState());
		assertFalse(profile.getRouteModelRuntimeReady());
		assertEquals("ROUTE_MODEL_CAPABILITY_STALE", profile.getRouteModelFailureCode());
		assertEquals("ROUTE_MODEL_CAPABILITY_STALE", profile.getLastErrorCode());
		verify(profileMapper).updateWithRevision(profile, 3L);
		verify(routeRetrievalExecutor, never()).execute(any(Runnable.class));
	}

	@Test
	void rebuildPassesUpdatedRevisionToAsyncTask() {
		DataAgentRouteProfile profile = rebuildableProfile();
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(any(), eq(3L))).thenReturn(1);
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(routeRetrievalExecutor).execute(any(Runnable.class));
		doAnswer(invocation -> {
			invocation.getArgument(1, Runnable.class).run();
			return null;
		}).when(asyncContextBridge).runWith(any(), any(Runnable.class));

		service.rebuild(1L);

		verify(artifactService).rebuildProfile(1L, 4L);
	}

	@Test
	void lexicalRebuildLocksRouteModelConfigBeforeReadyCas() {
		Instant probedAt = Instant.parse("2026-08-02T00:00:00Z");
		ModelConfigDTO routeConfig = routeConfig(11L, "https://route.example", probedAt);
		DataAgentRouteProfile profile = lexicalModelProfile(routeConfig);
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(any(), eq(3L))).thenReturn(1);
		when(modelConfigDataService.getConfigById(11L, ModelType.CHAT, "tenant-1")).thenReturn(routeConfig);
		when(modelConfigMapper.findByIdForUpdate(11L))
			.thenReturn(routeModelConfig(11L, "https://route.example", probedAt, ModelType.CHAT, false));

		var result = service.rebuild(1L);

		assertEquals("READY", result.status());
		InOrder inOrder = inOrder(modelConfigMapper, profileMapper);
		inOrder.verify(modelConfigMapper).findByIdForUpdate(11L);
		inOrder.verify(profileMapper).updateWithRevision(profile, 3L);
		verify(modelConfigDataService, never()).getConfigById(11L, ModelType.CHAT, "tenant-1");
	}

	@Test
	void rebuildStartsExecutorOnlyAfterPreparationTransactionCommits() {
		DataAgentRouteProfile profile = rebuildableProfile();
		AtomicBoolean preparationCommitted = new AtomicBoolean();
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(any(), eq(3L))).thenReturn(1);
		doAnswer(invocation -> {
			TransactionCallback<?> callback = invocation.getArgument(0);
			Object result = callback.doInTransaction(mock(TransactionStatus.class));
			preparationCommitted.set(true);
			return result;
		}).when(transactionTemplate).execute(any());
		doAnswer(invocation -> {
			assertTrue(preparationCommitted.get(), "executor 只能在 preparation 事务提交后启动");
			return null;
		}).when(routeRetrievalExecutor).execute(any(Runnable.class));

		service.rebuild(1L);

		assertTrue(preparationCommitted.get());
	}

	@Test
	void staleSemanticRebuildCommitsDraftBeforeThrowing() {
		Instant probedAt = Instant.parse("2026-08-02T00:00:00Z");
		ModelConfigDTO probedConfig = routeConfig(11L, "https://route.example", probedAt);
		DataAgentRouteProfile profile = routeModelReady(rebuildableProfile(), probedConfig,
				RouteModelOutputProtocol.JSON_OBJECT);
		AtomicBoolean preparationCommitted = new AtomicBoolean();
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(any(), eq(3L))).thenReturn(1);
		when(modelConfigDataService.getConfigById(11L, ModelType.CHAT, "tenant-1"))
			.thenReturn(routeConfig(11L, "https://route.example", probedAt.plusSeconds(1)));
		when(modelConfigMapper.findByIdForUpdate(11L))
			.thenReturn(routeModelConfig(11L, "https://route.example", probedAt.plusSeconds(1), ModelType.CHAT, false));
		doAnswer(invocation -> {
			TransactionCallback<?> callback = invocation.getArgument(0);
			Object result = callback.doInTransaction(mock(TransactionStatus.class));
			preparationCommitted.set(true);
			return result;
		}).when(transactionTemplate).execute(any());

		assertThrows(CheckedException.class, () -> service.rebuild(1L));

		assertTrue(preparationCommitted.get(), "DRAFT/STALE 必须先提交，再由事务外抛错");
		assertEquals("DRAFT", profile.getStatus());
		assertEquals(RouteCapabilityState.STALE.name(), profile.getRouteModelProbeState());
		verify(profileMapper).updateWithRevision(profile, 3L);
		verify(routeRetrievalExecutor, never()).execute(any(Runnable.class));
	}

	@Test
	void staleEmbeddingRebuildCommitsDraftBeforeThrowing() {
		DataAgentRouteProfile profile = rebuildableProfile();
		AtomicBoolean preparationCommitted = new AtomicBoolean();
		profile.setStatus("READY");
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(any(), eq(3L))).thenReturn(1);
		when(modelConfigDataService.getConfigById(22L, ModelType.EMBEDDING, "tenant-1"))
			.thenReturn(embeddingConfig(22L, "https://changed-embedding.example"));
		doAnswer(invocation -> {
			TransactionCallback<?> callback = invocation.getArgument(0);
			Object result = callback.doInTransaction(mock(TransactionStatus.class));
			preparationCommitted.set(true);
			return result;
		}).when(transactionTemplate).execute(any());

		assertThrows(CheckedException.class, () -> service.rebuild(1L));

		assertTrue(preparationCommitted.get(), "DRAFT/STALE 必须先提交，再由事务外抛错");
		assertEquals("DRAFT", profile.getStatus());
		assertEquals(RouteCapabilityState.STALE.name(), profile.getEmbeddingProbeState());
		assertEquals("ROUTE_EMBEDDING_CAPABILITY_STALE", profile.getLastErrorCode());
		verify(profileMapper).updateWithRevision(profile, 3L);
		verify(routeRetrievalExecutor, never()).execute(any(Runnable.class));
	}

	@Test
	void executorRejectionMarksCurrentBuildFailed() {
		DataAgentRouteProfile profile = rebuildableProfile();
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(any(), eq(3L))).thenReturn(1);
		when(profileMapper.updateBuildWithRevision(any(), eq(4L))).thenReturn(1);
		doThrow(new RejectedExecutionException("queue full")).when(routeRetrievalExecutor)
			.execute(any(Runnable.class));

		var result = service.rebuild(1L);

		assertEquals("FAILED", result.status());
		assertEquals("FAILED", result.buildStatus());
		assertEquals("ROUTE_RETRIEVAL_EXECUTOR_REJECTED", result.errorCode());
		verify(artifactService, never()).rebuildProfile(anyLong(), anyLong());
	}

	@Test
	void buildStatusRecoversTimedOutBuildWithRevisionGuard() {
		DataAgentRouteProfile profile = staleBuildingProfile();
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateBuildWithRevision(any(), eq(8L))).thenReturn(1);

		var result = service.buildStatus(1L);

		assertEquals("FAILED", result.status());
		assertEquals("FAILED", result.buildStatus());
		assertEquals("ROUTE_ARTIFACT_BUILD_TIMEOUT", result.errorCode());
		assertEquals(9L, result.revision());
	}

	@Test
	void scheduledRecoveryMarksTimedOutBuildFailed() {
		DataAgentRouteProfile profile = staleBuildingProfile();
		when(profileMapper.findStaleBuilding(any())).thenReturn(java.util.List.of(profile));
		when(profileMapper.updateBuildWithRevision(any(), eq(8L))).thenReturn(1);

		service.recoverStaleBuilds();

		assertEquals("FAILED", profile.getStatus());
		assertEquals("FAILED", profile.getBuildStatus());
		assertEquals("ROUTE_ARTIFACT_BUILD_TIMEOUT", profile.getLastErrorCode());
		verify(profileMapper).updateBuildWithRevision(profile, 8L);
	}

	@Test
	void staleCapabilityRoundSchedulesReprobeWhenTheRoundLockIsAcquired() {
		DataAgentRouteProfile profile = staleRouteModelProfile();
		when(profileMapper.findStaleRouteModelCapabilities()).thenReturn(java.util.List.of(profile));

		service.recoverStaleCapabilities();

		verify(routeRetrievalExecutor).execute(any(Runnable.class));
	}

	@Test
	void staleCapabilityRoundIsSkippedWhenAnotherReplicaHoldsTheRoundLock() {
		when(redisLockHelper.execute(eq("dataagent:routing:capability-recovery"), anyLong(), any(TimeUnit.class),
				any()))
			.thenThrow(new RedisLockException("Redis 锁获取失败"));

		service.recoverStaleCapabilities();

		verify(profileMapper, never()).findStaleRouteModelCapabilities();
		verify(routeRetrievalExecutor, never()).execute(any(Runnable.class));
	}

	@Test
	void routeModelReprobeIsSkippedWhenAnotherReplicaHoldsTheProfileClaim() {
		DataAgentRouteProfile profile = staleRouteModelProfile();
		when(profileMapper.findStaleRouteModelCapabilities()).thenReturn(java.util.List.of(profile));
		when(redisLockHelper.execute(startsWith("dataagent:routing:route-model-reprobe:"), anyLong(),
				any(TimeUnit.class), any()))
			.thenThrow(new RedisLockException("Redis 锁获取失败"));
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(routeRetrievalExecutor).execute(any(Runnable.class));
		doAnswer(invocation -> {
			invocation.getArgument(1, Runnable.class).run();
			return null;
		}).when(asyncContextBridge).runWith(any(), any(Runnable.class));

		service.recoverStaleCapabilities();

		verify(routeRetrievalExecutor).execute(any(Runnable.class));
		verify(profileMapper, never()).findAvailableById(anyLong());
		verify(routeModelAdapter, never()).probe(any());
	}

	@Test
	void unavailableRouteModelKeepsProfileDraftAndBlocksActivation() {
		DataAgentRouteProfile profile = probeableProfile();
		profile.setSemanticRecallEnabled(false);
		profile.setSemanticAutoSelectEnabled(false);
		profile.setEmbeddingModelConfigId(null);
		profile.setBuildStatus("SKIPPED");
		ModelConfigDTO routeConfig = routeConfig(11L, "https://route.example");
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(any(), anyLong())).thenReturn(1);
		when(modelConfigDataService.getRuntimeConfigById(11L, ModelType.CHAT, "tenant-1")).thenReturn(routeConfig);
		when(routeModelAdapter.probe(routeConfig)).thenReturn(new RouteModelProbeResult(RouteCapabilityState.UNAVAILABLE,
				RouteModelOutputProtocol.NONE, 0L, "ROUTE_MODEL_UNAVAILABLE"));
		when(artifactService.hasValidRuleSources()).thenReturn(true);

		var probeResult = service.probe(1L);

		assertEquals(RouteCapabilityState.UNAVAILABLE.name(), probeResult.modelCapability().state());
		assertEquals("DRAFT", probeResult.status());
		assertThrows(CheckedException.class, () -> service.rebuild(1L));
		assertThrows(CheckedException.class, () -> service.activate(1L));
		verify(profileMapper, never()).retireActiveExcept(anyString(), anyLong());
	}

	@Test
	void lexicalRebuildMarksStaleModelCapabilityDraft() {
		Instant probedAt = Instant.parse("2026-08-02T00:00:00Z");
		ModelConfigDTO probedConfig = routeConfig(11L, "https://route.example", probedAt);
		DataAgentRouteProfile profile = lexicalModelProfile(probedConfig);
		AtomicBoolean transactionCommitted = new AtomicBoolean();
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(any(), eq(3L))).thenReturn(1);
		when(modelConfigDataService.getConfigById(11L, ModelType.CHAT, "tenant-1"))
			.thenReturn(routeConfig(11L, "https://route.example", probedAt.plusSeconds(1)));
		when(modelConfigMapper.findByIdForUpdate(11L))
			.thenReturn(routeModelConfig(11L, "https://route.example", probedAt.plusSeconds(1), ModelType.CHAT, false));
		doAnswer(invocation -> {
			TransactionCallback<?> callback = invocation.getArgument(0);
			Object result = callback.doInTransaction(mock(TransactionStatus.class));
			transactionCommitted.set(true);
			return result;
		}).when(transactionTemplate).execute(any());

		assertThrows(CheckedException.class, () -> service.rebuild(1L));

		assertTrue(transactionCommitted.get(), "DRAFT/STALE 必须先提交，再由事务外抛错");
		assertEquals("DRAFT", profile.getStatus());
		assertEquals("SKIPPED", profile.getBuildStatus());
		assertEquals(RouteCapabilityState.STALE.name(), profile.getRouteModelProbeState());
		assertFalse(profile.getRouteModelRuntimeReady());
		assertEquals("ROUTE_MODEL_CAPABILITY_STALE", profile.getLastErrorCode());
		verify(profileMapper).updateWithRevision(profile, 3L);
		verify(modelConfigDataService, never()).getConfigById(11L, ModelType.CHAT, "tenant-1");
		verify(routeRetrievalExecutor, never()).execute(any(Runnable.class));
	}

	@Test
	void routeModelMissingConfigIdBlocksLexicalReadiness() {
		DataAgentRouteProfile profile = lexicalModelProfile(routeConfig(11L, "https://route.example"));
		profile.setRouteModelConfigId(null);

		assertLexicalSnapshotRemainsDraft(profile);
	}

	@Test
	void routeModelMissingFingerprintBlocksLexicalReadiness() {
		DataAgentRouteProfile profile = lexicalModelProfile(routeConfig(11L, "https://route.example"));
		profile.setRouteModelFingerprint(null);

		assertLexicalSnapshotRemainsDraft(profile);
	}

	@Test
	void routeModelNoneProtocolBlocksLexicalReadiness() {
		DataAgentRouteProfile profile = lexicalModelProfile(routeConfig(11L, "https://route.example"));
		profile.setRouteModelProtocol(RouteModelOutputProtocol.NONE.name());

		assertLexicalSnapshotRemainsDraft(profile);
	}

	@Test
	void routeModelNotRuntimeReadyBlocksLexicalReadiness() {
		DataAgentRouteProfile profile = lexicalModelProfile(routeConfig(11L, "https://route.example"));
		profile.setRouteModelRuntimeReady(false);

		assertLexicalSnapshotRemainsDraft(profile);
	}

	@Test
	void missingLockedModelConfigBlocksLexicalReadiness() {
		assertLockedConfigBlocksLexicalReadiness(null);
	}

	@Test
	void deletedLockedModelConfigBlocksLexicalReadiness() {
		Instant probedAt = Instant.parse("2026-08-02T00:00:00Z");
		assertLockedConfigBlocksLexicalReadiness(
				routeModelConfig(11L, "https://route.example", probedAt, ModelType.CHAT, true));
	}

	@Test
	void nonChatLockedModelConfigBlocksLexicalReadiness() {
		Instant probedAt = Instant.parse("2026-08-02T00:00:00Z");
		assertLockedConfigBlocksLexicalReadiness(
				routeModelConfig(11L, "https://route.example", probedAt, ModelType.EMBEDDING, false));
	}

	@Test
	void oldProbeVersionBlocksLexicalReadinessWithoutModelLookup() {
		DataAgentRouteProfile profile = lexicalModelProfile(routeConfig(11L, "https://route.example"));
		profile.setRouteModelProbeVersion("route-capability-v1");

		assertLexicalSnapshotRemainsDraft(profile);

		assertEquals(RouteCapabilityState.STALE.name(), profile.getRouteModelProbeState());
		verify(modelConfigMapper, never()).findByIdForUpdate(anyLong());
	}

	@Test
	void invalidCombinedProtocolBlocksLexicalReadinessWithoutModelLookup() {
		DataAgentRouteProfile profile = lexicalModelProfile(routeConfig(11L, "https://route.example"));
		profile.setRouteModelProtocol("JSON_OBJECT,PROMPT_JSON");

		assertLexicalSnapshotRemainsDraft(profile);

		verify(modelConfigMapper, never()).findByIdForUpdate(anyLong());
	}

	@Test
	void readyProfileCannotActivateWhenModelCapabilityIsNotRuntimeReady() {
		DataAgentRouteProfile profile = probeableProfile();
		profile.setStatus("READY");
		profile.setSemanticRecallEnabled(false);
		profile.setSemanticAutoSelectEnabled(false);
		profile.setEmbeddingModelConfigId(null);
		profile.setBuildStatus("SKIPPED");
		profile.setRouteModelProbeState(RouteCapabilityState.UNAVAILABLE.name());
		profile.setRouteModelProtocol(RouteModelOutputProtocol.NONE.name());
		profile.setRouteModelRuntimeReady(false);
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(artifactService.hasValidRuleSources()).thenReturn(true);

		assertThrows(CheckedException.class, () -> service.activate(1L));

		assertEquals("READY", profile.getStatus());
		verify(profileMapper, never()).retireActiveExcept(anyString(), anyLong());
	}

	@Test
	void readyProfileWithStaleRouteModelFingerprintCannotActivate() {
		Instant probedAt = Instant.parse("2026-08-02T00:00:00Z");
		ModelConfigDTO probedConfig = routeConfig(11L, "https://route.example", probedAt);
		DataAgentRouteProfile profile = lexicalModelProfile(probedConfig);
		profile.setStatus("READY");
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(any(), eq(3L))).thenReturn(1);
		when(artifactService.hasValidRuleSources()).thenReturn(true);
		when(modelConfigDataService.getConfigById(11L, ModelType.CHAT, "tenant-1"))
			.thenReturn(routeConfig(11L, "https://route.example", probedAt.plusSeconds(1)));
		when(modelConfigMapper.findByIdForUpdate(11L))
			.thenReturn(routeModelConfig(11L, "https://route.example", probedAt.plusSeconds(1), ModelType.CHAT, false));

		assertThrows(CheckedException.class, () -> service.activate(1L));

		assertEquals("READY", profile.getStatus());
		assertEquals(RouteCapabilityState.STALE.name(), profile.getRouteModelProbeState());
		assertFalse(profile.getRouteModelRuntimeReady());
		assertEquals("ROUTE_MODEL_CAPABILITY_STALE", profile.getRouteModelFailureCode());
		verify(profileMapper, never()).updateWithRevision(any(), anyLong());
		verify(profileMapper, never()).retireActiveExcept(anyString(), anyLong());
	}

	@Test
	void activateLocksRouteModelConfigBeforeRetireAndActiveCas() {
		Instant probedAt = Instant.parse("2026-08-02T00:00:00Z");
		ModelConfigDTO routeConfig = routeConfig(11L, "https://route.example", probedAt);
		DataAgentRouteProfile profile = lexicalModelProfile(routeConfig);
		profile.setStatus("READY");
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(any(), eq(3L))).thenReturn(1);
		when(artifactService.hasValidRuleSources()).thenReturn(true);
		when(modelConfigDataService.getConfigById(11L, ModelType.CHAT, "tenant-1")).thenReturn(routeConfig);
		when(modelConfigMapper.findByIdForUpdate(11L))
			.thenReturn(routeModelConfig(11L, "https://route.example", probedAt, ModelType.CHAT, false));

		var result = service.activate(1L);

		assertEquals("ACTIVE", result.status());
		InOrder inOrder = inOrder(modelConfigMapper, profileMapper);
		inOrder.verify(modelConfigMapper).findByIdForUpdate(11L);
		inOrder.verify(profileMapper).retireActiveExcept("tenant-1", 1L);
		inOrder.verify(profileMapper).updateWithRevision(profile, 3L);
		verify(modelConfigDataService, never()).getConfigById(11L, ModelType.CHAT, "tenant-1");
	}

	@Test
	void recallWithoutAutomaticSelectionCannotActivateWithIncompleteArtifacts() {
		DataAgentRouteProfile profile = rebuildableProfile();
		profile.setStatus("READY");
		profile.setSemanticAutoSelectEnabled(false);
		profile.setBuildStatus("READY");
		profile.setBuildTotal(1);
		profile.setBuildReady(1);
		profile.setBuildFailed(0);
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(artifactService.hasValidRuleSources()).thenReturn(true);
		when(artifactService.hasCompleteArtifacts(profile)).thenReturn(false);

		assertThrows(CheckedException.class, () -> service.activate(1L));

		verify(artifactService).hasCompleteArtifacts(profile);
		verify(profileMapper, never()).retireActiveExcept(anyString(), anyLong());
	}

	@Test
	void recallWithoutAutomaticSelectionActivatesWithCompleteArtifacts() {
		DataAgentRouteProfile profile = rebuildableProfile();
		profile.setStatus("READY");
		profile.setSemanticAutoSelectEnabled(false);
		profile.setBuildStatus("READY");
		profile.setBuildTotal(1);
		profile.setBuildReady(1);
		profile.setBuildFailed(0);
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(any(), eq(3L))).thenReturn(1);
		when(artifactService.hasValidRuleSources()).thenReturn(true);
		when(artifactService.hasCompleteArtifacts(profile)).thenReturn(true);

		var result = service.activate(1L);

		assertEquals("ACTIVE", result.status());
		verify(artifactService).hasCompleteArtifacts(profile);
		verify(profileMapper).retireActiveExcept("tenant-1", 1L);
	}

	@Test
	void lexicalOnlyProfileCanProbeAndActivateWithoutModelOrEmbedding() {
		DataAgentRouteProfile profile = lexicalProfile("DRAFT");
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(any(), anyLong())).thenReturn(1);
		when(artifactService.hasValidRuleSources()).thenReturn(true);

		var probeResult = service.probe(1L);
		var rebuildResult = service.rebuild(1L);
		var activated = service.activate(1L);

		assertEquals(RouteCapabilityState.NOT_PROBED.name(), probeResult.modelCapability().state());
		assertEquals(RouteCapabilityState.NOT_PROBED.name(), probeResult.embeddingCapability().state());
		assertEquals("READY", rebuildResult.status());
		assertEquals("ACTIVE", activated.status());
		assertEquals("SKIPPED", profile.getBuildStatus());
		verify(modelConfigDataService, never()).getRuntimeConfigById(any(), any(), any());
		verify(modelConfigDataService, never()).getConfigById(anyLong(), eq(ModelType.CHAT), any());
		verify(modelConfigMapper, never()).findByIdForUpdate(anyLong());
		verify(modelFactory, never()).createRouteEmbeddingModel(any(), any());
	}

	@Test
	void disabledModelDisambiguationWithResidualConfigDoesNotLookupRouteModelDuringProbe() {
		DataAgentRouteProfile profile = lexicalProfile("DRAFT");
		profile.setRouteModelConfigId(11L);
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(any(), eq(3L))).thenReturn(1);

		var result = service.probe(1L);

		assertEquals("READY", result.status());
		verify(modelConfigMapper, never()).findByIdForUpdate(anyLong());
		verify(modelConfigDataService, never()).getRuntimeConfigById(11L, ModelType.CHAT, "tenant-1");
		verify(modelConfigDataService, never()).getConfigById(11L, ModelType.CHAT, "tenant-1");
	}

	@Test
	void disabledModelDisambiguationWithResidualConfigDoesNotLookupRouteModelDuringRebuild() {
		DataAgentRouteProfile profile = rebuildableProfile();
		profile.setRouteModelConfigId(11L);
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(any(), eq(3L))).thenReturn(1);

		var result = service.rebuild(1L);

		assertEquals("BUILDING", result.status());
		verify(modelConfigDataService).getConfigById(22L, ModelType.EMBEDDING, "tenant-1");
		verify(modelConfigMapper, never()).findByIdForUpdate(anyLong());
		verify(modelConfigDataService, never()).getConfigById(11L, ModelType.CHAT, "tenant-1");
	}

	@Test
	void disabledModelDisambiguationWithResidualConfigDoesNotLookupRouteModelDuringActivate() {
		DataAgentRouteProfile profile = lexicalProfile("READY");
		profile.setRouteModelConfigId(11L);
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(any(), eq(3L))).thenReturn(1);
		when(artifactService.hasValidRuleSources()).thenReturn(true);

		var result = service.activate(1L);

		assertEquals("ACTIVE", result.status());
		verify(modelConfigMapper, never()).findByIdForUpdate(anyLong());
		verify(modelConfigDataService, never()).getConfigById(11L, ModelType.CHAT, "tenant-1");
	}

	@Test
	void validLexicalModelProfileRebuildsAndActivates() {
		ModelConfigDTO routeConfig = routeConfig(11L, "https://route.example",
				Instant.parse("2026-08-02T00:00:00Z"));
		DataAgentRouteProfile profile = lexicalModelProfile(routeConfig);
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(any(), anyLong())).thenReturn(1);
		when(modelConfigDataService.getConfigById(11L, ModelType.CHAT, "tenant-1")).thenReturn(routeConfig);
		when(modelConfigMapper.findByIdForUpdate(11L))
			.thenReturn(routeModelConfig(11L, "https://route.example", routeConfig.getLastModifyTime(), ModelType.CHAT,
					false));
		when(artifactService.hasValidRuleSources()).thenReturn(true);

		var rebuilt = service.rebuild(1L);
		var activated = service.activate(1L);

		assertEquals("READY", rebuilt.status());
		assertEquals("ACTIVE", activated.status());
		assertEquals("SKIPPED", profile.getBuildStatus());
		verify(profileMapper).retireActiveExcept("tenant-1", 1L);
	}

	@Test
	void toPolicyUsesProtocolPersistedByProbe() {
		DataAgentRouteProfile profile = probeableProfile();
		profile.setSemanticRecallEnabled(false);
		profile.setSemanticAutoSelectEnabled(false);
		profile.setEmbeddingModelConfigId(null);
		ModelConfigDTO routeConfig = routeConfig(11L, "https://route.example",
				Instant.parse("2026-08-02T00:00:00Z"));
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(any(), eq(3L))).thenReturn(1);
		when(modelConfigDataService.getRuntimeConfigById(11L, ModelType.CHAT, "tenant-1")).thenReturn(routeConfig);
		when(modelConfigDataService.getConfigById(11L, ModelType.CHAT, "tenant-1")).thenReturn(routeConfig);
		when(modelConfigMapper.findByIdForUpdate(11L))
			.thenReturn(routeModelConfig(11L, "https://route.example", routeConfig.getLastModifyTime(), ModelType.CHAT,
					false));
		when(routeModelAdapter.probe(routeConfig)).thenReturn(new RouteModelProbeResult(RouteCapabilityState.SUPPORTED,
				RouteModelOutputProtocol.STRICT_SCHEMA, 100L, null));

		service.probe(1L);
		var policy = service.toPolicy(profile);

		assertEquals(RouteModelOutputProtocol.STRICT_SCHEMA.name(), profile.getRouteModelProtocol());
		assertEquals(RouteModelOutputProtocol.STRICT_SCHEMA, policy.routeModelProtocol());
		assertTrue(policy.routeModelRuntimeReady());
	}

	@Test
	void dtoAndPolicyExposeRecallIndependentlyFromAutomaticSelection() {
		DataAgentRouteProfile profile = rebuildableProfile();
		profile.setSemanticAutoSelectEnabled(false);

		RouteProfileResp dto = RouteProfileResp.from(profile);
		var policy = service.toPolicy(profile);

		assertTrue(dto.semanticRecallEnabled());
		assertFalse(dto.semanticAutoSelectEnabled());
		assertTrue(policy.semanticRecallEnabled());
		assertFalse(policy.semanticAutoSelectEnabled());
		assertTrue(policy.semanticRuntimeReady());
	}

	@Test
	void draftProfileIsUsableForPreview() {
		DataAgentRouteProfile profile = lexicalProfile("DRAFT");
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);

		DataAgentRouteProfile usable = service.requireUsable(1L, true);

		assertEquals(profile, usable);
	}

	@Test
	void legacyActiveProfileRemainsUsableForRulesWithoutCapabilitySnapshots() {
		DataAgentRouteProfile profile = lexicalProfile("ACTIVE");
		profile.setStrictSchemaStatus("UNSUPPORTED");
		profile.setEmbeddingProbeState(null);
		profile.setRouteModelProbeState(null);
		when(profileMapper.findActive("tenant-1")).thenReturn(profile);

		var active = service.requireActive();

		assertEquals("ACTIVE", active.getStatus());
		verify(modelConfigDataService, never()).getConfigById(any(), any(), any());
	}

	@Test
	void staleModelFingerprintDisablesOnlyModelDisambiguation() {
		DataAgentRouteProfile profile = probeableProfile();
		profile.setSemanticRecallEnabled(false);
		profile.setSemanticAutoSelectEnabled(false);
		profile.setRouteModelProbeState(RouteCapabilityState.SUPPORTED.name());
		profile.setRouteModelProbeVersion(ROUTE_MODEL_PROBE_VERSION);
		profile.setRouteModelProtocol(RouteModelOutputProtocol.JSON_OBJECT.name());
		profile.setRouteModelRuntimeReady(true);
		profile.setRouteModelFingerprint(modelFingerprint.calculate(routeConfig(11L, "https://route.example")));
		when(modelConfigDataService.getConfigById(11L, ModelType.CHAT, "tenant-1"))
			.thenReturn(routeConfig(11L, "https://changed-route.example"));

		var policy = service.toPolicy(profile);

		assertFalse(policy.routeModelRuntimeReady());
		assertEquals(RouteCapabilityState.STALE.name(), profile.getRouteModelProbeState());
		assertFalse(policy.semanticRuntimeReady());
	}

	@Test
	void modelConfigChangePersistsStaleCapabilityBeforeSchedulingReprobe() {
		ModelConfigDTO routeConfig = routeConfig(11L, "https://route.example");
		DataAgentRouteProfile profile = lexicalModelProfile(routeConfig);
		profile.setStatus("ACTIVE");
		when(profileMapper.findRouteModelProfiles(11L)).thenReturn(java.util.List.of(profile));
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(profile, 3L)).thenReturn(1);

		service.onModelConfigChanged(new ModelConfigChangedEvent(this, 11L));

		assertEquals("ACTIVE", profile.getStatus());
		assertEquals(RouteCapabilityState.STALE.name(), profile.getRouteModelProbeState());
		assertFalse(profile.getRouteModelRuntimeReady());
		assertEquals("ROUTE_MODEL_CAPABILITY_STALE", profile.getRouteModelFailureCode());
		assertEquals("ROUTE_MODEL_CAPABILITY_STALE", profile.getLastErrorCode());
		assertEquals(4L, profile.getRevision());
		verify(profileMapper).updateWithRevision(profile, 3L);
		verify(routeRetrievalExecutor).execute(any(Runnable.class));
	}

	@Test
	void modelConfigChangeReprobesLatestConfigurationWithoutChangingActiveStatus() {
		ModelConfigDTO routeConfig = routeConfig(11L, "https://route.example");
		DataAgentRouteProfile profile = lexicalModelProfile(routeConfig);
		profile.setStatus("ACTIVE");
		when(profileMapper.findRouteModelProfiles(11L)).thenReturn(java.util.List.of(profile));
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(profile, 3L)).thenReturn(1);
		when(profileMapper.updateWithRevision(profile, 4L)).thenReturn(1);
		when(modelConfigDataService.getRuntimeConfigById(11L, ModelType.CHAT, "tenant-1")).thenReturn(routeConfig);
		when(routeModelAdapter.probe(routeConfig)).thenReturn(new RouteModelProbeResult(RouteCapabilityState.SUPPORTED,
				RouteModelOutputProtocol.JSON_OBJECT, 100L, null));
		when(modelConfigMapper.findByIdForUpdate(11L))
			.thenReturn(routeModelConfig(11L, "https://route.example", null, ModelType.CHAT, false));
		doAnswer(invocation -> {
			((Runnable) invocation.getArgument(1)).run();
			return null;
		}).when(asyncContextBridge).runWith(any(), any());

		service.onModelConfigChanged(new ModelConfigChangedEvent(this, 11L));

		ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
		verify(routeRetrievalExecutor).execute(taskCaptor.capture());
		taskCaptor.getValue().run();
		assertEquals("ACTIVE", profile.getStatus());
		assertEquals(RouteCapabilityState.SUPPORTED.name(), profile.getRouteModelProbeState());
		assertTrue(profile.getRouteModelRuntimeReady());
		assertEquals(5L, profile.getRevision());
		verify(routeModelAdapter).probe(routeConfig);
		verify(profileMapper).updateWithRevision(profile, 4L);
	}

	@Test
	void runtimeFingerprintMismatchPersistsStaleCapabilityWithCas() {
		ModelConfigDTO original = routeConfig(11L, "https://route.example");
		DataAgentRouteProfile profile = lexicalModelProfile(original);
		when(modelConfigDataService.getConfigById(11L, ModelType.CHAT, "tenant-1"))
			.thenReturn(routeConfig(11L, "https://changed-route.example"));
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(profile, 3L)).thenReturn(1);
		doAnswer(invocation -> {
			((Runnable) invocation.getArgument(1)).run();
			return null;
		}).when(asyncContextBridge).runWith(any(), any());

		var policy = service.toPolicy(profile);

		assertFalse(policy.routeModelRuntimeReady());
		ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
		verify(routeRetrievalExecutor).execute(taskCaptor.capture());
		taskCaptor.getValue().run();
		assertEquals(RouteCapabilityState.STALE.name(), profile.getRouteModelProbeState());
		assertEquals("ROUTE_MODEL_CAPABILITY_STALE", profile.getLastErrorCode());
		assertEquals(4L, profile.getRevision());
		verify(profileMapper).updateWithRevision(profile, 3L);
	}

	@Test
	void embeddingFingerprintIncludesModelConfigRevision() {
		ModelConfigDTO first = embeddingConfig(22L, "https://embedding.example",
				Instant.parse("2026-08-04T00:00:00Z"));
		ModelConfigDTO changed = embeddingConfig(22L, "https://embedding.example",
				Instant.parse("2026-08-04T00:00:01Z"));

		assertNotEquals(embeddingFingerprint.calculate(first), embeddingFingerprint.calculate(changed));
	}

	@Test
	void embeddingModelConfigChangePersistsStaleCapabilityAndInvalidatesArtifacts() {
		DataAgentRouteProfile profile = rebuildableProfile();
		profile.setStatus("ACTIVE");
		profile.setBuildStatus("READY");
		profile.setBuildTotal(2);
		profile.setBuildReady(2);
		when(profileMapper.findEmbeddingModelProfiles(22L)).thenReturn(java.util.List.of(profile));
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(profile, 3L)).thenReturn(1);

		service.onModelConfigChanged(new ModelConfigChangedEvent(this, 22L));

		assertEquals("ACTIVE", profile.getStatus());
		assertEquals(RouteCapabilityState.STALE.name(), profile.getEmbeddingProbeState());
		assertEquals("NOT_BUILT", profile.getBuildStatus());
		assertEquals("ROUTE_EMBEDDING_CAPABILITY_STALE", profile.getEmbeddingFailureCode());
		assertEquals("ROUTE_EMBEDDING_CAPABILITY_STALE", profile.getLastErrorCode());
		assertEquals(4L, profile.getRevision());
		verify(profileMapper).updateWithRevision(profile, 3L);
		verify(artifactService).invalidateEmbeddingArtifacts(1L);
		verify(routeRetrievalExecutor).execute(any(Runnable.class));
	}

	@Test
	void runtimeEmbeddingFingerprintMismatchPersistsStaleCapabilityWithCas() {
		DataAgentRouteProfile profile = rebuildableProfile();
		profile.setStatus("ACTIVE");
		when(modelConfigDataService.getConfigById(22L, ModelType.EMBEDDING, "tenant-1"))
			.thenReturn(embeddingConfig(22L, "https://changed-embedding.example"));
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(profile, 3L)).thenReturn(1);
		doAnswer(invocation -> {
			((Runnable) invocation.getArgument(1)).run();
			return null;
		}).when(asyncContextBridge).runWith(any(), any());

		var policy = service.toPolicy(profile);

		assertFalse(policy.semanticRuntimeReady());
		ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
		verify(routeRetrievalExecutor).execute(taskCaptor.capture());
		taskCaptor.getValue().run();
		assertEquals(RouteCapabilityState.STALE.name(), profile.getEmbeddingProbeState());
		assertEquals("ROUTE_EMBEDDING_CAPABILITY_STALE", profile.getLastErrorCode());
		assertEquals(4L, profile.getRevision());
		verify(profileMapper).updateWithRevision(profile, 3L);
		verify(artifactService).invalidateEmbeddingArtifacts(1L);
	}

	@Test
	void runtimeEmbeddingFingerprintMismatchFallsBackToSynchronousPersistenceWhenExecutorRejects() {
		DataAgentRouteProfile profile = rebuildableProfile();
		profile.setStatus("ACTIVE");
		when(modelConfigDataService.getConfigById(22L, ModelType.EMBEDDING, "tenant-1"))
			.thenReturn(embeddingConfig(22L, "https://changed-embedding.example"));
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(profile, 3L)).thenReturn(1);
		doThrow(new RejectedExecutionException("queue full")).when(routeRetrievalExecutor).execute(any(Runnable.class));

		var policy = service.toPolicy(profile);

		assertFalse(policy.semanticRuntimeReady());
		assertEquals(RouteCapabilityState.STALE.name(), profile.getEmbeddingProbeState());
		assertEquals("ROUTE_EMBEDDING_CAPABILITY_STALE", profile.getLastErrorCode());
		assertEquals(4L, profile.getRevision());
		verify(profileMapper).updateWithRevision(profile, 3L);
		verify(artifactService).invalidateEmbeddingArtifacts(1L);
	}

	@Test
	void failedAutomaticEmbeddingReprobeRemainsStaleForRecovery() {
		DataAgentRouteProfile profile = rebuildableProfile();
		profile.setStatus("ACTIVE");
		when(profileMapper.findEmbeddingModelProfiles(22L)).thenReturn(java.util.List.of(profile));
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(profile, 3L)).thenReturn(1);
		when(profileMapper.updateWithRevision(profile, 4L)).thenReturn(1);
		when(modelConfigDataService.getRuntimeConfigById(22L, ModelType.EMBEDDING, "tenant-1")).thenReturn(embeddingConfig(22L));
		when(modelFactory.createRouteEmbeddingModel(any(), any())).thenThrow(new IllegalStateException("unavailable"));
		doAnswer(invocation -> {
			((Runnable) invocation.getArgument(1)).run();
			return null;
		}).when(asyncContextBridge).runWith(any(), any());

		service.onModelConfigChanged(new ModelConfigChangedEvent(this, 22L));

		ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
		verify(routeRetrievalExecutor).execute(taskCaptor.capture());
		taskCaptor.getValue().run();
		assertEquals(RouteCapabilityState.STALE.name(), profile.getEmbeddingProbeState());
		assertEquals("ROUTE_EMBEDDING_CAPABILITY_STALE", profile.getEmbeddingFailureCode());
		assertEquals("ROUTE_EMBEDDING_CAPABILITY_STALE", profile.getLastErrorCode());
		assertEquals(5L, profile.getRevision());
		verify(profileMapper).updateWithRevision(profile, 4L);
	}

	@Test
	void activeProfileReprobePreservesActiveStatus() {
		DataAgentRouteProfile profile = rebuildableProfile();
		profile.setStatus("ACTIVE");
		profile.setBuildStatus("READY");
		profile.setBuildTotal(2);
		profile.setBuildReady(2);
		ModelConfigDTO embeddingConfig = embeddingConfig(22L);
		EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(profile, 3L)).thenReturn(1);
		when(modelConfigDataService.getRuntimeConfigById(22L, ModelType.EMBEDDING, "tenant-1")).thenReturn(embeddingConfig);
		when(modelConfigMapper.findByIdForUpdate(22L)).thenReturn(embeddingModelConfig(22L, "https://embedding.example",
				null, false));
		when(modelFactory.createRouteEmbeddingModel(embeddingConfig, java.time.Duration.ofSeconds(30)))
			.thenReturn(embeddingModel);
		when(embeddingModel.embed("hybrid route profile probe")).thenReturn(new float[] { 0.1F, 0.2F });

		var result = service.reprobe(1L);

		assertEquals("ACTIVE", result.status());
		assertEquals(RouteCapabilityState.SUPPORTED.name(), result.embeddingCapability().state());
		assertEquals(4L, result.revision());
		verify(artifactService, never()).invalidateEmbeddingArtifacts(anyLong());
		verify(routeRetrievalExecutor, never()).execute(any(Runnable.class));
	}

	@Test
	void oldEmbeddingProbeSnapshotIsNotRuntimeReady() {
		DataAgentRouteProfile profile = probeableProfile();
		ModelConfigDTO embeddingConfig = embeddingConfig(22L);
		profile.setEmbeddingProbeState(RouteCapabilityState.SUPPORTED.name());
		profile.setEmbeddingProbeVersion("route-capability-v1");
		profile.setEmbeddingFingerprint(embeddingFingerprint.calculate(embeddingConfig));
		profile.setEmbeddingDimension(2);

		var policy = service.toPolicy(profile);

		assertFalse(policy.semanticRuntimeReady());
		assertEquals(RouteCapabilityState.STALE.name(), profile.getEmbeddingProbeState());
		assertEquals("ROUTE_EMBEDDING_CAPABILITY_STALE", profile.getEmbeddingFailureCode());
	}

	@Test
	void oldRouteModelProbeSnapshotIsNotRuntimeReady() {
		DataAgentRouteProfile profile = probeableProfile();
		profile.setSemanticRecallEnabled(false);
		profile.setSemanticAutoSelectEnabled(false);
		profile.setRouteModelProbeState(RouteCapabilityState.SUPPORTED.name());
		profile.setRouteModelProbeVersion("route-capability-v1");
		profile.setRouteModelProtocol(RouteModelOutputProtocol.JSON_OBJECT.name());
		profile.setRouteModelRuntimeReady(true);
		ModelConfigDTO routeConfig = routeConfig(11L, "https://route.example");
		profile.setRouteModelFingerprint(modelFingerprint.calculate(routeConfig));
		when(modelConfigDataService.getConfigById(11L, ModelType.CHAT, "tenant-1")).thenReturn(routeConfig);

		var policy = service.toPolicy(profile);

		assertFalse(policy.routeModelRuntimeReady());
		assertEquals(RouteCapabilityState.STALE.name(), profile.getRouteModelProbeState());
		assertEquals("ROUTE_MODEL_CAPABILITY_STALE", profile.getRouteModelFailureCode());
	}

	private DataAgentRouteProfile probeableProfile() {
		return DataAgentRouteProfile.builder()
			.id(1L)
			.tenantId("tenant-1")
			.profileName("route-next")
			.status("DRAFT")
			.routeModelConfigId(11L)
			.embeddingModelConfigId(22L)
			.routeModelProbeState(RouteCapabilityState.NOT_PROBED.name())
			.routeModelProtocol(RouteModelOutputProtocol.NONE.name())
			.routeModelRuntimeReady(false)
			.embeddingProbeState(RouteCapabilityState.NOT_PROBED.name())
			.lexicalAutoSelectEnabled(true)
			.semanticRecallEnabled(true)
			.semanticAutoSelectEnabled(true)
			.modelDisambiguationEnabled(true)
			.lexicalMinScore(70)
			.lexicalMinGap(15)
			.vectorRecallThreshold(new BigDecimal("0.50"))
			.vectorAutoSelectThreshold(new BigDecimal("0.85"))
			.vectorMinGap(new BigDecimal("0.10"))
			.modelConfidenceThreshold(new BigDecimal("0.80"))
			.buildStatus("NOT_BUILT")
			.buildTotal(0)
			.buildReady(0)
			.buildFailed(0)
			.revision(3L)
			.deleted(false)
			.build();
	}

	private DataAgentRouteProfile lexicalProfile(String status) {
		DataAgentRouteProfile profile = probeableProfile();
		profile.setStatus(status);
		profile.setRouteModelConfigId(null);
		profile.setEmbeddingModelConfigId(null);
		profile.setSemanticRecallEnabled(false);
		profile.setSemanticAutoSelectEnabled(false);
		profile.setModelDisambiguationEnabled(false);
		profile.setBuildStatus("SKIPPED");
		return profile;
	}

	private DataAgentRouteProfile lexicalModelProfile(ModelConfigDTO routeConfig) {
		DataAgentRouteProfile profile = probeableProfile();
		profile.setSemanticRecallEnabled(false);
		profile.setSemanticAutoSelectEnabled(false);
		profile.setEmbeddingModelConfigId(null);
		profile.setBuildStatus("SKIPPED");
		return routeModelReady(profile, routeConfig, RouteModelOutputProtocol.JSON_OBJECT);
	}

	private DataAgentRouteProfile routeModelReady(DataAgentRouteProfile profile, ModelConfigDTO routeConfig,
			RouteModelOutputProtocol protocol) {
		profile.setModelDisambiguationEnabled(true);
		profile.setRouteModelConfigId(routeConfig.getId());
		profile.setRouteModelProbeState(RouteCapabilityState.SUPPORTED.name());
		profile.setRouteModelProbeVersion(ROUTE_MODEL_PROBE_VERSION);
		profile.setRouteModelProtocol(protocol.name());
		profile.setRouteModelRuntimeReady(true);
		profile.setRouteModelFingerprint(modelFingerprint.calculate(routeConfig));
		profile.setRouteModelFailureCode(null);
		return profile;
	}

	private void assertLexicalSnapshotRemainsDraft(DataAgentRouteProfile profile) {
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(any(), eq(3L))).thenReturn(1);

		assertThrows(CheckedException.class, () -> service.rebuild(1L));

		assertEquals("DRAFT", profile.getStatus());
		assertEquals("SKIPPED", profile.getBuildStatus());
		verify(profileMapper).updateWithRevision(profile, 3L);
		verify(modelConfigDataService, never()).getConfigById(anyLong(), eq(ModelType.CHAT), any());
		verify(routeRetrievalExecutor, never()).execute(any(Runnable.class));
	}

	private void assertLockedConfigBlocksLexicalReadiness(ModelConfig lockedConfig) {
		Instant probedAt = Instant.parse("2026-08-02T00:00:00Z");
		ModelConfigDTO probedConfig = routeConfig(11L, "https://route.example", probedAt);
		DataAgentRouteProfile profile = lexicalModelProfile(probedConfig);
		when(profileMapper.findAvailableById(1L)).thenReturn(profile);
		when(profileMapper.updateWithRevision(any(), eq(3L))).thenReturn(1);
		when(modelConfigDataService.getConfigById(11L, ModelType.CHAT, "tenant-1")).thenReturn(probedConfig);
		when(modelConfigMapper.findByIdForUpdate(11L)).thenReturn(lockedConfig);

		assertThrows(CheckedException.class, () -> service.rebuild(1L));

		assertEquals("DRAFT", profile.getStatus());
		assertEquals("SKIPPED", profile.getBuildStatus());
		assertEquals("ROUTE_MODEL_CAPABILITY_STALE", profile.getLastErrorCode());
		assertEquals(RouteCapabilityState.STALE.name(), profile.getRouteModelProbeState());
		assertFalse(profile.getRouteModelRuntimeReady());
		verify(profileMapper).updateWithRevision(profile, 3L);
		verify(modelConfigMapper).findByIdForUpdate(11L);
		verify(modelConfigDataService, never()).getConfigById(11L, ModelType.CHAT, "tenant-1");
		verify(routeRetrievalExecutor, never()).execute(any(Runnable.class));
	}

	private DataAgentRouteProfile rebuildableProfile() {
		DataAgentRouteProfile profile = probeableProfile();
		profile.setModelDisambiguationEnabled(false);
		ModelConfigDTO embeddingConfig = embeddingConfig(22L);
		profile.setEmbeddingProbeState(RouteCapabilityState.SUPPORTED.name());
		profile.setEmbeddingProbeVersion("route-capability-v2");
		profile.setEmbeddingFingerprint(embeddingFingerprint.calculate(embeddingConfig));
		profile.setEmbeddingDimension(2);
		when(modelConfigDataService.getConfigById(22L, ModelType.EMBEDDING, "tenant-1")).thenReturn(embeddingConfig);
		return profile;
	}

	/**
	 * 命中 {@code isRouteModelCapabilityStale}：STALE + runtimeReady=false + 失败码为能力过期。
	 */
	private DataAgentRouteProfile staleRouteModelProfile() {
		DataAgentRouteProfile profile = lexicalModelProfile(routeConfig(11L, "https://route.example"));
		profile.setRouteModelProbeState(RouteCapabilityState.STALE.name());
		profile.setRouteModelRuntimeReady(false);
		profile.setRouteModelFailureCode("ROUTE_MODEL_CAPABILITY_STALE");
		profile.setLastErrorCode("ROUTE_MODEL_CAPABILITY_STALE");
		return profile;
	}

	private DataAgentRouteProfile staleBuildingProfile() {
		DataAgentRouteProfile profile = rebuildableProfile();
		profile.setStatus("BUILDING");
		profile.setBuildStatus("RUNNING");
		profile.setBuildTotal(3);
		profile.setRevision(8L);
		profile.setLastModifyTime(Instant.now().minus(Duration.ofMinutes(11)));
		return profile;
	}

	private ModelConfigDTO routeConfig(Long id, String baseUrl) {
		return routeConfig(id, baseUrl, null);
	}

	private ModelConfigDTO routeConfig(Long id, String baseUrl, Instant lastModifyTime) {
		return ModelConfigDTO.builder()
			.id(id)
			.provider("custom")
			.baseUrl(baseUrl)
			.modelName("route-v2")
			.modelType(ModelType.CHAT.getCode())
			.lastModifyTime(lastModifyTime)
			.build();
	}

	private ModelConfigDTO embeddingConfig(Long id) {
		return embeddingConfig(id, "https://embedding.example");
	}

	private ModelConfigDTO embeddingConfig(Long id, String baseUrl) {
		return embeddingConfig(id, baseUrl, null);
	}

	private ModelConfigDTO embeddingConfig(Long id, String baseUrl, Instant lastModifyTime) {
		return ModelConfigDTO.builder()
			.id(id)
			.provider("custom")
			.baseUrl(baseUrl)
			.modelName("embedding-v2")
			.embeddingsPath("/v1/embeddings")
			.modelType(ModelType.EMBEDDING.getCode())
			.lastModifyTime(lastModifyTime)
			.build();
	}

	private ModelConfig embeddingModelConfig(Long id, String baseUrl, Instant lastModifyTime, boolean deleted) {
		return ModelConfig.builder()
			.id(id)
			.provider("custom")
			.baseUrl(baseUrl)
			.modelName("embedding-v2")
			.embeddingsPath("/v1/embeddings")
			.modelType(ModelType.EMBEDDING)
			.lastModifyTime(lastModifyTime)
			.deleted(deleted)
			.build();
	}

	private ModelConfig routeModelConfig(Long id, String baseUrl, Instant lastModifyTime, ModelType modelType,
			boolean deleted) {
		return ModelConfig.builder()
			.id(id)
			.provider("custom")
			.baseUrl(baseUrl)
			.modelName("route-v2")
			.temperature(0D)
			.maxTokens(2000L)
			.contextWindowTokens(32768L)
			.modelType(modelType)
			.proxyEnabled(false)
			.lastModifyTime(lastModifyTime)
			.deleted(deleted)
			.build();
	}
}
