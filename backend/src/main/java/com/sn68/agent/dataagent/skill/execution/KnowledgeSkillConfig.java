/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.skill.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

/**
 * Validated runtime configuration for a KNOWLEDGE Skill.
 */
record KnowledgeSkillConfig(int topK) {

	private static final int DEFAULT_TOP_K = 3;

	private static final int MAX_TOP_K = 5;

	static KnowledgeSkillConfig parse(String json, ObjectMapper objectMapper) {
		if (!StringUtils.hasText(json)) {
			return new KnowledgeSkillConfig(DEFAULT_TOP_K);
		}
		try {
			JsonNode root = objectMapper.readTree(json);
			if (root == null || !root.isObject()) {
				throw invalid("knowledgeConfig 必须是 JSON 对象");
			}
			JsonNode topKNode = root.get("topK");
			if (topKNode == null || topKNode.isNull()) {
				return new KnowledgeSkillConfig(DEFAULT_TOP_K);
			}
			if (!topKNode.isIntegralNumber() || !topKNode.canConvertToInt()) {
				throw invalid("topK 必须是整数");
			}
			int topK = topKNode.asInt();
			if (topK < 1 || topK > MAX_TOP_K) {
				throw invalid("topK 必须在 1 到 " + MAX_TOP_K + " 之间");
			}
			return new KnowledgeSkillConfig(topK);
		}
		catch (IllegalStateException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw invalid("knowledgeConfig 不是有效 JSON", ex);
		}
	}

	private static IllegalStateException invalid(String message) {
		return new IllegalStateException("Knowledge Skill 配置无效：" + message);
	}

	private static IllegalStateException invalid(String message, Exception cause) {
		return new IllegalStateException("Knowledge Skill 配置无效：" + message, cause);
	}

}
