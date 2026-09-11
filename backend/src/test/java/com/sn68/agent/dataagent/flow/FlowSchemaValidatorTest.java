/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlowSchemaValidatorTest {

	private final FlowSchemaValidator validator = new FlowSchemaValidator();

	@Test
	void rejectsUnknownFieldsEnumsAndInvalidArrayItemsRecursively() {
		Map<String, Object> schema = Map.of("type", "object", "additionalProperties", false, "properties", Map.of(
				"type", Map.of("type", "string", "enum", List.of("DELIVERY", "RETURN")),
				"items", Map.of("type", "array", "items", Map.of("type", "object", "additionalProperties", false,
						"properties", Map.of("quantity", Map.of("type", "integer"))))));

		List<String> errors = validator.validate(Map.of("type", "OTHER", "items",
				List.of(Map.of("quantity", "two", "internalId", 1L)), "unexpected", true), schema);

		assertFalse(errors.isEmpty());
		assertTrue(errors.stream().anyMatch(error -> error.contains("/type")));
		assertTrue(errors.stream().anyMatch(error -> error.contains("/items/0/quantity")));
		assertTrue(errors.stream().anyMatch(error -> error.contains("/items/0/internalId")));
		assertTrue(errors.stream().anyMatch(error -> error.contains("/unexpected")));
	}

}
