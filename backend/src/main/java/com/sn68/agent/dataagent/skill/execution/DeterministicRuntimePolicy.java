/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.skill.execution;

import com.sn68.agent.dataagent.properties.DataAgentProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the bounded policy for DETERMINISTIC Skill execution.
 */
public final class DeterministicRuntimePolicy {

	private static final String CONFIG_KEY = "deterministic";

	private static final String TOTAL_TIMEOUT_KEY = "totalTimeoutMs";

	private static final String PLANNER_TIMEOUT_KEY = "plannerTimeoutMs";

	private static final String SQL_TIMEOUT_KEY = "sqlTimeoutMs";

	private static final String MAX_OUTPUT_TOKENS_KEY = "maxOutputTokens";

	private static final String MAX_ATTEMPTS_KEY = "maxAttempts";

	private static final Set<String> ALLOWED_KEYS = Set.of(TOTAL_TIMEOUT_KEY, PLANNER_TIMEOUT_KEY, SQL_TIMEOUT_KEY,
			MAX_OUTPUT_TOKENS_KEY, MAX_ATTEMPTS_KEY);

	private DeterministicRuntimePolicy() {
	}

	/**
	 * Resolves a safe effective policy. Invalid draft values fall back to the platform
	 * limit and are reported separately by {@link #validate(DataAgentProperties.Runtime, Map)}.
	 */
	public static Policy resolve(DataAgentProperties.Runtime runtime, Map<String, Object> runtimeConfig) {
		Policy platform = platform(runtime);
		Map<String, Object> config = policyMap(runtimeConfig);
		Policy effective = new Policy(tighten(config.get(TOTAL_TIMEOUT_KEY), platform.totalTimeoutMs()),
				tighten(config.get(PLANNER_TIMEOUT_KEY), platform.plannerTimeoutMs()),
				tighten(config.get(SQL_TIMEOUT_KEY), platform.sqlTimeoutMs()),
				(int) tighten(config.get(MAX_OUTPUT_TOKENS_KEY), platform.maxOutputTokens()),
				(int) tighten(config.get(MAX_ATTEMPTS_KEY), platform.maxAttempts()), platform.finishBufferMs());
		return effective.stageBudgetValid() ? effective : platform;
	}

	public static List<String> validate(DataAgentProperties.Runtime runtime, Map<String, Object> runtimeConfig) {
		if (runtimeConfig == null || !runtimeConfig.containsKey(CONFIG_KEY)) {
			return List.of();
		}
		Object rawPolicy = runtimeConfig.get(CONFIG_KEY);
		if (!(rawPolicy instanceof Map<?, ?> values)) {
			return List.of("runtimeConfig.deterministic must be an object");
		}
		Policy platform = platform(runtime);
		List<String> errors = new ArrayList<>();
		Map<String, Object> config = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : values.entrySet()) {
			if (!(entry.getKey() instanceof String key) || !ALLOWED_KEYS.contains(key)) {
				errors.add("runtimeConfig.deterministic contains unknown field: " + entry.getKey());
				continue;
			}
			config.put(key, entry.getValue());
		}
		Long totalTimeoutMs = validateLimit(config, TOTAL_TIMEOUT_KEY, platform.totalTimeoutMs(), errors);
		Long plannerTimeoutMs = validateLimit(config, PLANNER_TIMEOUT_KEY, platform.plannerTimeoutMs(), errors);
		Long sqlTimeoutMs = validateLimit(config, SQL_TIMEOUT_KEY, platform.sqlTimeoutMs(), errors);
		Long maxOutputTokens = validateLimit(config, MAX_OUTPUT_TOKENS_KEY, platform.maxOutputTokens(), errors);
		Long maxAttempts = validateLimit(config, MAX_ATTEMPTS_KEY, platform.maxAttempts(), errors);
		if (totalTimeoutMs != null && plannerTimeoutMs != null && sqlTimeoutMs != null
				&& maxOutputTokens != null && maxAttempts != null) {
			Policy effective = new Policy(totalTimeoutMs, plannerTimeoutMs, sqlTimeoutMs,
					maxOutputTokens.intValue(), maxAttempts.intValue(), platform.finishBufferMs());
			if (!effective.stageBudgetValid()) {
				errors.add("runtimeConfig.deterministic plannerTimeoutMs + sqlTimeoutMs + finishBufferMs"
						+ " must not exceed totalTimeoutMs");
			}
		}
		return List.copyOf(errors);
	}

	private static Long validateLimit(Map<String, Object> config, String key, long limit, List<String> errors) {
		if (!config.containsKey(key)) {
			return limit;
		}
		Long value = positiveIntegral(config.get(key));
		if (value == null) {
			errors.add("runtimeConfig.deterministic." + key + " must be a positive integer");
			return null;
		}
		if (value > limit) {
			errors.add("runtimeConfig.deterministic." + key + " must not exceed platform limit " + limit);
			return null;
		}
		return value;
	}

	private static Policy platform(DataAgentProperties.Runtime runtime) {
		DataAgentProperties.Runtime.Deterministic config = runtime == null || runtime.getDeterministic() == null
				? new DataAgentProperties.Runtime.Deterministic() : runtime.getDeterministic();
		Policy policy = new Policy(milliseconds(config.getTotalTimeout()), milliseconds(config.getPlannerTimeout()),
				milliseconds(config.getSqlTimeout()), config.getMaxOutputTokens(), config.getMaxAttempts(),
				milliseconds(config.getFinishBuffer()));
		if (!policy.positive() || !policy.stageBudgetValid()) {
			throw new IllegalArgumentException("Deterministic platform policy is invalid");
		}
		return policy;
	}

	private static Map<String, Object> policyMap(Map<String, Object> runtimeConfig) {
		if (runtimeConfig == null || !(runtimeConfig.get(CONFIG_KEY) instanceof Map<?, ?> values)) {
			return Map.of();
		}
		Map<String, Object> result = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : values.entrySet()) {
			if (entry.getKey() instanceof String key && ALLOWED_KEYS.contains(key)) {
				result.put(key, entry.getValue());
			}
		}
		return result;
	}

	private static long tighten(Object rawValue, long limit) {
		Long value = positiveIntegral(rawValue);
		return value == null || value > limit ? limit : value;
	}

	private static Long positiveIntegral(Object value) {
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

	private static long milliseconds(Duration duration) {
		return duration == null ? 0L : duration.toMillis();
	}

	public record Policy(long totalTimeoutMs, long plannerTimeoutMs, long sqlTimeoutMs, int maxOutputTokens,
			int maxAttempts, long finishBufferMs) {

		public Map<String, Object> toMap() {
			Map<String, Object> values = new LinkedHashMap<>();
			values.put(TOTAL_TIMEOUT_KEY, totalTimeoutMs);
			values.put(PLANNER_TIMEOUT_KEY, plannerTimeoutMs);
			values.put(SQL_TIMEOUT_KEY, sqlTimeoutMs);
			values.put(MAX_OUTPUT_TOKENS_KEY, maxOutputTokens);
			values.put(MAX_ATTEMPTS_KEY, maxAttempts);
			values.put("finishBufferMs", finishBufferMs);
			return Map.copyOf(values);
		}

		private boolean positive() {
			return totalTimeoutMs > 0L && plannerTimeoutMs > 0L && sqlTimeoutMs > 0L
					&& maxOutputTokens > 0 && maxAttempts > 0 && finishBufferMs > 0L;
		}

		private boolean stageBudgetValid() {
			return positive() && totalTimeoutMs > finishBufferMs
					&& plannerTimeoutMs <= totalTimeoutMs - finishBufferMs
					&& sqlTimeoutMs <= totalTimeoutMs - finishBufferMs - plannerTimeoutMs;
		}

	}

}
