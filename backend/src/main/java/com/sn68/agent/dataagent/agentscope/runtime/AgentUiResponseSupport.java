/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.agentscope.runtime;

import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.tool.AgentModelToolName;
import com.sn68.agent.dataagent.agentscope.vo.AgentResponse;
import com.sn68.agent.dataagent.enums.TextType;
import com.sn68.agent.dataagent.ui.AgentUiMessage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * 识别统一 UI 消息，并投影受信工具返回的建议回复。
 */
@Slf4j
public final class AgentUiResponseSupport {

	public static final String UI_SCHEMA_VERSION = "agent-ui/v2";

	public static final String UI_KIND_SKILL_FLOW = "skill-flow";

	public static final String UI_KIND_ANALYSIS_RESULT = "analysis-result";

	public static final String UI_KIND_TOOL_CONFIRM = "tool-confirm";

	private static final String TOOL_NODE_PREFIX = "tool:";

	private static final String SUGGESTED_REPLIES_KEY = "suggestedReplies";

	private static final String SUGGESTED_REPLIES_SCHEMA_VERSION = "suggested-replies/v1";

	private static final Set<String> SUGGESTED_REPLIES_SOURCES = Set.of("query-clarify", "agent-runtime");

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private AgentUiResponseSupport() {
	}

	public static Optional<AgentResponse> parseTrustedToolResult(String agentId, String threadId, String toolName,
			String toolResult, ObjectMapper objectMapper) {
		if (!isTrustedSkillTool(toolName) || !StringUtils.hasText(toolResult) || objectMapper == null) {
			return Optional.empty();
		}
		try {
			Map<String, Object> payload = objectMapper.readValue(toolResult, MAP_TYPE);
			Object suggestedReplies = payload.get(SUGGESTED_REPLIES_KEY);
			if (!(suggestedReplies instanceof Map<?, ?> replies)
					|| !SUGGESTED_REPLIES_SCHEMA_VERSION.equals(replies.get("schemaVersion"))
					|| !SUGGESTED_REPLIES_SOURCES.contains(stringValue(replies.get("source")))
					|| !"confirm".equals(replies.get("submitMode"))
					|| !(replies.get("groups") instanceof List<?> groups) || groups.isEmpty()
					|| "auto_fill".equals(stringValue(replies.get("displayMode")))) {
				return Optional.empty();
			}
			return Optional.of(AgentResponse.builder()
				.agentId(agentId)
				.threadId(threadId)
				.nodeName("CandidateSelection")
				.textType(TextType.TEXT)
				.text(suggestedReplyTitle(replies))
				.metadata(Map.of(SUGGESTED_REPLIES_KEY, suggestedReplies))
				.build());
		}
		catch (Exception ex) {
			// Returning empty drops the candidate-selection prompt, leaving the user with no way to continue.
			log.warn("Failed to build the suggested reply UI response. agentId={}, threadId={}", agentId, threadId, ex);
			return Optional.empty();
		}
	}

	public static boolean isStructuredUiResponse(AgentResponse response) {
		return response != null && isStructuredUiMetadata(response.getMetadata());
	}

	public static boolean isStructuredUiMetadata(Map<String, Object> metadata) {
		if (metadata == null || !UI_SCHEMA_VERSION.equals(metadata.get("uiSchemaVersion"))) {
			return false;
		}
		Object agentUi = metadata.get("agentUi");
		if (agentUi instanceof AgentUiMessage message) {
			return UI_SCHEMA_VERSION.equals(message.schemaVersion()) && isSupportedKind(message.kind());
		}
		if (agentUi instanceof Map<?, ?> message) {
			return UI_SCHEMA_VERSION.equals(message.get("schemaVersion"))
					&& isSupportedKind(stringValue(message.get("kind")));
		}
		return false;
	}

	private static boolean isSupportedKind(String kind) {
		return UI_KIND_SKILL_FLOW.equals(kind) || UI_KIND_ANALYSIS_RESULT.equals(kind)
				|| UI_KIND_TOOL_CONFIRM.equals(kind);
	}

	private static boolean isTrustedSkillTool(String toolName) {
		if (!StringUtils.hasText(toolName)) {
			return false;
		}
		String normalized = toolName.trim();
		if (normalized.startsWith(TOOL_NODE_PREFIX)) {
			normalized = normalized.substring(TOOL_NODE_PREFIX.length());
		}
		return AgentModelToolName.isSkillTool(normalized);
	}

	private static String suggestedReplyTitle(Map<?, ?> replies) {
		Object groups = replies.get("groups");
		if (groups instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> group) {
			String title = stringValue(group.get("title"));
			if (StringUtils.hasText(title)) {
				return title.trim();
			}
		}
		return "请选择匹配记录";
	}

	private static String stringValue(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

}
