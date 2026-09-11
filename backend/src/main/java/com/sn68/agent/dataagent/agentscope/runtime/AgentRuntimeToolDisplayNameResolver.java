/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sn68.agent.dataagent.agentscope.runtime;

import java.util.Locale;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * 解析工具展示名称，优先使用业务标题并限制前端展示长度。
 */
public final class AgentRuntimeToolDisplayNameResolver {

	private static final int MAX_DISPLAY_NAME_LENGTH = 40;

	private AgentRuntimeToolDisplayNameResolver() {
	}

	public static String displayName(String toolName) {
		return displayName(toolName, null, null);
	}

	public static String displayName(String toolName, String description) {
		return displayName(toolName, description, null);
	}

	public static String displayName(String toolName, String description, Map<String, Object> input) {
		String resourceDisplayName = displayNameFromToolName(stringValue(input == null ? null : input.get("resourceKey")));
		if (StringUtils.hasText(resourceDisplayName)) {
			return resourceDisplayName;
		}
		String descriptionDisplayName = displayNameFromDescription(description);
		if (StringUtils.hasText(descriptionDisplayName)) {
			return descriptionDisplayName;
		}
		String toolDisplayName = displayNameFromToolName(toolName);
		return StringUtils.hasText(toolDisplayName) ? toolDisplayName : "执行工具操作";
	}

	public static String runningText(String toolName) {
		return "正在执行：" + displayName(toolName);
	}

	public static String failureMessage(String toolName, String description, Map<String, Object> input) {
		return displayName(toolName, description, input) + "暂时无法完成，请稍后重试或调整输入。";
	}

	private static String displayNameFromDescription(String description) {
		if (!StringUtils.hasText(description) || !containsHan(description)) {
			return null;
		}
		String normalized = description.replaceAll("\\s+", " ").trim();
		int end = normalized.length();
		for (String delimiter : new String[] { "。", "；", ";", ".", "\n" }) {
			int index = normalized.indexOf(delimiter);
			if (index > 0) {
				end = Math.min(end, index);
			}
		}
		return abbreviate(normalized.substring(0, end));
	}

	private static String displayNameFromToolName(String toolName) {
		if (!StringUtils.hasText(toolName)) {
			return null;
		}
		String normalized = toolName.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(".", "");
		if (normalized.contains("demandcustomeroptions")) {
			return "查询下单客户候选";
		}
		if (normalized.contains("demandprojectoptions")) {
			return "查询下单项目候选";
		}
		if (normalized.contains("demandproductoptions")) {
			return "查询下单商品候选";
		}
		if (normalized.contains("demandcreatelatest")) {
			return "查询最近下单记录";
		}
		if (normalized.contains("demandcreateexecute")) {
			return "创建需求单";
		}
		if (normalized.contains("domainbusinessknowledgesearch")) {
			return "检索业务知识";
		}
		if (normalized.contains("semanticmodelsearch")) {
			return "检索语义模型";
		}
		if (normalized.contains("sqlguardcheck")) {
			return "校验 SQL";
		}
		if (normalized.contains("datasourceskillsearch")) {
			return "查询数据";
		}
		if (normalized.contains("webfetch")) {
			return "网页读取";
		}
		if (normalized.endsWith("queryresource")) {
			return "查询能力参考记录";
		}
		if (normalized.endsWith("openinteraction")) {
			return "打开能力交互";
		}
		if (normalized.endsWith("openpage")) {
			return "打开系统页面";
		}
		if (normalized.endsWith("preview")) {
			return "预览能力交互";
		}
		if (normalized.endsWith("execute")) {
			return "提交能力执行";
		}
		return null;
	}

	private static boolean containsHan(String value) {
		if (!StringUtils.hasText(value)) {
			return false;
		}
		return value.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint)
				== Character.UnicodeScript.HAN);
	}

	private static String abbreviate(String value) {
		if (value == null) {
			return null;
		}
		return value.length() <= MAX_DISPLAY_NAME_LENGTH ? value : value.substring(0, MAX_DISPLAY_NAME_LENGTH);
	}

	private static String stringValue(Object value) {
		return value == null ? null : String.valueOf(value);
	}

}
