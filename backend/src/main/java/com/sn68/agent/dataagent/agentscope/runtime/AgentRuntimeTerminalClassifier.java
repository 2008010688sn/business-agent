/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.agentscope.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.sn68.agent.dataagent.util.JsonUtil;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Classifies the final AgentScope message without consulting cumulative tool metrics.
 */
public final class AgentRuntimeTerminalClassifier {

	private static final String DEFAULT_TOOL_FAILURE_MESSAGE =
			"The final tool operation failed and no public answer was generated.";

	private static final String DEFAULT_PROTOCOL_FAILURE_MESSAGE =
			AgentRuntimeErrorCode.MODEL_PROTOCOL_ERROR.getLabel();

	private static final String EMPTY_COMPLETION_FAILURE_MESSAGE =
			AgentRuntimeErrorCode.MODEL_EMPTY_COMPLETION.getLabel();

	private static final Pattern TOOL_XML_ENVELOPE = Pattern.compile(
			"(?is)^<\\s*(tool_call|tool_calls|function_call|function_calls)\\b[^>]*>.*</\\s*\\1\\s*>$");

	private static final Pattern LEGACY_ASSISTANT_TOOL_ENVELOPE = Pattern.compile(
			"(?is)^(?:<\\|)?assistant\\s+to\\s*=\\s*[^\\s>]+(?:\\s+code)?(?:\\|>)?\\s+.+$");

	public TerminalResult classify(Msg response) {
		if (response == null) {
			return emptyCompletion();
		}
		List<ToolResultBlock> toolResults = response.getContentBlocks(ToolResultBlock.class);
		ToolResultBlock businessFailure = toolResults.stream().filter(this::isBusinessFailure).findFirst().orElse(null);
		if (businessFailure != null) {
			return new TerminalResult(AgentRuntimeTerminalOutcome.BUSINESS_FAILED, "",
					errorCode(businessFailure, "BUSINESS_FAILED"), failureText(businessFailure));
		}
		ToolResultBlock toolError = toolResults.stream().filter(this::isToolError).findFirst().orElse(null);
		if (toolError != null || response.getGenerateReason() == GenerateReason.ACTING_STOP_REQUESTED) {
			return new TerminalResult(AgentRuntimeTerminalOutcome.TOOL_FAILED, "",
					toolError == null ? "ACTING_STOP_REQUESTED" : errorCode(toolError, "TOOL_FAILED"),
					toolError == null ? DEFAULT_TOOL_FAILURE_MESSAGE : failureText(toolError));
		}
		String answer = publicAssistantText(response);
		if (AgentRuntimeErrorClassifier.isBudgetExhaustedText(answer)) {
			return new TerminalResult(AgentRuntimeTerminalOutcome.RUNTIME_FAILED, "", "BUDGET_EXCEEDED",
					AgentRuntimeErrorCode.BUDGET_EXCEEDED.getLabel());
		}
		if (!answer.isBlank() && !isToolProtocolEnvelope(answer)) {
			return new TerminalResult(AgentRuntimeTerminalOutcome.SUCCESS, answer, null, null);
		}
		if (isToolProtocolEnvelope(answer)) {
			return protocolError();
		}
		return emptyCompletion();
	}

	private boolean isToolProtocolEnvelope(String answer) {
		String trimmed = answer.trim();
		if (isMarkdownCodeFence(trimmed)) {
			return false;
		}
		return TOOL_XML_ENVELOPE.matcher(trimmed).matches()
				|| LEGACY_ASSISTANT_TOOL_ENVELOPE.matcher(trimmed).matches()
				|| isKnownToolJsonEnvelope(trimmed);
	}

	private boolean isMarkdownCodeFence(String answer) {
		return (answer.startsWith("```") && answer.endsWith("```"))
				|| (answer.startsWith("~~~") && answer.endsWith("~~~"));
	}

	private boolean isKnownToolJsonEnvelope(String answer) {
		if (!answer.startsWith("{") || !answer.endsWith("}")) {
			return false;
		}
		try {
			JsonNode root = JsonUtil.getObjectMapper().readTree(answer);
			if (!root.isObject()) {
				return false;
			}
			if (isToolCallNode(root.get("tool_call")) || isToolCallNode(root.get("function_call"))) {
				return true;
			}
			JsonNode toolCalls = root.get("tool_calls");
			if (toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty()) {
				for (JsonNode toolCall : toolCalls) {
					if (!isToolCallNode(toolCall)) {
						return false;
					}
				}
				return true;
			}
			String type = root.path("type").asText("");
			return ("tool_call".equalsIgnoreCase(type) || "function_call".equalsIgnoreCase(type))
					&& isToolCallNode(root);
		}
		catch (Exception ignored) {
			return false;
		}
	}

	private boolean isToolCallNode(JsonNode node) {
		if (node == null || !node.isObject()) {
			return false;
		}
		JsonNode function = node.path("function");
		JsonNode callable = function.isObject() ? function : node;
		return callable.path("name").isTextual() && !callable.path("name").asText().isBlank()
				&& callable.has("arguments");
	}

	private TerminalResult protocolError() {
		return new TerminalResult(AgentRuntimeTerminalOutcome.MODEL_PROTOCOL_ERROR, "", "MODEL_PROTOCOL_ERROR",
				DEFAULT_PROTOCOL_FAILURE_MESSAGE);
	}

	private TerminalResult emptyCompletion() {
		return new TerminalResult(AgentRuntimeTerminalOutcome.MODEL_PROTOCOL_ERROR, "",
				AgentRuntimeErrorCode.MODEL_EMPTY_COMPLETION.getValue(), EMPTY_COMPLETION_FAILURE_MESSAGE);
	}

	private String publicAssistantText(Msg message) {
		if (message.getRole() != MsgRole.ASSISTANT) {
			return "";
		}
		return message.getContentBlocks(TextBlock.class)
			.stream()
			.map(TextBlock::getText)
			.filter(text -> text != null && !text.isBlank())
			.collect(Collectors.joining(System.lineSeparator()));
	}

	private boolean isBusinessFailure(ToolResultBlock block) {
		Map<String, Object> metadata = block.getMetadata();
		return metadata != null
				&& Boolean.TRUE.equals(metadata.get(SpringToolCallbackAgentAdapter.METADATA_BUSINESS_FAILED));
	}

	private boolean isToolError(ToolResultBlock block) {
		return outputText(block).trim().startsWith("Error:");
	}

	private String failureText(ToolResultBlock block) {
		String text = outputText(block).trim();
		return text.isBlank() ? DEFAULT_TOOL_FAILURE_MESSAGE : abbreviate(text, 240);
	}

	private String outputText(ToolResultBlock block) {
		List<ContentBlock> output = block == null ? null : block.getOutput();
		if (output == null || output.isEmpty()) {
			return "";
		}
		return output.stream()
			.filter(TextBlock.class::isInstance)
			.map(TextBlock.class::cast)
			.map(TextBlock::getText)
			.filter(text -> text != null && !text.isBlank())
			.collect(Collectors.joining(System.lineSeparator()));
	}

	private String errorCode(ToolResultBlock block, String fallback) {
		Object code = block.getMetadata() == null ? null : block.getMetadata().get("errorCode");
		return code == null || code.toString().isBlank() ? fallback : code.toString().trim();
	}

	private String abbreviate(String value, int maxLength) {
		String normalized = value.replaceAll("\\s+", " ").trim();
		return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength - 3) + "...";
	}

	public record TerminalResult(AgentRuntimeTerminalOutcome outcome, String answer, String errorCode,
			String failureMessage) {
	}

}
