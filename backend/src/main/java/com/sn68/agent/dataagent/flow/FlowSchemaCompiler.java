/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Compiles a FLOW writable schema into the small JSON-Schema subset accepted by
 * structured model protocols. It rejects keywords that cannot be enforced by
 * both the provider contract and the local validator.
 */
@Component
public class FlowSchemaCompiler {

	public static final String VERSION = "flow-schema-compiler/v1";

	/** x-temporal is accepted for local normalization and never copied into provider JSON Schema. */
	private static final Set<String> SUPPORTED_KEYWORDS = Set.of("type", "properties", "items", "enum",
			"minItems", "maxItems", "description", "format", "pattern", "minLength", "maxLength", "minimum",
			"maximum", "additionalProperties", "required", "x-temporal");

	private static final Set<String> SUPPORTED_TYPES = Set.of("object", "array", "string", "integer", "number",
			"boolean");

	private static final List<String> FEATURES = List.of("sparse-patch", "root-set-wrapper",
			"closed-objects", "identifier-filter", "strict-keyword-subset");

	private final ObjectMapper objectMapper;

	public FlowSchemaCompiler(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public CompiledSchema compile(Map<String, Object> writableSchema) {
		if (writableSchema == null || writableSchema.isEmpty()) {
			throw new IllegalArgumentException("FLOW writable schema must not be empty");
		}
		Map<String, Object> normalizedRoot = copyMap(writableSchema, "/set");
		if (!normalizedRoot.containsKey("type") && normalizedRoot.containsKey("properties")) {
			normalizedRoot.put("type", "object");
		}
		Map<String, Object> patchSchema = compileNode(normalizedRoot, "/set");
		if (!"object".equals(patchSchema.get("type"))) {
			throw new IllegalArgumentException("FLOW writable schema root must be an object");
		}
		Map<String, Object> responseSchema = new LinkedHashMap<>();
		responseSchema.put("type", "object");
		responseSchema.put("properties", Map.of(FlowStructuredOutputProtocol.SET_FIELD, patchSchema));
		responseSchema.put("required", List.of(FlowStructuredOutputProtocol.SET_FIELD));
		responseSchema.put("additionalProperties", false);
		try {
			String jsonSchema = objectMapper.writeValueAsString(responseSchema);
			return new CompiledSchema(patchSchema, responseSchema, jsonSchema, sha256(jsonSchema));
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("FLOW writable schema cannot be serialized", ex);
		}
	}

	public String version() {
		return VERSION;
	}

	public List<String> features() {
		return FEATURES;
	}

	private Map<String, Object> compileNode(Map<String, Object> schema, String path) {
		validateKeywords(schema, path);
		String type = type(schema, path);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("type", type);
		copyConstraint(schema, result, "description");
		copyConstraint(schema, result, "format");
		copyConstraint(schema, result, "pattern");
		copyConstraint(schema, result, "minLength");
		copyConstraint(schema, result, "maxLength");
		copyConstraint(schema, result, "minimum");
		copyConstraint(schema, result, "maximum");
		copyConstraint(schema, result, "enum");
		if ("object".equals(type)) {
			assertClosedObject(schema, path);
			Map<String, Object> properties = map(schema.get("properties"), path + "/properties", true);
			Map<String, Object> compiledProperties = new LinkedHashMap<>();
			for (Map.Entry<String, Object> entry : new TreeMap<>(properties).entrySet()) {
				String name = entry.getKey();
				if (!StringUtils.hasText(name)) {
					throw new IllegalArgumentException("FLOW schema contains a blank property at " + path);
				}
				if (FlowStructuredOutputProtocol.SET_FIELD.equals(name) && "/set".equals(path)) {
					throw new IllegalArgumentException("FLOW writable schema must not declare a top-level set field");
				}
				if (isInternalIdentifier(name)) {
					continue;
				}
				compiledProperties.put(name, compileNode(map(entry.getValue(), path + "/" + name, false),
						path + "/" + name));
			}
			result.put("properties", compiledProperties);
			result.put("additionalProperties", false);
			return result;
		}
		if ("array".equals(type)) {
			copyConstraint(schema, result, "minItems");
			copyConstraint(schema, result, "maxItems");
			result.put("items", compileNode(map(schema.get("items"), path + "/items", false), path + "/items"));
			return result;
		}
		if (schema.containsKey("properties") || schema.containsKey("items")
				|| schema.containsKey("additionalProperties")) {
			throw new IllegalArgumentException("FLOW schema keyword is incompatible with type " + type + " at " + path);
		}
		return result;
	}

	private void validateKeywords(Map<String, Object> schema, String path) {
		for (String key : schema.keySet()) {
			if (!SUPPORTED_KEYWORDS.contains(key)) {
				throw new IllegalArgumentException("Unsupported FLOW JSON Schema keyword " + key + " at " + path);
			}
		}
	}

	private String type(Map<String, Object> schema, String path) {
		Object value = schema.get("type");
		if (!(value instanceof String type) || !SUPPORTED_TYPES.contains(type)) {
			throw new IllegalArgumentException("FLOW schema must declare a supported type at " + path);
		}
		return type;
	}

	private void assertClosedObject(Map<String, Object> schema, String path) {
		Object additionalProperties = schema.get("additionalProperties");
		if (additionalProperties != null && !Boolean.FALSE.equals(additionalProperties)
				&& !"false".equalsIgnoreCase(String.valueOf(additionalProperties))) {
			throw new IllegalArgumentException("FLOW object schema must not allow additional properties at " + path);
		}
	}

	private void copyConstraint(Map<String, Object> source, Map<String, Object> target, String key) {
		if (source.containsKey(key)) {
			target.put(key, source.get(key));
		}
	}

	private Map<String, Object> copyMap(Map<String, Object> source, String path) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : source.entrySet()) {
			if (!StringUtils.hasText(entry.getKey())) {
				throw new IllegalArgumentException("FLOW schema contains a blank keyword at " + path);
			}
			result.put(entry.getKey(), entry.getValue());
		}
		return result;
	}

	private Map<String, Object> map(Object value, String path, boolean allowEmpty) {
		if (!(value instanceof Map<?, ?> map)) {
			throw new IllegalArgumentException("FLOW schema must contain an object at " + path);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			if (!(entry.getKey() instanceof String key)) {
				throw new IllegalArgumentException("FLOW schema contains a non-text key at " + path);
			}
			result.put(key, entry.getValue());
		}
		if (!allowEmpty && result.isEmpty()) {
			throw new IllegalArgumentException("FLOW schema object must not be empty at " + path);
		}
		return result;
	}

	private boolean isInternalIdentifier(String fieldName) {
		String normalized = fieldName.trim();
		return "id".equalsIgnoreCase(normalized) || "ids".equalsIgnoreCase(normalized)
				|| normalized.endsWith("Id") || normalized.endsWith("Ids") || normalized.endsWith("ID")
				|| normalized.endsWith("IDs") || normalized.endsWith("_id") || normalized.endsWith("_ids");
	}

	private String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}

	/**
	 * Canonical model-facing artifacts. All business fields remain optional; only
	 * the response wrapper requires {@code set}.
	 */
	public record CompiledSchema(Map<String, Object> patchSchema, Map<String, Object> responseSchema,
			String jsonSchema, String fingerprint) {

		public CompiledSchema {
			patchSchema = immutableMap(patchSchema);
			responseSchema = immutableMap(responseSchema);
		}

		private static Map<String, Object> immutableMap(Map<String, Object> source) {
			return Collections.unmodifiableMap(new LinkedHashMap<>(source == null ? Map.of() : source));
		}

	}

}
