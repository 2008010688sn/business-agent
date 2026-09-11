/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlowSchemaCompilerTest {

	private final FlowSchemaCompiler compiler = new FlowSchemaCompiler(new ObjectMapper());

	@Test
	void compilesSparseClosedPatchWithoutBusinessIdentifiers() {
		Map<String, Object> schema = Map.of("type", "object", "required", List.of("companyName"), "properties",
				Map.of("companyId", Map.of("type", "integer"), "companyName", Map.of("type", "string"),
						"items", Map.of("type", "array", "items", Map.of("type", "object", "required",
								List.of("productName"), "properties", Map.of("productId", Map.of("type", "integer"),
										"productName", Map.of("type", "string"))))));

		FlowSchemaCompiler.CompiledSchema compiled = compiler.compile(schema);

		assertEquals(FlowSchemaCompiler.VERSION, compiler.version());
		assertFalse(compiled.fingerprint().isBlank());
		assertEquals(List.of("set"), compiled.responseSchema().get("required"));
		Map<?, ?> rootProperties = (Map<?, ?>) compiled.patchSchema().get("properties");
		assertFalse(rootProperties.containsKey("companyId"));
		assertEquals(Boolean.FALSE, compiled.patchSchema().get("additionalProperties"));
		Map<?, ?> items = (Map<?, ?>) rootProperties.get("items");
		Map<?, ?> itemSchema = (Map<?, ?>) items.get("items");
		Map<?, ?> itemProperties = (Map<?, ?>) itemSchema.get("properties");
		assertFalse(itemProperties.containsKey("productId"));
		assertFalse(itemSchema.containsKey("required"));
		assertEquals(Boolean.FALSE, itemSchema.get("additionalProperties"));
	}

	@Test
	void omitsLocalTemporalHintsFromProviderSchema() {
		Map<String, Object> schema = Map.of("type", "object", "properties",
				Map.of("arrivalTime", Map.of("type", "string", "x-temporal",
						Map.of("kind", "DATE_OR_DATE_TIME", "targetType", "INSTANT"))));

		FlowSchemaCompiler.CompiledSchema compiled = compiler.compile(schema);

		assertFalse(compiled.jsonSchema().contains("x-temporal"));
		Map<?, ?> properties = (Map<?, ?>) compiled.patchSchema().get("properties");
		Map<?, ?> arrivalTime = (Map<?, ?>) properties.get("arrivalTime");
		assertFalse(arrivalTime.containsKey("x-temporal"));
		assertEquals("string", arrivalTime.get("type"));
	}

	@Test
	void rejectsUnsupportedSchemaKeywords() {
		Map<String, Object> schema = Map.of("type", "object", "properties",
				Map.of("companyName", Map.of("type", "string", "oneOf", List.of(Map.of("type", "string")))));

		assertThrows(IllegalArgumentException.class, () -> compiler.compile(schema));
	}

}
