/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * 从「A到B」类自然语言确定性抽出两条集合明细（发货/收货），不调用模型、不解析主数据 ID。
 */
public final class FlowAddressPairExtractor {

	private static final int MIN_NAME_LENGTH = 2;

	private static final List<String> DEFAULT_FROM_LABELS = List.of("发货", "提货");

	private static final List<String> DEFAULT_TO_LABELS = List.of("收货", "到达");

	/** 比「到」更长的说法，避免把「送到」切成「…送」+「到」。仅在配置已含「到」时启用。 */
	private static final List<String> TO_SEPARATOR_EXTENSIONS = List.of("送到", "运到", "发到", "寄到");

	private FlowAddressPairExtractor() {
	}

	public static Map<String, Object> extract(String query, List<Map<String, Object>> configs) {
		if (!StringUtils.hasText(query) || configs == null || configs.isEmpty()) {
			return Map.of();
		}
		Map<String, Object> result = new LinkedHashMap<>();
		for (Map<String, Object> config : configs) {
			if (config == null || config.isEmpty()) {
				continue;
			}
			String field = fieldName(config);
			List<Map<String, Object>> items = parsePair(query, config);
			if (items.size() != 2) {
				items = parseRolePrefixedPair(query, config);
			}
			if (StringUtils.hasText(field) && items.size() == 2) {
				result.put(field, items);
			}
		}
		return result;
	}

	private static List<Map<String, Object>> parsePair(String query, Map<String, Object> config) {
		String separator = findSeparator(query, separators(config));
		if (!StringUtils.hasText(separator)) {
			return List.of();
		}
		String source = query.trim();
		if (source.startsWith("从") && source.length() > 1) {
			source = source.substring(1).trim();
		}
		int index = source.indexOf(separator);
		if (index <= 0 || index + separator.length() >= source.length()) {
			return List.of();
		}
		String fromName = lastUsableName(source.substring(0, index).trim());
		String toName = firstUsableName(source.substring(index + separator.length()).trim());
		fromName = stripRoleGlue(stripLeadingLabels(fromName, labels(config.get("fromLabels"), DEFAULT_FROM_LABELS)));
		toName = stripRoleGlue(stripLeadingLabels(toName, labels(config.get("toLabels"), DEFAULT_TO_LABELS)));
		if (!usableName(fromName) || !usableName(toName) || fromName.equals(toName)) {
			return List.of();
		}
		return namedPair(config, fromName, toName);
	}

	private static List<Map<String, Object>> parseRolePrefixedPair(String query, Map<String, Object> config) {
		String fromLabel = findLabel(query, labels(config.get("fromLabels"), DEFAULT_FROM_LABELS), 0);
		if (!StringUtils.hasText(fromLabel)) {
			return List.of();
		}
		int fromIndex = query.indexOf(fromLabel);
		int fromEnd = fromIndex + fromLabel.length();
		String toLabel = findLabel(query, labels(config.get("toLabels"), DEFAULT_TO_LABELS), fromEnd);
		if (!StringUtils.hasText(toLabel)) {
			return List.of();
		}
		int toIndex = query.indexOf(toLabel, fromEnd);
		String fromName = stripRoleGlue(query.substring(fromEnd, toIndex));
		String toName = stripRoleGlue(query.substring(toIndex + toLabel.length()));
		if (!usableName(fromName) || !usableName(toName) || fromName.equals(toName)) {
			return List.of();
		}
		return namedPair(config, fromName, toName);
	}

	private static List<Map<String, Object>> namedPair(Map<String, Object> config, String fromName, String toName) {
		String nameField = firstText(text(config.get("nameField")), "siteName");
		String typeField = firstText(text(config.get("typeField")), "type");
		Map<String, Object> from = new LinkedHashMap<>();
		from.put(nameField, fromName);
		from.put(typeField, firstText(text(config.get("fromValue")), "0"));
		Map<String, Object> to = new LinkedHashMap<>();
		to.put(nameField, toName);
		to.put(typeField, firstText(text(config.get("toValue")), "1"));
		return List.of(from, to);
	}

	private static String findLabel(String query, List<String> labels, int fromIndex) {
		String best = null;
		int bestIndex = Integer.MAX_VALUE;
		for (String label : labels) {
			if (!StringUtils.hasText(label)) {
				continue;
			}
			int index = query.indexOf(label, fromIndex);
			if (index < 0) {
				continue;
			}
			if (index < bestIndex || (index == bestIndex && (best == null || label.length() > best.length()))) {
				best = label;
				bestIndex = index;
			}
		}
		return best;
	}

	private static List<String> labels(Object configured, List<String> defaults) {
		List<String> values = strings(configured);
		return values.isEmpty() ? defaults : values;
	}

	private static String stripRoleGlue(String value) {
		String text = value == null ? "" : value.trim();
		text = text.replaceFirst("^(网点|地址|[是为:：]\\s*)+", "");
		text = text.replaceFirst("[，。、；;,.]+$", "");
		return text.trim();
	}

	private static String stripLeadingLabels(String value, List<String> labels) {
		String text = value == null ? "" : value.trim();
		boolean stripped = true;
		while (stripped && StringUtils.hasText(text)) {
			stripped = false;
			String best = null;
			for (String label : labels) {
				if (StringUtils.hasText(label) && text.startsWith(label)
						&& (best == null || label.length() > best.length())) {
					best = label;
				}
			}
			if (best != null) {
				text = stripRoleGlue(text.substring(best.length()));
				stripped = true;
			}
		}
		return text;
	}

	private static String lastUsableName(String value) {
		String[] parts = splitNameParts(value);
		if (parts.length <= 1) {
			return value == null ? "" : value.trim();
		}
		for (int i = parts.length - 1; i >= 0; i--) {
			if (usableName(parts[i])) {
				return parts[i];
			}
		}
		return value.trim();
	}

	private static String firstUsableName(String value) {
		String[] parts = splitNameParts(value);
		if (parts.length <= 1) {
			return value == null ? "" : value.trim();
		}
		for (String part : parts) {
			if (usableName(part)) {
				return part;
			}
		}
		return value.trim();
	}

	private static String[] splitNameParts(String value) {
		if (!StringUtils.hasText(value)) {
			return new String[0];
		}
		return value.trim().split("[\\s,，、;；]+");
	}

	private static List<String> separators(Map<String, Object> config) {
		List<String> configured = strings(config.get("separators"));
		if (configured.isEmpty()) {
			return List.of();
		}
		List<String> values = new ArrayList<>(configured);
		if (configured.contains("到")) {
			for (String extra : TO_SEPARATOR_EXTENSIONS) {
				if (!values.contains(extra)) {
					values.add(extra);
				}
			}
		}
		return values;
	}

	private static String findSeparator(String query, List<String> separators) {
		String best = null;
		for (String separator : separators) {
			if (!StringUtils.hasText(separator) || !query.contains(separator)) {
				continue;
			}
			if (best == null || separator.length() > best.length()) {
				best = separator;
			}
		}
		return best;
	}

	private static boolean usableName(String name) {
		return StringUtils.hasText(name) && name.codePointCount(0, name.length()) >= MIN_NAME_LENGTH
				&& !name.contains("哪些") && !name.contains("什么") && !name.contains("时间");
	}

	private static String fieldName(Map<String, Object> config) {
		String field = text(config.get("field"));
		if (!StringUtils.hasText(field)) {
			field = text(config.get("path"));
		}
		if (!StringUtils.hasText(field)) {
			return "";
		}
		if (field.startsWith("/")) {
			int last = field.lastIndexOf('/');
			return last >= 0 && last < field.length() - 1 ? field.substring(last + 1) : field.substring(1);
		}
		return field;
	}

	private static List<String> strings(Object value) {
		if (!(value instanceof Iterable<?> iterable)) {
			return List.of();
		}
		List<String> result = new ArrayList<>();
		iterable.forEach(item -> {
			if (item != null && StringUtils.hasText(String.valueOf(item))) {
				result.add(String.valueOf(item));
			}
		});
		return result;
	}

	private static String text(Object value) {
		return value == null ? "" : String.valueOf(value).trim();
	}

	private static String firstText(String value, String fallback) {
		return StringUtils.hasText(value) ? value : fallback;
	}
}
