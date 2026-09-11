/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.adapter;

/**
 * 评估调用结果。
 */
public record EvalInvocationResult(boolean success, String answer, Long sessionId, String threadId,
		String runtimeRequestId, Long durationMs, Long promptTokens, Long completionTokens, Long totalTokens,
		Integer toolCount, Integer toolFailCount, String errorMessage) {
}
