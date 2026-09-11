/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Evaluates the restricted, script-free FLOW condition DSL.
 */
@Component
public class FlowConditionEvaluator {

	private final FlowContextMapper contextMapper;

	public FlowConditionEvaluator(FlowContextMapper contextMapper) {
		this.contextMapper = contextMapper;
	}

	public boolean evaluate(Map<String, Object> condition, Map<String, Object> context) {
		if (condition == null || condition.isEmpty()) {
			return false;
		}
		String op = String.valueOf(condition.get("op"));
		Object actual = condition.get("path") == null ? null
				: contextMapper.get(context, String.valueOf(condition.get("path")));
		Object expected = condition.get("value");
		return switch (op) {
			case "eq" -> equalsValue(actual, expected);
			case "ne" -> !equalsValue(actual, expected);
			case "empty" -> contextMapper.isEmpty(actual);
			case "notEmpty" -> !contextMapper.isEmpty(actual);
			case "gt" -> compare(actual, expected) > 0;
			case "gte" -> compare(actual, expected) >= 0;
			case "lt" -> compare(actual, expected) < 0;
			case "lte" -> compare(actual, expected) <= 0;
			case "in" -> expected instanceof Collection<?> values && values.stream().anyMatch(item -> equalsValue(actual, item));
			case "contains" -> contains(actual, expected);
			case "all" -> nested(condition).stream().allMatch(item -> evaluate(item, context));
			case "any" -> nested(condition).stream().anyMatch(item -> evaluate(item, context));
			case "not" -> !evaluate(single(condition), context);
			default -> false;
		};
	}

	private boolean contains(Object actual, Object expected) {
		if (actual instanceof Collection<?> values) {
			return values.stream().anyMatch(item -> equalsValue(item, expected));
		}
		return actual != null && expected != null && String.valueOf(actual).contains(String.valueOf(expected));
	}

	private int compare(Object actual, Object expected) {
		BigDecimal left = decimal(actual);
		BigDecimal right = decimal(expected);
		return left == null || right == null ? Integer.MIN_VALUE : left.compareTo(right);
	}

	private BigDecimal decimal(Object value) {
		try {
			return value == null ? null : new BigDecimal(String.valueOf(value));
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private boolean equalsValue(Object left, Object right) {
		if (left instanceof Number || right instanceof Number) {
			BigDecimal leftNumber = decimal(left);
			BigDecimal rightNumber = decimal(right);
			return leftNumber != null && rightNumber != null && leftNumber.compareTo(rightNumber) == 0;
		}
		return java.util.Objects.equals(left, right);
	}

	private List<Map<String, Object>> nested(Map<String, Object> condition) {
		Object value = condition.get("conditions");
		if (!(value instanceof Iterable<?> iterable)) {
			return List.of();
		}
		java.util.ArrayList<Map<String, Object>> result = new java.util.ArrayList<>();
		for (Object item : iterable) {
			if (item instanceof Map<?, ?> map) {
				result.add(castMap(map));
			}
		}
		return result;
	}

	private Map<String, Object> single(Map<String, Object> condition) {
		Object value = condition.get("condition");
		return value instanceof Map<?, ?> map ? castMap(map) : Map.of();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> castMap(Map<?, ?> value) {
		return (Map<String, Object>) value;
	}

}
