/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * 将 FLOW SELECT 候选项格式化为可回复的编号文本。Web 卡片仍走 payload.options，IM 必须把选项写进正文。
 */
public final class FlowSelectTextSupport {

	public static final int MAX_OPTIONS = 20;

	public static final String REPLY_HINT = "请回复序号、候选名称或业务编码。";

	public static final String TOO_MANY = "匹配项较多，请提供更具体的名称或编码。";

	public static final String NOT_FOUND = "暂未找到匹配项，请换一个名称或编码重试。";

	private FlowSelectTextSupport() {
	}

	public static boolean alreadyFormatted(String text) {
		if (!StringUtils.hasText(text)) {
			return false;
		}
		return text.contains(REPLY_HINT) || text.contains(TOO_MANY) || text.contains("未找到匹配项");
	}

	public static String format(String prompt, List<Map<String, Object>> options) {
		return format(prompt, options, List.of());
	}

	public static String format(String prompt, List<Map<String, Object>> options, List<String> skipCommands) {
		String base = prompt == null ? "" : prompt;
		if (alreadyFormatted(base)) {
			return base;
		}
		List<Map<String, Object>> safeOptions = options == null ? List.of() : options;
		if (safeOptions.isEmpty()) {
			return appendLine(base, NOT_FOUND);
		}
		if (safeOptions.size() > MAX_OPTIONS) {
			return appendLine(base, TOO_MANY);
		}
		StringBuilder result = new StringBuilder(base);
		for (int i = 0; i < safeOptions.size(); i++) {
			Map<String, Object> option = safeOptions.get(i);
			result.append('\n').append(i + 1).append(". ").append(label(option));
			String summary = textValue(option == null ? null : option.get("summary"));
			if (StringUtils.hasText(summary)) {
				result.append(" - ").append(summary);
			}
			String displayFields = renderDisplayFields(option == null ? null : option.get("displayFields"));
			if (StringUtils.hasText(displayFields)) {
				result.append(" - ").append(displayFields);
			}
		}
		result.append('\n').append(REPLY_HINT);
		List<String> skips = skipCommands == null ? List.of() : skipCommands.stream()
			.filter(StringUtils::hasText)
			.toList();
		if (!skips.isEmpty()) {
			result.append('\n').append("不需要时可回复：").append(String.join("、", skips));
		}
		return result.toString();
	}

	private static String label(Map<String, Object> option) {
		if (option == null) {
			return "";
		}
		return textValue(option.get("label"));
	}

	private static String renderDisplayFields(Object value) {
		if (!(value instanceof Iterable<?> fields)) {
			return "";
		}
		List<String> values = new ArrayList<>();
		for (Object field : fields) {
			if (!(field instanceof Map<?, ?> map)) {
				continue;
			}
			String fieldLabel = textValue(map.get("label"));
			String fieldValue = textValue(map.get("value"));
			if (StringUtils.hasText(fieldLabel) && StringUtils.hasText(fieldValue)) {
				values.add(fieldLabel + "：" + fieldValue);
			}
		}
		return String.join("，", values);
	}

	private static String appendLine(String base, String suffix) {
		if (!StringUtils.hasText(base)) {
			return suffix;
		}
		return base.contains(suffix) ? base : base + "\n" + suffix;
	}

	private static String textValue(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

}
