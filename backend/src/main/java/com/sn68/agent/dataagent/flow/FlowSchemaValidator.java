/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.stereotype.Component;

/**
 * Small deterministic JSON-Schema subset used for required fields and primitive types.
 * 递归校验嵌套对象、数组元素、枚举取值、类型、字符串长度/正则与数值范围。
 */
@Component
public class FlowSchemaValidator {

	public List<String> validate(Object value, Map<String, Object> schema) {
		return validate(value, schema, false);
	}

	/**
	 * 稀疏补丁校验：不要求 required / minItems。抽取补丁只带本轮说到的字段，
	 * 完整性由 collect 的 isIncompletePath 追问，而不是在解码阶段整包作废。
	 */
	public List<String> validateSparse(Object value, Map<String, Object> schema) {
		return validate(value, schema, true);
	}

	private List<String> validate(Object value, Map<String, Object> schema, boolean sparse) {
		List<String> errors = new ArrayList<>();
		validateValue(value, schema == null ? Map.of() : schema, "", errors, sparse);
		return List.copyOf(errors);
	}

	private void validateValue(Object value, Map<String, Object> schema, String path, List<String> errors,
			boolean sparse) {
		String type = schema.get("type") == null ? null : String.valueOf(schema.get("type"));
		if (value != null && type != null && !matchesType(value, type)) {
			errors.add(display(path) + " must be " + type);
			return;
		}
		if (value != null && !matchesEnum(value, schema.get("enum"))) {
			errors.add(display(path) + " must be one of the allowed values");
			return;
		}
		if (value instanceof String text) {
			validateText(text, schema, path, errors);
		}
		if (value instanceof Number number) {
			validateNumber(number, schema, path, errors);
		}
		if (value instanceof Map<?, ?> objectValues) {
			Map<String, Object> properties = map(schema.get("properties"));
			if (rejectsUnknownProperties(schema)) {
				for (Object key : objectValues.keySet()) {
					if (!properties.containsKey(String.valueOf(key))) {
						errors.add(display(path + "/" + key) + " is not allowed");
					}
				}
			}
			if (!sparse) {
				for (String required : strings(schema.get("required"))) {
					Object fieldValue = objectValues.get(required);
					if (fieldValue == null || (fieldValue instanceof String text && text.isBlank())) {
						errors.add(display(path + "/" + required) + " is required");
					}
				}
			}
			if (!properties.isEmpty()) {
				for (Map.Entry<String, Object> entry : properties.entrySet()) {
					if (entry.getValue() instanceof Map<?, ?> childSchema) {
						validateValue(objectValues.get(entry.getKey()), castMap(childSchema),
								path + "/" + entry.getKey(), errors, sparse);
					}
				}
			}
		}
		if (value instanceof List<?> list) {
			if (!sparse) {
				int minItems = integer(schema.get("minItems"), 0);
				if (list.size() < minItems) {
					errors.add(display(path) + " must contain at least " + minItems + " item(s)");
				}
				int maxItems = integer(schema.get("maxItems"), -1);
				if (maxItems >= 0 && list.size() > maxItems) {
					errors.add(display(path) + " must contain at most " + maxItems + " item(s)");
				}
			}
			Object itemSchema = schema.get("items");
			if (itemSchema instanceof Map<?, ?> childSchema) {
				for (int index = 0; index < list.size(); index++) {
					validateValue(list.get(index), castMap(childSchema), path + "/" + index, errors, sparse);
				}
			}
		}
	}

	private void validateText(String text, Map<String, Object> schema, String path, List<String> errors) {
		int length = text.codePointCount(0, text.length());
		int minLength = integer(schema.get("minLength"), -1);
		if (minLength >= 0 && length < minLength) {
			errors.add(display(path) + " must contain at least " + minLength + " character(s)");
		}
		int maxLength = integer(schema.get("maxLength"), -1);
		if (maxLength >= 0 && length > maxLength) {
			errors.add(display(path) + " must contain at most " + maxLength + " character(s)");
		}
		Object pattern = schema.get("pattern");
		if (pattern instanceof String expression && !expression.isBlank() && !matchesPattern(text, expression)) {
			errors.add(display(path) + " does not match the required pattern");
		}
	}

	private boolean matchesPattern(String text, String expression) {
		try {
			return Pattern.compile(expression).matcher(text).find();
		}
		catch (PatternSyntaxException ex) {
			// Schema 编译阶段限定了关键字子集但不校验正则语法；非法正则按不约束处理，避免把配置错误伪装成模型违规。
			return true;
		}
	}

	private void validateNumber(Number number, Map<String, Object> schema, String path, List<String> errors) {
		Object minimum = schema.get("minimum");
		if (minimum instanceof Number min && compare(number, min) < 0) {
			errors.add(display(path) + " must be greater than or equal to " + min);
		}
		Object maximum = schema.get("maximum");
		if (maximum instanceof Number max && compare(number, max) > 0) {
			errors.add(display(path) + " must be less than or equal to " + max);
		}
	}

	private int compare(Number left, Number right) {
		return new BigDecimal(String.valueOf(left)).compareTo(new BigDecimal(String.valueOf(right)));
	}

	private boolean matchesEnum(Object value, Object enumValues) {
		if (!(enumValues instanceof Iterable<?> allowed)) {
			return true;
		}
		for (Object candidate : allowed) {
			if (Objects.equals(candidate, value)) {
				return true;
			}
		}
		return false;
	}

	private boolean rejectsUnknownProperties(Map<String, Object> schema) {
		Object additionalProperties = schema.get("additionalProperties");
		return Boolean.FALSE.equals(additionalProperties)
				|| additionalProperties != null && "false".equalsIgnoreCase(String.valueOf(additionalProperties));
	}

	private Map<String, Object> map(Object value) {
		return value instanceof Map<?, ?> map ? castMap(map) : Map.of();
	}

	private boolean matchesType(Object value, String type) {
		return switch (type) {
			case "object" -> value instanceof Map<?, ?>;
			case "array" -> value instanceof Iterable<?>;
			case "string" -> value instanceof String;
			case "integer" -> value instanceof Byte || value instanceof Short || value instanceof Integer
					|| value instanceof Long;
			case "number" -> value instanceof Number;
			case "boolean" -> value instanceof Boolean;
			default -> true;
		};
	}

	private List<String> strings(Object value) {
		if (!(value instanceof Iterable<?> iterable)) {
			return List.of();
		}
		List<String> result = new ArrayList<>();
		iterable.forEach(item -> result.add(String.valueOf(item)));
		return result;
	}

	private int integer(Object value, int fallback) {
		if (value instanceof Number number) {
			return number.intValue();
		}
		try {
			return value == null ? fallback : Integer.parseInt(String.valueOf(value));
		}
		catch (NumberFormatException ex) {
			return fallback;
		}
	}

	private String display(String path) {
		return path == null || path.isBlank() ? "/" : path;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> castMap(Map<?, ?> value) {
		return (Map<String, Object>) value;
	}

}
