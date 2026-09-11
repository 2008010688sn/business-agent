/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.model;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Ordered, bounded, acyclic execution plan for a compound intent. */
public record RoutePlan(List<RoutePlanStep> steps) {

	public RoutePlan {
		steps = steps == null ? List.of() : List.copyOf(steps);
		validate(steps);
	}

	public static RoutePlan empty() {
		return new RoutePlan(List.of());
	}

	public static RoutePlan single(RouteSelection selection, String queryFragment, String expectedOutput) {
		if (selection == null) {
			return empty();
		}
		return new RoutePlan(List.of(new RoutePlanStep("s1", selection.target(), queryFragment, List.of(), expectedOutput)));
	}

	private static void validate(List<RoutePlanStep> steps) {
		if (steps.size() > 8) {
			throw new IllegalArgumentException("Route plan is too large");
		}
		Map<String, RoutePlanStep> byId = new HashMap<>();
		for (RoutePlanStep step : steps) {
			if (step == null || step.stepId() == null || step.stepId().isBlank() || step.target() == null
					|| byId.put(step.stepId(), step) != null) {
				throw new IllegalArgumentException("Route plan contains an invalid or duplicate step");
			}
		}
		Set<String> precedingStepIds = new HashSet<>();
		for (RoutePlanStep step : steps) {
			for (String dependency : step.dependsOn()) {
				if (!precedingStepIds.contains(dependency)) {
					throw new IllegalArgumentException("Route plan dependencies must reference preceding steps");
				}
			}
			validateMappings(step, byId);
			precedingStepIds.add(step.stepId());
		}
		Set<String> visiting = new HashSet<>();
		Set<String> visited = new HashSet<>();
		for (RoutePlanStep step : steps) {
			visit(step.stepId(), byId, visiting, visited);
		}
	}

	private static void validateMappings(RoutePlanStep step, Map<String, RoutePlanStep> byId) {
		if (step.inputMappings().isEmpty()) {
			return;
		}
		if (step.dependsOn().isEmpty()) {
			throw new IllegalArgumentException("Route step mappings require dependencies");
		}
		for (RouteStepInputMapping mapping : step.inputMappings()) {
			if (!step.dependsOn().contains(mapping.sourceStepId())) {
				throw new IllegalArgumentException("Route step mapping must reference a declared dependency");
			}
			RoutePlanStep source = byId.get(mapping.sourceStepId());
			boolean declared = source != null && source.outputBindings().stream()
				.anyMatch(binding -> binding.field().equals(mapping.sourceField())
						&& binding.valueType() == mapping.valueType());
			if (!declared) {
				throw new IllegalArgumentException("Route step mapping does not match a published predecessor output");
			}
		}
	}

	private static void visit(String stepId, Map<String, RoutePlanStep> byId, Set<String> visiting, Set<String> visited) {
		if (visited.contains(stepId)) {
			return;
		}
		if (!visiting.add(stepId)) {
			throw new IllegalArgumentException("Route plan dependencies must be acyclic");
		}
		for (String dependency : byId.get(stepId).dependsOn()) {
			visit(dependency, byId, visiting, visited);
		}
		visiting.remove(stepId);
		visited.add(stepId);
	}

}
