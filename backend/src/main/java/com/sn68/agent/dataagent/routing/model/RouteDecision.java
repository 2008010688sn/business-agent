/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing.model;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record RouteDecision(RouteDecisionType decision, String reasonCode, RouteDegradeMode degradeMode,
		List<RouteSelection> selections, List<RouteSuggestion> suggestions, String directText, boolean modelInvoked,
		RouteTiming timing, RouteClarification clarification, RoutePlan plan) {

	public RouteDecision(RouteDecisionType decision, String reasonCode, RouteDegradeMode degradeMode,
			List<RouteSelection> selections, List<RouteSuggestion> suggestions, String directText, boolean modelInvoked,
			RouteTiming timing) {
		this(decision, reasonCode, degradeMode, selections, suggestions, directText, modelInvoked, timing, null, null);
	}

	public RouteDecision {
		degradeMode = degradeMode == null ? RouteDegradeMode.NONE : degradeMode;
		selections = selections == null ? List.of() : List.copyOf(selections);
		suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
		timing = timing == null ? RouteTiming.empty() : timing;
		plan = plan == null ? RoutePlan.empty() : plan;
		validate(decision, selections, suggestions, directText, plan);
	}

	public static RouteDecision select(RouteCandidate candidate, String reasonCode, RouteDegradeMode degradeMode,
			boolean modelInvoked, RouteTiming timing) {
		return new RouteDecision(RouteDecisionType.SELECT, reasonCode, degradeMode,
				List.of(RouteSelection.from(candidate)), List.of(), null, modelInvoked, timing);
	}

	public static RouteDecision clarify(List<RouteSuggestion> suggestions, String reasonCode,
			RouteDegradeMode degradeMode, boolean modelInvoked, RouteTiming timing) {
		return new RouteDecision(RouteDecisionType.CLARIFY, reasonCode, degradeMode, List.of(), suggestions, null,
				modelInvoked, timing);
	}

	public static RouteDecision clarify(RouteClarification clarification, String reasonCode,
			RouteDegradeMode degradeMode, boolean modelInvoked, RouteTiming timing) {
		return new RouteDecision(RouteDecisionType.CLARIFY, reasonCode, degradeMode, List.of(), List.of(), null,
				modelInvoked, timing, clarification, null);
	}

	public static RouteDecision confirmRequired(List<RouteSelection> selections, RouteClarification clarification,
			RoutePlan plan, String reasonCode, RouteTiming timing) {
		return new RouteDecision(RouteDecisionType.CONFIRM_REQUIRED, reasonCode, RouteDegradeMode.NONE,
				selections, List.of(), null, false, timing, clarification, plan);
	}

	public static RouteDecision noMatch(RouteTiming timing) {
		return new RouteDecision(RouteDecisionType.NO_MATCH, "NO_RELEVANT_SIGNAL", RouteDegradeMode.NONE, List.of(),
				List.of(), null, false, timing);
	}

	public static RouteDecision unavailable(String reasonCode, RouteDegradeMode degradeMode, RouteTiming timing) {
		return new RouteDecision(RouteDecisionType.ROUTE_UNAVAILABLE, reasonCode, degradeMode, List.of(), List.of(),
				null, false, timing);
	}

	/** Returns a server-enriched copy while preserving the original route evidence. */
	public RouteDecision withPlan(RoutePlan updatedPlan) {
		return new RouteDecision(decision, reasonCode, degradeMode, selections, suggestions, directText, modelInvoked,
				timing, clarification, updatedPlan);
	}

	private static void validate(RouteDecisionType decision, List<RouteSelection> selections,
			List<RouteSuggestion> suggestions, String directText, RoutePlan plan) {
		if (decision == null) {
			throw new IllegalArgumentException("Route decision type is required");
		}
		if (decision == RouteDecisionType.SELECT && (selections.size() != 1 || !suggestions.isEmpty())) {
			throw new IllegalArgumentException("SELECT requires exactly one selection");
		}
		if (decision == RouteDecisionType.MULTI_SELECT && selections.size() < 2) {
			throw new IllegalArgumentException("MULTI_SELECT requires at least two selections");
		}
		if (decision == RouteDecisionType.CLARIFY && !selections.isEmpty()) {
			throw new IllegalArgumentException("CLARIFY cannot carry selections");
		}
		if (decision == RouteDecisionType.CONFIRM_REQUIRED && selections.isEmpty()) {
			throw new IllegalArgumentException("CONFIRM_REQUIRED requires a route selection");
		}
		if (selections.stream().map(RouteSelection::target).distinct().count() != selections.size()) {
			throw new IllegalArgumentException("Route selections must target distinct capabilities");
		}
		validatePlan(selections, plan);
		if (decision == RouteDecisionType.DIRECT && (directText == null || directText.isBlank())) {
			throw new IllegalArgumentException("DIRECT requires display text");
		}
		if ((decision == RouteDecisionType.NO_MATCH || decision == RouteDecisionType.ROUTE_UNAVAILABLE)
				&& (!selections.isEmpty() || !suggestions.isEmpty() || directText != null)) {
			throw new IllegalArgumentException(decision + " cannot carry route results");
		}
	}

	private static void validatePlan(List<RouteSelection> selections, RoutePlan plan) {
		if (plan.steps().isEmpty()) {
			if (selections.size() > 1) {
				throw new IllegalArgumentException("Multiple route selections require an execution plan");
			}
			return;
		}
		if (plan.steps().size() != selections.size()) {
			throw new IllegalArgumentException("Route plan must match route selections");
		}
		Set<RouteTargetRef> selectionTargets = new HashSet<>();
		selections.forEach(selection -> selectionTargets.add(selection.target()));
		Set<RouteTargetRef> planTargets = new HashSet<>();
		plan.steps().forEach(step -> planTargets.add(step.target()));
		if (planTargets.size() != plan.steps().size() || !planTargets.equals(selectionTargets)) {
			throw new IllegalArgumentException("Route plan must match route selections");
		}
		if (selections.size() > 1
				&& plan.steps().stream().anyMatch(step -> step.queryFragment() == null || step.queryFragment().isBlank())) {
			throw new IllegalArgumentException("Compound route steps require query fragments");
		}
	}

}
