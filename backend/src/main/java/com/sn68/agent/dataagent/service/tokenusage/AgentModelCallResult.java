/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.tokenusage;

/**
 * Model response and the token metadata recorded for one invocation.
 */
public record AgentModelCallResult(String text, String finishReason, long promptTokens, long completionTokens,
		long totalTokens, String meteringMode, long durationMs) {
}
