/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.routing;

import cn.hutool.crypto.SecureUtil;
import com.sn68.agent.dataagent.constant.AgentTypeConstant;
import com.sn68.agent.dataagent.constant.DocumentMetadataConstant;
import com.sn68.agent.dataagent.converter.ModelConfigConverter;
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
import com.sn68.agent.dataagent.repository.DataAgentSkillMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillBindingMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.repository.ModelConfigMapper;
import com.sn68.agent.dataagent.routing.RouteCapabilityState;
import com.sn68.agent.dataagent.routing.CollaboratorCapabilityResolver;
import com.sn68.agent.dataagent.routing.RouteEmbeddingModelResolver;
import com.sn68.agent.dataagent.routing.RouteArtifactChecksum;
import com.sn68.agent.dataagent.routing.RouteModelFingerprint;
import com.sn68.agent.dataagent.routing.RouteRiskResolver;
import com.sn68.agent.dataagent.routing.RouteRulesService;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import com.sn68.agent.dataagent.routing.model.DelegationMode;
import com.sn68.agent.dataagent.routing.model.RouteRules;
import com.sn68.agent.dataagent.service.vectorstore.AgentVectorStoreService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * 路由物料服务：将技能版本与协作者信息向量化为语义检索物料，
 * 管理物料的批量重建、增量预备、校验和比对与 Embedding 指纹失效。
 */
@Slf4j
@Service
public class RouteArtifactServiceImpl implements RouteArtifactService {

	private static final Duration ARTIFACT_EMBEDDING_TIMEOUT = Duration.ofSeconds(10);

	private static final Duration ROLLBACK_WINDOW = Duration.ofDays(7);

	private static final String ROUTE_EMBEDDING_CAPABILITY_STALE = "ROUTE_EMBEDDING_CAPABILITY_STALE";

	private static final String STATUS_ACTIVE = "ACTIVE";

	private static final String STATUS_READY = "READY";

	private static final String STATUS_FAILED = "FAILED";

	private static final String ROUTE_ARTIFACT_BUILD_FAILED = "ROUTE_ARTIFACT_BUILD_FAILED";

	private static final String AGENT_STATUS_PUBLISHED = "published";

	private final DataAgentRouteProfileMapper profileMapper;

	private final DataAgentRouteArtifactMapper artifactMapper;

	private final DataAgentSkillMapper skillMapper;

	private final DataAgentSkillBindingMapper bindingMapper;

	private final DataAgentSkillVersionMapper versionMapper;

	private final AgentCollaboratorMapper collaboratorMapper;

	private final DataAgentMapper agentMapper;

	private final RouteRulesService rulesService;

	private final AgentVectorStoreService vectorStoreService;

	private final RouteEmbeddingModelResolver embeddingModelResolver;

	private final RouteArtifactChecksum artifactChecksum;

	private final RouteRiskResolver riskResolver;

	private final CollaboratorCapabilityResolver capabilityResolver;

	private final ModelConfigMapper modelConfigMapper;

	private final RouteModelFingerprint routeModelFingerprint;

	private final TransactionTemplate transactionTemplate;

	public RouteArtifactServiceImpl(DataAgentRouteProfileMapper profileMapper,
			DataAgentRouteArtifactMapper artifactMapper, DataAgentSkillMapper skillMapper,
			DataAgentSkillBindingMapper bindingMapper,
			DataAgentSkillVersionMapper versionMapper, AgentCollaboratorMapper collaboratorMapper,
			DataAgentMapper agentMapper, RouteRulesService rulesService, AgentVectorStoreService vectorStoreService,
			RouteEmbeddingModelResolver embeddingModelResolver,
			RouteArtifactChecksum artifactChecksum, RouteRiskResolver riskResolver, ModelConfigMapper modelConfigMapper,
			CollaboratorCapabilityResolver capabilityResolver, RouteModelFingerprint routeModelFingerprint,
			TransactionTemplate transactionTemplate) {
		this.profileMapper = profileMapper;
		this.artifactMapper = artifactMapper;
		this.skillMapper = skillMapper;
		this.bindingMapper = bindingMapper;
		this.versionMapper = versionMapper;
		this.collaboratorMapper = collaboratorMapper;
		this.agentMapper = agentMapper;
		this.rulesService = rulesService;
		this.vectorStoreService = vectorStoreService;
		this.embeddingModelResolver = embeddingModelResolver;
		this.artifactChecksum = artifactChecksum;
		this.riskResolver = riskResolver;
		this.modelConfigMapper = modelConfigMapper;
		this.capabilityResolver = capabilityResolver;
		this.routeModelFingerprint = routeModelFingerprint;
		this.transactionTemplate = transactionTemplate;
	}

	@Override
	public void rebuildProfile(Long profileId, Long buildRevision) {
		DataAgentRouteProfile profile = profileMapper.findAvailableById(profileId);
		if (profile == null || !isBuildRunning(profile) || !"RUNNING".equals(profile.getBuildStatus())
				|| !Objects.equals(profile.getRevision(), buildRevision)) {
			log.warn("Route Profile build task became stale, profileId={}, buildRevision={}", profileId, buildRevision);
			return;
		}
		try {
			EmbeddingModel embeddingModel = resolveEmbeddingModel(profile);
			SourceSnapshot snapshot = loadSourceSnapshot();
			if (snapshot.invalidCount() > 0) {
				log.warn("Route Profile build rejected invalid sources, profileId={}, total={}, invalid={}", profileId,
						snapshot.totalCandidates(), snapshot.invalidCount());
				finishProfile(profileId, buildRevision, snapshot.totalCandidates(), 0, snapshot.totalCandidates(),
						"ROUTE_ARTIFACT_SOURCE_INVALID");
				return;
			}
			List<ArtifactSource> sources = snapshot.sources();
			int ready = 0;
			int failed = 0;
			for (ArtifactSource source : sources) {
				try {
					prepare(profile, source, embeddingModel);
					ready++;
				}
				catch (RuntimeException ex) {
					failed++;
					log.warn("Route Artifact build failed, profileId={}, targetKey={}", profileId, source.targetKey(), ex);
				}
			}
			finishProfile(profileId, buildRevision, sources.size(), ready, failed,
					failed == 0 ? null : ROUTE_ARTIFACT_BUILD_FAILED);
		}
		catch (RuntimeException ex) {
			log.warn("Route Profile build failed, profileId={}", profileId, ex);
			finishProfile(profileId, buildRevision, 0, 0, 0, ROUTE_ARTIFACT_BUILD_FAILED);
		}
	}

	@Override
	public void invalidateEmbeddingArtifacts(Long profileId) {
		if (profileId == null) {
			return;
		}
		int invalidated = artifactMapper.invalidateEmbeddingArtifacts(profileId, ROUTE_EMBEDDING_CAPABILITY_STALE);
		log.info("Route embedding artifacts invalidated, profileId={}, count={}", profileId, invalidated);
	}

	@Override
	public void prepareSkillVersion(DataAgentSkill skill, DataAgentSkillVersion version) {
		if (skill == null || version == null || !Objects.equals(skill.getId(), version.getSkillId())
				|| !Objects.equals(skill.getTenantId(), version.getTenantId())) {
			throw CheckedException.badRequest("Skill路由物料来源无效, 技能与版本的归属或租户不一致");
		}
		RouteRules rules = rulesService.parse(version.getRouteRules());
		String targetKey = "SKILL:" + skill.getId() + ":" + version.getId();
		ArtifactSource source = new ArtifactSource(skill.getTenantId(), "SKILL", skill.getId(), version.getId(), targetKey,
				version.getSkillName(), version.getDescription(), version.getSkillKind(), version.getExecutionMode(), rules,
				riskResolver.resolveSkill(version), null);
		prepareForArtifactProfiles(source);
	}

	/**
	 * 新增/修改协作关系时的显式构建：生命周期不合格是用户当场能修的配置错误，直接报错而不是静默跳过。
	 */
	@Override
	public void prepareCollaborator(AgentCollaborator relation) {
		CollaboratorRelation resolved = resolveRelation(relation);
		requireTrustedRelation(resolved);
		requireEligibleLifecycle(resolved);
		prepareForArtifactProfiles(collaboratorSource(resolved.relation(), resolved.owner(), resolved.collaborator()));
	}

	/**
	 * 目标 Agent 自身变化后刷新指向它的协作者物料，是主操作（如技能绑定保存）提交之后的衍生动作。
	 *
	 * <p>协作者下线、被删、被改成编排型都属预期内的生命周期漂移，只跳过该关系并告警，让其余关系照常刷新，
	 * 绝不能把已经落库的主操作反过来打成失败；租户串号这类越权风险仍然硬失败。
	 */
	@Override
	public void refreshCollaboratorsForTargetAgent(Long collaboratorAgentId) {
		if (collaboratorAgentId == null) {
			return;
		}
		for (AgentCollaborator relation : collaboratorMapper.findEnabledByCollaboratorAgentId(collaboratorAgentId)) {
			refreshCollaborator(relation);
		}
	}

	@Override
	public boolean hasValidRuleSources() {
		try {
			return loadSourceSnapshot().invalidCount() == 0;
		}
		catch (CheckedException ex) {
			log.warn("Route rule source validation failed, errorType={}", ex.getClass().getSimpleName());
			return false;
		}
	}

	@Override
	public boolean hasCompleteArtifacts(DataAgentRouteProfile profile) {
		if (profile != null && !Boolean.TRUE.equals(profile.getSemanticRecallEnabled())) {
			return true;
		}
		if (profile == null || profile.getId() == null || !StringUtils.hasText(profile.getEmbeddingFingerprint())
				|| profile.getEmbeddingDimension() == null) {
			return false;
		}
		List<ArtifactSource> sources;
		try {
			SourceSnapshot snapshot = loadSourceSnapshot();
			if (snapshot.invalidCount() > 0) {
				return false;
			}
			sources = snapshot.sources();
		}
		catch (CheckedException ex) {
			log.warn("Route source snapshot unavailable, treating artifacts as incomplete. profileId={}, errorType={}",
					profile.getId(), ex.getClass().getSimpleName());
			return false;
		}
		List<DataAgentRouteArtifact> artifacts = artifactMapper.findByProfile(profile.getId());
		Map<String, DataAgentRouteArtifact> artifactsByTarget = new HashMap<>();
		for (DataAgentRouteArtifact artifact : artifacts) {
			String key = artifactKey(artifact.getTenantId(), artifact.getTargetKey());
			if (artifactsByTarget.putIfAbsent(key, artifact) != null) {
				return false;
			}
		}
		for (ArtifactSource source : sources) {
			DataAgentRouteArtifact artifact = artifactsByTarget.get(artifactKey(source.tenantId(), source.targetKey()));
			if (!matchesReadyArtifact(profile, source, artifact)) {
				return false;
			}
		}
		return true;
	}

	private void refreshCollaborator(AgentCollaborator relation) {
		CollaboratorRelation resolved = resolveRelation(relation);
		requireTrustedRelation(resolved);
		if (driftedRelation(resolved)) {
			return;
		}
		ArtifactSource source;
		try {
			source = collaboratorSource(resolved.relation(), resolved.owner(), resolved.collaborator());
		}
		catch (IllegalArgumentException ex) {
			skipCapabilityStale(resolved, ex);
			return;
		}
		prepareForArtifactProfiles(source);
	}

	private void prepareForArtifactProfiles(ArtifactSource source) {
		for (DataAgentRouteProfile profile : artifactProfiles()) {
			prepare(profile, source, resolveEmbeddingModel(profile));
		}
	}

	private CollaboratorRelation resolveRelation(AgentCollaborator relation) {
		if (relation == null || relation.getId() == null || relation.getAgentId() == null
				|| relation.getCollaboratorAgentId() == null) {
			throw CheckedException.badRequest("协作关系数据不完整, 无法构建路由物料");
		}
		return new CollaboratorRelation(relation, agentMapper.selectById(relation.getAgentId()),
				agentMapper.selectById(relation.getCollaboratorAgentId()));
	}

	/**
	 * 第一级：身份/归属校验，失败关闭。
	 *
	 * <p>协作双方租户不一致属越权风险，不允许降级为"跳过这条继续刷新"。
	 */
	private void requireTrustedRelation(CollaboratorRelation resolved) {
		List<String> failures = identityFailures(resolved);
		if (failures.isEmpty()) {
			return;
		}
		logCollaboratorFailures("identity check failed", resolved, failures);
		throw CheckedException.badRequest("协作关系租户校验不通过, 无法构建路由物料, relationId="
				+ resolved.relation().getId());
	}

	private List<String> identityFailures(CollaboratorRelation resolved) {
		List<String> failures = new ArrayList<>();
		DataAgent owner = resolved.owner();
		DataAgent collaborator = resolved.collaborator();
		if (owner != null && !StringUtils.hasText(owner.getTenantId())) {
			failures.add("OWNER_TENANT_MISSING");
		}
		if (owner != null && collaborator != null
				&& !Objects.equals(owner.getTenantId(), collaborator.getTenantId())) {
			failures.add("COLLABORATOR_TENANT_MISMATCH");
		}
		return failures;
	}

	/**
	 * 第二级：生命周期漂移，跳过该协作关系并告警。
	 *
	 * @return true 表示本轮不为该协作关系刷新物料
	 */
	private boolean driftedRelation(CollaboratorRelation resolved) {
		List<String> failures = lifecycleFailures(resolved);
		if (failures.isEmpty()) {
			return false;
		}
		logCollaboratorFailures("skipped", resolved, failures);
		return true;
	}

	private void requireEligibleLifecycle(CollaboratorRelation resolved) {
		List<String> failures = lifecycleFailures(resolved);
		if (failures.isEmpty()) {
			return;
		}
		logCollaboratorFailures("rejected", resolved, failures);
		throw CheckedException.badRequest("协作者Agent当前不可用, 需为同租户已发布的非编排型Agent, collaboratorAgentId="
				+ resolved.relation().getCollaboratorAgentId());
	}

	private List<String> lifecycleFailures(CollaboratorRelation resolved) {
		List<String> failures = new ArrayList<>();
		if (resolved.owner() == null) {
			failures.add("OWNER_AGENT_MISSING_OR_DELETED");
		}
		DataAgent collaborator = resolved.collaborator();
		if (collaborator == null) {
			failures.add("COLLABORATOR_AGENT_MISSING_OR_DELETED");
			return failures;
		}
		if (!AGENT_STATUS_PUBLISHED.equalsIgnoreCase(collaborator.getStatus())) {
			failures.add("COLLABORATOR_NOT_PUBLISHED");
		}
		// 协作者事后被改成编排型会形成编排套编排，与运行时路由侧保持一致，按不可用处理。
		if (AgentTypeConstant.isOrchestrator(collaborator.getAgentType())) {
			failures.add("COLLABORATOR_IS_ORCHESTRATOR");
		}
		if (Boolean.TRUE.equals(collaborator.getDeleted())) {
			failures.add("COLLABORATOR_DELETED");
		}
		return failures;
	}

	/** 委派模式与协作者已发布能力不再匹配，同样只影响这一条关系。 */
	private void skipCapabilityStale(CollaboratorRelation resolved, IllegalArgumentException ex) {
		log.warn("Collaborator route artifact skipped, relationId={}, agentId={}, collaboratorAgentId={}, "
				+ "delegationMode={}, failures={}, reason={}", resolved.relation().getId(),
				resolved.relation().getAgentId(), resolved.relation().getCollaboratorAgentId(),
				resolved.relation().getDelegationMode(), List.of("COLLABORATOR_CAPABILITY_STALE"), ex.getMessage());
	}

	/**
	 * 调用方只拿得到一句中文提示，没有这条日志就无法知道是哪条协作关系、哪个条件挂了，只能靠翻库排查。
	 */
	private void logCollaboratorFailures(String outcome, CollaboratorRelation resolved, List<String> failures) {
		DataAgent collaborator = resolved.collaborator();
		log.warn("Collaborator route artifact {}, relationId={}, agentId={}, collaboratorAgentId={}, "
				+ "collaboratorStatus={}, collaboratorAgentType={}, failures={}", outcome,
				resolved.relation().getId(), resolved.relation().getAgentId(),
				resolved.relation().getCollaboratorAgentId(), collaborator == null ? null : collaborator.getStatus(),
				collaborator == null ? null : collaborator.getAgentType(), failures);
	}

	private List<DataAgentRouteProfile> artifactProfiles() {
		List<DataAgentRouteProfile> profiles = new ArrayList<>(profileMapper.findArtifactProfiles());
		DataAgentRouteProfile rollback = profileMapper.findLatestRetired(Instant.now().minus(ROLLBACK_WINDOW));
		if (supportsSemanticArtifacts(rollback)
				&& profiles.stream().noneMatch(profile -> Objects.equals(profile.getId(), rollback.getId()))) {
			profiles.add(rollback);
		}
		return profiles;
	}

	private boolean supportsSemanticArtifacts(DataAgentRouteProfile profile) {
		return profile != null && Boolean.TRUE.equals(profile.getSemanticRecallEnabled())
				&& "SUPPORTED".equals(profile.getEmbeddingProbeState())
				&& StringUtils.hasText(profile.getEmbeddingFingerprint()) && profile.getEmbeddingDimension() != null;
	}

	private DataAgentRouteArtifact prepare(DataAgentRouteProfile profile, ArtifactSource source,
			EmbeddingModel embeddingModel) {
		String sourceChecksum = checksum(source);
		DataAgentRouteArtifact reusable = artifactMapper.findReusable(profile.getId(), source.tenantId(),
				source.targetKey(), sourceChecksum, profile.getEmbeddingFingerprint());
		if (reusable != null && vectorStoreService.hasRouteDocument(profile.getId(), reusable.getId(),
				reusable.getVectorDocumentId(), embeddingModel)) {
			retireSuperseded(profile.getId(), source, reusable.getId());
			return reusable;
		}
		discardUnusableSameSource(profile.getId(), source, sourceChecksum);
		String normalizedRules = rulesService.toJson(source.rules());
		String content = routeContent(source);
		String contentChecksum = SecureUtil.sha256(content);
		Instant now = Instant.now();
		DataAgentRouteArtifact artifact = DataAgentRouteArtifact.builder()
			.profileId(profile.getId())
			.tenantId(source.tenantId())
			.targetType(source.targetType())
			.targetId(source.targetId())
			.targetVersionId(source.targetVersionId())
			.targetKey(source.targetKey())
			.skillKind(source.skillKind())
			.executionMode(source.executionMode())
			.displayName(source.name())
			.description(source.description())
			.normalizedRules(normalizedRules)
			.riskLevel(source.risk().name())
			.sourceChecksum(sourceChecksum)
			.contentChecksum(contentChecksum)
			.embeddingFingerprint(profile.getEmbeddingFingerprint())
			.embeddingDimension(profile.getEmbeddingDimension())
			.status("PREPARING")
			.createTime(now)
			.lastModifyTime(now)
			.deleted(false)
			.build();
		if (artifactMapper.insert(artifact) != 1 || artifact.getId() == null) {
			throw CheckedException.fail("路由物料持久化失败, targetKey=" + source.targetKey());
		}
		String documentId = UUID.randomUUID().toString();
		artifact.setVectorDocumentId(documentId);
		try {
			vectorStoreService.addRouteDocument(new Document(documentId, content,
					routeMetadata(profile, artifact, source)), embeddingModel);
			if (!vectorStoreService.hasRouteDocument(profile.getId(), artifact.getId(), documentId,
					embeddingModel)) {
				throw CheckedException.fail("路由向量写入后校验失败, artifactId=" + artifact.getId());
			}
			artifact.setStatus(STATUS_READY);
			artifact.setFailureCode(null);
			artifact.setLastModifyTime(Instant.now());
			if (artifactMapper.updateById(artifact) != 1) {
				throw CheckedException.fail("路由物料READY状态持久化失败, artifactId=" + artifact.getId());
			}
			retireSuperseded(profile.getId(), source, artifact.getId());
			return artifact;
		}
		catch (RuntimeException ex) {
			artifact.setStatus(STATUS_FAILED);
			artifact.setFailureCode("ROUTE_VECTOR_PREPARE_FAILED");
			artifact.setLastModifyTime(Instant.now());
			try {
				if (artifactMapper.updateById(artifact) != 1) {
					log.warn("Route Artifact failed status update missed, artifactId={}", artifact.getId());
				}
			}
			catch (RuntimeException statusEx) {
				log.warn("Route Artifact failed status update failed, artifactId={}", artifact.getId(), statusEx);
			}
			try {
				vectorStoreService.deleteRouteDocuments(profile.getId(), artifact.getId());
			}
			catch (RuntimeException cleanupEx) {
				log.warn("Route vector cleanup failed, artifactId={}", artifact.getId(), cleanupEx);
			}
			throw ex;
		}
	}

	private void discardUnusableSameSource(Long profileId, ArtifactSource source, String sourceChecksum) {
		DataAgentRouteArtifact existing = artifactMapper.findCurrent(profileId, source.tenantId(), source.targetKey(),
				sourceChecksum);
		if (existing == null) {
			return;
		}
		try {
			vectorStoreService.deleteRouteDocuments(profileId, existing.getId());
		}
		catch (RuntimeException ex) {
			log.warn("Unusable route vector cleanup failed, artifactId={}", existing.getId(), ex);
		}
		artifactMapper.deleteById(existing.getId());
	}

	private void retireSuperseded(Long profileId, ArtifactSource source, Long readyArtifactId) {
		for (DataAgentRouteArtifact existing : artifactMapper.findByTarget(profileId, source.tenantId(),
				source.targetKey())) {
			if (Objects.equals(existing.getId(), readyArtifactId)) {
				continue;
			}
			artifactMapper.deleteById(existing.getId());
			try {
				vectorStoreService.deleteRouteDocuments(profileId, existing.getId());
			}
			catch (RuntimeException ex) {
				log.warn("Superseded route vector cleanup failed, artifactId={}", existing.getId(), ex);
			}
		}
	}

	private SourceSnapshot loadSourceSnapshot() {
		SourceCollector collector = new SourceCollector();
		List<DataAgentSkillBinding> bindings = bindingMapper.findAllEnabled();
		collector.totalCandidates += bindings.size();
		Map<Long, DataAgentSkill> skills = skillMapper.selectBatchIds(bindings.stream()
				.map(DataAgentSkillBinding::getSkillId).filter(Objects::nonNull).distinct().toList()).stream()
			.collect(Collectors.toMap(DataAgentSkill::getId, Function.identity()));
		Map<Long, DataAgentSkillVersion> versions = versionMapper.selectBatchIds(bindings.stream()
				.map(DataAgentSkillBinding::getPinnedSkillVersionId).filter(Objects::nonNull).distinct().toList()).stream()
			.collect(Collectors.toMap(DataAgentSkillVersion::getId, Function.identity()));
		Set<String> seenTargets = new LinkedHashSet<>();
		for (DataAgentSkillBinding binding : bindings) {
			DataAgentSkill skill = skills.get(binding.getSkillId());
			DataAgentSkillVersion version = versions.get(binding.getPinnedSkillVersionId());
			if (skill == null || version == null || Boolean.TRUE.equals(skill == null ? null : skill.getDeleted())
					|| Boolean.TRUE.equals(version == null ? null : version.getDeleted())
					|| !"PUBLISHED".equalsIgnoreCase(skill == null ? null : skill.getStatus())
					|| !"PUBLISHED".equalsIgnoreCase(version == null ? null : version.getStatus())
					|| !Objects.equals(skill.getId(), version.getSkillId())
					|| !Objects.equals(skill.getTenantId(), version.getTenantId())
					|| !StringUtils.hasText(skill.getTenantId())
					|| !StringUtils.hasText(binding.getTenantId())
					|| !Objects.equals(binding.getTenantId(), skill.getTenantId())) {
				collector.invalidCount++;
				log.warn("Route artifact source is not eligible, bindingId={}, skillId={}, versionId={}", binding.getId(),
						binding.getSkillId(), binding.getPinnedSkillVersionId());
				continue;
			}
			String targetKey = "SKILL:" + skill.getId() + ":" + version.getId();
			if (!seenTargets.add(skill.getTenantId() + ":" + targetKey)) {
				continue;
			}
			RouteRules rules = rulesService.parse(version.getRouteRules());
			collector.sources.add(new ArtifactSource(skill.getTenantId(), "SKILL", skill.getId(), version.getId(),
					targetKey, version.getSkillName(), version.getDescription(),
					version.getSkillKind(), version.getExecutionMode(), rules, riskResolver.resolveSkill(version), null));
		}
		appendCollaboratorSources(collector);
		return new SourceSnapshot(collector.sources, collector.invalidCount, collector.totalCandidates);
	}

	private void appendCollaboratorSources(SourceCollector collector) {
		List<AgentCollaborator> relations = collaboratorMapper.findAllEnabled();
		collector.totalCandidates += relations.size();
		Set<Long> agentIds = relations.stream()
			.flatMap(relation -> java.util.stream.Stream.of(relation.getAgentId(), relation.getCollaboratorAgentId()))
			.filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
		Map<Long, DataAgent> agents = agentMapper.selectBatchIds(agentIds).stream()
			.collect(Collectors.toMap(DataAgent::getId, Function.identity()));
		for (AgentCollaborator relation : relations) {
			DataAgent owner = agents.get(relation.getAgentId());
			DataAgent collaborator = agents.get(relation.getCollaboratorAgentId());
			if (owner == null || collaborator == null || !AGENT_STATUS_PUBLISHED.equalsIgnoreCase(owner.getStatus())
					|| !AGENT_STATUS_PUBLISHED.equalsIgnoreCase(collaborator.getStatus())
					|| !StringUtils.hasText(owner.getTenantId())
					|| !Objects.equals(owner.getTenantId(), collaborator.getTenantId())) {
				collector.invalidCount++;
				log.warn("Collaborator route artifact source is not eligible, relationId={}, ownerId={}, collaboratorId={}",
						relation.getId(), relation.getAgentId(), relation.getCollaboratorAgentId());
				continue;
			}
			try {
				collector.sources.add(collaboratorSource(relation, owner, collaborator));
			}
			catch (IllegalArgumentException ex) {
				collector.invalidCount++;
				log.warn("Collaborator route artifact capability is not eligible, relationId={}, collaboratorId={}",
						relation.getId(), relation.getCollaboratorAgentId());
			}
		}
	}

	private ArtifactSource collaboratorSource(AgentCollaborator relation, DataAgent owner, DataAgent collaborator) {
		DelegationMode delegationMode = DelegationMode.resolve(relation.getDelegationMode());
		CollaboratorCapabilityResolver.CollaboratorCapability capability = capabilityResolver.resolve(
				collaborator.getId(), owner.getTenantId());
		capabilityResolver.requireSupported(delegationMode, capability);
		RouteRules rules = rulesService.normalize(relation.getRoutingRules());
		String name = StringUtils.hasText(relation.getRoleName()) ? relation.getRoleName() : collaborator.getName();
		String description = StringUtils.hasText(relation.getCapabilityDescription())
				? relation.getCapabilityDescription() : collaborator.getDescription();
		return new ArtifactSource(owner.getTenantId(), "COLLABORATOR", relation.getId(), null,
				"COLLABORATOR:" + relation.getId(), name, description, null, null, rules,
				capability.effectiveRisk(), delegationMode);
	}

	private Map<String, Object> routeMetadata(DataAgentRouteProfile profile, DataAgentRouteArtifact artifact,
			ArtifactSource source) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put(DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.ROUTE);
		metadata.put(DocumentMetadataConstant.ROUTE_TENANT_ID, source.tenantId());
		metadata.put(DocumentMetadataConstant.ROUTE_PROFILE_ID, profile.getId());
		metadata.put(DocumentMetadataConstant.ROUTE_ARTIFACT_ID, artifact.getId());
		metadata.put(DocumentMetadataConstant.ROUTE_TARGET_TYPE, source.targetType());
		metadata.put(DocumentMetadataConstant.ROUTE_TARGET_ID, source.targetId());
		metadata.put(DocumentMetadataConstant.ROUTE_TARGET_VERSION_ID,
				source.targetVersionId() == null ? 0L : source.targetVersionId());
		metadata.put(DocumentMetadataConstant.ROUTE_SOURCE_CHECKSUM, artifact.getSourceChecksum());
		metadata.put(DocumentMetadataConstant.ROUTE_EMBEDDING_FINGERPRINT, profile.getEmbeddingFingerprint());
		metadata.put(DocumentMetadataConstant.ROUTE_RISK_LEVEL, source.risk().name());
		return metadata;
	}

	private String routeContent(ArtifactSource source) {
		List<String> signals = new ArrayList<>();
		signals.add(source.name());
		signals.add(source.description());
		signals.addAll(source.rules().exact());
		signals.addAll(source.rules().phrases());
		signals.addAll(source.rules().aliases());
		signals.addAll(source.rules().positiveExamples());
		return signals.stream().filter(StringUtils::hasText).map(String::trim).distinct()
			.collect(Collectors.joining("\n"));
	}

	private String checksum(ArtifactSource source) {
		return artifactChecksum.calculate(source.targetKey(), source.tenantId(), source.name(), source.description(),
				source.skillKind(), source.executionMode(), source.rules(), source.risk(),
				source.delegationMode() == null ? null : source.delegationMode().name());
	}

	private boolean matchesReadyArtifact(DataAgentRouteProfile profile, ArtifactSource source,
			DataAgentRouteArtifact artifact) {
		return artifact != null && STATUS_READY.equals(artifact.getStatus()) && !Boolean.TRUE.equals(artifact.getDeleted())
				&& Objects.equals(profile.getId(), artifact.getProfileId())
				&& Objects.equals(source.targetType(), artifact.getTargetType())
				&& Objects.equals(source.targetId(), artifact.getTargetId())
				&& Objects.equals(source.targetVersionId(), artifact.getTargetVersionId())
				&& Objects.equals(checksum(source), artifact.getSourceChecksum())
				&& Objects.equals(profile.getEmbeddingFingerprint(), artifact.getEmbeddingFingerprint())
				&& Objects.equals(profile.getEmbeddingDimension(), artifact.getEmbeddingDimension())
				&& Objects.equals(source.risk().name(), artifact.getRiskLevel())
				&& StringUtils.hasText(artifact.getContentChecksum())
				&& StringUtils.hasText(artifact.getVectorDocumentId());
	}

	private String artifactKey(String tenantId, String targetKey) {
		return tenantId + "\n" + targetKey;
	}

	private EmbeddingModel resolveEmbeddingModel(DataAgentRouteProfile profile) {
		return embeddingModelResolver.resolve(profile.getEmbeddingModelConfigId(), profile.getEmbeddingFingerprint(),
				ARTIFACT_EMBEDDING_TIMEOUT);
	}

	private void finishProfile(Long profileId, Long buildRevision, int total, int ready, int failed, String errorCode) {
		try {
			transactionTemplate.executeWithoutResult(ignored -> finalizeProfile(profileId, buildRevision, total, ready,
					failed, errorCode));
		}
		catch (RuntimeException ex) {
			log.error("Route Profile build finalization failed, profileId={}, buildRevision={}", profileId,
					buildRevision, ex);
			fallbackBuildFailure(profileId, buildRevision, total);
		}
	}

	private void finalizeProfile(Long profileId, Long buildRevision, int total, int ready, int failed,
			String errorCode) {
		DataAgentRouteProfile profile = profileMapper.findAvailableById(profileId);
		if (profile == null || !isBuildRunning(profile) || !"RUNNING".equals(profile.getBuildStatus())
				|| !Objects.equals(profile.getRevision(), buildRevision)) {
			log.warn("Route Profile build result became stale, profileId={}, buildRevision={}", profileId, buildRevision);
			return;
		}
		boolean completed = errorCode == null && failed == 0 && ready == total;
		if (completed && !routeModelCapabilityCurrent(profile)) {
			markRouteModelCapabilityStale(profile);
			completed = false;
			errorCode = "ROUTE_MODEL_CAPABILITY_STALE";
		}
		profile.setBuildTotal(total);
		profile.setBuildReady(ready);
		profile.setBuildFailed(failed);
		profile.setBuildStatus(completed ? STATUS_READY : STATUS_FAILED);
		profile.setStatus(STATUS_ACTIVE.equals(profile.getStatus()) ? STATUS_ACTIVE : completed ? STATUS_READY : STATUS_FAILED);
		profile.setLastErrorCode(errorCode);
		if (profileMapper.updateBuildWithRevision(profile, buildRevision) != 1) {
			log.warn("Route Profile build result became stale, profileId={}, buildRevision={}", profileId,
					buildRevision);
		}
	}

	private boolean routeModelCapabilityCurrent(DataAgentRouteProfile profile) {
		if (!Boolean.TRUE.equals(profile.getModelDisambiguationEnabled())) {
			return true;
		}
		if (profile.getRouteModelConfigId() == null
				|| !RouteCapabilityState.SUPPORTED.name().equals(profile.getRouteModelProbeState())
				|| !Boolean.TRUE.equals(profile.getRouteModelRuntimeReady())
				|| !StringUtils.hasText(profile.getRouteModelFingerprint())
				|| !StringUtils.hasText(profile.getRouteModelProtocol())
				|| "NONE".equals(profile.getRouteModelProtocol())) {
			return false;
		}
		ModelConfig currentConfig = modelConfigMapper.findByIdForUpdate(profile.getRouteModelConfigId());
		return currentConfig != null && !Boolean.TRUE.equals(currentConfig.getDeleted())
				&& ModelType.CHAT == currentConfig.getModelType()
				&& Objects.equals(profile.getRouteModelFingerprint(),
						routeModelFingerprint.calculate(ModelConfigConverter.toDTO(currentConfig)));
	}

	private void markRouteModelCapabilityStale(DataAgentRouteProfile profile) {
		profile.setRouteModelProbeState(RouteCapabilityState.STALE.name());
		profile.setRouteModelRuntimeReady(false);
		profile.setRouteModelFailureCode("ROUTE_MODEL_CAPABILITY_STALE");
	}

	private void fallbackBuildFailure(Long profileId, Long buildRevision, int total) {
		try {
			DataAgentRouteProfile profile = profileMapper.findAvailableById(profileId);
			int updated = profile != null && STATUS_ACTIVE.equals(profile.getStatus())
					? profileMapper.markActiveBuildFailed(profileId, buildRevision, total, ROUTE_ARTIFACT_BUILD_FAILED)
					: profileMapper.markBuildFailed(profileId, buildRevision, total, ROUTE_ARTIFACT_BUILD_FAILED);
			if (updated != 1) {
				log.warn("Route Profile build failure fallback became stale, profileId={}, buildRevision={}", profileId,
						buildRevision);
			}
		}
		catch (RuntimeException ex) {
			log.error("Route Profile build failure fallback failed, profileId={}, buildRevision={}", profileId,
					buildRevision, ex);
		}
	}

	private boolean isBuildRunning(DataAgentRouteProfile profile) {
		return "BUILDING".equals(profile.getStatus()) || STATUS_ACTIVE.equals(profile.getStatus());
	}

	private static final class SourceCollector {

		private final List<ArtifactSource> sources = new ArrayList<>();

		private int totalCandidates;

		private int invalidCount;
	}

	private record SourceSnapshot(List<ArtifactSource> sources, int invalidCount, int totalCandidates) {
	}

	/** 协作关系与两端 Agent 的快照，供身份校验、生命周期校验与物料构建共用，避免重复查库。 */
	private record CollaboratorRelation(AgentCollaborator relation, DataAgent owner, DataAgent collaborator) {
	}

	private record ArtifactSource(String tenantId, String targetType, Long targetId, Long targetVersionId,
			String targetKey, String name, String description, String skillKind, String executionMode, RouteRules rules,
			RouteRisk risk, DelegationMode delegationMode) {
	}
}
