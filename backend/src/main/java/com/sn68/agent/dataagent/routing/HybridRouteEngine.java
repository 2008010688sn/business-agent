/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing;

import com.sn68.agent.dataagent.constant.AgentTypeConstant;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.routing.RouteScorer.ScoredCandidate;
import com.sn68.agent.dataagent.routing.model.RouteCandidate;
import com.sn68.agent.dataagent.routing.model.DelegationMode;
import com.sn68.agent.dataagent.routing.model.RouteContext;
import com.sn68.agent.dataagent.routing.model.RouteDecision;
import com.sn68.agent.dataagent.routing.model.RouteDecisionType;
import com.sn68.agent.dataagent.routing.model.RouteDegradeMode;
import com.sn68.agent.dataagent.routing.model.RouteClarification;
import com.sn68.agent.dataagent.routing.model.RouteClarificationOption;
import com.sn68.agent.dataagent.routing.model.RouteModelResult;
import com.sn68.agent.dataagent.routing.model.RoutePlan;
import com.sn68.agent.dataagent.routing.model.RoutePlanStep;
import com.sn68.agent.dataagent.routing.model.RoutePolicy;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import com.sn68.agent.dataagent.routing.model.RouteScope;
import com.sn68.agent.dataagent.routing.model.RouteSelection;
import com.sn68.agent.dataagent.routing.model.RouteSemanticMatch;
import com.sn68.agent.dataagent.routing.model.RouteSuggestion;
import com.sn68.agent.dataagent.routing.model.RouteTargetRef;
import com.sn68.agent.dataagent.routing.model.RouteTargetType;
import com.sn68.agent.dataagent.routing.model.RouteTiming;
import com.sn68.agent.dataagent.skill.SkillExecutionMode;
import com.sn68.agent.dataagent.skill.SkillKind;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
public class HybridRouteEngine {

	private static final String ROUTE_DURATION_METRIC = "data.agent.routing.duration";

	private static final String ROUTE_DECISION_METRIC = "data.agent.routing.decisions";

	private static final String ROUTE_STAGE_DURATION_METRIC = "data.agent.routing.stage.duration";

	private static final String ROUTE_BUDGET_EXHAUSTED_METRIC = "data.agent.routing.budget.exhausted";

	private static final String ROUTE_MODEL_TIMEOUT_METRIC = "data.agent.routing.model.timeouts";

	private static final String ROUTE_MODEL_DEGRADED_METRIC = "data.agent.routing.model.degraded";

	public static final int SMALL_CANDIDATE_LIMIT = 8;

	public static final int LEXICAL_TOP_K = 4;

	public static final int VECTOR_TOP_K = 6;

	public static final int MODEL_CANDIDATE_LIMIT = 5;

	public static final int CLARIFICATION_LIMIT = 2;

	public static final int RRF_K = 60;

	private static final String CLARIFY_TITLE = "请补充业务信息";

	private static final String CLARIFY_PROMPT = "请补充业务对象、时间范围、数据口径或希望执行的操作，我会据此继续判断。";

	private static final String DEGRADED_CLARIFY_TITLE = "智能匹配能力受限，请补充业务信息";

	private static final String DEGRADED_CLARIFY_PROMPT = "智能匹配能力当前受限，本轮候选可能不完整，暂不代你选择。"
			+ "请补充业务对象、时间范围、数据口径或希望执行的操作，或稍后重试；若持续如此请联系管理员。";

	private final RouteScorer scorer;

	private final RouteSemanticService semanticService;

	private final RouteModelClient modelClient;

	private final DataAgentProperties.Routing routing;

	private final MeterRegistry meterRegistry;

	public HybridRouteEngine(RouteScorer scorer, RouteSemanticService semanticService, RouteModelClient modelClient,
			DataAgentProperties properties, MeterRegistry meterRegistry) {
		this.scorer = scorer;
		this.semanticService = semanticService;
		this.modelClient = modelClient;
		this.routing = properties.getRuntime().getRouting();
		this.meterRegistry = meterRegistry;
		log.info("Hybrid route budgets configured, totalMs={}, vectorMs={}, modelMs={}, modelMinStartMs={}, "
				+ "finishBufferMs={}", routing.getTotalTimeout().toMillis(), routing.getVectorTimeout().toMillis(),
				routing.getModelTimeout().toMillis(), routing.getModelMinStart().toMillis(),
				routing.getFinishBuffer().toMillis());
	}

	public Duration totalTimeout() {
		return routing.getTotalTimeout();
	}

	public RouteDecision route(RouteContext context, RoutePolicy policy, List<RouteCandidate> candidates) {
		return route(context, policy, candidates, null);
	}

	public RouteDecision route(RouteContext context, RoutePolicy policy, List<RouteCandidate> candidates,
			RouteDiagnostics diagnostics) {
		return routeInternal(context, policy, candidates, diagnostics);
	}

	RouteDecision selectExplicit(RouteCandidate candidate) {
		return selected(candidate, "EXPLICIT_TARGET", false, new StageTiming(System.nanoTime()));
	}

	private RouteDecision routeInternal(RouteContext context, RoutePolicy policy, List<RouteCandidate> candidates,
			RouteDiagnostics diagnostics) {
		long started = System.nanoTime();
		StageTiming stageTiming = new StageTiming(started);
		if (context == null || policy == null || policy.profileId() == null) {
			return unavailable("PROFILE_UNAVAILABLE", RouteDegradeMode.NONE, stageTiming);
		}
		if (candidates == null || candidates.isEmpty()) {
			return RouteDecision.noMatch(stageTiming.finish());
		}
		RouteScope scope = context.effectiveScope();
		if (candidates.stream().anyMatch(candidate -> candidate == null || candidate.target() == null
				|| candidate.target().targetType() != scope.targetType())) {
			return unavailable("ROUTE_SCOPE_VIOLATION", RouteDegradeMode.NONE, stageTiming);
		}
		Instant deadline = effectiveDeadline(context.deadline());
		logBudget(context.deadline(), deadline);
		if (!hasStageBudget(deadline)) {
			return unavailable("ROUTE_DEADLINE_EXHAUSTED", RouteDegradeMode.NONE, stageTiming);
		}

		long lexicalStarted = System.nanoTime();
		List<ScoredCandidate> scored = scorer.score(context, candidates);
		if (diagnostics != null) {
			diagnostics.captureLexical(scored);
		}
		stageTiming.lexicalMs = elapsedMs(lexicalStarted);
		List<ScoredCandidate> eligible = scored.stream().filter(value -> !value.excluded()).toList();
		List<ScoredCandidate> exact = eligible.stream().filter(ScoredCandidate::exact).toList();
		if (exact.size() == 1) {
			ScoredCandidate candidate = exact.get(0);
			return candidate.candidate().risk() == RouteRisk.UNKNOWN
					? clarify(context, List.of(candidate), "AMBIGUOUS_CANDIDATES", RouteDegradeMode.NONE, false,
							stageTiming)
					: selected(candidate.candidate(), "UNIQUE_EXACT", false, stageTiming);
		}

		RouteDecision compoundPlan = tryCompoundCollaboratorPlan(context, candidates, "COMPOUND_INTENT_REPAIRED", false,
				stageTiming);
		if (compoundPlan != null) {
			return compoundPlan;
		}
		List<ScoredCandidate> lexicalCandidates = eligible.stream().filter(ScoredCandidate::relevant).toList();
		ScoredCandidate lexicalWinner = safeReadOnlyWinner(lexicalCandidates, policy.lexicalAutoSelectEnabled(),
				policy.lexicalMinScore(), policy.lexicalMinGap());
		if (exact.isEmpty() && lexicalWinner != null) {
			return selected(lexicalWinner.candidate(), "LEXICAL_HIGH_CONFIDENCE", false, stageTiming);
		}
		List<ScoredCandidate> knowledgeCandidates = knowledgeCandidates(context, eligible);
		if (eligible.size() == 1 && knowledgeCandidates.size() == 1) {
			return selected(knowledgeCandidates.get(0).candidate(), "KNOWLEDGE_SINGLE_CANDIDATE", false, stageTiming);
		}

		List<RouteSemanticMatch> semanticMatches = List.of();
		List<ScoredCandidate> ranked = lexicalCandidates;
		Set<RouteTargetRef> semanticRelevant = Set.of();
		List<ScoredCandidate> semanticCandidates = eligible.stream()
			.filter(value -> hasUsableArtifact(policy, value))
			.toList();
		if (policy.semanticRecallEnabled() && !policy.semanticRuntimeReady()) {
			logVectorDegrade(context, policy, "ROUTE_SEMANTIC_NOT_READY", eligible, lexicalCandidates, null);
			return vectorFallback(context, lexicalCandidates, "ROUTE_SEMANTIC_NOT_READY", stageTiming);
		}
		if (policy.semanticRecallEnabled() && semanticCandidates.size() < eligible.size()) {
			logVectorDegrade(context, policy, "ROUTE_ARTIFACT_NOT_READY", eligible, lexicalCandidates, null);
			return vectorFallback(context, lexicalCandidates, "ROUTE_ARTIFACT_NOT_READY", stageTiming);
		}
		if (policy.semanticRecallEnabled()) {
			long vectorStarted = System.nanoTime();
			try {
				if (!hasStageBudget(deadline)) {
					return deadlineFallback(stageTiming);
				}
				semanticMatches = semanticService.search(context, policy,
						semanticCandidates.stream().map(ScoredCandidate::candidate).toList(), VECTOR_TOP_K,
						stageTimeout(deadline, routing.getVectorTimeout()));
				stageTiming.vectorMs = elapsedMs(vectorStarted);
			}
			catch (RuntimeException ex) {
				stageTiming.vectorMs = elapsedMs(vectorStarted);
				String reasonCode = vectorFailureCode(ex);
				logVectorDegrade(context, policy, reasonCode, eligible, lexicalCandidates, ex);
				return vectorFallback(context, lexicalCandidates, reasonCode, stageTiming);
			}
			ranked = fuse(eligible, semanticMatches);
			if (diagnostics != null) {
				diagnostics.captureSemantic(ranked, semanticMatches);
			}
			ScoredCandidate semanticWinner = safeSemanticWinner(ranked, semanticMatches, policy);
			if (exact.isEmpty() && semanticWinner != null) {
				return selected(semanticWinner.candidate(), "SEMANTIC_HIGH_CONFIDENCE", false, stageTiming);
			}
			semanticRelevant = semanticMatches.stream()
				.filter(match -> match.score() >= policy.vectorRecallThreshold())
				.map(RouteSemanticMatch::target)
				.collect(Collectors.toSet());
		}
		if (knowledgeCandidates.size() >= 2) {
			return clarify(context, knowledgeCandidates, "KNOWLEDGE_CANDIDATES_AMBIGUOUS", RouteDegradeMode.NONE, false,
					stageTiming);
		}
		List<ScoredCandidate> modelCandidates = modelCandidates(exact.isEmpty() ? ranked : exact, semanticRelevant);
		if (modelCandidates.isEmpty()) {
			RouteDecision singleBound = selectSingleBoundReadOnlySkill(context, eligible, stageTiming);
			if (singleBound != null) {
				return singleBound;
			}
			return RouteDecision.noMatch(stageTiming.finish());
		}
		if (modelCandidates.size() < 2 || !policy.modelDisambiguationEnabled()) {
			if (modelCandidates.size() >= 2 && policy.semanticAutoSelectEnabled()) {
				return unavailable("ROUTE_SEMANTIC_AMBIGUOUS", RouteDegradeMode.NONE, stageTiming);
			}
			RouteDecision unique = selectUniqueAutoSelectable(context, modelCandidates, "UNIQUE_RELEVANT_CANDIDATE",
					stageTiming);
			if (unique != null) {
				return unique;
			}
			RouteDecision single = selectSingleReadOnlyCandidate(context, modelCandidates, stageTiming);
			if (single != null) {
				return single;
			}
			return clarify(context, modelCandidates, "AMBIGUOUS_CANDIDATES", RouteDegradeMode.NONE, false, stageTiming);
		}
		if (!policy.routeModelRuntimeReady()) {
			return modelFailureDecision(context, policy, modelCandidates, exact, semanticMatches,
					"ROUTE_MODEL_UNAVAILABLE", false, stageTiming);
		}
		Duration modelRemaining = remaining(deadline);
		Duration effectiveModelTimeout;
		try {
			effectiveModelTimeout = stageTimeout(modelRemaining, routing.getModelTimeout());
		}
		catch (RouteStageException ex) {
			return modelFailureDecision(context, policy, modelCandidates, exact, semanticMatches,
					"ROUTE_MODEL_UNAVAILABLE", false, stageTiming);
		}
		if (effectiveModelTimeout.compareTo(routing.getModelMinStart()) < 0) {
			return modelFailureDecision(context, policy, modelCandidates, exact, semanticMatches,
					"ROUTE_MODEL_UNAVAILABLE", false, stageTiming);
		}
		logModelBudget(modelRemaining, effectiveModelTimeout);

		long modelStarted = System.nanoTime();
		try {
			RouteModelResult modelResult = modelClient.disambiguate(context, policy, modelCandidates,
					effectiveModelTimeout);
			stageTiming.modelMs = elapsedMs(modelStarted);
			return applyModelResult(context, policy, modelCandidates, modelResult, stageTiming);
		}
		catch (RouteStageException ex) {
			stageTiming.modelMs = elapsedMs(modelStarted);
			String reasonCode = modelFailureCode(ex.reasonCode());
			return modelFailureDecision(context, policy, modelCandidates, exact, semanticMatches, reasonCode, true,
					stageTiming);
		}
		catch (RuntimeException ex) {
			stageTiming.modelMs = elapsedMs(modelStarted);
			return modelFailureDecision(context, policy, modelCandidates, exact, semanticMatches,
					"ROUTE_MODEL_UNAVAILABLE", true, stageTiming);
		}
	}

	private String vectorFailureCode(RuntimeException ex) {
		if (ex instanceof RouteStageException stage && stage.reasonCode() != null && !stage.reasonCode().isBlank()) {
			return stage.reasonCode();
		}
		return "VECTOR_UNAVAILABLE";
	}

	private String modelFailureCode(String reasonCode) {
		if ("MODEL_TIMEOUT".equals(reasonCode)) {
			return "ROUTE_MODEL_TIMEOUT";
		}
		if ("MODEL_INVALID_OUTPUT".equals(reasonCode)) {
			return "ROUTE_MODEL_INVALID_OUTPUT";
		}
		if (reasonCode != null && reasonCode.startsWith("ROUTE_MODEL_")) {
			return reasonCode;
		}
		return "ROUTE_MODEL_UNAVAILABLE";
	}

	private RouteDecision applyModelResult(RouteContext context, RoutePolicy policy, List<ScoredCandidate> candidates,
			RouteModelResult result, StageTiming timing) {
		if (result == null || result.decision() == null || result.confidence() < 0D || result.confidence() > 1D) {
			throw new RouteStageException("MODEL_INVALID_OUTPUT", "Route model returned an invalid result");
		}
		Map<RouteTargetRef, RouteCandidate> allowed = candidates.stream().map(ScoredCandidate::candidate)
			.collect(Collectors.toMap(RouteCandidate::target, Function.identity(), (left, right) -> left,
					LinkedHashMap::new));
		List<RouteCandidate> selected = result.targets().stream().map(allowed::get).filter(Objects::nonNull).distinct()
			.toList();
		if (selected.size() != result.targets().size()) {
			throw new RouteStageException("MODEL_INVALID_OUTPUT", "Route model selected an unknown candidate");
		}
		if (result.decision() == RouteDecisionType.CLARIFY) {
			return clarify(context, selected.isEmpty() ? candidates : scored(selected, candidates),
					"AMBIGUOUS_CANDIDATES", RouteDegradeMode.NONE, true, timing);
		}
		if (result.confidence() < policy.modelConfidenceThreshold()) {
			return clarify(context, candidates, "AMBIGUOUS_CANDIDATES", RouteDegradeMode.NONE, true, timing);
		}
		if (result.decision() == RouteDecisionType.NO_MATCH) {
			return new RouteDecision(RouteDecisionType.NO_MATCH, "NO_RELEVANT_SIGNAL", RouteDegradeMode.NONE, List.of(),
					List.of(), null, true, timing.finish());
		}
		if (result.decision() == RouteDecisionType.SELECT && selected.size() == 1
				&& selected.get(0).risk() != RouteRisk.UNKNOWN) {
			RouteDecision repaired = compileCompoundPlan(context, candidates, "COMPOUND_INTENT_REPAIRED", true, timing);
			if (repaired != null) {
				return repaired;
			}
			return selected(selected.get(0), "MODEL_SELECTED", true, timing);
		}
		if (result.decision() == RouteDecisionType.MULTI_SELECT && validMultiSelect(context, selected)) {
			RoutePlan plan = result.plan();
			if (plan == null || plan.steps().isEmpty()) {
				RouteDecision compiled = compileCompoundPlan(context, candidates, "COMPOUND_INTENT_REPAIRED", true, timing);
				if (compiled != null) {
					return compiled;
				}
				throw new RouteStageException("MODEL_INVALID_OUTPUT", "Compound route model result requires a plan");
			}
			try {
				return planDecision(selected, plan, "MODEL_MULTI_SELECTED", RouteDegradeMode.NONE, true, timing);
			}
			catch (IllegalArgumentException ex) {
				RouteDecision compiled = compileCompoundPlan(context, candidates, "COMPOUND_INTENT_REPAIRED", true, timing);
				if (compiled != null) {
					return compiled;
				}
				throw new RouteStageException("MODEL_INVALID_OUTPUT", "Compound route model result contains an invalid plan", ex);
			}
		}
		throw new RouteStageException("MODEL_INVALID_OUTPUT", "Route model decision violates route policy");
	}

	private boolean validMultiSelect(RouteContext context, List<RouteCandidate> selected) {
		return selected.size() >= 2 && context.maxSelections() >= selected.size() && hasCompoundIntent(context.query())
				&& context.effectiveScope() == RouteScope.COLLABORATOR_SCOPE
				&& selected.stream().allMatch(candidate -> candidate.target().targetType() == RouteTargetType.COLLABORATOR
						&& candidate.risk() != RouteRisk.UNKNOWN);
	}

	private boolean hasCompoundIntent(String query) {
		return compoundIntent(query) != CompoundIntent.SINGLE;
	}

	private boolean hasDeterministicParallelIntent(String query) {
		return compoundIntent(query) == CompoundIntent.PARALLEL;
	}

	private CompoundIntent compoundIntent(String query) {
		if (query == null) {
			return CompoundIntent.SINGLE;
		}
		String normalized = query.toLowerCase();
		boolean dependency = containsAny(normalized, "这些", "上述", "前述", "其中", "先", "再", "然后", "随后", "接着",
				"完成后", "后生成", "后汇总", " then ", " after ");
		boolean parallel = containsAny(normalized, "同时", "分别", " and ");
		boolean conjunction = containsAny(normalized, "以及", "并且");
		if (dependency) {
			return CompoundIntent.SEQUENTIAL;
		}
		if (parallel || conjunction) {
			return CompoundIntent.PARALLEL;
		}
		return CompoundIntent.SINGLE;
	}

	private boolean containsAny(String text, String... tokens) {
		for (String token : tokens) {
			if (text.contains(token)) {
				return true;
			}
		}
		return false;
	}

	private enum CompoundIntent {
		SINGLE, PARALLEL, SEQUENTIAL
	}

	private List<ScoredCandidate> fuse(List<ScoredCandidate> lexical, List<RouteSemanticMatch> semantic) {
		List<ScoredCandidate> lexicalRanked = lexical.stream().filter(ScoredCandidate::relevant).toList();
		List<RouteSemanticMatch> semanticRanked = semantic == null ? List.of() : semantic.stream()
			.filter(match -> match != null && match.target() != null)
			.sorted(Comparator.comparingDouble(RouteSemanticMatch::score).reversed())
			.toList();
		Map<RouteTargetRef, ScoredCandidate> eligible = lexical.stream()
			.collect(Collectors.toMap(value -> value.candidate().target(), Function.identity(), (left, right) -> left));
		Set<RouteTargetRef> retained = lexical.size() <= SMALL_CANDIDATE_LIMIT ? eligible.keySet()
				: java.util.stream.Stream.concat(lexicalRanked.stream().limit(LEXICAL_TOP_K)
					.map(value -> value.candidate().target()), semanticRanked.stream().limit(VECTOR_TOP_K)
					.map(RouteSemanticMatch::target)).collect(Collectors.toSet());
		Map<RouteTargetRef, Integer> lexicalRanks = ranks(
				lexicalRanked.stream().map(value -> value.candidate().target()).toList());
		Map<RouteTargetRef, Integer> semanticRanks = ranks(semanticRanked.stream().map(RouteSemanticMatch::target).toList());
		return retained.stream().map(eligible::get).filter(Objects::nonNull)
			.sorted(Comparator.comparingDouble((ScoredCandidate value) -> rrf(value.candidate().target(), lexicalRanks,
					semanticRanks)).reversed().thenComparing(scoredComparator()))
			.toList();
	}

	private double rrf(RouteTargetRef target, Map<RouteTargetRef, Integer> lexicalRanks,
			Map<RouteTargetRef, Integer> semanticRanks) {
		return rankScore(lexicalRanks.get(target)) + rankScore(semanticRanks.get(target));
	}

	private double rankScore(Integer rank) {
		return rank == null ? 0D : 1D / (RRF_K + rank);
	}

	private Map<RouteTargetRef, Integer> ranks(List<RouteTargetRef> targets) {
		Map<RouteTargetRef, Integer> result = new LinkedHashMap<>();
		for (int index = 0; index < targets.size(); index++) {
			result.putIfAbsent(targets.get(index), index + 1);
		}
		return result;
	}

	private ScoredCandidate safeReadOnlyWinner(List<ScoredCandidate> candidates, boolean enabled, double minScore,
			double minGap) {
		if (!enabled || candidates.isEmpty()) {
			return null;
		}
		ScoredCandidate first = candidates.get(0);
		double second = candidates.size() > 1 ? candidates.get(1).lexicalScore() : 0D;
		return autoSelectAllowed(first.candidate()) && first.lexicalScore() >= minScore
				&& first.lexicalScore() - second >= minGap ? first : null;
	}

	private ScoredCandidate safeSemanticWinner(List<ScoredCandidate> candidates, List<RouteSemanticMatch> matches,
			RoutePolicy policy) {
		if (!policy.semanticAutoSelectEnabled() || candidates.isEmpty() || matches == null || matches.isEmpty()) {
			return null;
		}
		Map<RouteTargetRef, Double> scores = matches.stream().collect(Collectors.toMap(RouteSemanticMatch::target,
				RouteSemanticMatch::score, Math::max));
		Set<RouteTargetRef> retainedTargets = candidates.stream().map(candidate -> candidate.candidate().target())
			.collect(Collectors.toSet());
		List<RouteSemanticMatch> semanticRanked = scores.entrySet()
			.stream()
			.filter(entry -> retainedTargets.contains(entry.getKey()))
			.map(entry -> new RouteSemanticMatch(entry.getKey(), entry.getValue()))
			.sorted(Comparator.comparingDouble(RouteSemanticMatch::score)
				.reversed()
				.thenComparing(match -> match.target().targetType())
				.thenComparing(match -> match.target().targetId(), Comparator.nullsLast(Long::compareTo)))
			.toList();
		if (semanticRanked.isEmpty()) {
			return null;
		}
		ScoredCandidate fusedWinner = candidates.get(0);
		RouteSemanticMatch semanticWinner = semanticRanked.get(0);
		if (!Objects.equals(fusedWinner.candidate().target(), semanticWinner.target())) {
			return null;
		}
		double secondScore = semanticRanked.size() > 1 ? semanticRanked.get(1).score() : 0D;
		return autoSelectAllowed(fusedWinner.candidate())
				&& semanticWinner.score() >= policy.vectorAutoSelectThreshold()
				&& semanticWinner.score() - secondScore >= policy.vectorMinGap() ? fusedWinner : null;
	}

	private boolean autoSelectAllowed(RouteCandidate candidate) {
		if (candidate == null) {
			return false;
		}
		if (candidate.target() != null && candidate.target().targetType() == RouteTargetType.COLLABORATOR) {
			return candidate.delegationMode() == DelegationMode.AUTO_READ_ONLY
					&& candidate.risk() == RouteRisk.READ_ONLY;
		}
		return candidate.risk() == RouteRisk.READ_ONLY || isAutoSelectableFlow(candidate);
	}

	private List<ScoredCandidate> modelCandidates(List<ScoredCandidate> candidates,
			Set<RouteTargetRef> semanticRelevant) {
		return candidates.stream()
			.filter(candidate -> candidate.relevant() || semanticRelevant.contains(candidate.candidate().target()))
			.limit(MODEL_CANDIDATE_LIMIT)
			.toList();
	}

	private RouteDecision selected(RouteCandidate candidate, String reasonCode, boolean modelInvoked,
			StageTiming timing) {
		if (candidate == null || candidate.risk() == RouteRisk.UNKNOWN) {
			return unavailable("ROUTE_CAPABILITY_UNAVAILABLE", RouteDegradeMode.NONE, timing);
		}
		RouteSelection selection = RouteSelection.from(candidate);
		if (requiresConfirmation(candidate)) {
			return RouteDecision.confirmRequired(List.of(selection), confirmation("本次请求可能产生业务变更，请确认后继续"),
					RoutePlan.single(selection, null, "Return the result of the selected business operation."),
					reasonCode + "_CONFIRM_REQUIRED", timing.finish());
		}
		return RouteDecision.select(candidate, reasonCode, RouteDegradeMode.NONE, modelInvoked, timing.finish());
	}

	private boolean requiresConfirmation(RouteCandidate candidate) {
		if (candidate == null) {
			return false;
		}
		if (candidate.target() != null && candidate.target().targetType() == RouteTargetType.COLLABORATOR) {
			return candidate.delegationMode() == DelegationMode.PLAN_CONFIRM;
		}
		return candidate.risk() == RouteRisk.WRITE || candidate.risk() == RouteRisk.DELEGATED
				|| candidate.risk() == RouteRisk.FLOW && !isAutoSelectableFlow(candidate);
	}

	private boolean isAutoSelectableFlow(RouteCandidate candidate) {
		return candidate != null && candidate.risk() == RouteRisk.FLOW
				&& candidate.target() != null && candidate.target().targetType() == RouteTargetType.SKILL
				&& List.of(SkillKind.ACTION.name(), SkillKind.ORCHESTRATION.name()).contains(candidate.skillKind())
				&& SkillExecutionMode.FLOW.name().equals(candidate.executionMode())
				&& candidate.rules().allowFlowAutoSelect();
	}

	private RouteClarification confirmation(String prompt) {
		return new RouteClarification(prompt, "请确认本次操作", List.of(
				new RouteClarificationOption("confirm", "确认继续", "confirm", null),
				new RouteClarificationOption("cancel", "取消操作", "cancel", null)), false, "high");
	}

	private List<ScoredCandidate> knowledgeCandidates(RouteContext context, List<ScoredCandidate> candidates) {
		if (context == null || !AgentTypeConstant.KNOWLEDGE_BASE.equals(
				AgentTypeConstant.normalize(context.ownerAgentType())) || candidates == null || candidates.isEmpty()) {
			return List.of();
		}
		return candidates.stream().filter(value -> isKnowledgeCandidate(value.candidate())).toList();
	}

	private boolean isKnowledgeCandidate(RouteCandidate candidate) {
		return candidate != null && candidate.target() != null
				&& candidate.target().targetType() == RouteTargetType.SKILL
				&& SkillKind.QA.name().equals(candidate.skillKind())
				&& SkillExecutionMode.KNOWLEDGE.name().equals(candidate.executionMode());
	}

	private List<ScoredCandidate> scored(List<RouteCandidate> selected, List<ScoredCandidate> candidates) {
		Map<RouteTargetRef, ScoredCandidate> byTarget = candidates.stream()
			.collect(Collectors.toMap(value -> value.candidate().target(), Function.identity()));
		return selected.stream().map(candidate -> byTarget.get(candidate.target())).filter(Objects::nonNull).toList();
	}

	private RouteDecision deadlineFallback(StageTiming timing) {
		return unavailable("ROUTE_DEADLINE_EXHAUSTED", RouteDegradeMode.NONE, timing);
	}

	private RouteDecision vectorFallback(RouteContext context, List<ScoredCandidate> lexicalCandidates,
			String reasonCode, StageTiming timing) {
		if (!lexicalCandidates.isEmpty()) {
			RouteDecision unique = selectUniqueAutoSelectable(context, lexicalCandidates, "UNIQUE_RELEVANT_CANDIDATE",
					timing);
			if (unique != null) {
				return unique;
			}
			RouteDecision single = selectSingleReadOnlyCandidate(context, lexicalCandidates, timing);
			if (single != null) {
				return single;
			}
			return clarify(context, lexicalCandidates, reasonCode, RouteDegradeMode.DEGRADED_VECTOR, false, timing);
		}
		return unavailable(reasonCode, RouteDegradeMode.DEGRADED_VECTOR, timing);
	}

	private boolean hasUsableArtifact(RoutePolicy policy, ScoredCandidate scored) {
		return scored.candidate().routeArtifactId() != null
				&& Objects.equals(policy.embeddingFingerprint(), scored.candidate().embeddingFingerprint());
	}

	/**
	 * 语义召回已开启却没能跑完时的唯一可观测入口：词法侧还有候选就只是降级澄清，词法侧为空则整轮
	 * 路由不可用，两者对运维的含义完全不同，必须在同一条日志里区分出来。
	 */
	private void logVectorDegrade(RouteContext context, RoutePolicy policy, String reasonCode,
			List<ScoredCandidate> eligible, List<ScoredCandidate> lexicalCandidates, RuntimeException cause) {
		List<String> artifactGaps = eligible.stream()
			.filter(value -> !hasUsableArtifact(policy, value))
			.map(value -> describeArtifactGap(value.candidate()))
			.toList();
		log.warn("Route vector stage degraded reasonCode={}, routeUnavailable={}, tenantId={}, profileId={}, "
				+ "eligibleCount={}, lexicalRelevantCount={}, artifactGaps={}, embeddingModelConfigId={}, "
				+ "embeddingFingerprint={}", reasonCode, lexicalCandidates.isEmpty(), context.tenantId(),
				policy.profileId(), eligible.size(), lexicalCandidates.size(), artifactGaps,
				policy.embeddingModelConfigId(), policy.embeddingFingerprint(), cause);
	}

	private String describeArtifactGap(RouteCandidate candidate) {
		return candidate.target().targetType() + ":" + candidate.target().targetId() + ":"
				+ candidate.target().targetVersionId() + "(artifactId=" + candidate.routeArtifactId()
				+ ", embeddingFingerprint=" + candidate.embeddingFingerprint() + ")";
	}

	/**
	 * 澄清话术按 degradeMode 分流：候选歧义是正常业务追问，降级则必须让用户看出"智能匹配当前受限、
	 * 候选可能不完整"，否则故障会伪装成连续几轮正常追问而无人察觉。降级话术只描述能力受限，不携带
	 * 内部失败码，具体失败码走 route_reason_code 诊断通道。
	 * 业务选项必须可点选；禁止空 options 只丢一句「请补充业务对象」。
	 */
	private RouteDecision clarify(RouteContext context, List<ScoredCandidate> candidates, String reasonCode,
			RouteDegradeMode degradeMode, boolean modelInvoked, StageTiming timing) {
		List<ScoredCandidate> usable = candidates == null ? List.of()
				: candidates.stream().filter(value -> value != null && !value.excluded()).toList();
		List<RouteSuggestion> suggestions = usable.stream()
			.limit(CLARIFICATION_LIMIT)
			.map(value -> new RouteSuggestion(value.candidate().name(), value.candidate().description(),
					RouteSelection.from(value.candidate())))
			.toList();
		boolean degraded = degradeMode != null && degradeMode != RouteDegradeMode.NONE;
		List<RouteClarificationOption> options = businessClarificationOptions(context, usable);
		String title = degraded ? DEGRADED_CLARIFY_TITLE : clarificationTitle(context, options);
		String prompt = degraded ? DEGRADED_CLARIFY_PROMPT : clarificationPrompt(context, options);
		RouteClarification clarification = new RouteClarification(prompt, title, options, true, "medium");
		return new RouteDecision(RouteDecisionType.CLARIFY, reasonCode, degradeMode, List.of(), suggestions, null,
				modelInvoked, timing.finish(), clarification, null);
	}

	private RouteDecision tryCompoundCollaboratorPlan(RouteContext context, List<RouteCandidate> pool,
			String reasonCode, boolean modelInvoked, StageTiming timing) {
		if (context == null || context.effectiveScope() != RouteScope.COLLABORATOR_SCOPE
				|| compoundIntent(context.query()) == CompoundIntent.SINGLE || pool == null) {
			return null;
		}
		List<RouteCandidate> collaborators = pool.stream()
			.filter(candidate -> candidate != null && candidate.target() != null
					&& candidate.target().targetType() == RouteTargetType.COLLABORATOR
					&& candidate.risk() != RouteRisk.UNKNOWN)
			.toList();
		if (collaborators.size() < 2) {
			return null;
		}
		List<String> clauses = splitTaskClauses(context.query());
		if (clauses.size() < 2) {
			return null;
		}
		List<RouteCandidate> remaining = new ArrayList<>(collaborators);
		List<RouteCandidate> ordered = new ArrayList<>();
		for (String clause : clauses) {
			if (remaining.isEmpty()) {
				break;
			}
			List<ScoredCandidate> scored = scorer.score(clauseContext(context, clause), remaining);
			ScoredCandidate winner = scored.stream().filter(ScoredCandidate::relevant).findFirst().orElse(null);
			if (winner == null) {
				continue;
			}
			ordered.add(winner.candidate());
			remaining.remove(winner.candidate());
		}
		if (ordered.size() < 2 || ordered.size() > context.maxSelections()) {
			return null;
		}
		return buildCompoundDecision(context, ordered, clauses, reasonCode, modelInvoked, timing);
	}

	private RouteDecision selectUniqueAutoSelectable(RouteContext context, List<ScoredCandidate> candidates,
			String reasonCode, StageTiming timing) {
		if (context == null || candidates == null || candidates.size() != 1 || hasCompoundIntent(context.query())) {
			return null;
		}
		RouteCandidate only = candidates.get(0).candidate();
		if (!autoSelectAllowed(only) || only.target() == null) {
			return null;
		}
		return selected(only, reasonCode, false, timing);
	}

	private RouteDecision selectSingleReadOnlyCandidate(RouteContext context, List<ScoredCandidate> candidates,
			StageTiming timing) {
		if (context == null || candidates == null || candidates.size() != 1 || hasCompoundIntent(context.query())) {
			return null;
		}
		RouteCandidate only = candidates.get(0).candidate();
		if (only == null || only.risk() != RouteRisk.READ_ONLY || isKnowledgeCandidate(only) || only.target() == null
				|| only.target().targetType() != RouteTargetType.COLLABORATOR) {
			return null;
		}
		return selected(only, "SINGLE_RELEVANT_CANDIDATE", false, timing);
	}

	private RouteDecision selectSingleBoundReadOnlySkill(RouteContext context, List<ScoredCandidate> eligible,
			StageTiming timing) {
		if (context == null || eligible == null || eligible.size() != 1 || isKnowledgeOwner(context)) {
			return null;
		}
		RouteCandidate only = eligible.get(0).candidate();
		if (only == null || only.risk() != RouteRisk.READ_ONLY || isKnowledgeCandidate(only)
				|| only.target() == null || only.target().targetType() != RouteTargetType.SKILL) {
			return null;
		}
		return selected(only, "SINGLE_BOUND_SKILL", false, timing);
	}

	private boolean isKnowledgeOwner(RouteContext context) {
		return context != null
				&& AgentTypeConstant.KNOWLEDGE_BASE.equals(AgentTypeConstant.normalize(context.ownerAgentType()));
	}

	private List<RouteClarificationOption> businessClarificationOptions(RouteContext context,
			List<ScoredCandidate> candidates) {
		List<RouteClarificationOption> options = new ArrayList<>();
		boolean compoundCollaborator = context != null && hasCompoundIntent(context.query())
				&& context.effectiveScope() == RouteScope.COLLABORATOR_SCOPE;
		if (compoundCollaborator && candidates.size() >= 1) {
			options.add(new RouteClarificationOption("both", "两个都要：按问法分步查询", "两个都要", null));
		}
		for (ScoredCandidate scored : candidates) {
			if (options.size() >= 6) {
				break;
			}
			RouteCandidate candidate = scored.candidate();
			String label = businessLabel(candidate);
			if (!StringUtils.hasText(label)) {
				continue;
			}
			String value = firstBusinessPhrase(candidate, label);
			String description = StringUtils.hasText(candidate.description()) ? candidate.description().trim() : value;
			options.add(new RouteClarificationOption(null, label, value, RouteSelection.from(candidate)));
		}
		if (options.size() < 2) {
			for (String phrase : rankingPhrases(candidates)) {
				if (options.size() >= 6) {
					break;
				}
				if (options.stream().anyMatch(option -> phrase.equals(option.label()) || phrase.equals(option.value()))) {
					continue;
				}
				options.add(new RouteClarificationOption(null, phrase, phrase, null));
			}
		}
		return options;
	}

	private String clarificationTitle(RouteContext context, List<RouteClarificationOption> options) {
		if (context != null && hasCompoundIntent(context.query())
				&& context.effectiveScope() == RouteScope.COLLABORATOR_SCOPE) {
			return "请确认查询范围";
		}
		if (options != null && !options.isEmpty()) {
			return "请选择要办理的事项";
		}
		return CLARIFY_TITLE;
	}

	private String clarificationPrompt(RouteContext context, List<RouteClarificationOption> options) {
		if (context != null && hasCompoundIntent(context.query())
				&& context.effectiveScope() == RouteScope.COLLABORATOR_SCOPE) {
			return "这次问法包含多件事。请点选要查的范围，也可以手写补充口径，例如「按用箱量」或「两个都要」。";
		}
		if (options != null && !options.isEmpty()) {
			return skillChoicePrompt(options);
		}
		return CLARIFY_PROMPT;
	}

	private String skillChoicePrompt(List<RouteClarificationOption> options) {
		List<String> labels = options.stream()
			.map(RouteClarificationOption::label)
			.filter(StringUtils::hasText)
			.distinct()
			.limit(4)
			.toList();
		if (labels.size() >= 2) {
			return "您是想办理「" + String.join("」，还是「", labels) + "」？请点选下面一项，或直接说明想办的事。";
		}
		if (labels.size() == 1) {
			return "请确认要办理「" + labels.get(0) + "」，或补充更具体的说明。";
		}
		return "这次有多项能力可能相关。请点选要办理的事项，或直接说明想办的事。";
	}

	private String businessLabel(RouteCandidate candidate) {
		if (candidate == null) {
			return null;
		}
		String name = candidate.name() == null ? "" : candidate.name().trim();
		if (name.toLowerCase().contains("skill")) {
			name = name.replaceAll("(?i)skill", "").trim();
		}
		return StringUtils.hasText(name) ? name : firstBusinessPhrase(candidate, null);
	}

	private String firstBusinessPhrase(RouteCandidate candidate, String fallback) {
		if (candidate != null && candidate.rules() != null) {
			for (String phrase : candidate.rules().phrases()) {
				if (StringUtils.hasText(phrase) && !phrase.toLowerCase().contains("skill")) {
					return phrase.trim();
				}
			}
		}
		return fallback;
	}

	private List<String> rankingPhrases(List<ScoredCandidate> candidates) {
		List<String> phrases = new ArrayList<>();
		for (ScoredCandidate scored : candidates) {
			if (scored.candidate() == null || scored.candidate().rules() == null) {
				continue;
			}
			for (String phrase : scored.candidate().rules().phrases()) {
				if (StringUtils.hasText(phrase) && !phrases.contains(phrase) && !phrase.toLowerCase().contains("skill")) {
					phrases.add(phrase.trim());
				}
			}
		}
		return phrases;
	}

	private RouteContext clauseContext(RouteContext context, String clause) {
		return new RouteContext(context.tenantId(), context.ownerAgentId(), context.ownerAgentType(),
				context.sessionId(), context.userId(), context.runtimeRequestId(), clause, context.previousQuery(),
				context.previousTarget(), context.explicitTarget(), context.deadline(), context.maxSelections(),
				context.scope(), context.pinnedSkillVersionIds());
	}

	private RouteDecision modelFailureDecision(RouteContext context, RoutePolicy policy,
			List<ScoredCandidate> modelCandidates, List<ScoredCandidate> exactCandidates,
			List<RouteSemanticMatch> semanticMatches, String reasonCode, boolean modelInvoked, StageTiming timing) {
		if ("ROUTE_MODEL_INVALID_OUTPUT".equals(reasonCode)) {
			RouteDecision unique = selectUniqueAutoSelectable(context, modelCandidates, "MODEL_INVALID_UNIQUE_SIGNAL",
					timing);
			if (unique != null) {
				return unique;
			}
			RouteDecision dominant = selectDominantSemanticCandidate(context, policy, modelCandidates, semanticMatches,
					timing);
			if (dominant != null) {
				return dominant;
			}
			return clarify(context, modelCandidates, "AMBIGUOUS_CANDIDATES", RouteDegradeMode.NONE, modelInvoked,
					timing);
		}
		RouteDecision deterministic = deterministicPlan(context, modelCandidates, exactCandidates, reasonCode,
				modelInvoked, timing);
		return deterministic == null
				? unavailable(reasonCode, RouteDegradeMode.DEGRADED_MODEL, timing, modelInvoked) : deterministic;
	}

	private RouteDecision selectDominantSemanticCandidate(RouteContext context, RoutePolicy policy,
			List<ScoredCandidate> candidates, List<RouteSemanticMatch> semanticMatches, StageTiming timing) {
		if (context == null || candidates == null || candidates.isEmpty()) {
			return null;
		}
		double recall = policy == null ? 0D : policy.vectorRecallThreshold();
		List<ScoredCandidate> recalled = candidates.stream()
			.filter(value -> value != null && autoSelectAllowed(value.candidate()))
			.filter(value -> {
				Double score = semanticScore(value.candidate(), semanticMatches);
				return score != null && score >= recall;
			})
			.toList();
		if (recalled.size() != 1) {
			return null;
		}
		return selected(recalled.get(0).candidate(), "MODEL_INVALID_UNIQUE_SIGNAL", true, timing);
	}

	private Double semanticScore(RouteCandidate candidate, List<RouteSemanticMatch> matches) {
		if (candidate == null || candidate.target() == null || matches == null || matches.isEmpty()) {
			return null;
		}
		return matches.stream()
			.filter(match -> match != null && Objects.equals(match.target(), candidate.target()))
			.map(RouteSemanticMatch::score)
			.max(Double::compare)
			.orElse(null);
	}

	private RouteDecision deterministicPlan(RouteContext context, List<ScoredCandidate> modelCandidates,
			List<ScoredCandidate> exactCandidates, String reasonCode, boolean modelInvoked, StageTiming timing) {
		RouteDecision compiled = compileCompoundPlan(context, modelCandidates, reasonCode, modelInvoked, timing);
		if (compiled != null) {
			return compiled;
		}
		if (context == null || context.effectiveScope() != RouteScope.COLLABORATOR_SCOPE
				|| !hasDeterministicParallelIntent(context.query()) || modelCandidates.size() >= MODEL_CANDIDATE_LIMIT
				|| exactCandidates.size() < 2 || exactCandidates.size() != modelCandidates.size()
				|| exactCandidates.size() > context.maxSelections()) {
			return null;
		}
		List<RouteCandidate> selected = exactCandidates.stream().map(ScoredCandidate::candidate).toList();
		if (selected.stream().anyMatch(candidate -> candidate.target() == null
				|| candidate.target().targetType() != RouteTargetType.COLLABORATOR
				|| candidate.risk() == RouteRisk.UNKNOWN)) {
			return null;
		}
		String query = context.query();
		if (query == null || query.isBlank()) {
			return null;
		}
		RoutePlan plan = new RoutePlan(java.util.stream.IntStream.range(0, selected.size())
			.mapToObj(index -> new RoutePlanStep("deterministic-" + (index + 1), selected.get(index).target(), query,
					List.of(), "Collaborator operation result"))
			.toList());
		try {
			return planDecision(selected, plan, reasonCode, RouteDegradeMode.DEGRADED_MODEL, modelInvoked, timing);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private RouteDecision compileCompoundPlan(RouteContext context, List<ScoredCandidate> candidates, String reasonCode,
			boolean modelInvoked, StageTiming timing) {
		if (context == null || context.effectiveScope() != RouteScope.COLLABORATOR_SCOPE
				|| compoundIntent(context.query()) == CompoundIntent.SINGLE) {
			return null;
		}
		List<ScoredCandidate> relevant = candidates == null ? List.of()
				: candidates.stream().filter(Objects::nonNull).filter(ScoredCandidate::relevant)
					.filter(value -> value.candidate() != null && value.candidate().target() != null
							&& value.candidate().target().targetType() == RouteTargetType.COLLABORATOR
							&& value.candidate().risk() != RouteRisk.UNKNOWN)
					.toList();
		if (relevant.size() < 2 || relevant.size() > context.maxSelections()) {
			return null;
		}
		List<String> clauses = splitTaskClauses(context.query());
		List<RouteCandidate> ordered = assignCandidates(context, relevant, clauses);
		if (ordered.size() < 2) {
			return null;
		}
		return buildCompoundDecision(context, ordered, clauses, reasonCode, modelInvoked, timing);
	}

	private RouteDecision buildCompoundDecision(RouteContext context, List<RouteCandidate> ordered,
			List<String> clauses, String reasonCode, boolean modelInvoked, StageTiming timing) {
		boolean sequential = compoundIntent(context.query()) == CompoundIntent.SEQUENTIAL;
		List<RoutePlanStep> steps = new ArrayList<>();
		for (int index = 0; index < ordered.size(); index++) {
			String stepId = "s" + (index + 1);
			String fragment = index < clauses.size() && StringUtils.hasText(clauses.get(index))
					? clauses.get(index).trim() : context.query();
			List<String> dependsOn = sequential && index > 0 ? List.of("s" + index) : List.of();
			String expectedOutput = sequential && index == 0
					? "中文业务结果。正文一两句结论，并列出后续步骤对齐用的业务名称（逗号分隔即可）；明细由结果表展示；不要输出 JSON、Markdown 表或内部字段名。"
					: sequential
							? "中文业务结果。按前置步骤给出的业务对象名称过滤查询；只用当前技能可见表；明细由结果表展示；不要输出 JSON、Markdown 表或内部字段名。"
							: "中文业务结论，不要输出 JSON、物理表名或字段名。";
			steps.add(new RoutePlanStep(stepId, ordered.get(index).target(), fragment, dependsOn, expectedOutput));
		}
		try {
			RouteDegradeMode degradeMode = "COMPOUND_INTENT_REPAIRED".equals(reasonCode)
					|| "MODEL_MULTI_SELECTED".equals(reasonCode) ? RouteDegradeMode.NONE
							: RouteDegradeMode.DEGRADED_MODEL;
			return planDecision(ordered, new RoutePlan(steps), reasonCode, degradeMode, modelInvoked, timing);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private List<String> splitTaskClauses(String query) {
		if (!StringUtils.hasText(query)) {
			return List.of();
		}
		String[] parts = query.split("以及|并且|同时|分别|然后|接着|随后|再| and ", -1);
		List<String> clauses = new ArrayList<>();
		for (String part : parts) {
			String trimmed = part == null ? "" : part.replace("先", "").trim();
			if (!trimmed.isEmpty()) {
				clauses.add(trimmed);
			}
		}
		return clauses.size() >= 2 ? clauses : List.of(query);
	}

	private List<RouteCandidate> assignCandidates(RouteContext context, List<ScoredCandidate> relevant,
			List<String> clauses) {
		List<RouteCandidate> remaining = new ArrayList<>(relevant.stream().map(ScoredCandidate::candidate).toList());
		List<RouteCandidate> ordered = new ArrayList<>();
		for (String clause : clauses) {
			if (remaining.size() <= 1) {
				break;
			}
			RouteContext clauseContext = new RouteContext(context.tenantId(), context.ownerAgentId(),
					context.ownerAgentType(), context.sessionId(), context.userId(), context.runtimeRequestId(), clause,
					context.previousQuery(), context.previousTarget(), context.explicitTarget(), context.deadline(),
					context.maxSelections(), context.scope(), context.pinnedSkillVersionIds());
			List<ScoredCandidate> scored = scorer.score(clauseContext, remaining);
			ScoredCandidate winner = scored.stream().filter(ScoredCandidate::relevant).findFirst().orElse(null);
			if (winner == null) {
				continue;
			}
			ordered.add(winner.candidate());
			remaining.remove(winner.candidate());
		}
		ordered.addAll(remaining);
		return ordered;
	}

	private RouteDecision planDecision(List<RouteCandidate> selected, RoutePlan plan, String reasonCode,
			RouteDegradeMode degradeMode, boolean modelInvoked, StageTiming timing) {
		List<RouteSelection> selections = selected.stream().map(RouteSelection::from).toList();
		if (selected.stream().anyMatch(this::requiresConfirmation)) {
			return new RouteDecision(RouteDecisionType.CONFIRM_REQUIRED, reasonCode, degradeMode, selections, List.of(),
					null, modelInvoked, timing.finish(), confirmation("本次请求将执行完整业务计划，请确认后继续"), plan);
		}
		return new RouteDecision(RouteDecisionType.MULTI_SELECT, reasonCode, degradeMode, selections, List.of(), null,
				modelInvoked, timing.finish(), null, plan);
	}

	private RouteDecision unavailable(String reasonCode, RouteDegradeMode degradeMode, StageTiming timing) {
		return unavailable(reasonCode, degradeMode, timing, false);
	}

	private RouteDecision unavailable(String reasonCode, RouteDegradeMode degradeMode, StageTiming timing,
			boolean modelInvoked) {
		return new RouteDecision(RouteDecisionType.ROUTE_UNAVAILABLE, reasonCode, degradeMode, List.of(), List.of(),
				null, modelInvoked, timing.finish());
	}

	private Instant effectiveDeadline(Instant requestDeadline) {
		Instant routeDeadline = Instant.now().plus(routing.getTotalTimeout());
		return requestDeadline == null || requestDeadline.isAfter(routeDeadline) ? routeDeadline : requestDeadline;
	}

	private boolean hasStageBudget(Instant deadline) {
		return remaining(deadline).compareTo(routing.getFinishBuffer()) > 0;
	}

	private Duration stageTimeout(Instant deadline, Duration maximum) {
		return stageTimeout(remaining(deadline), maximum);
	}

	private Duration stageTimeout(Duration remaining, Duration maximum) {
		Duration available = remaining.minus(routing.getFinishBuffer());
		if (available.isNegative() || available.isZero()) {
			throw new RouteStageException("ROUTE_DEADLINE_EXHAUSTED", "Route deadline exhausted");
		}
		return available.compareTo(maximum) < 0 ? available : maximum;
	}

	private Duration remaining(Instant deadline) {
		Duration remaining = Duration.between(Instant.now(), deadline);
		return remaining.isNegative() ? Duration.ZERO : remaining;
	}

	private Comparator<ScoredCandidate> scoredComparator() {
		return Comparator.comparingInt(ScoredCandidate::lexicalScore).reversed()
			.thenComparing(Comparator.comparingInt((ScoredCandidate value) -> value.candidate().priority()).reversed())
			.thenComparing(value -> value.candidate().target().targetType())
			.thenComparing(value -> value.candidate().target().targetId(), Comparator.nullsLast(Long::compareTo));
	}

	private long elapsedMs(long started) {
		return Duration.ofNanos(System.nanoTime() - started).toMillis();
	}

	public void recordDecision(RouteDecision decision) {
		if (decision == null) {
			return;
		}
		String decisionTag = decision.decision().name();
		String degradeTag = decision.degradeMode().name();
		String reasonTag = decision.reasonCode() == null ? "NONE" : decision.reasonCode();
		Timer.builder(ROUTE_DURATION_METRIC)
			.description("Hybrid route decision duration")
			.tag("decision", decisionTag)
			.tag("reason_code", reasonTag)
			.tag("degrade_mode", degradeTag)
			.register(meterRegistry)
			.record(decision.timing().totalMs(), TimeUnit.MILLISECONDS);
		Counter.builder(ROUTE_DECISION_METRIC)
			.description("Hybrid route decisions")
			.tag("decision", decisionTag)
			.tag("reason_code", reasonTag)
			.tag("degrade_mode", degradeTag)
			.tag("model_invoked", Boolean.toString(decision.modelInvoked()))
			.register(meterRegistry)
			.increment();
		recordStage("candidate", decision.timing().eligibilityMs());
		recordStage("lexical", decision.timing().lexicalMs());
		recordStage("vector", decision.timing().vectorMs());
		recordStage("model", decision.timing().modelMs());
		if ("ROUTE_DEADLINE_EXHAUSTED".equals(decision.reasonCode())) {
			Counter.builder(ROUTE_BUDGET_EXHAUSTED_METRIC)
				.description("Hybrid route budget exhaustion")
				.tag("budget", "total")
				.register(meterRegistry)
				.increment();
			log.warn("Hybrid route budget exhausted, configuredTotalMs={}, elapsedMs={}, decision={}, degrade={}",
					routing.getTotalTimeout().toMillis(), decision.timing().totalMs(), decisionTag, degradeTag);
		}
		if (decision.degradeMode() == RouteDegradeMode.DEGRADED_MODEL) {
			Counter.builder(ROUTE_MODEL_DEGRADED_METRIC)
				.description("Hybrid route model degradations")
				.register(meterRegistry)
				.increment();
		}
		if ("ROUTE_MODEL_TIMEOUT".equals(decision.reasonCode())) {
			Counter.builder(ROUTE_MODEL_TIMEOUT_METRIC)
				.description("Hybrid route model timeouts")
				.register(meterRegistry)
				.increment();
		}
	}

	private void recordStage(String stage, long elapsedMs) {
		if (elapsedMs <= 0) {
			return;
		}
		Timer.builder(ROUTE_STAGE_DURATION_METRIC)
			.description("Hybrid route stage duration")
			.tag("stage", stage)
			.register(meterRegistry)
			.record(elapsedMs, TimeUnit.MILLISECONDS);
	}

	private void logBudget(Instant requestDeadline, Instant effectiveDeadline) {
		long requestRemainingMs = requestDeadline == null ? routing.getTotalTimeout().toMillis()
				: Math.max(0L, Duration.between(Instant.now(), requestDeadline).toMillis());
		long effectiveRemainingMs = Math.max(0L, Duration.between(Instant.now(), effectiveDeadline).toMillis());
		log.debug("Hybrid route budget resolved, configuredMs={}, requestRemainingMs={}, effectiveRemainingMs={}",
				routing.getTotalTimeout().toMillis(), requestRemainingMs, effectiveRemainingMs);
	}

	private void logModelBudget(Duration remaining, Duration effective) {
		log.debug("Hybrid route model budget resolved, configuredMs={}, remainingMs={}, effectiveMs={}",
				routing.getModelTimeout().toMillis(), remaining.toMillis(), effective.toMillis());
	}

	private final class StageTiming {

		private final long started;

		private long lexicalMs;

		private long vectorMs;

		private long modelMs;

		private StageTiming(long started) {
			this.started = started;
		}

		private RouteTiming finish() {
			return new RouteTiming(0L, lexicalMs, vectorMs, modelMs, elapsedMs(started));
		}

	}

}
