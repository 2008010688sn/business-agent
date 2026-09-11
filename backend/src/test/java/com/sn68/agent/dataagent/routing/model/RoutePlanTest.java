/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.model;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class RoutePlanTest {

	@Test
	void rejectsForwardDependencyEvenWhenAcyclic() {
		RouteTargetRef firstTarget = new RouteTargetRef(RouteTargetType.COLLABORATOR, 10L, null, 20L);
		RouteTargetRef secondTarget = new RouteTargetRef(RouteTargetType.COLLABORATOR, 11L, null, 21L);

		assertThrows(IllegalArgumentException.class, () -> new RoutePlan(List.of(
				new RoutePlanStep("s1", firstTarget, "first task", List.of("s2"), "first result"),
				new RoutePlanStep("s2", secondTarget, "second task", List.of(), "second result"))));
	}

}
