/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.skill.execution;

import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;
import org.springframework.util.StringUtils;

/**
 * Immutable resources frozen with a published Skill version.
 */
public record SkillVersionResources(Long skillId, Long skillVersionId, DatasourceResource datasource,
		List<Long> semanticModelIds, List<Long> businessKnowledgeIds, List<Long> skillKnowledgeIds,
		Map<String, Object> runtimeConfig) {

	public SkillVersionResources {
		semanticModelIds = semanticModelIds == null ? List.of() : List.copyOf(semanticModelIds);
		businessKnowledgeIds = businessKnowledgeIds == null ? List.of() : List.copyOf(businessKnowledgeIds);
		skillKnowledgeIds = skillKnowledgeIds == null ? List.of() : List.copyOf(skillKnowledgeIds);
		runtimeConfig = immutableMap(runtimeConfig);
	}

	/**
	 * Compatibility constructor for callers compiled against the original snapshot
	 * shape. New snapshots must use the explicit Skill knowledge list.
	 */
	public SkillVersionResources(Long skillId, Long skillVersionId, DatasourceResource datasource,
			List<Long> semanticModelIds, List<Long> businessKnowledgeIds, Map<String, Object> runtimeConfig) {
		this(skillId, skillVersionId, datasource, semanticModelIds, businessKnowledgeIds, List.of(), runtimeConfig);
	}

	public static SkillVersionResources empty() {
		return new SkillVersionResources(null, null, null, List.of(), List.of(), List.of(), Map.of());
	}

	public Long datasourceId() {
		return datasource == null ? null : datasource.datasourceId();
	}

	public boolean hasDatasourceAccess() {
		return datasource != null && datasource.usable();
	}

	public record DatasourceResource(Long datasourceId, List<TableScope> tables, boolean readOnly, Integer maxRows,
			Map<String, Object> maskingPolicy, Map<String, Object> permissionPolicy) {

		public DatasourceResource {
			tables = tables == null ? List.of() : List.copyOf(tables);
			maskingPolicy = immutableMap(maskingPolicy);
			permissionPolicy = immutableMap(permissionPolicy);
		}

		public boolean usable() {
			return datasourceId != null && readOnly && !tables.isEmpty()
					&& tables.stream().allMatch(TableScope::usable);
		}
	}

	public record TableScope(String table, List<String> columns) {

		public TableScope {
			table = StringUtils.hasText(table) ? table.trim() : null;
			columns = columns == null ? List.of() : columns.stream()
				.filter(StringUtils::hasText)
				.map(String::trim)
				.distinct()
				.toList();
		}

		public boolean usable() {
			return StringUtils.hasText(table) && !columns.isEmpty();
		}
	}

	private static Map<String, Object> immutableMap(Map<String, Object> values) {
		if (values == null || values.isEmpty()) {
			return Map.of();
		}
		return Collections.unmodifiableMap(new LinkedHashMap<>(values));
	}
}
