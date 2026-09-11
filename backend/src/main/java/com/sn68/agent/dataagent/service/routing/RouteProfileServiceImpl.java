/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.routing;

import com.sn68.agent.dataagent.context.DataAgentAsyncContextBridge;
import com.sn68.agent.dataagent.converter.ModelConfigConverter;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.dto.routing.RouteProfileBuildStatusResp;
import com.sn68.agent.dataagent.dto.routing.RouteProfileConfigurationDTO;
import com.sn68.agent.dataagent.dto.routing.RouteProfileCreateReq;
import com.sn68.agent.dataagent.dto.routing.RouteProfileCurrentResp;
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
import com.sn68.agent.dataagent.routing.model.RoutePolicy;
import com.sn68.agent.dataagent.service.aimodelconfig.DynamicModelFactory;
import com.sn68.agent.dataagent.service.aimodelconfig.ModelConfigDataService;
import com.sn68.agent.dataagent.service.permission.PlatformScopePermissionService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.redis.plus.exception.RedisLockException;
import com.sn68.agent.framework.redis.plus.lock.RedisLockHelper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * 路由画像服务：管理 Agent 路由画像的创建/修改/发布/回滚全生命周期，
 * 并负责路由模型与 Embedding 能力探测、指纹失效检测、语义物料重建的异步调度。
 */
@Slf4j
@Service
public class RouteProfileServiceImpl implements RouteProfileService {

	private static final boolean DEFAULT_LEXICAL_AUTO_SELECT_ENABLED = true;

	private static final boolean DEFAULT_SEMANTIC_RECALL_ENABLED = true;

	private static final boolean DEFAULT_SEMANTIC_AUTO_SELECT_ENABLED = false;

	private static final boolean DEFAULT_MODEL_DISAMBIGUATION_ENABLED = false;

	private static final int DEFAULT_LEXICAL_MIN_SCORE = 70;

	private static final int DEFAULT_LEXICAL_MIN_GAP = 15;

	private static final BigDecimal DEFAULT_VECTOR_RECALL_THRESHOLD = new BigDecimal("0.50");

	private static final BigDecimal DEFAULT_VECTOR_AUTO_SELECT_THRESHOLD = new BigDecimal("0.85");

	private static final BigDecimal DEFAULT_VECTOR_MIN_GAP = new BigDecimal("0.10");

	private static final BigDecimal DEFAULT_MODEL_CONFIDENCE_THRESHOLD = new BigDecimal("0.80");

	private static final int PROFILE_NAME_MAX_LENGTH = 128;

	private static final int LEXICAL_THRESHOLD_MIN = 0;

	private static final int LEXICAL_THRESHOLD_MAX = 1000;

	private static final Duration ROLLBACK_WINDOW = Duration.ofDays(7);

	private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(10);

	private static final long BUILD_RECOVERY_DELAY_MS = 60_000L;

	private static final String ROUTE_MODEL_PROBE_VERSION = "route-model-capability-v3";

	private static final String EMBEDDING_PROBE_VERSION = "route-capability-v2";

	private static final String PROBE_RESULT_METRIC = "data.agent.routing.probe.results";

	private static final String PROBE_BUDGET_EXCEEDED_METRIC = "data.agent.routing.probe.budget.exceeded";

	private static final String ROUTE_MODEL_CAPABILITY_STALE = "ROUTE_MODEL_CAPABILITY_STALE";

	private static final String ROUTE_EMBEDDING_CAPABILITY_STALE = "ROUTE_EMBEDDING_CAPABILITY_STALE";

	private static final String ROUTE_EMBEDDING_ARTIFACT_REBUILD_PENDING = "ROUTE_EMBEDDING_ARTIFACT_REBUILD_PENDING";

	private static final String CAPABILITY_RECOVERY_LOCK = "dataagent:routing:capability-recovery";

	private static final String ROUTE_MODEL_REPROBE_LOCK_PREFIX = "dataagent:routing:route-model-reprobe:";

	private static final String EMBEDDING_REPROBE_LOCK_PREFIX = "dataagent:routing:embedding-reprobe:";

	private static final String ARTIFACT_REBUILD_LOCK_PREFIX = "dataagent:routing:artifact-rebuild:";

	private static final String STATUS_DRAFT = "DRAFT";

	private static final String STATUS_ACTIVE = "ACTIVE";

	private static final String STATUS_RETIRED = "RETIRED";

	private static final String STATUS_READY = "READY";

	private static final String STATUS_BUILDING = "BUILDING";

	private static final String STATUS_FAILED = "FAILED";

	private static final String BUILD_STATUS_NOT_BUILT = "NOT_BUILT";

	private static final String BUILD_STATUS_SKIPPED = "SKIPPED";

	private static final String REVISION_STALE_MESSAGE = "Route Profile revision is stale";

	private final DataAgentRouteProfileMapper profileMapper;

	private final ModelConfigDataService modelConfigDataService;

	private final ModelConfigMapper modelConfigMapper;

	private final DynamicModelFactory modelFactory;

	private final RouteModelAdapter routeModelAdapter;

	private final RouteModelFingerprint routeModelFingerprint;

	private final RouteArtifactService artifactService;

	private final ExecutorService routeRetrievalExecutor;

	private final DataAgentAsyncContextBridge asyncContextBridge;

	private final RouteEmbeddingFingerprint embeddingFingerprint;

	private final DataAgentProperties.Routing routing;

	private final MeterRegistry meterRegistry;

	private final TransactionTemplate transactionTemplate;

	private final RedisLockHelper redisLockHelper;

	private final PlatformScopePermissionService platformScopePermissionService;

	/**
	 * 副本内的在途去重：键为 {@code profileId:revision}，任务提交时占位、任务结束时释放。
	 *
	 * <p>生产是多副本部署，这几个 Set 只在单个 JVM 内有效，跨副本的互斥由 {@code runExclusively}
	 * 的 Redis 锁负责（锁键与这里的 taskKey 同构）。两层都要保留：Redis 锁在 worker 线程内加解，
	 * 拦不住同一副本重复提交任务，这些 Set 才能让重复提交在进线程池之前就被挡掉。
	 */
	private final Set<String> scheduledRouteModelReprobes = ConcurrentHashMap.newKeySet();

	private final Set<String> scheduledEmbeddingReprobes = ConcurrentHashMap.newKeySet();

	private final Set<String> scheduledStaleCapabilityPersists = ConcurrentHashMap.newKeySet();

	private final Set<String> scheduledEmbeddingCapabilityPersists = ConcurrentHashMap.newKeySet();

	private final Set<String> scheduledArtifactRebuilds = ConcurrentHashMap.newKeySet();

	public RouteProfileServiceImpl(DataAgentRouteProfileMapper profileMapper,
			ModelConfigDataService modelConfigDataService, ModelConfigMapper modelConfigMapper,
			DynamicModelFactory modelFactory,
			RouteModelAdapter routeModelAdapter, RouteModelFingerprint routeModelFingerprint,
			RouteArtifactService artifactService,
			@Qualifier("routeRetrievalExecutor") ExecutorService routeRetrievalExecutor,
			DataAgentAsyncContextBridge asyncContextBridge, RouteEmbeddingFingerprint embeddingFingerprint,
			DataAgentProperties properties, MeterRegistry meterRegistry, TransactionTemplate transactionTemplate,
			RedisLockHelper redisLockHelper, PlatformScopePermissionService platformScopePermissionService) {
		this.profileMapper = profileMapper;
		this.modelConfigDataService = modelConfigDataService;
		this.modelConfigMapper = modelConfigMapper;
		this.modelFactory = modelFactory;
		this.routeModelAdapter = routeModelAdapter;
		this.routeModelFingerprint = routeModelFingerprint;
		this.artifactService = artifactService;
		this.routeRetrievalExecutor = routeRetrievalExecutor;
		this.asyncContextBridge = asyncContextBridge;
		this.embeddingFingerprint = embeddingFingerprint;
		this.routing = properties.getRuntime().getRouting();
		this.meterRegistry = meterRegistry;
		this.transactionTemplate = transactionTemplate;
		this.redisLockHelper = redisLockHelper;
		this.platformScopePermissionService = platformScopePermissionService;
	}

	/**
	 * 集群内互斥地跑一次任务：抢不到锁说明另一个副本正在做同一份工作，直接跳过，不等待也不重排。
	 *
	 * <p>{@code waitTime = 0} 保证非阻塞；租期交给 Redisson 看门狗自动续约（框架的四参重载即
	 * {@code leaseTime = -1}），因此不必猜一轮探测要跑多久，而副本进程消失后锁会在续约停止后自行过期。
	 *
	 * <p><b>必须在真正执行任务的那个线程里调用</b>：Redisson 的 {@code RLock} 归属线程，跨线程释放会被
	 * {@code isHeldByCurrentThread()} 静默跳过，锁会一直挂到租约到期。
	 */
	private void runExclusively(String lockKey, Runnable task) {
		try {
			redisLockHelper.execute(lockKey, 0L, TimeUnit.SECONDS, () -> {
				task.run();
				return null;
			});
		}
		catch (RedisLockException ex) {
			log.debug("Route capability task skipped, another replica holds the lock, lockKey={}", lockKey);
		}
	}

	@Override
	public RouteProfileCurrentResp current() {
		String tenantId = requireCurrentTenantId();
		return RouteProfileCurrentResp.from(capabilityView(profileMapper.findActive(tenantId)),
				capabilityView(profileMapper.findWorking(tenantId)),
				capabilityView(profileMapper.findLatestRetired(tenantId, Instant.now().minus(ROLLBACK_WINDOW))),
				RouteProfileConfigurationDTO.from(routing, configurationDefaults(), configurationConstraints()));
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public RouteProfileResp create(RouteProfileCreateReq request) {
		ProfileValues values = values(request);
		validate(values);
		Instant now = Instant.now();
		DataAgentRouteProfile profile = DataAgentRouteProfile.builder()
			.tenantId(requireCurrentTenantId())
			.profileName(values.profileName())
			.status(STATUS_DRAFT)
			.routeModelConfigId(values.routeModelConfigId())
			.routeModelProbeState(RouteCapabilityState.NOT_PROBED.name())
			.routeModelProtocol(RouteModelOutputProtocol.NONE.name())
			.routeModelRuntimeReady(false)
			.embeddingModelConfigId(values.embeddingModelConfigId())
			.embeddingProbeState(RouteCapabilityState.NOT_PROBED.name())
			.lexicalAutoSelectEnabled(values.lexicalAutoSelectEnabled())
			.semanticRecallEnabled(values.semanticRecallEnabled())
			.semanticAutoSelectEnabled(values.semanticAutoSelectEnabled())
			.modelDisambiguationEnabled(values.modelDisambiguationEnabled())
			.lexicalMinScore(values.lexicalMinScore())
			.lexicalMinGap(values.lexicalMinGap())
			.vectorRecallThreshold(values.vectorRecallThreshold())
			.vectorAutoSelectThreshold(values.vectorAutoSelectThreshold())
			.vectorMinGap(values.vectorMinGap())
			.modelConfidenceThreshold(values.modelConfidenceThreshold())
			.strictSchemaStatus("UNKNOWN")
			.buildStatus(values.semanticRecallEnabled() ? BUILD_STATUS_NOT_BUILT : BUILD_STATUS_SKIPPED)
			.buildTotal(0)
			.buildReady(0)
			.buildFailed(0)
			.revision(0L)
			.createTime(now)
			.lastModifyTime(now)
			.deleted(false)
			.build();
		profileMapper.insert(profile);
		return RouteProfileResp.from(profileMapper.selectById(profile.getId()));
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public RouteProfileResp modify(Long id, RouteProfileModifyReq request) {
		DataAgentRouteProfile profile = requireEditable(id);
		if (request == null || request.revision() == null || !request.revision().equals(profile.getRevision())) {
			throw CheckedException.badRequest(REVISION_STALE_MESSAGE);
		}
		ProfileValues values = values(request, profile);
		validate(values);
		apply(profile, values);
		profile.setStatus(STATUS_DRAFT);
		resetRouteModelCapability(profile);
		resetEmbeddingCapability(profile);
		resetBuildStatus(profile);
		profile.setLastErrorCode(null);
		update(profile, request.revision());
		return RouteProfileResp.from(requireProfile(id));
	}

	@Override
	public RouteProfileResp probe(Long id) {
		DataAgentRouteProfile probedProfile = requireEditable(id);
		long revision = probedProfile.getRevision();
		probeRouteModel(probedProfile);
		probeEmbedding(probedProfile);
		probedProfile.setProbeCheckedAt(Instant.now());
		DataAgentRouteProfile finalizedProfile = transactionTemplate
			.execute(ignored -> finalizeProbe(id, revision, probedProfile));
		recordProbeResults(finalizedProfile);
		return RouteProfileResp.from(requireProfile(id));
	}

	@Override
	public RouteProfileResp reprobe(Long id) {
		DataAgentRouteProfile probedProfile = requireProfile(id);
		if (!STATUS_ACTIVE.equals(probedProfile.getStatus())) {
			return probe(id);
		}
		long revision = probedProfile.getRevision();
		String previousEmbeddingFingerprint = probedProfile.getEmbeddingFingerprint();
		boolean embeddingWasStale = isEmbeddingCapabilityStale(probedProfile);
		probeRouteModel(probedProfile);
		probeEmbedding(probedProfile);
		probedProfile.setProbeCheckedAt(Instant.now());
		ActiveReprobeResult result = transactionTemplate.execute(
				ignored -> finalizeActiveReprobe(id, revision, probedProfile, previousEmbeddingFingerprint,
						embeddingWasStale));
		if (result == null) {
			throw CheckedException.badRequest(REVISION_STALE_MESSAGE);
		}
		recordProbeResults(result.profile());
		if (result.invalidateArtifacts()) {
			invalidateEmbeddingArtifacts(result.profile().getId());
		}
		if (result.rebuildArtifacts()) {
			scheduleArtifactRebuild(result.profile().getId(), result.profile().getRevision());
		}
		return RouteProfileResp.from(requireProfile(id));
	}

	@Override
	public RouteProfileBuildStatusResp rebuild(Long id) {
		RebuildPreparation preparation = transactionTemplate.execute(ignored -> prepareRebuild(id));
		if (preparation.failureMessage() != null) {
			throw CheckedException.badRequest(preparation.failureMessage());
		}
		if (!preparation.startBuild()) {
			return buildStatus(id);
		}
		DataAgentAsyncContextBridge.Snapshot asyncContext = asyncContextBridge.capture();
		try {
			routeRetrievalExecutor.execute(
					() -> asyncContextBridge.runWith(asyncContext,
							() -> artifactService.rebuildProfile(id, preparation.buildRevision())));
		}
		catch (RejectedExecutionException ex) {
			log.warn("Route profile rebuild rejected by the executor, marking the build failed. profileId={}, revision={}",
					id, preparation.buildRevision(), ex);
			markBuildFailed(id, preparation.buildRevision(), "ROUTE_RETRIEVAL_EXECUTOR_REJECTED");
		}
		return buildStatus(id);
	}

	@Override
	public RouteProfileBuildStatusResp buildStatus(Long id) {
		return RouteProfileBuildStatusResp.from(reconcileStaleBuild(requireProfile(id)));
	}

	@Scheduled(fixedDelay = BUILD_RECOVERY_DELAY_MS)
	public void recoverStaleBuilds() {
		Instant cutoff = Instant.now().minus(BUILD_TIMEOUT);
		for (DataAgentRouteProfile profile : profileMapper.findStaleBuilding(cutoff)) {
			reconcileStaleBuild(profile);
		}
	}

	/**
	 * 每 60 秒扫一轮需要补救的 Profile。多副本下整轮扫描只让一个副本做，抢不到锁就跳过本轮
	 * ——下一个 60 秒还会再来，不需要等待。
	 *
	 * <p>注意这层锁只挡住「同时扫描」：扫描本身只派发异步任务、毫秒级就返回并释放锁，各副本的调度
	 * 时钟又是错开的，所以真正防住重复探测的是 {@code scheduleXxx} 里按 {@code profileId:revision}
	 * 加的那把细粒度锁。
	 */
	@Scheduled(fixedDelay = BUILD_RECOVERY_DELAY_MS)
	public void recoverStaleCapabilities() {
		runExclusively(CAPABILITY_RECOVERY_LOCK, this::scanStaleCapabilities);
	}

	private void scanStaleCapabilities() {
		for (DataAgentRouteProfile profile : profileMapper.findStaleRouteModelCapabilities()) {
			if (profile != null) {
				scheduleRouteModelReprobe(profile.getId(), profile.getRevision());
			}
		}
		for (DataAgentRouteProfile profile : profileMapper.findStaleEmbeddingCapabilities()) {
			if (profile != null) {
				scheduleEmbeddingReprobe(profile.getId(), profile.getRevision());
			}
		}
		for (DataAgentRouteProfile profile : profileMapper.findPendingEmbeddingArtifactRebuilds()) {
			if (profile != null) {
				scheduleArtifactRebuild(profile.getId(), profile.getRevision());
			}
		}
		for (DataAgentRouteProfile profile : profileMapper.findRejectedArtifactBuilds()) {
			if (profile != null) {
				scheduleArtifactRebuild(profile.getId(), profile.getRevision());
			}
		}
	}

	@EventListener
	public void onModelConfigChanged(ModelConfigChangedEvent event) {
		if (event == null || event.getModelConfigId() == null) {
			return;
		}
		for (DataAgentRouteProfile profile : profileMapper.findRouteModelProfiles(event.getModelConfigId())) {
			if (profile == null || profile.getId() == null) {
				continue;
			}
			try {
				StaleCapabilityUpdate update = persistRouteModelCapabilityStale(profile.getId(), profile.getRevision(),
						event.getModelConfigId(), false);
				if (update.reprobe()) {
					scheduleRouteModelReprobe(update.profileId(), update.revision());
				}
			}
			catch (RuntimeException ex) {
				log.error("Route model capability invalidation failed, profileId={}, modelConfigId={}", profile.getId(),
						event.getModelConfigId(), ex);
			}
		}
		for (DataAgentRouteProfile profile : profileMapper.findEmbeddingModelProfiles(event.getModelConfigId())) {
			if (profile == null || profile.getId() == null) {
				continue;
			}
			try {
				StaleCapabilityUpdate update = persistEmbeddingCapabilityStale(profile.getId(), profile.getRevision(),
						event.getModelConfigId(), false);
				if (update.reprobe()) {
					invalidateEmbeddingArtifacts(update.profileId());
					scheduleEmbeddingReprobe(update.profileId(), update.revision());
				}
			}
			catch (RuntimeException ex) {
				log.error("Route embedding capability invalidation failed, profileId={}, modelConfigId={}", profile.getId(),
						event.getModelConfigId(), ex);
			}
		}
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public RouteProfileResp activate(Long id) {
		DataAgentRouteProfile profile = requireProfile(id);
		boolean rollback = STATUS_RETIRED.equals(profile.getStatus());
		if (rollback && (profile.getLastModifyTime() == null
				|| profile.getLastModifyTime().isBefore(Instant.now().minus(ROLLBACK_WINDOW)))) {
			throw CheckedException.badRequest("Route Profile is outside the rollback window");
		}
		if (!(STATUS_READY.equals(profile.getStatus()) || rollback)) {
			throw CheckedException.badRequest("Route Profile is not ready to activate");
		}
		if (!artifactService.hasValidRuleSources()) {
			throw CheckedException.badRequest("Route rules are not valid for activation");
		}
		validateActivationCapabilities(profile);
		profileMapper.retireActiveExcept(profile.getTenantId(), id);
		long revision = profile.getRevision();
		profile.setStatus(STATUS_ACTIVE);
		profile.setLastErrorCode(null);
		update(profile, revision);
		return RouteProfileResp.from(requireProfile(id));
	}

	@Override
	public DataAgentRouteProfile requireActive() {
		DataAgentRouteProfile profile = profileMapper.findActive(requireCurrentTenantId());
		if (profile == null) {
			throw new IllegalStateException("ROUTE_PROFILE_UNAVAILABLE");
		}
		validateRuntimeProfile(profile, false);
		return profile;
	}

	@Override
	public DataAgentRouteProfile requireUsable(Long profileId, boolean allowDraft) {
		DataAgentRouteProfile profile = profileId == null ? requireActive() : requireProfile(profileId);
		boolean usable = STATUS_ACTIVE.equals(profile.getStatus())
				|| (allowDraft && Set.of(STATUS_DRAFT, STATUS_READY).contains(profile.getStatus()));
		if (!usable) {
			throw CheckedException.badRequest("Route Profile is not usable for preview");
		}
		validateRuntimeProfile(profile, allowDraft);
		return profile;
	}

	@Override
	public RoutePolicy toPolicy(DataAgentRouteProfile profile) {
		boolean semanticRuntimeReady = embeddingCapabilityReady(profile);
		boolean routeModelRuntimeReady = modelCapabilityReady(profile);
		return new RoutePolicy(profile.getId(), Boolean.TRUE.equals(profile.getLexicalAutoSelectEnabled()),
				Boolean.TRUE.equals(profile.getSemanticRecallEnabled()),
				Boolean.TRUE.equals(profile.getSemanticAutoSelectEnabled()),
				Boolean.TRUE.equals(profile.getModelDisambiguationEnabled()),
				intValue(profile.getLexicalMinScore(), DEFAULT_LEXICAL_MIN_SCORE),
				intValue(profile.getLexicalMinGap(), DEFAULT_LEXICAL_MIN_GAP),
				decimalValue(profile.getVectorRecallThreshold(), DEFAULT_VECTOR_RECALL_THRESHOLD),
				decimalValue(profile.getVectorAutoSelectThreshold(), DEFAULT_VECTOR_AUTO_SELECT_THRESHOLD),
				decimalValue(profile.getVectorMinGap(), DEFAULT_VECTOR_MIN_GAP),
				decimalValue(profile.getModelConfidenceThreshold(), DEFAULT_MODEL_CONFIDENCE_THRESHOLD),
				profile.getRouteModelConfigId(),
				profile.getEmbeddingModelConfigId(), profile.getEmbeddingFingerprint(), semanticRuntimeReady,
				routeModelRuntimeReady, profile.getRouteModelFingerprint(), routeModelProtocol(profile));
	}

	private ModelConfigDTO runtimeConfigForProfile(DataAgentRouteProfile profile, Long modelConfigId, ModelType type) {
		if (profile == null || modelConfigId == null || type == null || !StringUtils.hasText(profile.getTenantId())) {
			throw new IllegalStateException("ROUTE_PROFILE_TENANT_OR_MODEL_MISSING");
		}
		return modelConfigDataService.getRuntimeConfigById(modelConfigId, type, profile.getTenantId());
	}

	private ModelConfigDTO configForProfile(DataAgentRouteProfile profile, Long modelConfigId, ModelType type) {
		if (profile == null || modelConfigId == null || type == null || !StringUtils.hasText(profile.getTenantId())) {
			throw new IllegalStateException("ROUTE_PROFILE_TENANT_OR_MODEL_MISSING");
		}
		return modelConfigDataService.getConfigById(modelConfigId, type, profile.getTenantId());
	}

	private void probeRouteModel(DataAgentRouteProfile profile) {
		if (!Boolean.TRUE.equals(profile.getModelDisambiguationEnabled())) {
			resetRouteModelCapability(profile);
			return;
		}
		Instant checkedAt = Instant.now();
		try {
			ModelConfigDTO config = runtimeConfigForProfile(profile, profile.getRouteModelConfigId(), ModelType.CHAT);
			RouteModelProbeResult result = routeModelAdapter.probe(config);
			profile.setRouteModelProbeState(result.state().name());
			profile.setRouteModelProtocol(result.protocol().name());
			profile.setRouteModelFingerprint(routeModelFingerprint.calculate(config));
			profile.setRouteModelProbeVersion(ROUTE_MODEL_PROBE_VERSION);
			profile.setRouteModelCheckedAt(checkedAt);
			profile.setRouteModelLatencyMs(result.latencyMs());
			profile.setRouteModelFailureCode(result.failureCode());
			profile.setRouteModelRuntimeReady(result.supported()
					&& result.latencyMs() <= routing.getModelTimeout().toMillis());
			if (result.supported() && !Boolean.TRUE.equals(profile.getRouteModelRuntimeReady())) {
				profile.setRouteModelFailureCode("ROUTE_MODEL_RUNTIME_BUDGET_EXCEEDED");
				log.warn("Route model capability exceeds runtime budget, profileId={}, latencyMs={}, modelTimeoutMs={}",
						profile.getId(), result.latencyMs(), routing.getModelTimeout().toMillis());
			}
		}
		catch (RuntimeException ex) {
			profile.setRouteModelProbeState(RouteCapabilityState.UNAVAILABLE.name());
			profile.setRouteModelProtocol(RouteModelOutputProtocol.NONE.name());
			profile.setRouteModelFingerprint(null);
			profile.setRouteModelProbeVersion(ROUTE_MODEL_PROBE_VERSION);
			profile.setRouteModelCheckedAt(checkedAt);
			profile.setRouteModelLatencyMs(null);
			profile.setRouteModelFailureCode("ROUTE_MODEL_PROBE_FAILED");
			profile.setRouteModelRuntimeReady(false);
			log.warn("Route model probe failed, profileId={}, errorType={}", profile.getId(),
					ex.getClass().getSimpleName());
		}
	}

	private void probeEmbedding(DataAgentRouteProfile profile) {
		if (!Boolean.TRUE.equals(profile.getSemanticRecallEnabled())) {
			resetEmbeddingCapability(profile);
			return;
		}
		Instant checkedAt = Instant.now();
		long started = System.nanoTime();
		try {
			ModelConfigDTO config = runtimeConfigForProfile(profile, profile.getEmbeddingModelConfigId(),
					ModelType.EMBEDDING);
			EmbeddingModel model = modelFactory.createRouteEmbeddingModel(config, routing.getEmbeddingProbeTimeout());
			float[] embedding = model.embed("hybrid route profile probe");
			if (embedding == null || embedding.length == 0) {
				throw new IllegalStateException("Embedding probe returned no dimensions");
			}
			profile.setEmbeddingProbeState(RouteCapabilityState.SUPPORTED.name());
			profile.setEmbeddingFingerprint(embeddingFingerprint.calculate(config));
			profile.setEmbeddingDimension(embedding.length);
			profile.setEmbeddingCheckedAt(checkedAt);
			profile.setEmbeddingLatencyMs(elapsedMs(started));
			profile.setEmbeddingProbeVersion(EMBEDDING_PROBE_VERSION);
			profile.setEmbeddingFailureCode(null);
		}
		catch (RuntimeException ex) {
			profile.setEmbeddingProbeState(RouteCapabilityState.UNAVAILABLE.name());
			profile.setEmbeddingFingerprint(null);
			profile.setEmbeddingDimension(null);
			profile.setEmbeddingCheckedAt(checkedAt);
			profile.setEmbeddingLatencyMs(elapsedMs(started));
			profile.setEmbeddingProbeVersion(EMBEDDING_PROBE_VERSION);
			profile.setEmbeddingFailureCode("EMBEDDING_PROBE_FAILED");
			log.warn("Route embedding probe failed, profileId={}, errorType={}", profile.getId(),
					ex.getClass().getSimpleName());
		}
	}

	private DataAgentRouteProfile finalizeProbe(Long id, long expectedRevision,
			DataAgentRouteProfile probedProfile) {
		DataAgentRouteProfile profile = requireEditable(id);
		if (!Objects.equals(profile.getRevision(), expectedRevision)) {
			throw CheckedException.badRequest(REVISION_STALE_MESSAGE);
		}
		applyProbeResult(profile, probedProfile);
		boolean routeModelReady = routeModelCapabilityReadyLocked(profile);
		if (Boolean.TRUE.equals(profile.getSemanticRecallEnabled())) {
			profile.setStatus(STATUS_DRAFT);
			profile.setBuildStatus(BUILD_STATUS_NOT_BUILT);
		}
		else {
			profile.setStatus(routeModelReady ? STATUS_READY : STATUS_DRAFT);
			profile.setBuildStatus(BUILD_STATUS_SKIPPED);
		}
		profile.setBuildTotal(0);
		profile.setBuildReady(0);
		profile.setBuildFailed(0);
		profile.setLastErrorCode(probeFailureCode(profile, routeModelReady));
		update(profile, expectedRevision);
		return profile;
	}

	private ActiveReprobeResult finalizeActiveReprobe(Long id, long expectedRevision,
			DataAgentRouteProfile probedProfile, String previousEmbeddingFingerprint, boolean embeddingWasStale) {
		DataAgentRouteProfile profile = requireProfile(id);
		if (!STATUS_ACTIVE.equals(profile.getStatus()) || !Objects.equals(profile.getRevision(), expectedRevision)) {
			return null;
		}
		boolean embeddingChanged = Boolean.TRUE.equals(profile.getSemanticRecallEnabled())
				&& !Objects.equals(previousEmbeddingFingerprint, probedProfile.getEmbeddingFingerprint());
		applyProbeResult(profile, probedProfile);
		boolean routeModelReady = routeModelCapabilityReadyLocked(profile);
		boolean embeddingReady = embeddingCapabilityReadyLocked(profile);
		boolean rebuildPending = Boolean.TRUE.equals(profile.getSemanticRecallEnabled())
				&& (embeddingWasStale || embeddingChanged);
		if (rebuildPending) {
			resetBuildStatus(profile);
			profile.setLastErrorCode(embeddingReady ? ROUTE_EMBEDDING_ARTIFACT_REBUILD_PENDING
					: profile.getEmbeddingFailureCode());
		}
		else if (!routeModelReady) {
			profile.setLastErrorCode(profile.getRouteModelFailureCode());
		}
		else if (Boolean.TRUE.equals(profile.getSemanticRecallEnabled()) && !embeddingReady) {
			profile.setLastErrorCode(profile.getEmbeddingFailureCode());
		}
		else {
			profile.setLastErrorCode(null);
		}
		update(profile, expectedRevision);
		return new ActiveReprobeResult(profile, rebuildPending, rebuildPending && routeModelReady && embeddingReady);
	}

	private void applyProbeResult(DataAgentRouteProfile profile, DataAgentRouteProfile probedProfile) {
		profile.setRouteModelProbeState(probedProfile.getRouteModelProbeState());
		profile.setRouteModelProtocol(probedProfile.getRouteModelProtocol());
		profile.setRouteModelFingerprint(probedProfile.getRouteModelFingerprint());
		profile.setRouteModelProbeVersion(probedProfile.getRouteModelProbeVersion());
		profile.setRouteModelCheckedAt(probedProfile.getRouteModelCheckedAt());
		profile.setRouteModelLatencyMs(probedProfile.getRouteModelLatencyMs());
		profile.setRouteModelFailureCode(probedProfile.getRouteModelFailureCode());
		profile.setRouteModelRuntimeReady(probedProfile.getRouteModelRuntimeReady());
		profile.setEmbeddingProbeState(probedProfile.getEmbeddingProbeState());
		profile.setEmbeddingFingerprint(probedProfile.getEmbeddingFingerprint());
		profile.setEmbeddingDimension(probedProfile.getEmbeddingDimension());
		profile.setEmbeddingCheckedAt(probedProfile.getEmbeddingCheckedAt());
		profile.setEmbeddingLatencyMs(probedProfile.getEmbeddingLatencyMs());
		profile.setEmbeddingProbeVersion(probedProfile.getEmbeddingProbeVersion());
		profile.setEmbeddingFailureCode(probedProfile.getEmbeddingFailureCode());
		profile.setProbeCheckedAt(probedProfile.getProbeCheckedAt());
	}

	private RebuildPreparation prepareRebuild(Long id) {
		DataAgentRouteProfile profile = requireRebuildable(id);
		long revision = profile.getRevision();
		boolean activeBuild = STATUS_ACTIVE.equals(profile.getStatus());
		boolean routeModelReady = routeModelCapabilityReadyLocked(profile);
		if (!Boolean.TRUE.equals(profile.getSemanticRecallEnabled())) {
			profile.setBuildStatus(BUILD_STATUS_SKIPPED);
			profile.setBuildTotal(0);
			profile.setBuildReady(0);
			profile.setBuildFailed(0);
			profile.setStatus(activeBuild ? STATUS_ACTIVE : routeModelReady ? STATUS_READY : STATUS_DRAFT);
			profile.setLastErrorCode(routeModelReady ? null : profile.getRouteModelFailureCode());
			update(profile, revision);
			return routeModelReady
					? RebuildPreparation.skipped()
					: RebuildPreparation.rejected(
							"Route model capability must be probed before rebuilding route Artifacts");
		}
		if (!routeModelReady) {
			if (!activeBuild) {
				profile.setStatus(STATUS_DRAFT);
			}
			profile.setLastErrorCode(profile.getRouteModelFailureCode());
			update(profile, revision);
			return RebuildPreparation.rejected(
					"Route model capability must be probed before rebuilding route Artifacts");
		}
		// 本方法整体跑在 rebuild 的准备事务里（:272），STALE 由下面的 update() 同步落库，
		// 因此不能再让只读路径那套异步补写掺进来。
		if (!embeddingCapabilityReady(profile, false)) {
			if (RouteCapabilityState.STALE.name().equals(profile.getEmbeddingProbeState())) {
				if (!activeBuild) {
					profile.setStatus(STATUS_DRAFT);
				}
				profile.setLastErrorCode(profile.getEmbeddingFailureCode());
				update(profile, revision);
				return RebuildPreparation.rejected(
						"Embedding capability must be probed before rebuilding route Artifacts");
			}
			throw CheckedException.badRequest("Embedding capability must be probed before rebuilding route Artifacts");
		}
		profile.setStatus(activeBuild ? STATUS_ACTIVE : STATUS_BUILDING);
		profile.setBuildStatus("RUNNING");
		profile.setBuildTotal(0);
		profile.setBuildReady(0);
		profile.setBuildFailed(0);
		profile.setLastErrorCode(null);
		update(profile, revision);
		return RebuildPreparation.start(profile.getRevision());
	}

	private void validateActivationCapabilities(DataAgentRouteProfile profile) {
		if (Boolean.TRUE.equals(profile.getModelDisambiguationEnabled())
				&& !routeModelCapabilityReadyLocked(profile)) {
			throw CheckedException.badRequest("Route model capability is not ready for activation");
		}
		if (Boolean.TRUE.equals(profile.getSemanticRecallEnabled())) {
			if (!embeddingCapabilityReady(profile)) {
				throw CheckedException.badRequest("Embedding capability is not ready for activation");
			}
			if (!STATUS_READY.equals(profile.getBuildStatus())
					|| !Objects.equals(profile.getBuildTotal(), profile.getBuildReady())
					|| profile.getBuildFailed() == null || profile.getBuildFailed() != 0
					|| !artifactService.hasCompleteArtifacts(profile)) {
				throw CheckedException.badRequest("Route Artifacts are not ready for activation");
			}
		}
		else if (!BUILD_STATUS_SKIPPED.equals(profile.getBuildStatus()) && !STATUS_READY.equals(profile.getBuildStatus())) {
			throw CheckedException.badRequest("Route Artifact build state is invalid for lexical-only activation");
		}
	}

	private boolean routeModelCapabilityReadyLocked(DataAgentRouteProfile profile) {
		if (!Boolean.TRUE.equals(profile.getModelDisambiguationEnabled())) {
			return true;
		}
		if (!modelCapabilitySnapshotReady(profile)) {
			return false;
		}
		ModelConfig currentConfig = modelConfigMapper.findByIdForUpdate(profile.getRouteModelConfigId());
		if (currentConfig != null && !Boolean.TRUE.equals(currentConfig.getDeleted())
				&& Objects.equals(profile.getRouteModelConfigId(), currentConfig.getId())
				&& ModelType.CHAT == currentConfig.getModelType()
				&& Objects.equals(profile.getRouteModelFingerprint(),
						routeModelFingerprint.calculate(ModelConfigConverter.toDTO(currentConfig)))) {
			return true;
		}
		markRouteModelCapabilityStale(profile);
		return false;
	}

	private boolean embeddingCapabilityReadyLocked(DataAgentRouteProfile profile) {
		if (!embeddingCapabilitySnapshotReady(profile)) {
			return false;
		}
		ModelConfig currentConfig = modelConfigMapper.findByIdForUpdate(profile.getEmbeddingModelConfigId());
		if (currentConfig != null && !Boolean.TRUE.equals(currentConfig.getDeleted())
				&& Objects.equals(profile.getEmbeddingModelConfigId(), currentConfig.getId())
				&& ModelType.EMBEDDING == currentConfig.getModelType()
				&& Objects.equals(profile.getEmbeddingFingerprint(),
						embeddingFingerprint.calculate(ModelConfigConverter.toDTO(currentConfig)))) {
			return true;
		}
		markEmbeddingCapabilityStale(profile);
		return false;
	}

	private void validateRuntimeProfile(DataAgentRouteProfile profile, boolean allowDraft) {
		boolean usableStatus = STATUS_ACTIVE.equals(profile.getStatus())
				|| (allowDraft && Set.of(STATUS_DRAFT, STATUS_READY).contains(profile.getStatus()));
		if (profile.getId() == null || !usableStatus
				|| Boolean.TRUE.equals(profile.getDeleted())) {
			throw new IllegalStateException("ROUTE_PROFILE_UNAVAILABLE");
		}
	}

	private boolean embeddingCapabilityReady(DataAgentRouteProfile profile) {
		return embeddingCapabilityReady(profile, true);
	}

	/**
	 * @param persistStaleAsync 只读路径传 true，由异步任务补写 STALE 标记。<b>调用方本身处于写事务、
	 * 且随后会自行落库时必须传 false</b>：异步任务会以同一 revision 去 CAS 同一行，轻则空转一次事务，
	 * 重则在 executor 打满时走同步兜底（见 {@code persistEmbeddingCapabilityStaleAsync} 的
	 * {@code RejectedExecutionException} 分支）抢先推进 revision，使调用方的 {@code update()}
	 * 撞上「Route Profile revision is stale」，把真实原因盖成一个假的并发冲突。
	 */
	private boolean embeddingCapabilityReady(DataAgentRouteProfile profile, boolean persistStaleAsync) {
		boolean staleBeforeCheck = isEmbeddingCapabilityStale(profile);
		if (!embeddingCapabilitySnapshotReady(profile)) {
			if (persistStaleAsync && !staleBeforeCheck && isEmbeddingCapabilityStale(profile)) {
				persistEmbeddingCapabilityStaleAsync(profile);
			}
			return false;
		}
		try {
			ModelConfigDTO config = configForProfile(profile, profile.getEmbeddingModelConfigId(), ModelType.EMBEDDING);
			if (Objects.equals(profile.getEmbeddingFingerprint(), embeddingFingerprint.calculate(config))) {
				return true;
			}
		}
		catch (RuntimeException ex) {
			log.warn("Route embedding capability cannot be refreshed, profileId={}, errorType={}", profile.getId(),
					ex.getClass().getSimpleName());
		}
		markEmbeddingCapabilityStale(profile);
		if (persistStaleAsync) {
			persistEmbeddingCapabilityStaleAsync(profile);
		}
		return false;
	}

	private boolean embeddingCapabilitySnapshotReady(DataAgentRouteProfile profile) {
		if (profile == null || !Boolean.TRUE.equals(profile.getSemanticRecallEnabled())
				|| !RouteCapabilityState.SUPPORTED.name().equals(profile.getEmbeddingProbeState())
				|| profile.getEmbeddingModelConfigId() == null || !StringUtils.hasText(profile.getEmbeddingFingerprint())
				|| profile.getEmbeddingDimension() == null || profile.getEmbeddingDimension() < 1) {
			return false;
		}
		if (EMBEDDING_PROBE_VERSION.equals(profile.getEmbeddingProbeVersion())) {
			return true;
		}
		markEmbeddingCapabilityStale(profile);
		return false;
	}

	private boolean modelCapabilityReady(DataAgentRouteProfile profile) {
		boolean staleBeforeCheck = isRouteModelCapabilityStale(profile);
		if (!modelCapabilitySnapshotReady(profile)) {
			if (!staleBeforeCheck && isRouteModelCapabilityStale(profile)) {
				persistRouteModelCapabilityStaleAsync(profile);
			}
			return false;
		}
		try {
			ModelConfigDTO config = configForProfile(profile, profile.getRouteModelConfigId(), ModelType.CHAT);
			if (Objects.equals(profile.getRouteModelFingerprint(), routeModelFingerprint.calculate(config))) {
				return true;
			}
		}
		catch (RuntimeException ex) {
			log.warn("Route model capability cannot be refreshed, profileId={}, errorType={}", profile.getId(),
					ex.getClass().getSimpleName());
		}
		markRouteModelCapabilityStale(profile);
		persistRouteModelCapabilityStaleAsync(profile);
		return false;
	}

	private boolean modelCapabilitySnapshotReady(DataAgentRouteProfile profile) {
		if (profile == null || !Boolean.TRUE.equals(profile.getModelDisambiguationEnabled())
				|| !RouteCapabilityState.SUPPORTED.name().equals(profile.getRouteModelProbeState())
				|| !Boolean.TRUE.equals(profile.getRouteModelRuntimeReady())
				|| profile.getRouteModelConfigId() == null || !StringUtils.hasText(profile.getRouteModelFingerprint())
				|| routeModelProtocol(profile) == RouteModelOutputProtocol.NONE) {
			return false;
		}
		if (ROUTE_MODEL_PROBE_VERSION.equals(profile.getRouteModelProbeVersion())) {
			return true;
		}
		markRouteModelCapabilityStale(profile);
		return false;
	}

	private void markRouteModelCapabilityStale(DataAgentRouteProfile profile) {
		if (profile == null) {
			return;
		}
		profile.setRouteModelProbeState(RouteCapabilityState.STALE.name());
		profile.setRouteModelRuntimeReady(false);
		profile.setRouteModelFailureCode(ROUTE_MODEL_CAPABILITY_STALE);
		profile.setLastErrorCode(ROUTE_MODEL_CAPABILITY_STALE);
	}

	private void persistRouteModelCapabilityStaleAsync(DataAgentRouteProfile profile) {
		if (!isRouteModelCapabilityStale(profile) || profile.getId() == null || profile.getRevision() == null) {
			return;
		}
		Long profileId = profile.getId();
		Long expectedRevision = profile.getRevision();
		Long modelConfigId = profile.getRouteModelConfigId();
		String taskKey = profileId + ":" + expectedRevision;
		if (!scheduledStaleCapabilityPersists.add(taskKey)) {
			return;
		}
		DataAgentAsyncContextBridge.Snapshot asyncContext = asyncContextBridge.capture();
		try {
			routeRetrievalExecutor.execute(() -> asyncContextBridge.runWith(asyncContext, () -> {
				try {
					StaleCapabilityUpdate update = persistRouteModelCapabilityStale(profileId, expectedRevision,
							modelConfigId, true);
					if (update.reprobe()) {
						scheduleRouteModelReprobe(update.profileId(), update.revision());
					}
				}
				catch (RuntimeException ex) {
					log.warn("Route model runtime stale capability persistence failed, profileId={}", profileId, ex);
				}
				finally {
					scheduledStaleCapabilityPersists.remove(taskKey);
				}
			}));
		}
		catch (RejectedExecutionException ex) {
			scheduledStaleCapabilityPersists.remove(taskKey);
			log.warn("Route model runtime stale capability persistence was rejected, profileId={}", profileId, ex);
			try {
				persistRouteModelCapabilityStale(profileId, expectedRevision, modelConfigId, true);
			}
			catch (RuntimeException fallbackEx) {
				log.error("Route model stale capability fallback persistence failed, profileId={}", profileId, fallbackEx);
			}
		}
	}

	private StaleCapabilityUpdate persistRouteModelCapabilityStale(Long profileId, Long expectedRevision,
			Long expectedModelConfigId, boolean forcePersist) {
		if (profileId == null) {
			return StaleCapabilityUpdate.skipped();
		}
		StaleCapabilityUpdate update = transactionTemplate.execute(ignored -> {
			DataAgentRouteProfile profile = profileMapper.findAvailableById(profileId);
			if (!usesRouteModelConfig(profile, expectedModelConfigId)
					|| expectedRevision != null && !Objects.equals(profile.getRevision(), expectedRevision)
					|| profile.getRevision() == null) {
				return StaleCapabilityUpdate.skipped();
			}
			if (forcePersist || !isRouteModelCapabilityStale(profile)) {
				long revision = profile.getRevision();
				markRouteModelCapabilityStale(profile);
				if (profileMapper.updateWithRevision(profile, revision) != 1) {
					return StaleCapabilityUpdate.skipped();
				}
				profile.setRevision(revision + 1);
			}
			return StaleCapabilityUpdate.reprobe(profile.getId(), profile.getRevision());
		});
		return update == null ? StaleCapabilityUpdate.skipped() : update;
	}

	private boolean usesRouteModelConfig(DataAgentRouteProfile profile, Long modelConfigId) {
		return profile != null && profile.getId() != null
				&& Boolean.TRUE.equals(profile.getModelDisambiguationEnabled())
				&& Objects.equals(profile.getRouteModelConfigId(), modelConfigId);
	}

	private boolean isRouteModelCapabilityStale(DataAgentRouteProfile profile) {
		return profile != null && RouteCapabilityState.STALE.name().equals(profile.getRouteModelProbeState())
				&& !Boolean.TRUE.equals(profile.getRouteModelRuntimeReady())
				&& ROUTE_MODEL_CAPABILITY_STALE.equals(profile.getRouteModelFailureCode());
	}

	private void scheduleRouteModelReprobe(Long profileId, Long expectedRevision) {
		if (profileId == null || expectedRevision == null) {
			return;
		}
		String taskKey = profileId + ":" + expectedRevision;
		if (!scheduledRouteModelReprobes.add(taskKey)) {
			return;
		}
		DataAgentAsyncContextBridge.Snapshot asyncContext = asyncContextBridge.capture();
		try {
			routeRetrievalExecutor.execute(() -> asyncContextBridge.runWith(asyncContext, () -> {
				try {
					runExclusively(ROUTE_MODEL_REPROBE_LOCK_PREFIX + taskKey,
							() -> reprobeRouteModel(profileId, expectedRevision));
				}
				catch (RuntimeException ex) {
					log.warn("Route model capability reprobe failed, profileId={}", profileId, ex);
				}
				finally {
					scheduledRouteModelReprobes.remove(taskKey);
				}
			}));
		}
		catch (RejectedExecutionException ex) {
			scheduledRouteModelReprobes.remove(taskKey);
			log.warn("Route model capability reprobe was rejected, profileId={}", profileId, ex);
		}
	}

	private void reprobeRouteModel(Long profileId, long expectedRevision) {
		DataAgentRouteProfile probedProfile = profileMapper.findAvailableById(profileId);
		if (!usesRouteModelConfig(probedProfile, probedProfile == null ? null : probedProfile.getRouteModelConfigId())
				|| !Objects.equals(probedProfile.getRevision(), expectedRevision)
				|| !isRouteModelCapabilityStale(probedProfile)) {
			return;
		}
		if (STATUS_BUILDING.equals(probedProfile.getStatus())) {
			log.info("Route model capability reprobe deferred for building Profile, profileId={}", profileId);
			return;
		}
		probeRouteModel(probedProfile);
		DataAgentRouteProfile finalizedProfile = transactionTemplate
			.execute(ignored -> finalizeRouteModelReprobe(profileId, expectedRevision, probedProfile));
		if (finalizedProfile != null) {
			recordRouteModelProbeResult(finalizedProfile);
		}
	}

	private DataAgentRouteProfile finalizeRouteModelReprobe(Long profileId, long expectedRevision,
			DataAgentRouteProfile probedProfile) {
		DataAgentRouteProfile profile = profileMapper.findAvailableById(profileId);
		if (!usesRouteModelConfig(profile, probedProfile.getRouteModelConfigId())
				|| !Objects.equals(profile.getRevision(), expectedRevision) || STATUS_BUILDING.equals(profile.getStatus())) {
			return null;
		}
		applyRouteModelProbeResult(profile, probedProfile);
		if (!RouteCapabilityState.SUPPORTED.name().equals(profile.getRouteModelProbeState())) {
			markRouteModelCapabilityStale(profile);
		}
		boolean ready = routeModelCapabilityReadyLocked(profile);
		profile.setLastErrorCode(ready ? null : profile.getRouteModelFailureCode());
		if (profileMapper.updateWithRevision(profile, expectedRevision) != 1) {
			return null;
		}
		profile.setRevision(expectedRevision + 1);
		return profile;
	}

	private void applyRouteModelProbeResult(DataAgentRouteProfile profile, DataAgentRouteProfile probedProfile) {
		profile.setRouteModelProbeState(probedProfile.getRouteModelProbeState());
		profile.setRouteModelProtocol(probedProfile.getRouteModelProtocol());
		profile.setRouteModelFingerprint(probedProfile.getRouteModelFingerprint());
		profile.setRouteModelProbeVersion(probedProfile.getRouteModelProbeVersion());
		profile.setRouteModelCheckedAt(probedProfile.getRouteModelCheckedAt());
		profile.setRouteModelLatencyMs(probedProfile.getRouteModelLatencyMs());
		profile.setRouteModelFailureCode(probedProfile.getRouteModelFailureCode());
		profile.setRouteModelRuntimeReady(probedProfile.getRouteModelRuntimeReady());
	}

	private void markEmbeddingCapabilityStale(DataAgentRouteProfile profile) {
		if (profile == null) {
			return;
		}
		profile.setEmbeddingProbeState(RouteCapabilityState.STALE.name());
		profile.setEmbeddingFailureCode(ROUTE_EMBEDDING_CAPABILITY_STALE);
		if (Boolean.TRUE.equals(profile.getSemanticRecallEnabled())) {
			profile.setBuildTotal(0);
			profile.setBuildReady(0);
			profile.setBuildFailed(0);
			if (STATUS_BUILDING.equals(profile.getStatus())) {
				profile.setStatus(STATUS_FAILED);
				profile.setBuildStatus(STATUS_FAILED);
			}
			else {
				profile.setBuildStatus(BUILD_STATUS_NOT_BUILT);
			}
		}
		profile.setLastErrorCode(ROUTE_EMBEDDING_CAPABILITY_STALE);
	}

	private void persistEmbeddingCapabilityStaleAsync(DataAgentRouteProfile profile) {
		if (!isEmbeddingCapabilityStale(profile) || profile.getId() == null || profile.getRevision() == null) {
			return;
		}
		Long profileId = profile.getId();
		Long expectedRevision = profile.getRevision();
		Long modelConfigId = profile.getEmbeddingModelConfigId();
		String taskKey = profileId + ":" + expectedRevision;
		if (!scheduledEmbeddingCapabilityPersists.add(taskKey)) {
			return;
		}
		DataAgentAsyncContextBridge.Snapshot asyncContext = asyncContextBridge.capture();
		try {
			routeRetrievalExecutor.execute(() -> asyncContextBridge.runWith(asyncContext, () -> {
				try {
					StaleCapabilityUpdate update = persistEmbeddingCapabilityStale(profileId, expectedRevision,
							modelConfigId, true);
					if (update.reprobe()) {
						invalidateEmbeddingArtifacts(update.profileId());
						scheduleEmbeddingReprobe(update.profileId(), update.revision());
					}
				}
				catch (RuntimeException ex) {
					log.warn("Route embedding runtime stale capability persistence failed, profileId={}", profileId, ex);
				}
				finally {
					scheduledEmbeddingCapabilityPersists.remove(taskKey);
				}
			}));
		}
		catch (RejectedExecutionException ex) {
			scheduledEmbeddingCapabilityPersists.remove(taskKey);
			log.warn("Route embedding runtime stale capability persistence was rejected, profileId={}", profileId, ex);
			try {
				StaleCapabilityUpdate update = persistEmbeddingCapabilityStale(profileId, expectedRevision, modelConfigId, true);
				if (update.reprobe()) {
					invalidateEmbeddingArtifacts(update.profileId());
				}
			}
			catch (RuntimeException fallbackEx) {
				log.error("Route embedding stale capability fallback persistence failed, profileId={}", profileId,
						fallbackEx);
			}
		}
	}

	private StaleCapabilityUpdate persistEmbeddingCapabilityStale(Long profileId, Long expectedRevision,
			Long expectedModelConfigId, boolean forcePersist) {
		if (profileId == null) {
			return StaleCapabilityUpdate.skipped();
		}
		StaleCapabilityUpdate update = transactionTemplate.execute(ignored -> {
			DataAgentRouteProfile profile = profileMapper.findAvailableById(profileId);
			if (!usesEmbeddingModelConfig(profile, expectedModelConfigId)
					|| expectedRevision != null && !Objects.equals(profile.getRevision(), expectedRevision)
					|| profile.getRevision() == null) {
				return StaleCapabilityUpdate.skipped();
			}
			if (forcePersist || !isEmbeddingCapabilityStale(profile)) {
				long revision = profile.getRevision();
				markEmbeddingCapabilityStale(profile);
				if (profileMapper.updateWithRevision(profile, revision) != 1) {
					return StaleCapabilityUpdate.skipped();
				}
				profile.setRevision(revision + 1);
			}
			return StaleCapabilityUpdate.reprobe(profile.getId(), profile.getRevision());
		});
		return update == null ? StaleCapabilityUpdate.skipped() : update;
	}

	private boolean usesEmbeddingModelConfig(DataAgentRouteProfile profile, Long modelConfigId) {
		return profile != null && profile.getId() != null && Boolean.TRUE.equals(profile.getSemanticRecallEnabled())
				&& Objects.equals(profile.getEmbeddingModelConfigId(), modelConfigId);
	}

	private boolean isEmbeddingCapabilityStale(DataAgentRouteProfile profile) {
		return profile != null && RouteCapabilityState.STALE.name().equals(profile.getEmbeddingProbeState())
				&& ROUTE_EMBEDDING_CAPABILITY_STALE.equals(profile.getEmbeddingFailureCode());
	}

	private void invalidateEmbeddingArtifacts(Long profileId) {
		try {
			artifactService.invalidateEmbeddingArtifacts(profileId);
		}
		catch (RuntimeException ex) {
			log.error("Route embedding artifact invalidation failed, profileId={}", profileId, ex);
		}
	}

	private void scheduleEmbeddingReprobe(Long profileId, Long expectedRevision) {
		if (profileId == null || expectedRevision == null) {
			return;
		}
		String taskKey = profileId + ":" + expectedRevision;
		if (!scheduledEmbeddingReprobes.add(taskKey)) {
			return;
		}
		DataAgentAsyncContextBridge.Snapshot asyncContext = asyncContextBridge.capture();
		try {
			routeRetrievalExecutor.execute(() -> asyncContextBridge.runWith(asyncContext, () -> {
				try {
					runExclusively(EMBEDDING_REPROBE_LOCK_PREFIX + taskKey,
							() -> reprobeEmbedding(profileId, expectedRevision));
				}
				catch (RuntimeException ex) {
					log.warn("Route embedding capability reprobe failed, profileId={}", profileId, ex);
				}
				finally {
					scheduledEmbeddingReprobes.remove(taskKey);
				}
			}));
		}
		catch (RejectedExecutionException ex) {
			scheduledEmbeddingReprobes.remove(taskKey);
			log.warn("Route embedding capability reprobe was rejected, profileId={}", profileId, ex);
		}
	}

	private void reprobeEmbedding(Long profileId, long expectedRevision) {
		DataAgentRouteProfile probedProfile = profileMapper.findAvailableById(profileId);
		if (!usesEmbeddingModelConfig(probedProfile, probedProfile == null ? null : probedProfile.getEmbeddingModelConfigId())
				|| !Objects.equals(probedProfile.getRevision(), expectedRevision)
				|| !isEmbeddingCapabilityStale(probedProfile)) {
			return;
		}
		if (STATUS_BUILDING.equals(probedProfile.getStatus())) {
			log.info("Route embedding capability reprobe deferred for building Profile, profileId={}", profileId);
			return;
		}
		probeEmbedding(probedProfile);
		probedProfile.setProbeCheckedAt(Instant.now());
		DataAgentRouteProfile finalizedProfile = transactionTemplate
			.execute(ignored -> finalizeEmbeddingReprobe(profileId, expectedRevision, probedProfile));
		if (finalizedProfile == null) {
			return;
		}
		recordProbeResults(finalizedProfile);
		if (isEmbeddingArtifactRebuildPending(finalizedProfile, finalizedProfile.getRevision())) {
			scheduleArtifactRebuild(finalizedProfile.getId(), finalizedProfile.getRevision());
		}
	}

	private DataAgentRouteProfile finalizeEmbeddingReprobe(Long profileId, long expectedRevision,
			DataAgentRouteProfile probedProfile) {
		DataAgentRouteProfile profile = profileMapper.findAvailableById(profileId);
		if (!usesEmbeddingModelConfig(profile, probedProfile.getEmbeddingModelConfigId())
				|| !Objects.equals(profile.getRevision(), expectedRevision) || STATUS_BUILDING.equals(profile.getStatus())) {
			return null;
		}
		applyEmbeddingProbeResult(profile, probedProfile);
		if (!RouteCapabilityState.SUPPORTED.name().equals(profile.getEmbeddingProbeState())) {
			markEmbeddingCapabilityStale(profile);
		}
		boolean ready = embeddingCapabilityReadyLocked(profile);
		if (Boolean.TRUE.equals(profile.getSemanticRecallEnabled())) {
			if (!STATUS_ACTIVE.equals(profile.getStatus())) {
				profile.setStatus(STATUS_DRAFT);
			}
			resetBuildStatus(profile);
		}
		profile.setLastErrorCode(ready ? ROUTE_EMBEDDING_ARTIFACT_REBUILD_PENDING
				: profile.getEmbeddingFailureCode());
		if (profileMapper.updateWithRevision(profile, expectedRevision) != 1) {
			return null;
		}
		profile.setRevision(expectedRevision + 1);
		return profile;
	}

	private void applyEmbeddingProbeResult(DataAgentRouteProfile profile, DataAgentRouteProfile probedProfile) {
		profile.setEmbeddingProbeState(probedProfile.getEmbeddingProbeState());
		profile.setEmbeddingFingerprint(probedProfile.getEmbeddingFingerprint());
		profile.setEmbeddingDimension(probedProfile.getEmbeddingDimension());
		profile.setEmbeddingCheckedAt(probedProfile.getEmbeddingCheckedAt());
		profile.setEmbeddingLatencyMs(probedProfile.getEmbeddingLatencyMs());
		profile.setEmbeddingProbeVersion(probedProfile.getEmbeddingProbeVersion());
		profile.setEmbeddingFailureCode(probedProfile.getEmbeddingFailureCode());
		profile.setProbeCheckedAt(probedProfile.getProbeCheckedAt());
	}

	private void scheduleArtifactRebuild(Long profileId, Long expectedRevision) {
		if (profileId == null || expectedRevision == null) {
			return;
		}
		String taskKey = profileId + ":" + expectedRevision;
		if (!scheduledArtifactRebuilds.add(taskKey)) {
			return;
		}
		DataAgentAsyncContextBridge.Snapshot asyncContext = asyncContextBridge.capture();
		try {
			routeRetrievalExecutor.execute(() -> asyncContextBridge.runWith(asyncContext,
					() -> rebuildArtifactExclusively(profileId, expectedRevision, taskKey)));
		}
		catch (RejectedExecutionException ex) {
			scheduledArtifactRebuilds.remove(taskKey);
			log.warn("Route embedding artifact rebuild scheduling was rejected, profileId={}", profileId, ex);
		}
	}

	/**
	 * 异步线程内执行：持分布式锁复查画像仍处于待重建状态且能力就绪后触发重建，
	 * 无论成败都释放去重标记，允许后续重新调度。
	 */
	private void rebuildArtifactExclusively(Long profileId, Long expectedRevision, String taskKey) {
		try {
			runExclusively(ARTIFACT_REBUILD_LOCK_PREFIX + taskKey, () -> {
				DataAgentRouteProfile profile = profileMapper.findAvailableById(profileId);
				if (!isEmbeddingArtifactRebuildPending(profile, expectedRevision)
						|| !modelCapabilityReady(profile) || !embeddingCapabilityReady(profile)) {
					return;
				}
				rebuild(profileId);
			});
		}
		catch (RuntimeException ex) {
			log.warn("Route embedding artifact rebuild scheduling failed, profileId={}", profileId, ex);
		}
		finally {
			scheduledArtifactRebuilds.remove(taskKey);
		}
	}

	private boolean isEmbeddingArtifactRebuildPending(DataAgentRouteProfile profile, Long expectedRevision) {
		if (profile == null || !Objects.equals(profile.getRevision(), expectedRevision)
				|| !Boolean.TRUE.equals(profile.getSemanticRecallEnabled())) {
			return false;
		}
		boolean pending = RouteCapabilityState.SUPPORTED.name().equals(profile.getEmbeddingProbeState())
				&& BUILD_STATUS_NOT_BUILT.equals(profile.getBuildStatus())
				&& ROUTE_EMBEDDING_ARTIFACT_REBUILD_PENDING.equals(profile.getLastErrorCode());
		boolean rejected = RouteCapabilityState.SUPPORTED.name().equals(profile.getEmbeddingProbeState())
				&& STATUS_FAILED.equals(profile.getBuildStatus())
				&& "ROUTE_RETRIEVAL_EXECUTOR_REJECTED".equals(profile.getLastErrorCode());
		return pending || rejected;
	}

	private DataAgentRouteProfile capabilityView(DataAgentRouteProfile profile) {
		if (profile == null) {
			return null;
		}
		embeddingCapabilityReady(profile);
		modelCapabilityReady(profile);
		return profile;
	}

	private RouteModelOutputProtocol routeModelProtocol(DataAgentRouteProfile profile) {
		try {
			return RouteModelOutputProtocol.valueOf(profile.getRouteModelProtocol());
		}
		catch (RuntimeException ex) {
			// 库里存了未知协议值，静默降级成 NONE 会让路由模型行为与配置不符
			log.warn("Persisted route model protocol is unknown, falling back to NONE. profileId={}, protocol={}",
					profile.getId(), profile.getRouteModelProtocol());
			return RouteModelOutputProtocol.NONE;
		}
	}

	private void resetRouteModelCapability(DataAgentRouteProfile profile) {
		profile.setRouteModelProbeState(RouteCapabilityState.NOT_PROBED.name());
		profile.setRouteModelProtocol(RouteModelOutputProtocol.NONE.name());
		profile.setRouteModelFingerprint(null);
		profile.setRouteModelProbeVersion(null);
		profile.setRouteModelCheckedAt(null);
		profile.setRouteModelLatencyMs(null);
		profile.setRouteModelFailureCode(null);
		profile.setRouteModelRuntimeReady(false);
	}

	private void resetEmbeddingCapability(DataAgentRouteProfile profile) {
		profile.setEmbeddingProbeState(RouteCapabilityState.NOT_PROBED.name());
		profile.setEmbeddingFingerprint(null);
		profile.setEmbeddingDimension(null);
		profile.setEmbeddingCheckedAt(null);
		profile.setEmbeddingLatencyMs(null);
		profile.setEmbeddingProbeVersion(null);
		profile.setEmbeddingFailureCode(null);
	}

	private void resetBuildStatus(DataAgentRouteProfile profile) {
		profile.setBuildStatus(Boolean.TRUE.equals(profile.getSemanticRecallEnabled()) ? BUILD_STATUS_NOT_BUILT : BUILD_STATUS_SKIPPED);
		profile.setBuildTotal(0);
		profile.setBuildReady(0);
		profile.setBuildFailed(0);
	}

	private String probeFailureCode(DataAgentRouteProfile profile, boolean routeModelReady) {
		if (Boolean.TRUE.equals(profile.getSemanticRecallEnabled())
				&& !RouteCapabilityState.SUPPORTED.name().equals(profile.getEmbeddingProbeState())) {
			return profile.getEmbeddingFailureCode();
		}
		if (Boolean.TRUE.equals(profile.getModelDisambiguationEnabled()) && !routeModelReady) {
			return profile.getRouteModelFailureCode();
		}
		return null;
	}

	private DataAgentRouteProfile requireRebuildable(Long id) {
		DataAgentRouteProfile profile = requireProfile(id);
		if (STATUS_RETIRED.equals(profile.getStatus()) || STATUS_BUILDING.equals(profile.getStatus())) {
			throw CheckedException.badRequest("Route Profile cannot be rebuilt in its current state");
		}
		return profile;
	}

	private DataAgentRouteProfile requireEditable(Long id) {
		DataAgentRouteProfile profile = requireProfile(id);
		if (STATUS_ACTIVE.equals(profile.getStatus()) || STATUS_RETIRED.equals(profile.getStatus())
				|| STATUS_BUILDING.equals(profile.getStatus())) {
			throw CheckedException.badRequest("Route Profile cannot be edited in its current state");
		}
		return profile;
	}

	private DataAgentRouteProfile requireProfile(Long id) {
		String tenantId = requireCurrentTenantId();
		DataAgentRouteProfile profile = id == null ? null : profileMapper.findAvailableById(id);
		if (profile == null || !tenantId.equals(profile.getTenantId())) {
			throw CheckedException.notFound("Route Profile does not exist");
		}
		return profile;
	}

	private String requireCurrentTenantId() {
		return platformScopePermissionService.requireCurrentTenantId("路由档案");
	}

	private void update(DataAgentRouteProfile profile, long expectedRevision) {
		if (profileMapper.updateWithRevision(profile, expectedRevision) != 1) {
			throw CheckedException.badRequest(REVISION_STALE_MESSAGE);
		}
		profile.setRevision(expectedRevision + 1);
	}

	private DataAgentRouteProfile reconcileStaleBuild(DataAgentRouteProfile profile) {
		if (!isStaleBuild(profile)) {
			return profile;
		}
		long revision = profile.getRevision();
		markBuildFields(profile, "ROUTE_ARTIFACT_BUILD_TIMEOUT");
		try {
			if (profileMapper.updateBuildWithRevision(profile, revision) == 1) {
				profile.setRevision(revision + 1);
				log.warn("Route Profile build timed out, profileId={}, buildRevision={}", profile.getId(), revision);
				return profile;
			}
		}
		catch (RuntimeException ex) {
			log.error("Route Profile build timeout update failed, profileId={}, buildRevision={}", profile.getId(),
					revision, ex);
			if (profileMapper.markBuildFailed(profile.getId(), revision,
					profile.getBuildTotal() == null ? 0 : profile.getBuildTotal(), "ROUTE_ARTIFACT_BUILD_TIMEOUT") == 1) {
				profile.setRevision(revision + 1);
				return profile;
			}
		}
		log.warn("Route Profile stale build reconciliation lost race, profileId={}, buildRevision={}",
				profile.getId(), revision);
		return requireProfile(profile.getId());
	}

	private boolean isStaleBuild(DataAgentRouteProfile profile) {
		return profile != null && STATUS_BUILDING.equals(profile.getStatus())
				&& "RUNNING".equals(profile.getBuildStatus()) && profile.getLastModifyTime() != null
				&& profile.getLastModifyTime().isBefore(Instant.now().minus(BUILD_TIMEOUT));
	}

	private void markBuildFailed(Long id, long buildRevision, String errorCode) {
		DataAgentRouteProfile current = requireProfile(id);
		if (!STATUS_BUILDING.equals(current.getStatus()) || !"RUNNING".equals(current.getBuildStatus())
				|| !Objects.equals(current.getRevision(), buildRevision)) {
			log.warn("Route Profile build rejection became stale, profileId={}, buildRevision={}", id, buildRevision);
			return;
		}
		markBuildFields(current, errorCode);
		if (profileMapper.updateBuildWithRevision(current, buildRevision) != 1) {
			throw CheckedException.badRequest("Route Profile build result is stale");
		}
	}

	private void markBuildFields(DataAgentRouteProfile profile, String errorCode) {
		int total = profile.getBuildTotal() == null ? 0 : profile.getBuildTotal();
		profile.setStatus(STATUS_FAILED);
		profile.setBuildStatus(STATUS_FAILED);
		profile.setBuildReady(0);
		profile.setBuildFailed(total);
		profile.setLastErrorCode(errorCode);
	}

	private ProfileValues values(RouteProfileCreateReq request) {
		if (request == null) {
			throw CheckedException.badRequest("Route Profile configuration must not be empty");
		}
		return new ProfileValues(request.profileName(), request.routeModelConfigId(), request.embeddingModelConfigId(),
				request.lexicalAutoSelectEnabled() == null ? DEFAULT_LEXICAL_AUTO_SELECT_ENABLED
						: request.lexicalAutoSelectEnabled(),
				request.semanticRecallEnabled() == null ? DEFAULT_SEMANTIC_RECALL_ENABLED
						: request.semanticRecallEnabled(),
				request.semanticAutoSelectEnabled() == null ? DEFAULT_SEMANTIC_AUTO_SELECT_ENABLED
						: request.semanticAutoSelectEnabled(),
				request.modelDisambiguationEnabled() == null ? DEFAULT_MODEL_DISAMBIGUATION_ENABLED
						: request.modelDisambiguationEnabled(),
				request.lexicalMinScore() == null ? DEFAULT_LEXICAL_MIN_SCORE : request.lexicalMinScore(),
				request.lexicalMinGap() == null ? DEFAULT_LEXICAL_MIN_GAP : request.lexicalMinGap(),
				defaultDecimal(request.vectorRecallThreshold(), DEFAULT_VECTOR_RECALL_THRESHOLD),
				defaultDecimal(request.vectorAutoSelectThreshold(), DEFAULT_VECTOR_AUTO_SELECT_THRESHOLD),
				defaultDecimal(request.vectorMinGap(), DEFAULT_VECTOR_MIN_GAP),
				defaultDecimal(request.modelConfidenceThreshold(), DEFAULT_MODEL_CONFIDENCE_THRESHOLD));
	}

	private ProfileValues values(RouteProfileModifyReq request, DataAgentRouteProfile profile) {
		if (request == null) {
			throw CheckedException.badRequest("Route Profile configuration must not be empty");
		}
		boolean semanticRecallEnabled = request.semanticRecallEnabled() != null
				? request.semanticRecallEnabled()
				: profile.getSemanticRecallEnabled() != null ? profile.getSemanticRecallEnabled()
						: Boolean.TRUE.equals(profile.getSemanticAutoSelectEnabled());
		return new ProfileValues(request.profileName(), request.routeModelConfigId(), request.embeddingModelConfigId(),
				Boolean.TRUE.equals(request.lexicalAutoSelectEnabled()),
				semanticRecallEnabled,
				Boolean.TRUE.equals(request.semanticAutoSelectEnabled()),
				Boolean.TRUE.equals(request.modelDisambiguationEnabled()), request.lexicalMinScore(),
				request.lexicalMinGap(), request.vectorRecallThreshold(), request.vectorAutoSelectThreshold(),
				request.vectorMinGap(), request.modelConfidenceThreshold());
	}

	private void apply(DataAgentRouteProfile profile, ProfileValues values) {
		profile.setProfileName(values.profileName());
		profile.setRouteModelConfigId(values.routeModelConfigId());
		profile.setEmbeddingModelConfigId(values.embeddingModelConfigId());
		profile.setLexicalAutoSelectEnabled(values.lexicalAutoSelectEnabled());
		profile.setSemanticRecallEnabled(values.semanticRecallEnabled());
		profile.setSemanticAutoSelectEnabled(values.semanticAutoSelectEnabled());
		profile.setModelDisambiguationEnabled(values.modelDisambiguationEnabled());
		profile.setLexicalMinScore(values.lexicalMinScore());
		profile.setLexicalMinGap(values.lexicalMinGap());
		profile.setVectorRecallThreshold(values.vectorRecallThreshold());
		profile.setVectorAutoSelectThreshold(values.vectorAutoSelectThreshold());
		profile.setVectorMinGap(values.vectorMinGap());
		profile.setModelConfidenceThreshold(values.modelConfidenceThreshold());
	}

	private void validate(ProfileValues values) {
		if (!StringUtils.hasText(values.profileName())
				|| values.profileName().trim().length() > PROFILE_NAME_MAX_LENGTH) {
			throw CheckedException.badRequest("Route Profile name is invalid");
		}
		if (values.semanticAutoSelectEnabled() && !values.semanticRecallEnabled()) {
			throw CheckedException.badRequest("语义自动选择开启时必须同时开启语义召回");
		}
		if (values.modelDisambiguationEnabled()) {
			if (values.routeModelConfigId() == null) {
				throw CheckedException.badRequest("Route model configuration is required when disambiguation is enabled");
			}
			modelConfigDataService.getConfigById(values.routeModelConfigId(), ModelType.CHAT);
		}
		if (values.semanticRecallEnabled()) {
			if (values.embeddingModelConfigId() == null) {
				throw CheckedException.badRequest("Embedding configuration is required when semantic routing is enabled");
			}
			modelConfigDataService.getConfigById(values.embeddingModelConfigId(), ModelType.EMBEDDING);
		}
		if (values.lexicalMinScore() == null || values.lexicalMinScore() < LEXICAL_THRESHOLD_MIN
				|| values.lexicalMinScore() > LEXICAL_THRESHOLD_MAX || values.lexicalMinGap() == null
				|| values.lexicalMinGap() < LEXICAL_THRESHOLD_MIN || values.lexicalMinGap() > LEXICAL_THRESHOLD_MAX) {
			throw CheckedException.badRequest("Lexical thresholds must be between 0 and 1000");
		}
		validateRatio("vectorRecallThreshold", values.vectorRecallThreshold());
		validateRatio("vectorAutoSelectThreshold", values.vectorAutoSelectThreshold());
		validateRatio("vectorMinGap", values.vectorMinGap());
		validateRatio("modelConfidenceThreshold", values.modelConfidenceThreshold());
		if (values.vectorRecallThreshold().compareTo(values.vectorAutoSelectThreshold()) > 0) {
			throw CheckedException.badRequest("Vector recall threshold cannot exceed auto-select threshold");
		}
	}

	private void validateRatio(String name, BigDecimal value) {
		if (value == null || value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
			throw CheckedException.badRequest(name + " must be between 0 and 1");
		}
	}

	private int intValue(Integer value, int fallback) {
		return value == null ? fallback : value;
	}

	private double decimalValue(BigDecimal value, BigDecimal fallback) {
		return (value == null ? fallback : value).doubleValue();
	}

	private BigDecimal defaultDecimal(BigDecimal value, BigDecimal fallback) {
		return value == null ? fallback : value;
	}

	private RouteProfileConfigurationDTO.Defaults configurationDefaults() {
		return new RouteProfileConfigurationDTO.Defaults(DEFAULT_LEXICAL_AUTO_SELECT_ENABLED,
				DEFAULT_SEMANTIC_RECALL_ENABLED, DEFAULT_SEMANTIC_AUTO_SELECT_ENABLED,
				DEFAULT_MODEL_DISAMBIGUATION_ENABLED, DEFAULT_LEXICAL_MIN_SCORE, DEFAULT_LEXICAL_MIN_GAP,
				DEFAULT_VECTOR_RECALL_THRESHOLD, DEFAULT_VECTOR_AUTO_SELECT_THRESHOLD, DEFAULT_VECTOR_MIN_GAP,
				DEFAULT_MODEL_CONFIDENCE_THRESHOLD);
	}

	private RouteProfileConfigurationDTO.Constraints configurationConstraints() {
		return new RouteProfileConfigurationDTO.Constraints(PROFILE_NAME_MAX_LENGTH, LEXICAL_THRESHOLD_MIN,
				LEXICAL_THRESHOLD_MAX, BigDecimal.ZERO, BigDecimal.ONE, true, true, true);
	}

	private void recordProbeResults(DataAgentRouteProfile profile) {
		recordRouteModelProbeResult(profile);
		recordProbeResult("embedding", profile.getEmbeddingProbeState(), "not_applicable",
				RouteModelOutputProtocol.NONE.name());
	}

	private void recordRouteModelProbeResult(DataAgentRouteProfile profile) {
		recordProbeResult("model", profile.getRouteModelProbeState(),
				Boolean.toString(Boolean.TRUE.equals(profile.getRouteModelRuntimeReady())),
				protocolTag(profile.getRouteModelProtocol()));
		if ("ROUTE_MODEL_RUNTIME_BUDGET_EXCEEDED".equals(profile.getRouteModelFailureCode())) {
			Counter.builder(PROBE_BUDGET_EXCEEDED_METRIC)
				.description("Route capability probe results outside online runtime budgets")
				.tag("capability", "model")
				.register(meterRegistry)
				.increment();
		}
	}

	private void recordProbeResult(String capability, String state, String runtimeReady, String protocol) {
		Counter.builder(PROBE_RESULT_METRIC)
			.description("Route capability probe results")
			.tag("capability", capability)
			.tag("state", state == null ? RouteCapabilityState.NOT_PROBED.name() : state)
			.tag("runtime_ready", runtimeReady)
			.tag("protocol", protocol)
			.register(meterRegistry)
			.increment();
	}

	private String protocolTag(String protocol) {
		if (!StringUtils.hasText(protocol)) {
			return RouteModelOutputProtocol.NONE.name();
		}
		try {
			return RouteModelOutputProtocol.valueOf(protocol).name();
		}
		catch (IllegalArgumentException ex) {
			log.warn("Unknown route model protocol tag, reporting it as NONE. protocol={}", protocol);
			return RouteModelOutputProtocol.NONE.name();
		}
	}

	private long elapsedMs(long started) {
		return Duration.ofNanos(System.nanoTime() - started).toMillis();
	}

	private record RebuildPreparation(boolean startBuild, long buildRevision, String failureMessage) {

		private static RebuildPreparation skipped() {
			return new RebuildPreparation(false, 0L, null);
		}

		private static RebuildPreparation start(long buildRevision) {
			return new RebuildPreparation(true, buildRevision, null);
		}

		private static RebuildPreparation rejected(String failureMessage) {
			return new RebuildPreparation(false, 0L, failureMessage);
		}
	}

	private record ActiveReprobeResult(DataAgentRouteProfile profile, boolean invalidateArtifacts,
			boolean rebuildArtifacts) {
	}

	private record StaleCapabilityUpdate(Long profileId, Long revision, boolean reprobe) {

		private static StaleCapabilityUpdate skipped() {
			return new StaleCapabilityUpdate(null, null, false);
		}

		private static StaleCapabilityUpdate reprobe(Long profileId, Long revision) {
			return new StaleCapabilityUpdate(profileId, revision, true);
		}
	}

	private record ProfileValues(String profileName, Long routeModelConfigId, Long embeddingModelConfigId,
			boolean lexicalAutoSelectEnabled, boolean semanticRecallEnabled, boolean semanticAutoSelectEnabled,
			boolean modelDisambiguationEnabled, Integer lexicalMinScore, Integer lexicalMinGap,
			BigDecimal vectorRecallThreshold,
			BigDecimal vectorAutoSelectThreshold, BigDecimal vectorMinGap, BigDecimal modelConfidenceThreshold) {
	}
}
