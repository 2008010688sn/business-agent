/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.skill.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Decodes the immutable resource snapshot persisted with a Skill version.
 */
@Component
@RequiredArgsConstructor
public class SkillVersionResourceLoader {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final ObjectMapper objectMapper;

	public SkillVersionResources load(DataAgentSkillVersion version) {
		if (version == null) {
			return SkillVersionResources.empty();
		}
		Map<String, Object> datasourceConfig = read(version.getDatasourceConfig());
		return new SkillVersionResources(version.getSkillId(), version.getId(), datasource(datasourceConfig),
				ids(read(version.getSemanticConfig()), "semanticModelIds"),
				ids(read(version.getKnowledgeConfig()), "businessKnowledgeIds"),
				ids(read(version.getKnowledgeConfig()), "skillKnowledgeIds"), read(version.getRuntimeConfig()));
	}

	private SkillVersionResources.DatasourceResource datasource(Map<String, Object> config) {
		Long datasourceId = longValue(config.get("datasourceId"));
		if (datasourceId == null) {
			return null;
		}
		List<SkillVersionResources.TableScope> tables = new ArrayList<>();
		for (Object raw : list(config.get("tables"))) {
			if (!(raw instanceof Map<?, ?> map)) {
				continue;
			}
			tables.add(new SkillVersionResources.TableScope(text(map.get("table")), strings(map.get("columns"))));
		}
		return new SkillVersionResources.DatasourceResource(datasourceId, tables,
				Boolean.TRUE.equals(config.get("readOnly")), integer(config.get("maxRows")),
				map(config.get("maskingPolicy")), map(config.get("permissionPolicy")));
	}

	private Map<String, Object> read(String json) {
		if (!StringUtils.hasText(json)) {
			return Map.of();
		}
		try {
			Map<String, Object> result = objectMapper.readValue(json, MAP_TYPE);
			return immutableMap(result);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Skill version resource snapshot is invalid", ex);
		}
	}

	private List<Long> ids(Map<String, Object> config, String key) {
		return list(config.get(key)).stream().map(this::longValue).filter(java.util.Objects::nonNull).distinct().toList();
	}

	private List<?> list(Object value) {
		return value instanceof List<?> list ? list : List.of();
	}

	private List<String> strings(Object value) {
		return list(value).stream().filter(String.class::isInstance).map(String.class::cast).toList();
	}

	private Map<String, Object> map(Object value) {
		if (!(value instanceof Map<?, ?> map)) {
			return Map.of();
		}
		LinkedHashMap<String, Object> result = new LinkedHashMap<>();
		map.forEach((key, item) -> result.put(String.valueOf(key), item));
		return immutableMap(result);
	}

	private Long longValue(Object value) {
		if (value instanceof Number number) {
			return number.longValue();
		}
		try {
			return value == null ? null : Long.valueOf(String.valueOf(value));
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private Integer integer(Object value) {
		Long valueAsLong = longValue(value);
		return valueAsLong == null ? null : valueAsLong.intValue();
	}

	private String text(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private Map<String, Object> immutableMap(Map<String, Object> values) {
		if (values == null || values.isEmpty()) {
			return Map.of();
		}
		return Collections.unmodifiableMap(new LinkedHashMap<>(values));
	}
}
