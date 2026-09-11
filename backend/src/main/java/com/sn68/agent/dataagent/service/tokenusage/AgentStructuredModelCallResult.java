/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.service.tokenusage;

import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * In-memory result for a structured model invocation. Tool arguments are kept
 * out of logs and persistence and are available only to the immediate decoder.
 */
public record AgentStructuredModelCallResult(ChatResponse chatResponse, String text,
		List<AssistantMessage.ToolCall> toolCalls, String finishReason, long promptTokens, long completionTokens,
		long totalTokens, String meteringMode, long durationMs) {

	public AgentStructuredModelCallResult {
		text = text == null ? "" : text;
		toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
	}

	public AgentModelCallResult asTextResult() {
		return new AgentModelCallResult(text, finishReason, promptTokens, completionTokens, totalTokens, meteringMode,
				durationMs);
	}

}
