/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing;

import com.sn68.agent.dataagent.constant.AgentTypeConstant;
import com.sn68.agent.dataagent.entity.AgentCollaborator;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.entity.DataAgentRouteArtifact;
import com.sn68.agent.dataagent.entity.DataAgentRouteProfile;
import com.sn68.agent.dataagent.repository.AgentCollaboratorMapper;
import com.sn68.agent.dataagent.repository.DataAgentMapper;
import com.sn68.agent.dataagent.repository.DataAgentRouteArtifactMapper;
import com.sn68.agent.dataagent.routing.model.RouteCandidate;
import com.sn68.agent.dataagent.routing.model.RouteContext;
import com.sn68.agent.dataagent.routing.model.DelegationMode;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import com.sn68.agent.dataagent.routing.model.RouteRules;
import com.sn68.agent.dataagent.routing.model.RoutePolicy;
import com.sn68.agent.dataagent.routing.model.RouteTargetRef;
import com.sn68.agent.dataagent.routing.model.RouteTargetType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
public class CollaboratorRouteCandidateProvider {

	private final AgentCollaboratorMapper collaboratorMapper;

	private final DataAgentMapper agentMapper;

	private final DataAgentRouteArtifactMapper artifactMapper;

	private final RouteRulesService rulesService;

	private final CollaboratorCapabilityResolver capabilityResolver;

	private final RouteArtifactChecksum artifactChecksum;

	public CollaboratorRouteCandidateProvider(AgentCollaboratorMapper collaboratorMapper, DataAgentMapper agentMapper,
			DataAgentRouteArtifactMapper artifactMapper, RouteRulesService rulesService,
			CollaboratorCapabilityResolver capabilityResolver, RouteArtifactChecksum artifactChecksum) {
		this.collaboratorMapper = collaboratorMapper;
		this.agentMapper = agentMapper;
		this.artifactMapper = artifactMapper;
		this.rulesService = rulesService;
		this.capabilityResolver = capabilityResolver;
		this.artifactChecksum = artifactChecksum;
	}

	public List<RouteCandidate> load(RouteContext context, DataAgentRouteProfile profile, RoutePolicy policy,
			RouteDiagnostics diagnostics) {
		List<AgentCollaborator> relations = collaboratorMapper.findEnabledByAgentId(context.ownerAgentId());
		if (relations.isEmpty()) {
			return List.of();
		}
		Map<Long, DataAgent> agents = agentMapper.selectBatchIds(relations.stream()
				.map(AgentCollaborator::getCollaboratorAgentId).distinct().toList()).stream()
			.collect(Collectors.toMap(DataAgent::getId, Function.identity()));
		List<String> targetKeys = relations.stream().map(relation -> targetKey(relation.getId())).toList();
		String unavailableStatus = artifactUnavailableStatus(policy);
		List<DataAgentRouteArtifact> artifacts = List.of();
		if (unavailableStatus == null) {
			if (diagnostics != null) {
				diagnostics.markArtifactQueryExecuted();
			}
			artifacts = artifactMapper.findReady(policy.profileId(), context.tenantId(), targetKeys);
		}
		List<RouteCandidate> result = new ArrayList<>();
		for (AgentCollaborator relation : relations) {
			DataAgent collaborator = agents.get(relation.getCollaboratorAgentId());
			requireTrustedRelation(context, relation, collaborator);
			if (driftedRelation(context, relation, collaborator, diagnostics)) {
				continue;
			}
			DelegationMode delegationMode;
			CollaboratorCapabilityResolver.CollaboratorCapability capability;
			try {
				delegationMode = DelegationMode.resolve(relation.getDelegationMode());
				capability = capabilityResolver.resolve(collaborator.getId(), context.tenantId());
				capabilityResolver.requireSupported(delegationMode, capability);
			}
			catch (IllegalArgumentException ex) {
				skipCapabilityStale(context, relation, ex, diagnostics);
				continue;
			}
			RouteRules rules = rulesService.normalize(relation.getRoutingRules());
			String name = StringUtils.hasText(relation.getRoleName()) ? relation.getRoleName() : collaborator.getName();
			String description = StringUtils.hasText(relation.getCapabilityDescription())
					? relation.getCapabilityDescription() : collaborator.getDescription();
			int priority = relation.getPriority() == null ? 0 : relation.getPriority();
			String targetKey = targetKey(relation.getId());
			String sourceChecksum = artifactChecksum.calculate(targetKey, context.tenantId(), name, description, null,
					null, rules, capability.effectiveRisk(), delegationMode.name());
			RouteTargetRef target = new RouteTargetRef(RouteTargetType.COLLABORATOR, relation.getId(), null,
					relation.getId());
			ArtifactMatch match = unavailableStatus == null
					? matchingArtifact(artifacts, targetKey, sourceChecksum, policy.embeddingFingerprint())
					: new ArtifactMatch(null, unavailableStatus);
			if (diagnostics != null) {
				diagnostics.captureArtifact(target, match.status());
			}
			DataAgentRouteArtifact artifact = match.artifact();
			result.add(new RouteCandidate(target, context.tenantId(), context.ownerAgentId(), name, description, null, null,
					rules, capability.effectiveRisk(), priority, policy.profileId(), artifact == null ? null : artifact.getId(),
					artifact == null ? null : artifact.getSourceChecksum(),
					artifact == null ? null : artifact.getEmbeddingFingerprint(), delegationMode));
		}
		requireAnyEligible(context, relations, result);
		return result;
	}

	/**
	 * 第一级：身份/归属校验，失败关闭。
	 *
	 * <p>协作关系不属于本 Agent、协作者不属于本租户都属越权风险，不允许降级为"跳过这条继续跑"。
	 */
	private void requireTrustedRelation(RouteContext context, AgentCollaborator relation, DataAgent collaborator) {
		List<String> failures = identityFailures(context, relation, collaborator);
		if (failures.isEmpty()) {
			return;
		}
		// 调用方只拿得到 ROUTE_ELIGIBILITY_INVALID 这个 reasonCode，没有这条日志就无法知道是哪条协作关系、
		// 哪个条件挂了，只能靠翻库排查。
		log.warn("Collaborator identity check failed, agentId={}, relationId={}, collaboratorAgentId={}, failures={}",
				context.ownerAgentId(), relation == null ? null : relation.getId(),
				relation == null ? null : relation.getCollaboratorAgentId(), failures);
		throw new RouteStageException("ROUTE_ELIGIBILITY_INVALID", "Collaborator identity check failed");
	}

	private List<String> identityFailures(RouteContext context, AgentCollaborator relation, DataAgent collaborator) {
		List<String> failures = new ArrayList<>();
		if (relation == null) {
			failures.add("RELATION_MISSING");
			return failures;
		}
		if (!Objects.equals(relation.getAgentId(), context.ownerAgentId())) {
			failures.add("RELATION_OWNER_MISMATCH");
		}
		if (collaborator != null && !Objects.equals(collaborator.getTenantId(), context.tenantId())) {
			failures.add("COLLABORATOR_TENANT_MISMATCH");
		}
		return failures;
	}

	/**
	 * 第二级：生命周期漂移，跳过该协作关系并记入诊断，让同 Agent 下其余协作者照常可路由。
	 *
	 * @return true 表示该协作关系本轮不参与路由
	 */
	private boolean driftedRelation(RouteContext context, AgentCollaborator relation, DataAgent collaborator,
			RouteDiagnostics diagnostics) {
		List<String> failures = lifecycleFailures(collaborator);
		if (failures.isEmpty()) {
			return false;
		}
		log.warn("Collaborator skipped for this route, agentId={}, relationId={}, collaboratorAgentId={}, "
				+ "collaboratorStatus={}, collaboratorAgentType={}, failures={}", context.ownerAgentId(),
				relation.getId(), relation.getCollaboratorAgentId(),
				collaborator == null ? null : collaborator.getStatus(),
				collaborator == null ? null : collaborator.getAgentType(), failures);
		captureIneligible(relation, failures, diagnostics);
		return true;
	}

	private List<String> lifecycleFailures(DataAgent collaborator) {
		List<String> failures = new ArrayList<>();
		if (collaborator == null) {
			failures.add("COLLABORATOR_AGENT_MISSING_OR_DELETED");
			return failures;
		}
		if (!"published".equalsIgnoreCase(collaborator.getStatus())) {
			failures.add("COLLABORATOR_NOT_PUBLISHED");
		}
		// 协作者事后被改成编排型，会形成编排套编排，本轮直接跳过而不是让整个编排 Agent 失能。
		if (AgentTypeConstant.isOrchestrator(collaborator.getAgentType())) {
			failures.add("COLLABORATOR_IS_ORCHESTRATOR");
		}
		if (Boolean.TRUE.equals(collaborator.getDeleted())) {
			failures.add("COLLABORATOR_DELETED");
		}
		return failures;
	}

	/** 委派模式与协作者已发布能力不再匹配，同样只影响这一条关系。 */
	private void skipCapabilityStale(RouteContext context, AgentCollaborator relation, IllegalArgumentException ex,
			RouteDiagnostics diagnostics) {
		List<String> failures = List.of("COLLABORATOR_CAPABILITY_STALE");
		log.warn("Collaborator skipped for this route, agentId={}, relationId={}, collaboratorAgentId={}, "
				+ "delegationMode={}, failures={}, reason={}", context.ownerAgentId(), relation.getId(),
				relation.getCollaboratorAgentId(), relation.getDelegationMode(), failures, ex.getMessage());
		captureIneligible(relation, failures, diagnostics);
	}

	private void captureIneligible(AgentCollaborator relation, List<String> failures, RouteDiagnostics diagnostics) {
		if (diagnostics == null) {
			return;
		}
		diagnostics.captureIneligible(new RouteTargetRef(RouteTargetType.COLLABORATOR, relation.getId(), null,
				relation.getId()), failures);
	}

	/**
	 * 全部启用协作关系都被跳过时，本轮没有任何可委派对象，仍按不可用返回。
	 *
	 * <p>与"编排 Agent 根本没配协作者"（返回空列表、后续判为 NO_MATCH）区分开：这里是配置坏了，需要人工修。
	 */
	private void requireAnyEligible(RouteContext context, List<AgentCollaborator> relations,
			List<RouteCandidate> result) {
		if (!result.isEmpty()) {
			return;
		}
		log.warn("All enabled collaborators drifted out of eligibility, agentId={}, relationCount={}",
				context.ownerAgentId(), relations.size());
		throw new RouteStageException("ROUTE_ELIGIBILITY_INVALID", "No eligible collaborator remains");
	}

	private String targetKey(Long relationId) {
		return "COLLABORATOR:" + relationId;
	}

	private ArtifactMatch matchingArtifact(List<DataAgentRouteArtifact> artifacts, String targetKey,
			String sourceChecksum, String embeddingFingerprint) {
		List<DataAgentRouteArtifact> targetArtifacts = artifacts.stream()
			.filter(artifact -> targetKey.equals(artifact.getTargetKey()))
			.toList();
		if (targetArtifacts.isEmpty()) {
			return new ArtifactMatch(null, RouteDiagnostics.ARTIFACT_NOT_READY);
		}
		List<DataAgentRouteArtifact> checksumArtifacts = targetArtifacts.stream()
			.filter(artifact -> sourceChecksum.equals(artifact.getSourceChecksum()))
			.toList();
		if (checksumArtifacts.isEmpty()) {
			return new ArtifactMatch(null, RouteDiagnostics.ARTIFACT_CHECKSUM_MISMATCH);
		}
		DataAgentRouteArtifact artifact = checksumArtifacts.stream()
			.filter(value -> Objects.equals(embeddingFingerprint, value.getEmbeddingFingerprint()))
			.findFirst()
			.orElse(null);
		return artifact == null
				? new ArtifactMatch(null, RouteDiagnostics.ARTIFACT_FINGERPRINT_MISMATCH)
				: new ArtifactMatch(artifact, RouteDiagnostics.ARTIFACT_READY);
	}

	private String artifactUnavailableStatus(RoutePolicy policy) {
		if (policy == null || !policy.semanticRecallEnabled()) {
			return RouteDiagnostics.ARTIFACT_QUERY_DISABLED;
		}
		return policy.semanticRuntimeReady() && StringUtils.hasText(policy.embeddingFingerprint())
				? null : RouteDiagnostics.ARTIFACT_PROFILE_NOT_READY;
	}

	private record ArtifactMatch(DataAgentRouteArtifact artifact, String status) {
	}
}
