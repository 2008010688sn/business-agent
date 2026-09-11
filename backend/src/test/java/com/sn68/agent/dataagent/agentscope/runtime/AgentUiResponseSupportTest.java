/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.agentscope.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.tool.AgentModelToolName;
import com.sn68.agent.dataagent.agentscope.vo.AgentResponse;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentUiResponseSupportTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	/**
	 * 技能工具名必须经 {@link AgentModelToolName#skill} 生成：模型可调用的工具名不允许出现点号，
	 * 而 {@code isTrustedSkillTool} 判的是 {@code skill_} 前缀。写死 {@code "skill.xxx.yyy"}
	 * 会让本类所有用例都在「压根不是技能工具」这一步提前返回空，看似通过实则一条断言都没走到。
	 */
	private static String skillTool(String skillCode, String resourceKey) {
		return AgentModelToolName.skill(skillCode, resourceKey);
	}

	@Test
	void isStructuredUiResponse_acceptsSkillFlowPayload() {
		AgentResponse response = AgentResponse.builder()
			.metadata(Map.of("uiSchemaVersion", AgentUiResponseSupport.UI_SCHEMA_VERSION, "agentUi",
					Map.of("schemaVersion", AgentUiResponseSupport.UI_SCHEMA_VERSION, "kind", "skill-flow")))
			.build();

		assertTrue(AgentUiResponseSupport.isStructuredUiResponse(response));
	}

	@Test
	void isStructuredUiResponse_acceptsToolConfirmPayload() {
		AgentResponse response = AgentResponse.builder()
			.metadata(Map.of("uiSchemaVersion", AgentUiResponseSupport.UI_SCHEMA_VERSION, "agentUi",
					Map.of("schemaVersion", AgentUiResponseSupport.UI_SCHEMA_VERSION, "kind", "tool-confirm")))
			.build();

		assertTrue(AgentUiResponseSupport.isStructuredUiResponse(response));
	}

	@Test
	void isStructuredUiResponse_rejectsOtherKinds() {
		AgentResponse response = AgentResponse.builder()
			.metadata(Map.of("uiSchemaVersion", AgentUiResponseSupport.UI_SCHEMA_VERSION, "agentUi",
					Map.of("schemaVersion", AgentUiResponseSupport.UI_SCHEMA_VERSION, "kind", "capability-card")))
			.build();

		assertFalse(AgentUiResponseSupport.isStructuredUiResponse(response));
	}

	@Test
	void parseTrustedToolResult_returnsSuggestedRepliesForSkillTool() throws Exception {
		String result = objectMapper.writeValueAsString(Map.of("suggestedReplies",
				Map.of("schemaVersion", "suggested-replies/v1", "source", "agent-runtime", "submitMode", "confirm",
						"displayMode", "table", "groups", java.util.List.of(Map.of("title", "客户候选")))));

		Optional<AgentResponse> response = AgentUiResponseSupport.parseTrustedToolResult("1", "100",
				skillTool("waybill", "query"), result, objectMapper);

		assertTrue(response.isPresent());
		assertTrue(response.get().getMetadata().containsKey("suggestedReplies"));
	}

	@Test
	void parseTrustedToolResult_rejectsRemovedRouteClarificationContract() throws Exception {
		String result = objectMapper.writeValueAsString(Map.of("suggestedReplies",
				Map.of("schemaVersion", "suggested-replies/v1", "source", "route-clarify", "submitMode", "confirm",
						"displayMode", "table", "groups", java.util.List.of(Map.of("title", "请选择内部路由目标")))));

		assertTrue(AgentUiResponseSupport.parseTrustedToolResult("1", "100", skillTool("route", "select"), result,
				objectMapper).isEmpty());
	}

	@Test
	void parseTrustedToolResult_ignoresNonSkillToolsAndAutoFill() throws Exception {
		String result = objectMapper.writeValueAsString(Map.of("suggestedReplies",
				Map.of("schemaVersion", "suggested-replies/v1", "source", "agent-runtime", "submitMode", "confirm",
						"displayMode", "auto_fill", "groups", java.util.List.of(Map.of("title", "客户候选")))));

		assertTrue(AgentUiResponseSupport.parseTrustedToolResult("1", "100",
				AgentModelToolName.DATASOURCE_SKILL_SEARCH, result, objectMapper).isEmpty());
		assertTrue(AgentUiResponseSupport.parseTrustedToolResult("1", "100", skillTool("customer", "query"), result,
				objectMapper).isEmpty());
	}

}
