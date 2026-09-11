/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.constant.AgentTypeConstant;
import com.sn68.agent.dataagent.employee.dto.EmployeeReleaseSnapshot;
import com.sn68.agent.dataagent.employee.service.EmployeeReleaseSnapshotResolver;
import com.sn68.agent.dataagent.entity.AgentOrchestrationPolicy;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.entity.DataAgentFlowInstance;
import com.sn68.agent.dataagent.entity.DataAgentRouteProfile;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.entity.DataChatTurn;
import com.sn68.agent.dataagent.flow.FlowAction;
import com.sn68.agent.dataagent.flow.FlowPersistenceService;
import com.sn68.agent.dataagent.flow.FlowTextInteractionResolver;
import com.sn68.agent.dataagent.repository.AgentOrchestrationPolicyMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.repository.DataChatTurnMapper;
import com.sn68.agent.dataagent.routing.model.ExplicitRouteTarget;
import com.sn68.agent.dataagent.routing.model.RouteCandidate;
import com.sn68.agent.dataagent.routing.model.RouteContext;
import com.sn68.agent.dataagent.routing.model.RouteDecision;
import com.sn68.agent.dataagent.routing.model.RouteDecisionType;
import com.sn68.agent.dataagent.routing.model.RouteDegradeMode;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import com.sn68.agent.dataagent.routing.model.RouteRules;
import com.sn68.agent.dataagent.routing.model.RoutePolicy;
import com.sn68.agent.dataagent.routing.model.RouteScope;
import com.sn68.agent.dataagent.routing.model.RouteSelection;
import com.sn68.agent.dataagent.routing.model.RouteTargetRef;
import com.sn68.agent.dataagent.routing.model.RouteTargetType;
import com.sn68.agent.dataagent.routing.model.RouteTiming;
import com.sn68.agent.dataagent.routing.shadow.ShadowRouteCompileService;
import com.sn68.agent.dataagent.service.routing.RouteProfileService;
import com.sn68.agent.dataagent.skill.SkillExecutionMode;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class HybridRouteCoordinator {

	private static final Set<String> CANCEL_COMMANDS = Set.of("\u53d6\u6d88", "\u505c\u6b62", "\u9000\u51fa",
			"cancel", "stop");

	private static final Set<String> CAPABILITY_QUESTIONS = Set.of("\u4f60\u80fd\u5e72\u5565",
			"\u4f60\u80fd\u5e72\u4ec0\u4e48", "\u4f60\u80fd\u505a\u4ec0\u4e48",
			"\u4f60\u6709\u54ea\u4e9b\u80fd\u529b", "\u4f60\u53ef\u4ee5\u505a\u4ec0\u4e48");

	private static final String OWNER_TYPE_DIGITAL_EMPLOYEE = "DIGITAL_EMPLOYEE";

	private final HybridRouteEngine engine;

	private final RouteProfileService profileService;

	private final SkillRouteCandidateProvider skillProvider;

	private final CollaboratorRouteCandidateProvider collaboratorProvider;

	private final AgentOrchestrationPolicyMapper orchestrationPolicyMapper;

	private final FlowPersistenceService flowPersistenceService;

	private final FlowTextInteractionResolver flowTextInteractionResolver;

	private final DataAgentSkillMapper skillMapper;

	private final DataAgentSkillVersionMapper versionMapper;

	private final DataChatTurnMapper turnMapper;

	private final AuthenticationContext authenticationContext;

	private final ShadowRouteCompileService shadowRouteCompileService;

	private final EmployeeReleaseSnapshotResolver employeeReleaseSnapshotResolver;

	private final RouteScorer routeScorer;

	public HybridRouteCoordinator(HybridRouteEngine engine, RouteProfileService profileService,
			SkillRouteCandidateProvider skillProvider, CollaboratorRouteCandidateProvider collaboratorProvider,
			AgentOrchestrationPolicyMapper orchestrationPolicyMapper, FlowPersistenceService flowPersistenceService,
			FlowTextInteractionResolver flowTextInteractionResolver, DataAgentSkillMapper skillMapper,
			DataAgentSkillVersionMapper versionMapper, DataChatTurnMapper turnMapper,
			AuthenticationContext authenticationContext, ShadowRouteCompileService shadowRouteCompileService,
			EmployeeReleaseSnapshotResolver employeeReleaseSnapshotResolver, RouteScorer routeScorer) {
		this.engine = engine;
		this.profileService = profileService;
		this.skillProvider = skillProvider;
		this.collaboratorProvider = collaboratorProvider;
		this.orchestrationPolicyMapper = orchestrationPolicyMapper;
		this.flowPersistenceService = flowPersistenceService;
		this.flowTextInteractionResolver = flowTextInteractionResolver;
		this.skillMapper = skillMapper;
		this.versionMapper = versionMapper;
		this.turnMapper = turnMapper;
		this.authenticationContext = authenticationContext;
		this.shadowRouteCompileService = shadowRouteCompileService;
		this.employeeReleaseSnapshotResolver = employeeReleaseSnapshotResolver;
		this.routeScorer = routeScorer;
	}

	public RouteDecision route(AgentRequest request, DataAgent owner) {
		RouteDecision decision = coordinate(request, owner);
		engine.recordDecision(decision);
		return decision;
	}

	/** Re-checks a server-stored confirmation snapshot against current eligibility without invoking a model. */
	public RouteDecision validateConfirmed(AgentRequest request, DataAgent owner, RouteDecision snapshot) {
		if (request == null || owner == null || snapshot == null
				|| snapshot.decision() != RouteDecisionType.CONFIRM_REQUIRED || snapshot.selections().isEmpty()) {
			throw new RouteUnavailableException("CONFIRMED_ROUTE_INVALID");
		}
		RouteContext context = runtimeContext(request, owner);
		try {
			validateOwner(context, owner);
		}
		catch (RouteStageException ex) {
			throw new RouteUnavailableException("CONFIRMED_ROUTE_STALE");
		}
		if (snapshot.selections().size() > context.maxSelections()
				|| snapshot.selections().size() > 1 && context.effectiveScope() != RouteScope.COLLABORATOR_SCOPE) {
			throw new RouteUnavailableException("CONFIRMED_ROUTE_INVALID");
		}
		DataAgentRouteProfile profile = profileService.requireUsable(resolveRuntimeProfileId(request), false);
		RoutePolicy policy = profileService.toPolicy(profile);
		List<RouteCandidate> candidates;
		try {
			candidates = loadCandidates(context, owner, profile, policy, new RouteDiagnostics(),
					EnumSet.of(context.effectiveScope().targetType()));
		}
		catch (RouteStageException ex) {
			// 一条候选都建不起来（绑定/协作者全部漂移），确认快照必然已失效；这里与 validateOwner 一样收成
			// CONFIRMED_ROUTE_STALE，避免裸的 stage 异常绕过运行时的 ROUTE_UNAVAILABLE 分类。
			throw new RouteUnavailableException("CONFIRMED_ROUTE_STALE");
		}
		List<RouteSelection> validatedSelections = new ArrayList<>();
		for (RouteSelection selection : snapshot.selections()) {
			RouteCandidate current = candidates.stream().filter(candidate -> matchesSelection(selection, candidate))
				.findFirst().orElse(null);
			if (current == null || !requiresConfirmation(current)) {
				throw new RouteUnavailableException("CONFIRMED_ROUTE_STALE");
			}
			validatedSelections.add(RouteSelection.from(current));
		}
		RouteDecisionType executableType = validatedSelections.size() == 1 ? RouteDecisionType.SELECT
				: RouteDecisionType.MULTI_SELECT;
		RouteDecision confirmed;
		try {
			confirmed = new RouteDecision(executableType, "CONFIRMED_ROUTE", snapshot.degradeMode(), validatedSelections,
					List.of(), null, snapshot.modelInvoked(), snapshot.timing(), null, snapshot.plan());
		}
		catch (IllegalArgumentException ex) {
			throw new RouteUnavailableException("CONFIRMED_ROUTE_INVALID");
		}
		request.setRouteConfirmed(true);
		return confirmed;
	}

	private boolean matchesSelection(RouteSelection selection, RouteCandidate candidate) {
		return selection != null && candidate != null && selection.target() != null
				&& Objects.equals(selection.target(), candidate.target())
				&& Objects.equals(selection.routeProfileId(), candidate.routeProfileId())
				&& Objects.equals(selection.routeArtifactId(), candidate.routeArtifactId())
				&& selection.risk() == candidate.risk()
				&& Objects.equals(selection.sourceChecksum(), candidate.sourceChecksum())
				&& Objects.equals(selection.delegationMode(), candidate.delegationMode());
	}

	private boolean requiresConfirmation(RouteCandidate candidate) {
		if (candidate == null) {
			return false;
		}
		if (candidate.target() != null && candidate.target().targetType() == RouteTargetType.COLLABORATOR) {
			return candidate.delegationMode() == com.sn68.agent.dataagent.routing.model.DelegationMode.PLAN_CONFIRM;
		}
		RouteRisk risk = candidate.risk();
		return risk == RouteRisk.WRITE || risk == RouteRisk.FLOW || risk == RouteRisk.DELEGATED;
	}

	private RouteDecision coordinate(AgentRequest request, DataAgent owner) {
		long started = System.nanoTime();
		if (request == null || owner == null || owner.getId() == null) {
			return RouteDecision.unavailable("ROUTE_CONTEXT_INVALID", RouteDegradeMode.NONE,
					timing(started, 0L, RouteTiming.empty()));
		}
		try {
			DataAgentFlowInstance activeFlow = flowPersistenceService.findActive(request);
			if (request.getFlowAction() != null && activeFlow == null) {
				return clarify("FLOW_INSTANCE_UNAVAILABLE", started);
			}
			if (activeFlow != null) {
				applyCancelAction(request);
				flowTextInteractionResolver.resolve(request, activeFlow);
				return activeFlow(activeFlow, owner, started);
			}
			RouteContext context = runtimeContext(request, owner);
			Set<RouteTargetType> scopeTypes = EnumSet.of(context.effectiveScope().targetType());
			Long profileId = resolveRuntimeProfileId(request);
			if (context.explicitTarget() != null) {
				return route(context, owner, profileId, scopeTypes, false, started);
			}
			RouteDecision direct = platformIntent(context, owner, started);
			if (direct != null) {
				return direct;
			}
			return route(context, owner, profileId, scopeTypes, false, started);
		}
		catch (RouteStageException ex) {
			log.warn("Hybrid route stage rejected the request, agentId={}, runtimeRequestId={}, reasonCode={}",
					owner.getId(), request.getRuntimeRequestId(), ex.reasonCode());
			return RouteDecision.unavailable(ex.reasonCode(), RouteDegradeMode.NONE,
					timing(started, 0L, RouteTiming.empty()));
		}
		catch (RuntimeException ex) {
			log.warn("Hybrid route coordination failed, agentId={}, runtimeRequestId={}, errorType={}", owner.getId(),
					request.getRuntimeRequestId(), ex.getClass().getSimpleName(), ex);
			return RouteDecision.unavailable("ROUTE_COORDINATOR_FAILED", RouteDegradeMode.NONE,
					timing(started, 0L, RouteTiming.empty()));
		}
	}

	public RouteDecision preview(RouteContext context, DataAgent owner, Long profileId,
			Set<RouteTargetType> targetTypes) {
		RouteDecision decision = previewInternal(context, owner, profileId, targetTypes);
		engine.recordDecision(decision);
		return decision;
	}

	private RouteDecision previewInternal(RouteContext context, DataAgent owner, Long profileId,
			Set<RouteTargetType> targetTypes) {
		long started = System.nanoTime();
		try {
			RouteScope scope = context == null ? RouteScope.forOwnerType(owner == null ? null : owner.getAgentType())
					: context.effectiveScope();
			Set<RouteTargetType> requestedTypes = targetTypes == null || targetTypes.isEmpty()
					? EnumSet.of(scope.targetType()) : EnumSet.copyOf(targetTypes);
			Set<RouteTargetType> safeTypes = EnumSet.of(scope.targetType());
			if (!requestedTypes.contains(scope.targetType())) {
				return RouteDecision.noMatch(timing(started));
			}
			return route(context, owner, profileId, safeTypes, true, started);
		}
		catch (RouteStageException ex) {
			log.warn("Hybrid route preview stage rejected the request, agentId={}, reasonCode={}",
					owner == null ? null : owner.getId(), ex.reasonCode());
			return RouteDecision.unavailable(ex.reasonCode(), RouteDegradeMode.NONE,
					timing(started, 0L, RouteTiming.empty()));
		}
		catch (RuntimeException ex) {
			log.warn("Hybrid route preview failed, agentId={}, errorType={}", owner == null ? null : owner.getId(),
					ex.getClass().getSimpleName());
			return RouteDecision.unavailable("ROUTE_PREVIEW_FAILED", RouteDegradeMode.NONE,
					timing(started, 0L, RouteTiming.empty()));
		}
	}

	private RouteDecision route(RouteContext context, DataAgent owner, Long profileId,
			Set<RouteTargetType> targetTypes, boolean allowDraft, long started) {
		validateOwner(context, owner);
		DataAgentRouteProfile profile = profileService.requireUsable(profileId, allowDraft);
		RoutePolicy policy = profileService.toPolicy(profile);
		RouteDiagnostics diagnostics = new RouteDiagnostics();
		long candidateStarted = System.nanoTime();
		List<RouteCandidate> candidates = loadCandidates(context, owner, profile, policy, diagnostics, targetTypes);
		long candidateLoadMs = elapsedMs(candidateStarted);
		RouteDecision explicit = explicitTarget(context, profile, candidates);
		if (explicit != null) {
			logRouteDiagnostics(profile, policy, diagnostics, explicit);
			submitShadowCompare(context, explicit, candidates, allowDraft);
			return withTiming(explicit, started, candidateLoadMs);
		}
		RouteDecision sessionPin = analysisSessionPin(context, candidates);
		if (sessionPin != null) {
			logRouteDiagnostics(profile, policy, diagnostics, sessionPin);
			submitShadowCompare(context, sessionPin, candidates, allowDraft);
			return withTiming(sessionPin, started, candidateLoadMs);
		}
		RouteDecision decision = engine.route(context, policy, candidates, diagnostics);
		logRouteDiagnostics(profile, policy, diagnostics, decision);
		submitShadowCompare(context, decision, candidates, allowDraft);
		return withTiming(decision, started, candidateLoadMs);
	}

	/**
	 * SHADOW 灰度对比：V1 决策产出后异步提交 V2 影子编译（模式判断与复杂逻辑全部收在
	 * ShadowRouteCompileService 内，LEGACY 下零开销）。仅生产链路（coordinate，allowDraft=false）
	 * 触发且单请求一次；预览链路（preview，allowDraft=true）不触发，避免污染对比数据。
	 */
	private void submitShadowCompare(RouteContext context, RouteDecision decision, List<RouteCandidate> candidates,
			boolean allowDraft) {
		if (allowDraft) {
			return;
		}
		shadowRouteCompileService.submitCompare(context, decision, candidates);
	}

	private void logRouteDiagnostics(DataAgentRouteProfile profile, RoutePolicy policy, RouteDiagnostics diagnostics,
			RouteDecision decision) {
		if (decision != null && "AMBIGUOUS_CANDIDATES".equals(decision.reasonCode())) {
			log.info("Route ambiguity summary profileId={}, candidateCount={}, modelInvoked={}, degradeMode={}",
					profile == null ? null : profile.getId(), diagnostics == null ? 0 : diagnostics.rankedCandidates().size(),
					decision.modelInvoked(), decision.degradeMode());
		}
		if (!log.isDebugEnabled() || diagnostics == null) {
			return;
		}
		log.debug("Route profile profileId={}, profileStatus={}, semanticRecallEnabled={}, "
				+ "semanticAutoSelectEnabled={}, embeddingProbeState={}, embeddingFingerprintPresent={}, "
				+ "semanticRuntimeReady={}, artifactQueryExecuted={}",
				profile == null ? null : profile.getId(), profile == null ? null : profile.getStatus(),
				policy != null && policy.semanticRecallEnabled(),
				policy != null && policy.semanticAutoSelectEnabled(),
				profile == null ? null : profile.getEmbeddingProbeState(),
				policy != null && StringUtils.hasText(policy.embeddingFingerprint()),
				policy != null && policy.semanticRuntimeReady(), diagnostics.artifactQueryExecuted());
		for (var entry : diagnostics.artifactStatuses().entrySet()) {
			log.debug("Route Artifact lookup targetType={}, targetId={}, targetVersionId={}, status={}",
					entry.getKey().targetType(), entry.getKey().targetId(), entry.getKey().targetVersionId(),
					entry.getValue());
		}
		for (var scored : diagnostics.rankedCandidates()) {
			RouteCandidate candidate = scored.candidate();
			log.debug(
					"Route candidate targetType={}, targetId={}, targetVersionId={}, risk={}, delegationMode={}, lexicalScore={}, matchedSignals={}, artifactId={}, vectorScore={}",
					candidate.target().targetType(), candidate.target().targetId(), candidate.target().targetVersionId(),
					candidate.risk(), candidate.delegationMode(), scored.lexicalScore(), scored.matchedSignals(), candidate.routeArtifactId(),
					diagnostics.vectorScore(candidate.target()));
		}
		log.debug("Route decision decision={}, reasonCode={}, modelInvoked={}, degradeMode={}", decision.decision(),
				decision.reasonCode(), decision.modelInvoked(), decision.degradeMode());
	}

	private List<RouteCandidate> loadCandidates(RouteContext context, DataAgent owner,
			DataAgentRouteProfile profile, RoutePolicy policy, RouteDiagnostics diagnostics,
			Set<RouteTargetType> targetTypes) {
		List<RouteCandidate> result = new ArrayList<>();
		RouteTargetType scopeType = context.effectiveScope().targetType();
		if (scopeType == RouteTargetType.SKILL && targetTypes.contains(RouteTargetType.SKILL)) {
			result.addAll(skillProvider.load(context, profile, policy, diagnostics));
		}
		if (scopeType == RouteTargetType.COLLABORATOR && targetTypes.contains(RouteTargetType.COLLABORATOR)
				&& AgentTypeConstant.isOrchestrator(owner.getAgentType())) {
			AgentOrchestrationPolicy orchestrationPolicy = orchestrationPolicyMapper.findByAgentId(owner.getId());
			if (orchestrationPolicy != null && Boolean.TRUE.equals(orchestrationPolicy.getEnabled())) {
				result.addAll(collaboratorProvider.load(context, profile, policy, diagnostics));
			}
		}
		return List.copyOf(result);
	}

	private RouteDecision analysisSessionPin(RouteContext context, List<RouteCandidate> candidates) {
		if (context == null || context.previousTarget() == null || candidates == null) {
			return null;
		}
		RouteTargetRef previous = context.previousTarget();
		List<RouteCandidate> matches = candidates.stream()
			.filter(candidate -> matchesPreviousReadOnlySkill(previous, candidate))
			.toList();
		if (matches.size() != 1) {
			return null;
		}
		if (!AnalysisSessionContinuation.matches(context.query())
				&& hasIndependentSkillSignal(context, candidates)) {
			return null;
		}
		return RouteDecision.select(matches.get(0), "ANALYSIS_SESSION_CONTINUATION", RouteDegradeMode.NONE, false,
				RouteTiming.empty());
	}

	/**
	 * 当前问句是否自己选得出技能。有词法/精确命中则重新路由；全无命中才沿用上一轮。
	 * 不枚举用户说法。
	 */
	private boolean hasIndependentSkillSignal(RouteContext context, List<RouteCandidate> candidates) {
		if (routeScorer == null) {
			return true;
		}
		return routeScorer.score(context, candidates).stream().anyMatch(RouteScorer.ScoredCandidate::relevant);
	}

	private boolean matchesPreviousReadOnlySkill(RouteTargetRef previous, RouteCandidate candidate) {
		if (previous == null || candidate == null || candidate.target() == null
				|| candidate.risk() != RouteRisk.READ_ONLY) {
			return false;
		}
		return previous.targetType() == candidate.target().targetType()
				&& Objects.equals(previous.targetId(), candidate.target().targetId())
				&& (previous.targetVersionId() == null
						|| Objects.equals(previous.targetVersionId(), candidate.target().targetVersionId()));
	}

	private RouteDecision explicitTarget(RouteContext context, DataAgentRouteProfile profile,
			List<RouteCandidate> candidates) {
		ExplicitRouteTarget explicit = context.explicitTarget();
		if (explicit == null) {
			return null;
		}
		if (!isValidExplicitTarget(explicit)) {
			return RouteDecision.clarify(List.of(), "EXPLICIT_TARGET_INVALID", RouteDegradeMode.NONE, false,
					RouteTiming.empty());
		}
		if (!Objects.equals(explicit.routeProfileId(), profile.getId())) {
			return RouteDecision.clarify(List.of(), "EXPLICIT_TARGET_PROFILE_STALE", RouteDegradeMode.NONE, false,
					RouteTiming.empty());
		}
		List<RouteCandidate> matches = candidates.stream()
			.filter(candidate -> matches(explicit, candidate)
					&& Objects.equals(context.tenantId(), candidate.tenantId())
					&& Objects.equals(context.ownerAgentId(), candidate.ownerAgentId())
					&& Objects.equals(profile.getId(), candidate.routeProfileId()))
			.toList();
		if (matches.size() != 1) {
			return RouteDecision.clarify(List.of(), "EXPLICIT_TARGET_INVALID", RouteDegradeMode.NONE, false,
					RouteTiming.empty());
		}
		RouteCandidate candidate = matches.get(0);
		if (candidate.risk() == RouteRisk.UNKNOWN) {
			return RouteDecision.clarify(List.of(), "EXPLICIT_TARGET_RISK_UNKNOWN", RouteDegradeMode.NONE, false,
					RouteTiming.empty());
		}
		return engine.selectExplicit(candidate);
	}

	private boolean isValidExplicitTarget(ExplicitRouteTarget explicit) {
		return explicit.targetType() != null && explicit.targetId() != null && explicit.routeProfileId() != null
				&& (explicit.targetType() != RouteTargetType.SKILL || explicit.targetVersionId() != null);
	}

	private boolean matches(ExplicitRouteTarget explicit, RouteCandidate candidate) {
		return explicit.targetType() == candidate.target().targetType()
				&& Objects.equals(explicit.targetId(), candidate.target().targetId())
				&& Objects.equals(explicit.targetVersionId(), candidate.target().targetVersionId())
				&& Objects.equals(explicit.routeArtifactId(), candidate.routeArtifactId())
				&& Objects.equals(explicit.routeProfileId(), candidate.routeProfileId());
	}

	private RouteDecision activeFlow(DataAgentFlowInstance instance, DataAgent owner, long started) {
		DataAgentSkill skill = instance.getSkillId() == null ? null : skillMapper.selectById(instance.getSkillId());
		DataAgentSkillVersion version = instance.getSkillVersionId() == null ? null
				: versionMapper.selectById(instance.getSkillVersionId());
		if (skill == null || version == null || !Objects.equals(instance.getAgentId(), owner.getId())
				|| !Objects.equals(instance.getTenantId(), owner.getTenantId())
				|| !Objects.equals(skill.getId(), version.getSkillId())
				|| !"PUBLISHED".equals(skill.getStatus()) || !"PUBLISHED".equals(version.getStatus())
				|| !SkillExecutionMode.FLOW.name().equals(version.getExecutionMode())) {
			throw new RouteStageException("ACTIVE_FLOW_TARGET_INVALID", "Active FLOW target is no longer eligible");
		}
		RouteCandidate candidate = new RouteCandidate(new RouteTargetRef(RouteTargetType.SKILL, skill.getId(),
				version.getId(), instance.getId()), instance.getTenantId(), owner.getId(), version.getSkillName(),
				version.getDescription(), version.getSkillKind(), version.getExecutionMode(), RouteRules.empty(),
				RouteRisk.FLOW, 0, null, null, null, null);
		return RouteDecision.select(candidate, "ACTIVE_FLOW", RouteDegradeMode.NONE, false,
				timing(started, 0L, RouteTiming.empty()));
	}

	/**
	 * 数字员工生产路由钉死 Seal 快照里的 routeProfileId，避免 Seal 后改租户默认 profile 漂到已发布员工。
	 * MODEL_ONLY（无 releaseId）、快照未冻结 profile、普通 DataAgent 均回落 null，走租户 ACTIVE profile。
	 * 不调用 resolveActive，避免未钉死 Release 的员工回落生产部署。
	 */
	private Long resolveRuntimeProfileId(AgentRequest request) {
		if (request == null || !isDigitalEmployeeRoute(request) || request.getReleaseId() == null) {
			return null;
		}
		String tenantId = firstText(request.getTenantIdSnapshot(), currentTenantId());
		EmployeeReleaseSnapshot snapshot = employeeReleaseSnapshotResolver.resolveById(tenantId,
				request.getOwnerId(), request.getReleaseId());
		return snapshot == null ? null : snapshot.routeProfileId();
	}

	private boolean isDigitalEmployeeRoute(AgentRequest request) {
		if (OWNER_TYPE_DIGITAL_EMPLOYEE.equals(request.getOwnerType())) {
			return true;
		}
		// 调用方已钉死 employee Release、但尚未回填 ownerType 时仍按快照消费。
		return request.getReleaseId() != null && !StringUtils.hasText(request.getOwnerType());
	}

	private RouteContext runtimeContext(AgentRequest request, DataAgent owner) {
		String tenantId = firstText(request.getTenantIdSnapshot(), currentTenantId());
		String userId = firstText(request.getUserIdSnapshot(), currentUserId());
		int maxSelections = 1;
		if (AgentTypeConstant.isOrchestrator(owner.getAgentType())) {
			AgentOrchestrationPolicy policy = orchestrationPolicyMapper.findByAgentId(owner.getId());
			if (policy != null && Boolean.TRUE.equals(policy.getEnabled())
					&& policy.getMaxCollaboratorsPerRun() != null) {
				maxSelections = Math.max(1, policy.getMaxCollaboratorsPerRun());
			}
		}
		Instant deadline = request.getRuntimeDeadline() == null ? Instant.now().plus(engine.totalTimeout())
				: Instant.now().plus(request.getRuntimeDeadline().remaining());
		Long sessionId = parseLong(request.getThreadId());
		DataChatTurn previous = turnMapper.findRecentSuccessfulSingleSelection(owner.getId(), sessionId,
				parseLong(userId), Instant.now().minus(Duration.ofMinutes(30)));
		RouteTargetRef previousTarget = previousTarget(previous);
		return new RouteContext(tenantId, owner.getId(), owner.getAgentType(), sessionId, userId,
				request.getRuntimeRequestId(), effectiveQuery(request), previous == null ? null : previous.getQuestion(),
				previousTarget, request.getExplicitRouteTarget(), deadline, maxSelections,
				RouteScope.forOwnerType(owner.getAgentType()), request.getPinnedSkillVersionIds());
	}

	private RouteTargetRef previousTarget(DataChatTurn turn) {
		if (turn == null || !StringUtils.hasText(turn.getRouteTargetType()) || turn.getRouteTargetId() == null) {
			return null;
		}
		try {
			return new RouteTargetRef(RouteTargetType.valueOf(turn.getRouteTargetType()), turn.getRouteTargetId(),
					turn.getRouteTargetVersionId(), null);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private RouteDecision platformIntent(RouteContext context, DataAgent owner, long started) {
		String compact = compact(context.query());
		if (CAPABILITY_QUESTIONS.contains(compact)) {
			String description = StringUtils.hasText(owner.getDescription()) ? owner.getDescription().trim()
					: "\u8bf7\u8f93\u5165\u5177\u4f53\u7684\u4e1a\u52a1\u95ee\u9898\u3002";
			return new RouteDecision(RouteDecisionType.DIRECT, "CAPABILITY_INTENT", RouteDegradeMode.NONE, List.of(),
					List.of(), description, false, timing(started, 0L, RouteTiming.empty()));
		}
		if (CANCEL_COMMANDS.contains(compact)) {
			return new RouteDecision(RouteDecisionType.DIRECT, "NO_ACTIVE_FLOW", RouteDegradeMode.NONE, List.of(),
					List.of(), "\u5f53\u524d\u6ca1\u6709\u8fdb\u884c\u4e2d\u7684\u6d41\u7a0b\u3002", false,
					timing(started, 0L, RouteTiming.empty()));
		}
		return null;
	}

	private void applyCancelAction(AgentRequest request) {
		if (request.getFlowAction() == null && CANCEL_COMMANDS.contains(compact(request.getQuery()))) {
			request.setFlowAction(new FlowAction("request-cancel", "CANCEL", true, java.util.Map.of()));
		}
	}

	private void validateOwner(RouteContext context, DataAgent owner) {
		if (context == null || owner == null || owner.getId() == null
				|| !Objects.equals(context.ownerAgentId(), owner.getId())
				|| !Objects.equals(context.tenantId(), owner.getTenantId())
				|| Boolean.TRUE.equals(owner.getDeleted()) || !"published".equalsIgnoreCase(owner.getStatus())) {
			throw new RouteStageException("ROUTE_OWNER_INVALID", "Route owner eligibility check failed");
		}
	}

	private RouteDecision clarify(String reasonCode, long started) {
		return RouteDecision.clarify(List.of(), reasonCode, RouteDegradeMode.NONE, false,
				timing(started, 0L, RouteTiming.empty()));
	}

	private RouteDecision withTiming(RouteDecision decision, long started, long candidateLoadMs) {
		return new RouteDecision(decision.decision(), decision.reasonCode(), decision.degradeMode(),
				decision.selections(), decision.suggestions(), decision.directText(), decision.modelInvoked(),
				timing(started, candidateLoadMs, decision.timing()), decision.clarification(), decision.plan());
	}

	private String effectiveQuery(AgentRequest request) {
		if (request == null) {
			return null;
		}
		if (StringUtils.hasText(request.getEffectiveRoutingQuery())) {
			return request.getEffectiveRoutingQuery();
		}
		String original = StringUtils.hasText(request.getQuery()) ? request.getQuery().trim() : "";
		String feedback = StringUtils.hasText(request.getHumanFeedbackContent())
				? request.getHumanFeedbackContent().trim() : "";
		return StringUtils.hasText(feedback) ? (original + "\n" + feedback).trim() : original;
	}

	private RouteTiming timing(long started) {
		return new RouteTiming(0L, 0L, 0L, 0L, elapsedMs(started));
	}

	private RouteTiming timing(long started, long candidateLoadMs, RouteTiming routeTiming) {
		RouteTiming safe = routeTiming == null ? RouteTiming.empty() : routeTiming;
		return new RouteTiming(candidateLoadMs, safe.lexicalMs(), safe.vectorMs(), safe.modelMs(), elapsedMs(started));
	}

	private String compact(String value) {
		return value == null ? "" : value.trim().toLowerCase().replaceAll("[\\s,.!?\\u3002\\uff0c\\uff01\\uff1f]", "");
	}

	private String currentTenantId() {
		try {
			return authenticationContext.tenantId();
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	private String currentUserId() {
		try {
			return authenticationContext.userId();
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	private String firstText(String first, String second) {
		return StringUtils.hasText(first) ? first.trim() : StringUtils.hasText(second) ? second.trim() : null;
	}

	private Long parseLong(String value) {
		try {
			return StringUtils.hasText(value) ? Long.valueOf(value.trim()) : null;
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private long elapsedMs(long started) {
		return Duration.ofNanos(System.nanoTime() - started).toMillis();
	}
}
