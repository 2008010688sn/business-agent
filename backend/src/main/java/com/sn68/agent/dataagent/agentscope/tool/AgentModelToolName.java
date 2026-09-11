/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.agentscope.tool;

import cn.hutool.crypto.SecureUtil;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * Model-callable tool-name contract shared by static, Skill, and MCP tools.
 */
public final class AgentModelToolName {

	public static final String DATASOURCE_SKILL_SEARCH = "datasource_skill_search";

	public static final String SEMANTIC_MODEL_SEARCH = "semantic_model_search";

	public static final String SQL_GUARD_CHECK = "sql_guard_check";

	public static final String DOMAIN_BUSINESS_KNOWLEDGE_SEARCH = "domain_business_knowledge_search";

	public static final String WEB_FETCH = "web_fetch";

	private static final int MAX_LENGTH = 64;

	private static final int HASH_LENGTH = 12;

	private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]{1," + MAX_LENGTH + "}");

	private AgentModelToolName() {
	}

	public static String skill(String skillCode, String resourceKey) {
		return normalize("skill." + firstText(skillCode, "tool") + "." + firstText(resourceKey, "tool"),
				"skill_tool");
	}

	public static String mcp(String serverCode, String toolName) {
		return normalize(firstText(serverCode, "mcp") + "__" + firstText(toolName, "tool"), "mcp_tool");
	}

	public static boolean isValid(String toolName) {
		return StringUtils.hasText(toolName) && VALID_NAME.matcher(toolName).matches();
	}

	public static boolean isSkillTool(String toolName) {
		return toolName != null && toolName.startsWith("skill_");
	}

	public static boolean isSkillActionTool(String toolName) {
		return isSkillTool(toolName) && toolName.matches("^skill_.+_execute(?:_[0-9a-f]{" + HASH_LENGTH + "})?$");
	}

	public static boolean isKnowledgeTool(String toolName) {
		return toolName != null && toolName.startsWith("domain_business_knowledge_");
	}

	public static boolean isDataQueryTool(String toolName) {
		return isDatasourceTool(toolName) || isSemanticModelTool(toolName) || isSqlGuardTool(toolName);
	}

	public static boolean isDatasourceTool(String toolName) {
		return toolName != null && toolName.startsWith("datasource_");
	}

	public static boolean isSemanticModelTool(String toolName) {
		return toolName != null && toolName.startsWith("semantic_model_");
	}

	public static boolean isSqlGuardTool(String toolName) {
		return toolName != null && toolName.startsWith("sql_guard_");
	}

	/**
	 * 网页证据工具。精确匹配常量，禁止 {@code web_*} 通配（与 CapabilitySourceGuard 豁免同一口径）。
	 */
	public static boolean isWebEvidenceTool(String toolName) {
		return WEB_FETCH.equals(toolName);
	}

	private static String normalize(String value, String fallback) {
		String original = firstText(value, fallback);
		String normalized = original.replaceAll("[^A-Za-z0-9_-]+", "_").replaceAll("(^[_-]+|[_-]+$)", "");
		if (!StringUtils.hasText(normalized)) {
			normalized = fallback;
		}
		if (normalized.equals(original) && normalized.length() <= MAX_LENGTH) {
			return normalized;
		}
		String suffix = "_" + SecureUtil.sha256(original).substring(0, HASH_LENGTH);
		int prefixLength = MAX_LENGTH - suffix.length();
		String prefix = normalized.length() <= prefixLength ? normalized : normalized.substring(0, prefixLength);
		return prefix + suffix;
	}

	private static String firstText(String value, String fallback) {
		return StringUtils.hasText(value) ? value.trim() : fallback;
	}

}
