/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.sn68.agent.dataagent.dto.routing.RouteRulesDTO;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RouteRulesContractArtifactTest {

	private static final List<String> CONTRACT_FIELDS = List.of("exact", "phrases", "aliases", "positiveExamples",
			"positivePatterns", "negativeExamples", "hardExcludes", "hardExcludePatterns",
			"allowFlowAutoSelect");

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void responseDtoMatchesStrictWriterFieldsAndWritesFrontendContractArtifact() throws Exception {
		List<String> serviceFields = supportedWriterFields();
		List<String> responseFields = serializableResponseFields();
		List<String> openApiFields = openApiResponseFields();

		assertEquals(new LinkedHashSet<>(CONTRACT_FIELDS), new LinkedHashSet<>(serviceFields));
		assertEquals(new LinkedHashSet<>(CONTRACT_FIELDS), new LinkedHashSet<>(responseFields));
		assertEquals(new LinkedHashSet<>(CONTRACT_FIELDS), new LinkedHashSet<>(openApiFields));
		assertEquals(new LinkedHashSet<>(serviceFields), new LinkedHashSet<>(responseFields));

		RouteRulesService routeRulesService = new RouteRulesService(objectMapper, new RouteTextNormalizer());
		JsonNode emptyResponse = objectMapper.valueToTree(routeRulesService.toResponseDTO(null));
		for (String field : CONTRACT_FIELDS.subList(0, CONTRACT_FIELDS.size() - 1)) {
			assertTrue(emptyResponse.path(field).isArray(), () -> field + " must serialize as an array");
		}
		assertTrue(emptyResponse.path("allowFlowAutoSelect").isBoolean());
		assertEquals(false, emptyResponse.path("allowFlowAutoSelect").booleanValue());
		assertEquals(new LinkedHashSet<>(CONTRACT_FIELDS),
				new LinkedHashSet<>(routeRulesService.toResponseMap(null).keySet()));

		Path artifact = Path.of(System.getProperty("project.build.directory", "target"),
				"route-rules-contract.json");
		Files.createDirectories(artifact.getParent());
		Map<String, Object> contract = new LinkedHashMap<>();
		contract.put("fields", CONTRACT_FIELDS);
		objectMapper.writerWithDefaultPrettyPrinter().writeValue(artifact.toFile(), contract);
	}

	@SuppressWarnings("unchecked")
	private List<String> supportedWriterFields() throws ReflectiveOperationException {
		Field fields = RouteRulesService.class.getDeclaredField("FIELDS");
		assertTrue(fields.trySetAccessible(), "RouteRulesService.FIELDS must remain inspectable by the contract test");
		Object value = fields.get(null);
		assertTrue(value instanceof List<?>, "RouteRulesService.FIELDS must be a List");
		return (List<String>) value;
	}

	private List<String> serializableResponseFields() {
		JavaType type = objectMapper.constructType(RouteRulesDTO.class);
		return objectMapper.getSerializationConfig().introspect(type).findProperties().stream()
			.filter(BeanPropertyDefinition::couldSerialize)
			.map(BeanPropertyDefinition::getName)
			.toList();
	}

	private List<String> openApiResponseFields() {
		var resolvedSchema = ModelConverters.getInstance()
			.resolveAsResolvedSchema(new AnnotatedType(RouteRulesDTO.class));
		assertTrue(resolvedSchema.schema != null, "RouteRulesDTO must resolve to an OpenAPI schema");
		Map<String, ?> properties = resolvedSchema.schema.getProperties();
		return properties == null ? List.of() : List.copyOf(properties.keySet());
	}

}
