/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.skill.execution;

import com.sn68.agent.dataagent.properties.DataAgentProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves routed ReAct budgets. Positive lower limits always win.
 */
public final class ReactRuntimeBudgetPolicy {

	private static final List<String> INTEGER_KEYS = List.of("maxIterations", "maxModelCalls", "maxToolCalls");

	private static final String PROMPT_TOKEN_KEY = "maxPromptTokens";

	private ReactRuntimeBudgetPolicy() {
	}

	public static Budget resolve(DataAgentProperties.Runtime runtime, Integer maxIterations, Integer maxModelCalls,
			Integer maxToolCalls, Long maxPromptTokens) {
		Budget platform = platform(runtime);
		return platform.tighten(maxIterations, maxModelCalls, maxToolCalls, maxPromptTokens);
	}

	public static Budget tighten(Budget current, Map<String, Object> reactConfig) {
		if (current == null) {
			throw new IllegalArgumentException("Current ReAct budget is required");
		}
		Map<String, Object> config = reactConfig == null ? Map.of() : reactConfig;
		return current.tighten(positiveInteger(config.get("maxIterations")),
				positiveInteger(config.get("maxModelCalls")), positiveInteger(config.get("maxToolCalls")),
				positiveLong(config.get(PROMPT_TOKEN_KEY)));
	}

	public static List<String> validate(Map<String, Object> reactConfig) {
		if (reactConfig == null || reactConfig.isEmpty()) {
			return List.of();
		}
		List<String> errors = new ArrayList<>();
		for (String key : INTEGER_KEYS) {
			if (reactConfig.containsKey(key) && positiveInteger(reactConfig.get(key)) == null) {
				errors.add("ReAct " + key + " must be a positive integer");
			}
		}
		if (reactConfig.containsKey(PROMPT_TOKEN_KEY)
				&& positiveLong(reactConfig.get(PROMPT_TOKEN_KEY)) == null) {
			errors.add("ReAct maxPromptTokens must be a positive integer");
		}
		return List.copyOf(errors);
	}

	private static Budget platform(DataAgentProperties.Runtime runtime) {
		if (runtime == null) {
			return new Budget(1, 0, 0, 0L);
		}
		return new Budget(Math.max(1, runtime.getReactMaxIterations()), Math.max(0, runtime.getMaxModelCalls()),
				Math.max(0, runtime.getMaxToolCalls()), Math.max(0L, runtime.getMaxPromptTokens()));
	}

	private static Integer positiveInteger(Object value) {
		Long number = integralNumber(value);
		return number == null || number > Integer.MAX_VALUE ? null : number.intValue();
	}

	private static Long positiveLong(Object value) {
		return integralNumber(value);
	}

	private static Long integralNumber(Object value) {
		if (!(value instanceof Number number)) {
			return null;
		}
		double doubleValue = number.doubleValue();
		long longValue = number.longValue();
		if (!Double.isFinite(doubleValue) || doubleValue != longValue || longValue <= 0L) {
			return null;
		}
		return longValue;
	}

	public record Budget(int maxIterations, int maxModelCalls, int maxToolCalls, long maxPromptTokens) {

		public Budget {
			maxIterations = Math.max(1, maxIterations);
			maxModelCalls = Math.max(0, maxModelCalls);
			maxToolCalls = Math.max(0, maxToolCalls);
			maxPromptTokens = Math.max(0L, maxPromptTokens);
		}

		public Budget tighten(Integer iterations, Integer modelCalls, Integer toolCalls, Long promptTokens) {
			return new Budget(minPositive(maxIterations, iterations), minPositive(maxModelCalls, modelCalls),
					minPositive(maxToolCalls, toolCalls), minPositive(maxPromptTokens, promptTokens));
		}

		public Map<String, Object> toMap() {
			Map<String, Object> values = new LinkedHashMap<>();
			values.put("maxIterations", maxIterations);
			values.put("maxModelCalls", maxModelCalls);
			values.put("maxToolCalls", maxToolCalls);
			values.put("maxPromptTokens", maxPromptTokens);
			return Map.copyOf(values);
		}

		private static int minPositive(int current, Integer candidate) {
			if (candidate == null || candidate <= 0) {
				return current;
			}
			return current > 0 ? Math.min(current, candidate) : candidate;
		}

		private static long minPositive(long current, Long candidate) {
			if (candidate == null || candidate <= 0L) {
				return current;
			}
			return current > 0L ? Math.min(current, candidate) : candidate;
		}
	}

}
