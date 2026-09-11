/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.flow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.service.tokenusage.AgentStructuredModelCallResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Converts a model response into one sparse FLOW patch.
 *
 * <p>优先读 {@code {"set":{...}}}；JSON Object 也可能直接给出 schema 形状。未知键、
 * 控制字段、Id 字段剥掉后留下合法子集，避免模型多写一个键就把整句用户信息作废。
 */
@Component
public class FlowStructuredPatchDecoder {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	/** 历史协议遗留的模型控制字段，出现即技术失败，用于给出可定位的错误消息。 */
	private static final Set<String> FORBIDDEN_CONTROL_FIELDS = Set.of("clear", "directives", "__flowclear",
			"__flowdirectives");

	private static final Pattern LEADING_INTEGER = Pattern.compile("^\\s*([+-]?\\d+)");

	private final ObjectMapper objectMapper;

	private final FlowSchemaValidator schemaValidator;

	public FlowStructuredPatchDecoder(ObjectMapper objectMapper, FlowSchemaValidator schemaValidator) {
		this.objectMapper = objectMapper;
		this.schemaValidator = schemaValidator;
	}

	public Map<String, Object> decode(FlowStructuredOutputProtocol protocol,
			AgentStructuredModelCallResult modelCall, Map<String, Object> patchSchema) {
		if (protocol == null || protocol == FlowStructuredOutputProtocol.NONE || modelCall == null) {
			throw invalid("FLOW structured response is unavailable");
		}
		Map<String, Object> raw = protocol == FlowStructuredOutputProtocol.FUNCTION_CALL
				? readFunctionArguments(modelCall.toolCalls()) : readTextResponse(modelCall);
		Map<String, Object> patch = unwrap(raw);
		validatePatch(patch, patchSchema);
		return patch;
	}

	private Map<String, Object> readFunctionArguments(List<AssistantMessage.ToolCall> toolCalls) {
		if (toolCalls == null || toolCalls.size() != 1) {
			throw invalid("FLOW Function Calling response must contain exactly one tool call");
		}
		AssistantMessage.ToolCall toolCall = toolCalls.get(0);
		if (toolCall == null || !FlowPatchSubmissionToolCallback.NAME.equals(toolCall.name())
				|| !StringUtils.hasText(toolCall.arguments())) {
			throw invalid("FLOW Function Calling response must submit one named patch");
		}
		return readObject(toolCall.arguments());
	}

	private Map<String, Object> readTextResponse(AgentStructuredModelCallResult modelCall) {
		if (modelCall.toolCalls() != null && !modelCall.toolCalls().isEmpty()) {
			throw invalid("FLOW JSON response must not contain tool calls");
		}
		return readObject(modelCall.text());
	}

	private Map<String, Object> readObject(String content) {
		if (!StringUtils.hasText(content)) {
			throw invalid("FLOW structured response must be a JSON object");
		}
		try {
			JsonNode node = objectMapper.readTree(unwrapMarkdownFence(content));
			if (node == null || !node.isObject()) {
				throw invalid("FLOW structured response must be a JSON object");
			}
			return objectMapper.convertValue(node, MAP_TYPE);
		}
		catch (FlowExtractionException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new FlowExtractionException(FlowExtractionException.INVALID_RESPONSE,
					"FLOW structured response is invalid", ex);
		}
	}

	private String unwrapMarkdownFence(String content) {
		String trimmed = content.trim();
		if (!trimmed.startsWith("```")) {
			return content;
		}
		int firstNewline = trimmed.indexOf('\n');
		int lastFence = trimmed.lastIndexOf("```");
		if (firstNewline < 0 || lastFence <= firstNewline) {
			return content;
		}
		return trimmed.substring(firstNewline + 1, lastFence).trim();
	}

	/**
	 * 有 {@code set} 就用 set（忽略旁边多出来的顶层键）；否则把整个对象当稀疏补丁。
	 */
	private Map<String, Object> unwrap(Map<String, Object> raw) {
		if (raw == null || raw.isEmpty()) {
			throw invalid("FLOW structured response must contain a JSON object");
		}
		Object setValue = raw.get(FlowStructuredOutputProtocol.SET_FIELD);
		if (setValue instanceof Map<?, ?> values) {
			return copyMap(values);
		}
		return copyMap(raw);
	}

	private void validatePatch(Map<String, Object> patch, Map<String, Object> patchSchema) {
		Map<String, Object> sanitized = sanitize(patch, patchSchema == null ? Map.of() : patchSchema);
		if (sanitized.isEmpty()) {
			throw invalid("FLOW structured response contains no writable schema fields");
		}
		patch.clear();
		patch.putAll(sanitized);
		List<String> errors = schemaValidator.validateSparse(patch, patchSchema == null ? Map.of() : patchSchema);
		if (!errors.isEmpty()) {
			throw invalid("FLOW structured response values do not match the writable schema: "
					+ String.join("; ", errors));
		}
	}

	Map<String, Object> sanitize(Map<String, Object> patch, Map<String, Object> schema) {
		return sanitizeObject(patch, schema);
	}

	private Map<String, Object> sanitizeObject(Map<String, Object> patch, Map<String, Object> schema) {
		Map<String, Object> properties = schemaProperties(schema);
		Map<String, Object> result = new LinkedHashMap<>();
		if (patch == null || patch.isEmpty()) {
			return result;
		}
		for (Map.Entry<String, Object> entry : patch.entrySet()) {
			String field = entry.getKey();
			if (!StringUtils.hasText(field) || isForbiddenControlField(field) || isIdentifierField(field)) {
				continue;
			}
			if (!properties.isEmpty() && !properties.containsKey(field)) {
				continue;
			}
			Map<String, Object> fieldSchema = properties.isEmpty() ? Map.of() : map(properties.get(field));
			Object sanitized = sanitizeValue(entry.getValue(), fieldSchema);
			if (sanitized != null) {
				result.put(field, sanitized);
			}
		}
		return result;
	}

	private Object sanitizeValue(Object value, Map<String, Object> schema) {
		if (value == null) {
			return null;
		}
		Object coerced = coerceType(value, schema);
		if (coerced == null) {
			return null;
		}
		String type = schema.get("type") == null ? null : String.valueOf(schema.get("type"));
		if (coerced instanceof Map<?, ?> objectValues) {
			Map<String, Object> nested = sanitizeObject(copyMap(objectValues), schema);
			return nested.isEmpty() ? null : nested;
		}
		if (coerced instanceof Iterable<?> items && (type == null || "array".equals(type))) {
			Map<String, Object> itemSchema = map(schema.get("items"));
			List<Object> sanitizedItems = new ArrayList<>();
			for (Object item : items) {
				Object sanitizedItem = sanitizeValue(item, itemSchema);
				if (sanitizedItem != null) {
					sanitizedItems.add(sanitizedItem);
				}
			}
			return sanitizedItems.isEmpty() ? null : sanitizedItems;
		}
		return coerced;
	}

	private Object coerceType(Object value, Map<String, Object> schema) {
		String type = schema.get("type") == null ? null : String.valueOf(schema.get("type"));
		if (!StringUtils.hasText(type) || "object".equals(type) || "array".equals(type)) {
			return value;
		}
		if ("string".equals(type)) {
			if (value instanceof String text) {
				return text;
			}
			if (value instanceof Number number) {
				return stripTrailingZero(number);
			}
			if (value instanceof Boolean) {
				return String.valueOf(value);
			}
			return null;
		}
		if ("integer".equals(type)) {
			return toInteger(value);
		}
		if ("number".equals(type)) {
			return toNumber(value);
		}
		if ("boolean".equals(type)) {
			if (value instanceof Boolean bool) {
				return bool;
			}
			return null;
		}
		return value;
	}

	private Long toInteger(Object value) {
		if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
			return ((Number) value).longValue();
		}
		if (value instanceof Number number) {
			BigDecimal decimal = new BigDecimal(String.valueOf(number));
			if (decimal.stripTrailingZeros().scale() <= 0) {
				return decimal.longValueExact();
			}
			return null;
		}
		if (value instanceof String text && StringUtils.hasText(text)) {
			try {
				BigDecimal decimal = new BigDecimal(text.trim());
				if (decimal.stripTrailingZeros().scale() <= 0) {
					return decimal.longValueExact();
				}
			}
			catch (RuntimeException ignored) {
				Matcher matcher = LEADING_INTEGER.matcher(text);
				if (matcher.find()) {
					return Long.parseLong(matcher.group(1));
				}
				return null;
			}
		}
		return null;
	}

	private Number toNumber(Object value) {
		if (value instanceof Number number) {
			return number;
		}
		if (value instanceof String text && StringUtils.hasText(text)) {
			try {
				return new BigDecimal(text.trim());
			}
			catch (RuntimeException ignored) {
				return null;
			}
		}
		return null;
	}

	private String stripTrailingZero(Number number) {
		BigDecimal decimal = new BigDecimal(String.valueOf(number));
		if (decimal.stripTrailingZeros().scale() <= 0) {
			return Long.toString(decimal.longValue());
		}
		return decimal.stripTrailingZeros().toPlainString();
	}

	private Map<String, Object> map(Object value) {
		return value instanceof Map<?, ?> source ? copyMap(source) : Map.of();
	}

	private boolean isIdentifierField(String field) {
		String normalized = field == null ? "" : field.trim();
		String lower = normalized.toLowerCase(Locale.ROOT);
		return "id".equals(lower) || "ids".equals(lower) || lower.endsWith("_id") || lower.endsWith("_ids")
				|| normalized.endsWith("Id") || normalized.endsWith("Ids") || normalized.endsWith("ID")
				|| normalized.endsWith("IDs");
	}

	private boolean isForbiddenControlField(String field) {
		String normalized = field == null ? "" : field.trim().toLowerCase(Locale.ROOT);
		return FORBIDDEN_CONTROL_FIELDS.contains(normalized) || normalized.startsWith("__flow");
	}

	private Map<String, Object> schemaProperties(Map<String, Object> schema) {
		if (schema == null || !(schema.get("properties") instanceof Map<?, ?> properties)) {
			return Map.of();
		}
		return copyMap(properties);
	}

	private Map<String, Object> copyMap(Map<?, ?> values) {
		Map<String, Object> result = new LinkedHashMap<>();
		values.forEach((key, value) -> result.put(String.valueOf(key), value));
		return result;
	}

	private FlowExtractionException invalid(String message) {
		return new FlowExtractionException(FlowExtractionException.INVALID_RESPONSE, message, null);
	}

}
