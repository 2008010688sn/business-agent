/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.model;

import java.util.List;

/** One executable step in a compound orchestration plan. */
public record RoutePlanStep(String stepId, RouteTargetRef target, String queryFragment, List<String> dependsOn,
		String expectedOutput, List<RouteStepOutputBinding> outputBindings,
		List<RouteStepInputMapping> inputMappings, String delegationMode, Long collaboratorAgentId) {

	public RoutePlanStep {
		dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
		outputBindings = outputBindings == null ? List.of() : List.copyOf(outputBindings);
		inputMappings = inputMappings == null ? List.of() : List.copyOf(inputMappings);
		delegationMode = delegationMode == null ? null : DelegationMode.resolve(delegationMode).name();
		if (outputBindings.stream().map(RouteStepOutputBinding::field).collect(java.util.stream.Collectors.toSet()).size()
				!= outputBindings.size()) {
			throw new IllegalArgumentException("Route step output bindings must use distinct fields");
		}
		if (inputMappings.stream().map(RouteStepInputMapping::targetField).collect(java.util.stream.Collectors.toSet()).size()
				!= inputMappings.size()) {
			throw new IllegalArgumentException("Route step input mappings must use distinct target fields");
		}
	}

	public RoutePlanStep(String stepId, RouteTargetRef target, String queryFragment, List<String> dependsOn,
			String expectedOutput, List<RouteStepOutputBinding> outputBindings,
			List<RouteStepInputMapping> inputMappings, String delegationMode) {
		this(stepId, target, queryFragment, dependsOn, expectedOutput, outputBindings, inputMappings, delegationMode,
				null);
	}

	public RoutePlanStep(String stepId, RouteTargetRef target, String queryFragment, List<String> dependsOn,
			String expectedOutput, List<RouteStepOutputBinding> outputBindings,
			List<RouteStepInputMapping> inputMappings) {
		this(stepId, target, queryFragment, dependsOn, expectedOutput, outputBindings, inputMappings, null);
	}

	/** Compatibility constructor for model-generated plans before server binding. */
	public RoutePlanStep(String stepId, RouteTargetRef target, String queryFragment, List<String> dependsOn,
			String expectedOutput) {
		this(stepId, target, queryFragment, dependsOn, expectedOutput, List.of(), List.of());
	}

}
