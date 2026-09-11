/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.skill.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sn68.agent.dataagent.properties.DataAgentProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeterministicRuntimePolicyTest {

	private final DataAgentProperties.Runtime runtime = new DataAgentProperties.Runtime();

	@Test
	void usesPlatformDefaultsWhenSkillHasNoPolicy() {
		assertEquals(new DeterministicRuntimePolicy.Policy(20000L, 8000L, 6000L, 2048, 2, 1000L),
				DeterministicRuntimePolicy.resolve(runtime, Map.of()));
	}

	@Test
	void skillPolicyCanOnlyTightenPlatformLimits() {
		Map<String, Object> runtimeConfig = Map.of("maxRows", 200, "deterministic",
				Map.of("totalTimeoutMs", 12000, "plannerTimeoutMs", 5000, "sqlTimeoutMs", 4000,
						"maxOutputTokens", 1024, "maxAttempts", 1));

		DeterministicRuntimePolicy.Policy policy = DeterministicRuntimePolicy.resolve(runtime, runtimeConfig);

		assertEquals(new DeterministicRuntimePolicy.Policy(12000L, 5000L, 4000L, 1024, 1, 1000L), policy);
		assertEquals(200, runtimeConfig.get("maxRows"));
	}

	@Test
	void validationRejectsUnknownNonPositiveAndOverLimitValues() {
		List<String> errors = DeterministicRuntimePolicy.validate(runtime, Map.of("deterministic",
				Map.of("unknown", true, "maxAttempts", 0, "maxOutputTokens", 2049)));

		assertTrue(errors.stream().anyMatch(error -> error.contains("unknown field: unknown")));
		assertTrue(errors.stream().anyMatch(error -> error.contains("maxAttempts must be a positive integer")));
		assertTrue(errors.stream().anyMatch(error -> error.contains("maxOutputTokens must not exceed platform limit 2048")));
	}

	@Test
	void validationRejectsStagesThatDoNotFitTotalBudget() {
		List<String> errors = DeterministicRuntimePolicy.validate(runtime, Map.of("deterministic",
				Map.of("totalTimeoutMs", 10000, "plannerTimeoutMs", 6000, "sqlTimeoutMs", 4000)));

		assertTrue(errors.stream().anyMatch(error -> error.contains("must not exceed totalTimeoutMs")));
	}

}
