/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.agentscope.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工具结果预算裁剪器：结果进入模型上下文前的第一层源上裁剪。
 *
 * <p>超限时优先按 JSON 结构裁剪——对根对象里的数组字段截前 N 个元素并补 {@code "truncated": true}；
 * 解析失败或重组后仍超限，退化为保留头部并追加截断标记。最终长度不超过 {@code maxChars + 截断标记}，
 * 避免 NL2SQL 的 schema dump 全文（实测 15KB+）挤占模型窗口。
 */
public final class ToolResultBudgetSanitizer {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private static final String TRUNCATION_MARK = "\n...(结果超限已截断)";

	private static final String TRUNCATED_FIELD = "truncated";

	private ToolResultBudgetSanitizer() {
	}

	/**
	 * 结果未超限原样返回；{@code maxChars <= 0} 视为不限制。
	 */
	public static String sanitize(String result, int maxChars, int headKeepChars) {
		if (result == null || maxChars <= 0 || result.length() <= maxChars) {
			return result;
		}
		if (isJson(result)) {
			String structured = truncateJsonArrays(result, maxChars);
			if (structured != null) {
				return structured;
			}
		}
		return headTruncate(result, headKeepChars, maxChars);
	}

	private static boolean isJson(String result) {
		char first = result.charAt(0);
		return first == '{' || first == '[';
	}

	/**
	 * 对根对象中的数组字段自适应截前 N 个元素（N 从最大数组长度起减半直到放得下，最少保留 1 个），
	 * 并在根对象上补 {@code "truncated": true}；根不是对象、没有数组字段或截到 1 个仍放不下时
	 * 返回 null，由调用方退化为头部截断。
	 */
	private static String truncateJsonArrays(String result, int maxChars) {
		try {
			JsonNode root = OBJECT_MAPPER.readTree(result);
			if (root == null || !root.isObject()) {
				return null;
			}
			List<Map.Entry<String, JsonNode>> arrayFields = new ArrayList<>();
			root.fields().forEachRemaining(field -> {
				if (field.getValue().isArray() && field.getValue().size() > 0) {
					arrayFields.add(field);
				}
			});
			if (arrayFields.isEmpty()) {
				return null;
			}
			int maxArraySize = 0;
			for (Map.Entry<String, JsonNode> field : arrayFields) {
				maxArraySize = Math.max(maxArraySize, field.getValue().size());
			}
			for (int keep = maxArraySize; keep >= 1; keep = keep / 2) {
				String candidateJson = OBJECT_MAPPER.writeValueAsString(
						rebuildWithArrayHeads((ObjectNode) root, arrayFields, keep));
				if (candidateJson.length() <= maxChars) {
					return candidateJson;
				}
			}
			return null;
		}
		catch (Exception ex) {
			return null;
		}
	}

	private static ObjectNode rebuildWithArrayHeads(ObjectNode root, List<Map.Entry<String, JsonNode>> arrayFields,
			int keep) {
		ObjectNode candidate = root.deepCopy();
		for (Map.Entry<String, JsonNode> field : arrayFields) {
			ArrayNode head = candidate.putArray(field.getKey());
			JsonNode source = field.getValue();
			for (int i = 0; i < keep && i < source.size(); i++) {
				head.add(source.get(i).deepCopy());
			}
		}
		candidate.put(TRUNCATED_FIELD, true);
		return candidate;
	}

	private static String headTruncate(String result, int headKeepChars, int maxChars) {
		int keep = headKeepChars > 0 ? Math.min(headKeepChars, maxChars) : maxChars;
		return result.substring(0, Math.min(keep, result.length())) + TRUNCATION_MARK;
	}

}
