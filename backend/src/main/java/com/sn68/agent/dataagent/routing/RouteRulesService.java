/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.dto.routing.RouteRulesDTO;
import com.sn68.agent.dataagent.routing.model.RouteRules;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class RouteRulesService {

	private static final int MAX_ITEMS_PER_FIELD = 100;

	private static final int MAX_ITEM_LENGTH = 200;

	private static final int MAX_SERIALIZED_BYTES = 32 * 1024;

	private static final int MAX_WILDCARDS_PER_PATTERN = 3;

	private static final int MAX_TOTAL_ITEMS = 300;

	private static final List<String> FIELDS = List.of("exact", "phrases", "aliases", "positiveExamples",
			"positivePatterns", "negativeExamples", "hardExcludes", "hardExcludePatterns",
			"allowFlowAutoSelect");

	private static final Set<String> POSITIVE_FIELDS = Set.of("exact", "phrases", "aliases", "positiveExamples",
			"positivePatterns");

	private final ObjectMapper objectMapper;

	private final RouteTextNormalizer normalizer;

	public RouteRulesService(ObjectMapper objectMapper, RouteTextNormalizer normalizer) {
		this.objectMapper = objectMapper;
		this.normalizer = normalizer;
	}

	public RouteRules normalize(Map<String, Object> source) {
		Map<String, Object> safeSource = source == null ? Map.of() : source;
		rejectUnknownFields(safeSource.keySet());
		RouteRules rules = new RouteRules(normalizeList(safeSource, "exact"), normalizeList(safeSource, "phrases"),
				normalizeList(safeSource, "aliases"), normalizeList(safeSource, "positiveExamples"),
				normalizePatternList(safeSource, "positivePatterns"), normalizeList(safeSource, "negativeExamples"),
				normalizeList(safeSource, "hardExcludes"), normalizePatternList(safeSource, "hardExcludePatterns"),
				readBoolean(safeSource, "allowFlowAutoSelect"));
		validateConflicts(rules);
		validateItemCount(rules);
		validateSerializedSize(rules);
		return rules;
	}

	public RouteRules parse(String json) {
		if (json == null || json.isBlank()) {
			return RouteRules.empty();
		}
		try {
			JsonNode root = objectMapper.readTree(json);
			if (root == null || !root.isObject()) {
				throw CheckedException.badRequest("路由规则必须是 JSON 对象");
			}
			Map<String, Object> values = objectMapper.convertValue(root, Map.class);
			return normalize(values);
		}
		catch (JsonProcessingException | IllegalArgumentException ex) {
			throw CheckedException.badRequest("路由规则 JSON 无效");
		}
	}

	public Map<String, Object> toMap(RouteRules rules) {
		RouteRules safeRules = rules == null ? RouteRules.empty() : rules;
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("exact", safeRules.exact());
		result.put("phrases", safeRules.phrases());
		result.put("aliases", safeRules.aliases());
		result.put("positiveExamples", safeRules.positiveExamples());
		result.put("negativeExamples", safeRules.negativeExamples());
		result.put("hardExcludes", safeRules.hardExcludes());
		if (!safeRules.positivePatterns().isEmpty()) {
			result.put("positivePatterns", safeRules.positivePatterns());
		}
		if (!safeRules.hardExcludePatterns().isEmpty()) {
			result.put("hardExcludePatterns", safeRules.hardExcludePatterns());
		}
		if (safeRules.allowFlowAutoSelect()) {
			result.put("allowFlowAutoSelect", true);
		}
		return result;
	}

	public String toJson(RouteRules rules) {
		try {
			return objectMapper.writeValueAsString(toMap(rules));
		}
		catch (JsonProcessingException ex) {
			throw new IllegalStateException("Failed to serialize route rules", ex);
		}
	}

	/**
	 * Converts validated domain rules into the fixed response contract.
	 */
	public RouteRulesDTO toResponseDTO(RouteRules rules) {
		RouteRules safeRules = rules == null ? RouteRules.empty() : rules;
		return new RouteRulesDTO(safeRules.exact(), safeRules.phrases(), safeRules.aliases(),
				safeRules.positiveExamples(), safeRules.positivePatterns(), safeRules.negativeExamples(),
				safeRules.hardExcludes(), safeRules.hardExcludePatterns(), safeRules.allowFlowAutoSelect());
	}

	/**
	 * Converts validated domain rules into a complete map for map-based response payloads.
	 */
	public Map<String, Object> toResponseMap(RouteRules rules) {
		RouteRulesDTO response = toResponseDTO(rules);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("exact", response.exact());
		result.put("phrases", response.phrases());
		result.put("aliases", response.aliases());
		result.put("positiveExamples", response.positiveExamples());
		result.put("positivePatterns", response.positivePatterns());
		result.put("negativeExamples", response.negativeExamples());
		result.put("hardExcludes", response.hardExcludes());
		result.put("hardExcludePatterns", response.hardExcludePatterns());
		result.put("allowFlowAutoSelect", response.allowFlowAutoSelect());
		return result;
	}

	private List<String> normalizeList(Map<String, Object> source, String field) {
		return normalizeList(source, field, false);
	}

	private List<String> normalizePatternList(Map<String, Object> source, String field) {
		return normalizeList(source, field, true);
	}

	private List<String> normalizeList(Map<String, Object> source, String field, boolean pattern) {
		if (!source.containsKey(field)) {
			return List.of();
		}
		Object raw = source.get(field);
		if (!(raw instanceof Iterable<?> iterable)) {
			throw CheckedException.badRequest("路由规则字段 " + field + " 必须是字符串数组");
		}
		Set<String> normalized = new LinkedHashSet<>();
		for (Object item : iterable) {
			if (!(item instanceof String text)) {
				throw CheckedException.badRequest("路由规则字段 " + field + " 只能包含字符串");
			}
			String value = pattern ? normalizePattern(text, field) : normalizer.normalize(text);
			if (value.isBlank()) {
				continue;
			}
			if (value.length() > MAX_ITEM_LENGTH) {
				throw CheckedException.badRequest("路由规则字段 " + field + " 的单条内容不能超过 200 字");
			}
			normalized.add(value);
		}
		if (normalized.size() > MAX_ITEMS_PER_FIELD) {
			throw CheckedException.badRequest("路由规则字段 " + field + " 不能超过 100 条");
		}
		return List.copyOf(normalized);
	}

	private String normalizePattern(String text, String field) {
		String value = text.trim();
		if (value.isBlank()) {
			throw CheckedException.badRequest("Route rule pattern " + field + " must not be blank");
		}
		long wildcardCount = value.chars().filter(character -> character == '*').count();
		if (wildcardCount > MAX_WILDCARDS_PER_PATTERN || value.contains("**")) {
			throw CheckedException.badRequest("Route rule pattern " + field + " has invalid wildcard syntax");
		}
		String[] segments = value.split("\\*", -1);
		StringBuilder normalized = new StringBuilder(value.length());
		boolean hasTextSegment = false;
		for (int index = 0; index < segments.length; index++) {
			String segment = normalizer.normalize(segments[index]);
			if (segment.isBlank() && index > 0 && index < segments.length - 1) {
				throw CheckedException.badRequest("Route rule pattern " + field + " has an empty wildcard segment");
			}
			if (index > 0) {
				normalized.append('*');
			}
			normalized.append(segment);
			hasTextSegment |= !segment.isBlank();
		}
		if (!hasTextSegment) {
			throw CheckedException.badRequest("Route rule pattern " + field + " must not be only a wildcard");
		}
		if (normalized.length() > MAX_ITEM_LENGTH) {
			throw CheckedException.badRequest("路由规则 pattern " + field + " 的单项内容不能超过 200 字符");
		}
		return normalized.toString();
	}

	private boolean readBoolean(Map<String, Object> source, String field) {
		if (!source.containsKey(field)) {
			return false;
		}
		Object raw = source.get(field);
		if (!(raw instanceof Boolean value)) {
			throw CheckedException.badRequest("路由规则字段 " + field + " 必须是布尔值");
		}
		return value;
	}

	private void rejectUnknownFields(Set<String> fields) {
		List<String> unknown = fields.stream().filter(field -> !FIELDS.contains(field)).sorted().toList();
		if (!unknown.isEmpty()) {
			throw CheckedException.badRequest("路由规则包含未知字段: " + String.join(", ", unknown));
		}
	}

	private void validateConflicts(RouteRules rules) {
		Set<String> hardExcludes = new LinkedHashSet<>(rules.hardExcludes());
		Map<String, List<String>> values = Map.of("exact", rules.exact(), "phrases", rules.phrases(), "aliases",
				rules.aliases(), "positiveExamples", rules.positiveExamples(), "positivePatterns",
				rules.positivePatterns());
		List<String> conflicts = new ArrayList<>();
		for (String field : POSITIVE_FIELDS) {
			for (String value : values.get(field)) {
				if (hardExcludes.contains(value)) {
					conflicts.add(field + ":" + value);
				}
			}
		}
		if (!conflicts.isEmpty()) {
			throw CheckedException.badRequest("正向路由规则不能同时出现在 hardExcludes: "
					+ String.join(", ", conflicts));
		}
	}

	private void validateItemCount(RouteRules rules) {
		int count = rules.exact().size() + rules.phrases().size() + rules.aliases().size()
				+ rules.positiveExamples().size() + rules.positivePatterns().size() + rules.negativeExamples().size()
				+ rules.hardExcludes().size() + rules.hardExcludePatterns().size();
		if (count > MAX_TOTAL_ITEMS) {
			throw CheckedException.badRequest("路由规则条目总数不能超过 300 条");
		}
	}

	private void validateSerializedSize(RouteRules rules) {
		int bytes = toJson(rules).getBytes(StandardCharsets.UTF_8).length;
		if (bytes > MAX_SERIALIZED_BYTES) {
			throw CheckedException.badRequest("路由规则总大小不能超过 32KB");
		}
	}

}
