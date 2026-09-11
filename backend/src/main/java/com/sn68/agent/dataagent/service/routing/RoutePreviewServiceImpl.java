/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.routing;

import com.sn68.agent.dataagent.constant.AgentTypeConstant;
import com.sn68.agent.dataagent.dto.routing.RoutePreviewReq;
import com.sn68.agent.dataagent.dto.routing.RoutePreviewResp;
import com.sn68.agent.dataagent.entity.AgentOrchestrationPolicy;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.entity.DataAgentRouteProfile;
import com.sn68.agent.dataagent.repository.AgentOrchestrationPolicyMapper;
import com.sn68.agent.dataagent.routing.CollaboratorRouteCandidateProvider;
import com.sn68.agent.dataagent.routing.HybridRouteEngine;
import com.sn68.agent.dataagent.routing.RouteDiagnostics;
import com.sn68.agent.dataagent.routing.RouteScorer.ScoredCandidate;
import com.sn68.agent.dataagent.routing.SkillRouteCandidateProvider;
import com.sn68.agent.dataagent.routing.model.RouteCandidate;
import com.sn68.agent.dataagent.routing.model.RouteContext;
import com.sn68.agent.dataagent.routing.model.RouteDecision;
import com.sn68.agent.dataagent.routing.model.RoutePolicy;
import com.sn68.agent.dataagent.routing.model.RouteTiming;
import com.sn68.agent.dataagent.routing.model.RouteTargetType;
import com.sn68.agent.dataagent.service.agent.DataAgentService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 路由预览服务：以只读方式装配候选集并调用混合路由引擎产出决策快照，
 * 不落库、不触发澄清或执行，仅用于管理端调试路由画像效果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoutePreviewServiceImpl implements RoutePreviewService {

	private final DataAgentService agentService;

	private final RouteProfileService profileService;

	private final SkillRouteCandidateProvider skillProvider;

	private final CollaboratorRouteCandidateProvider collaboratorProvider;

	private final AgentOrchestrationPolicyMapper orchestrationPolicyMapper;

	private final HybridRouteEngine engine;

	private final AuthenticationContext authenticationContext;

	@Override
	public RoutePreviewResp preview(Long agentId, RoutePreviewReq request) {
		long started = System.nanoTime();
		if (agentId == null || request == null || !StringUtils.hasText(request.query())) {
			throw CheckedException.badRequest("agentId and query are required");
		}
		DataAgent owner = agentService.requireAgent(agentId);
		String tenantId = currentTenantId();
		if (!Objects.equals(tenantId, owner.getTenantId())) {
			throw CheckedException.forbidden();
		}
		DataAgentRouteProfile profile = profileService.requireUsable(request.profileId(), true);
		RoutePolicy policy = profileService.toPolicy(profile);
		Set<RouteTargetType> targetTypes = AgentTypeConstant.isOrchestrator(owner.getAgentType())
				? EnumSet.of(RouteTargetType.COLLABORATOR) : EnumSet.of(RouteTargetType.SKILL);
		if (request.targetTypes() != null && !request.targetTypes().isEmpty()) {
			targetTypes.retainAll(request.targetTypes());
		}
		int maxSelections = maxSelections(owner);
		RouteContext context = new RouteContext(tenantId, owner.getId(), owner.getAgentType(), null,
				currentUserId(), "route-preview", request.query(), null, null, null,
				Instant.now().plus(engine.totalTimeout()), maxSelections);
		RouteDiagnostics diagnostics = new RouteDiagnostics();
		long candidateStarted = System.nanoTime();
		List<RouteCandidate> candidates = loadCandidates(context, owner, profile, policy, diagnostics, targetTypes);
		long candidateLoadMs = elapsedMs(candidateStarted);
		RouteDecision decision = engine.route(context, policy, candidates, diagnostics);
		decision = withTiming(decision, started, candidateLoadMs);
		engine.recordDecision(decision);
		List<RoutePreviewResp.Candidate> traces = new ArrayList<>();
		int rank = 1;
		for (ScoredCandidate candidate : diagnostics.rankedCandidates()) {
			if (candidate.excluded()) {
				continue;
			}
			RouteCandidate routeCandidate = candidate.candidate();
			traces.add(new RoutePreviewResp.Candidate(rank++, routeCandidate.target().targetType(),
					routeCandidate.target().targetId(), routeCandidate.target().targetVersionId(),
					routeCandidate.routeArtifactId(), routeCandidate.name(), candidate.lexicalScore(),
					diagnostics.vectorScore(routeCandidate.target()),
					candidate.matchedSignals(), routeCandidate.risk()));
		}
		return new RoutePreviewResp(decision.decision().name(), decision.reasonCode(),
				decision.degradeMode().name(), profile.getId(), decision.modelInvoked(), traces, decision.timing());
	}

	private RouteDecision withTiming(RouteDecision decision, long started, long candidateLoadMs) {
		RouteTiming timing = decision.timing();
		return new RouteDecision(decision.decision(), decision.reasonCode(), decision.degradeMode(),
				decision.selections(), decision.suggestions(), decision.directText(), decision.modelInvoked(),
				new RouteTiming(candidateLoadMs, timing.lexicalMs(), timing.vectorMs(), timing.modelMs(), elapsedMs(started)));
	}

	private long elapsedMs(long started) {
		return Duration.ofNanos(System.nanoTime() - started).toMillis();
	}

	private List<RouteCandidate> loadCandidates(RouteContext context, DataAgent owner,
			DataAgentRouteProfile profile, RoutePolicy policy, RouteDiagnostics diagnostics,
			Set<RouteTargetType> targetTypes) {
		List<RouteCandidate> candidates = new ArrayList<>();
		if (targetTypes.contains(RouteTargetType.SKILL)) {
			candidates.addAll(skillProvider.load(context, profile, policy, diagnostics));
		}
		if (targetTypes.contains(RouteTargetType.COLLABORATOR) && AgentTypeConstant.isOrchestrator(owner.getAgentType())) {
			AgentOrchestrationPolicy orchestrationPolicy = orchestrationPolicyMapper.findByAgentId(owner.getId());
			if (orchestrationPolicy != null && Boolean.TRUE.equals(orchestrationPolicy.getEnabled())) {
				candidates.addAll(collaboratorProvider.load(context, profile, policy, diagnostics));
			}
		}
		return List.copyOf(candidates);
	}

	private int maxSelections(DataAgent owner) {
		if (!AgentTypeConstant.isOrchestrator(owner.getAgentType())) {
			return 1;
		}
		AgentOrchestrationPolicy policy = orchestrationPolicyMapper.findByAgentId(owner.getId());
		return policy == null || !Boolean.TRUE.equals(policy.getEnabled())
				|| policy.getMaxCollaboratorsPerRun() == null ? 1 : Math.max(1, policy.getMaxCollaboratorsPerRun());
	}

	private String currentTenantId() {
		try {
			return authenticationContext.tenantId();
		}
		catch (RuntimeException ex) {
			throw CheckedException.badRequest("Tenant context is required");
		}
	}

	private String currentUserId() {
		try {
			return authenticationContext.userId();
		}
		catch (RuntimeException ex) {
			log.warn("Unable to resolve current user id for route preview attribution", ex);
			return null;
		}
	}
}
