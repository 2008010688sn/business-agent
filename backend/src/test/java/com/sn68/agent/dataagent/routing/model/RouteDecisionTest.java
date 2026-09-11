/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.model;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class RouteDecisionTest {

	@Test
	void multiSelectRequiresNonEmptyPlan() {
		List<RouteSelection> selections = List.of(selection(10L), selection(11L));

		assertThrows(IllegalArgumentException.class, () -> multiSelect(selections, RoutePlan.empty()));
	}

	@Test
	void multiSelectRejectsPlanTargetsThatDoNotMatchSelections() {
		List<RouteSelection> selections = List.of(selection(10L), selection(11L));
		RoutePlan plan = new RoutePlan(List.of(
				step("s1", selection(10L), "first task", List.of()),
				step("s2", selection(12L), "second task", List.of("s1"))));

		assertThrows(IllegalArgumentException.class, () -> multiSelect(selections, plan));
	}

	@Test
	void multiSelectRejectsBlankQueryFragment() {
		List<RouteSelection> selections = List.of(selection(10L), selection(11L));
		RoutePlan plan = new RoutePlan(List.of(
				step("s1", selections.get(0), "first task", List.of()),
				step("s2", selections.get(1), " ", List.of("s1"))));

		assertThrows(IllegalArgumentException.class, () -> multiSelect(selections, plan));
	}

	@Test
	void multiSelectionConfirmationRequiresExecutablePlan() {
		List<RouteSelection> selections = List.of(selection(10L), selection(11L));

		assertThrows(IllegalArgumentException.class, () -> RouteDecision.confirmRequired(
				selections, null, RoutePlan.empty(), "CONFIRM_REQUIRED", RouteTiming.empty()));
	}

	@Test
	void confirmationSnapshotRejectsDuplicateSelections() {
		RouteSelection selection = selection(10L);

		assertThrows(IllegalArgumentException.class, () -> RouteDecision.confirmRequired(
				List.of(selection, selection), null, RoutePlan.empty(), "CONFIRM_REQUIRED", RouteTiming.empty()));
	}

	private RouteDecision multiSelect(List<RouteSelection> selections, RoutePlan plan) {
		return new RouteDecision(RouteDecisionType.MULTI_SELECT, "MODEL_MULTI_SELECTED", RouteDegradeMode.NONE,
				selections, List.of(), null, true, RouteTiming.empty(), null, plan);
	}

	private RoutePlanStep step(String stepId, RouteSelection selection, String queryFragment, List<String> dependsOn) {
		return new RoutePlanStep(stepId, selection.target(), queryFragment, dependsOn, "expected result");
	}

	private RouteSelection selection(Long targetId) {
		return new RouteSelection(
				new RouteTargetRef(RouteTargetType.COLLABORATOR, targetId, null, targetId + 100L), 30L, 40L,
				RouteRisk.DELEGATED, "checksum-" + targetId);
	}

}
