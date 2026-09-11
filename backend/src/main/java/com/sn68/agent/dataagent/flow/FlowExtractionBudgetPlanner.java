/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Calculates a single structured extraction call budget from the selected model
 * and the actual prompt. It never truncates source text to make a request fit.
 */
@Component
public class FlowExtractionBudgetPlanner {

	private static final long DEFAULT_CONTEXT_WINDOW = 32768L;

	private static final long MIN_OUTPUT_TOKENS = 64L;

	private static final long MIN_CONTEXT_RESERVE = 256L;

	public Budget plan(AgentRequest request, ModelConfigDTO modelConfig, String prompt, DataAgentProperties.Flow flow) {
		long contextWindow = positive(modelConfig == null ? null : modelConfig.getContextWindowTokens(),
				DEFAULT_CONTEXT_WINDOW);
		long promptTokens = estimateTokens(prompt);
		long reserve = Math.max(MIN_CONTEXT_RESERVE, Math.min(2048L, contextWindow / 16L));
		long availableOutput = contextWindow - promptTokens - reserve;
		if (availableOutput < MIN_OUTPUT_TOKENS) {
			throw new FlowExtractionException(FlowExtractionException.INPUT_TOO_LARGE,
					"FLOW extraction prompt exceeds the selected model context window", null);
		}
		long modelOutputLimit = positive(modelConfig == null ? null : modelConfig.getMaxTokens(), availableOutput);
		long configuredOutputLimit = flow == null ? 0L : Math.max(0L, flow.getExtractMaxTokens());
		long maxOutputTokens = Math.min(modelOutputLimit, availableOutput);
		if (configuredOutputLimit > 0L) {
			maxOutputTokens = Math.min(maxOutputTokens, configuredOutputLimit);
		}
		if (maxOutputTokens < MIN_OUTPUT_TOKENS) {
			throw new FlowExtractionException(FlowExtractionException.INPUT_TOO_LARGE,
					"FLOW extraction has no usable structured output budget", null);
		}
		Duration timeout = timeout(request, flow == null ? null : flow.getExtractTimeout());
		return new Budget(maxOutputTokens, timeout, promptTokens, contextWindow);
	}

	private Duration timeout(AgentRequest request, Duration configured) {
		Duration timeout = configured == null || configured.isNegative() || configured.isZero() ? Duration.ofSeconds(15)
				: configured;
		if (request == null || request.getRuntimeDeadline() == null) {
			return timeout;
		}
		Duration bounded = request.getRuntimeDeadline().timeoutFor(timeout, request.getRuntimeFinishBuffer());
		if (bounded.isNegative() || bounded.isZero()) {
			throw new FlowExtractionException(FlowExtractionException.TIMEOUT,
					"FLOW extraction deadline has expired", null);
		}
		return bounded;
	}

	private long estimateTokens(String content) {
		if (content == null || content.isEmpty()) {
			return 1L;
		}
		long units = content.codePoints().mapToLong(codePoint -> codePoint <= 0x7f ? 1L : 2L).sum();
		return Math.max(1L, (units + 3L) / 4L);
	}

	private long positive(Long value, long fallback) {
		return value == null || value <= 0L ? fallback : value;
	}

	/** Budget submitted to the model call and token accounting layer. */
	public record Budget(long maxOutputTokens, Duration timeout, long estimatedPromptTokens, long contextWindowTokens) {
	}

}
