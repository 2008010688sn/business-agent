/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing.model;

import java.util.List;

public record RouteRules(List<String> exact, List<String> phrases, List<String> aliases,
		List<String> positiveExamples, List<String> positivePatterns, List<String> negativeExamples,
		List<String> hardExcludes, List<String> hardExcludePatterns, boolean allowFlowAutoSelect) {

	public RouteRules(List<String> exact, List<String> phrases, List<String> aliases, List<String> positiveExamples,
			List<String> negativeExamples, List<String> hardExcludes) {
		this(exact, phrases, aliases, positiveExamples, List.of(), negativeExamples, hardExcludes, List.of(), false);
	}

	public RouteRules {
		exact = copy(exact);
		phrases = copy(phrases);
		aliases = copy(aliases);
		positiveExamples = copy(positiveExamples);
		positivePatterns = copy(positivePatterns);
		negativeExamples = copy(negativeExamples);
		hardExcludes = copy(hardExcludes);
		hardExcludePatterns = copy(hardExcludePatterns);
	}

	public static RouteRules empty() {
		return new RouteRules(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), false);
	}

	private static List<String> copy(List<String> values) {
		return values == null ? List.of() : List.copyOf(values);
	}

}
