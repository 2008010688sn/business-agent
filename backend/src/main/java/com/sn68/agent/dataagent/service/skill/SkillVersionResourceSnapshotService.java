/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.skill;

import cn.hutool.crypto.SecureUtil;
import com.sn68.agent.dataagent.entity.SkillDatasource;
import com.sn68.agent.dataagent.entity.BusinessKnowledge;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.Datasource;
import com.sn68.agent.dataagent.entity.SemanticModel;
import com.sn68.agent.dataagent.enums.EmbeddingStatus;
import com.sn68.agent.dataagent.repository.BusinessKnowledgeMapper;
import com.sn68.agent.dataagent.repository.SkillKnowledgeMapper;
import com.sn68.agent.dataagent.repository.SemanticModelMapper;
import com.sn68.agent.dataagent.service.analysis.AnalysisConfig;
import com.sn68.agent.dataagent.service.analysis.AnalysisConfig.AnalysisAssociation;
import com.sn68.agent.dataagent.service.analysis.AnalysisConfig.AnalysisAssociation.Endpoint;
import com.sn68.agent.dataagent.service.analysis.AnalysisConfig.AnalysisSource;
import com.sn68.agent.dataagent.service.analysis.AnalysisConfigParser;
import com.sn68.agent.dataagent.service.analysis.AnalysisConfigParser.ParseResult;
import com.sn68.agent.dataagent.service.datasource.SkillDatasourceService;
import com.sn68.agent.dataagent.skill.SkillExecutionMode;
import com.sn68.agent.dataagent.skill.SkillKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Builds the resource portion of a published Skill version from Skill-owned records.
 *
 * <p>Draft payloads never decide which tenant resources are executable. This service is
 * deliberately the only place that converts editable resource rows into a version snapshot.</p>
 */
@Service
public class SkillVersionResourceSnapshotService {

	private static final int DEFAULT_MAX_ROWS = 200;

	private final SkillDatasourceService skillDatasourceService;

	private final SemanticModelMapper semanticModelMapper;

	private final BusinessKnowledgeMapper businessKnowledgeMapper;

	private final SkillKnowledgeMapper skillKnowledgeMapper;

	private final AnalysisConfigParser analysisConfigParser = new AnalysisConfigParser();

	public SkillVersionResourceSnapshotService(SkillDatasourceService skillDatasourceService,
			SemanticModelMapper semanticModelMapper, BusinessKnowledgeMapper businessKnowledgeMapper,
			SkillKnowledgeMapper skillKnowledgeMapper) {
		this.skillDatasourceService = skillDatasourceService;
		this.semanticModelMapper = semanticModelMapper;
		this.businessKnowledgeMapper = businessKnowledgeMapper;
		this.skillKnowledgeMapper = skillKnowledgeMapper;
	}

	public ResourceSnapshot capture(DataAgentSkill skill, Map<String, Object> draftKnowledgeConfig,
			Map<String, Object> draftRuntimeConfig) {
		return capture(skill, draftKnowledgeConfig, draftRuntimeConfig, Map.of());
	}

	public ResourceSnapshot capture(DataAgentSkill skill, Map<String, Object> draftKnowledgeConfig,
			Map<String, Object> draftRuntimeConfig, Map<String, Object> draftAnalysisConfig) {
		if (skill == null || skill.getId() == null) {
			return ResourceSnapshot.invalid("Skill does not exist");
		}
		List<String> errors = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		boolean querySkill = SkillKind.QUERY.name().equals(skill.getSkillKind());
		boolean businessKnowledgeSkill = querySkill || isFlowBusinessKnowledgeSkill(skill);
		ParseResult analysis = analysisConfigParser.parse(draftAnalysisConfig);
		errors.addAll(analysis.errors());
		boolean analysisValid = analysis.valid();
		boolean fileOnly = analysisValid && analysis.config().fileOnly();
		boolean allowMultipleJdbc = querySkill && analysisValid;
		boolean allowZeroJdbc = querySkill && fileOnly;
		List<SkillDatasource> datasources = querySkill ? skillDatasourceService.listSkillDatasources(skill.getId())
				: List.of();

		List<SkillDatasource> activeDatasources = datasources.stream()
			.filter(item -> Boolean.TRUE.equals(item.getIsActive()))
			.toList();
		if (querySkill && activeDatasources.size() > 1 && !allowMultipleJdbc) {
			errors.add("Skill has more than one enabled datasource");
		}
		Map<String, Object> datasourceConfig = Map.of();
		Map<String, Object> semanticConfig = Map.of();
		Map<Long, Map<String, List<String>>> columnsByDatasource = new LinkedHashMap<>();
		List<SkillDatasource> snapshotDatasources = querySkill
				? selectableDatasources(activeDatasources, allowMultipleJdbc, analysisValid ? analysis.config() : null)
				: List.of();
		if (!snapshotDatasources.isEmpty()) {
			try {
				for (SkillDatasource relation : snapshotDatasources) {
					columnsByDatasource.put(relation.getDatasourceId(), skillDatasourceService
						.getEffectiveSkillDatasourceColumns(skill.getId(), relation.getDatasourceId()));
				}
				datasourceConfig = snapshotDatasourceConfigs(skill, snapshotDatasources, columnsByDatasource,
						draftRuntimeConfig, errors);
				semanticConfig = snapshotSemanticModels(skill, snapshotDatasources, columnsByDatasource, errors);
			}
			catch (Exception ex) {
				errors.add("Unable to resolve datasource field whitelist: " + message(ex));
			}
		}
		Map<String, Object> knowledgeConfig;
		if (SkillKind.QA.name().equals(skill.getSkillKind())
				&& SkillExecutionMode.KNOWLEDGE.name().equals(skill.getExecutionMode())) {
			knowledgeConfig = snapshotSkillKnowledge(skill, draftKnowledgeConfig, warnings);
		}
		else {
			knowledgeConfig = businessKnowledgeSkill
					? snapshotBusinessKnowledge(skill, draftKnowledgeConfig, errors) : Map.of();
		}
		if (querySkill && activeDatasources.isEmpty() && !allowZeroJdbc) {
			errors.add("QUERY Skill requires one enabled datasource");
		}
		Map<String, Object> analysisConfig = snapshotAnalysisConfig(skill, analysis, columnsByDatasource, errors);
		return new ResourceSnapshot(datasourceConfig, semanticConfig, knowledgeConfig, analysisConfig,
				List.copyOf(errors), List.copyOf(warnings));
	}

	private List<SkillDatasource> selectableDatasources(List<SkillDatasource> activeDatasources,
			boolean allowMultipleJdbc, AnalysisConfig analysisConfig) {
		if (activeDatasources == null || activeDatasources.isEmpty()) {
			return List.of();
		}
		if (analysisConfig != null && analysisConfig.present()) {
			Set<Long> tableDatasourceIds = new LinkedHashSet<>();
			for (AnalysisSource source : analysisConfig.sources()) {
				if (source.isTable() && source.datasourceId() != null) {
					tableDatasourceIds.add(source.datasourceId());
				}
			}
			return activeDatasources.stream()
				.filter(item -> item != null && tableDatasourceIds.contains(item.getDatasourceId()))
				.toList();
		}
		if (activeDatasources.size() == 1) {
			return List.of(activeDatasources.get(0));
		}
		return allowMultipleJdbc ? List.copyOf(activeDatasources) : List.of();
	}

	private Map<String, Object> snapshotDatasourceConfigs(DataAgentSkill skill, List<SkillDatasource> relations,
			Map<Long, Map<String, List<String>>> columnsByDatasource, Map<String, Object> runtimeConfig,
			List<String> errors) {
		if (relations == null || relations.isEmpty()) {
			return Map.of();
		}
		if (relations.size() == 1) {
			SkillDatasource relation = relations.get(0);
			return snapshotDatasource(skill, relation,
					columnsByDatasource.getOrDefault(relation.getDatasourceId(), Map.of()), runtimeConfig, errors);
		}
		List<Map<String, Object>> datasources = new ArrayList<>();
		for (SkillDatasource relation : relations) {
			Map<String, Object> item = snapshotDatasource(skill, relation,
					columnsByDatasource.getOrDefault(relation.getDatasourceId(), Map.of()), runtimeConfig, errors);
			if (!item.isEmpty()) {
				datasources.add(item);
			}
		}
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("datasources", List.copyOf(datasources));
		snapshot.put("readOnly", true);
		return Map.copyOf(snapshot);
	}

	private Map<String, Object> snapshotAnalysisConfig(DataAgentSkill skill, ParseResult analysis,
			Map<Long, Map<String, List<String>>> columnsByDatasource, List<String> errors) {
		if (analysis == null || !analysis.present()) {
			return Map.of();
		}
		AnalysisConfig config = analysis.config();
		Set<Long> knowledgeIds = skillKnowledgeIds(skill);
		List<AnalysisSource> resolved = new ArrayList<>();
		boolean querySkill = SkillKind.QUERY.name().equals(skill.getSkillKind());
		Map<String, List<String>> columnsBySourceId = new LinkedHashMap<>();
		for (AnalysisSource source : config.sources()) {
			if (source.isTable()) {
				Map<String, List<String>> tables = columnsByDatasource.get(source.datasourceId());
				if (tables == null || tables.isEmpty()) {
					errors.add("analysisConfig TABLE source " + source.id() + " datasource is not an enabled Skill datasource");
					continue;
				}
				List<String> whitelist = tableWhitelist(tables, source.table());
				if (whitelist.isEmpty()) {
					errors.add("analysisConfig TABLE source " + source.id() + " table " + source.table()
							+ " is outside the datasource whitelist");
					continue;
				}
				List<String> columns = source.columns().isEmpty() ? List.copyOf(whitelist)
						: source.columns().stream().filter(column -> containsIgnoreCase(whitelist, column)).toList();
				if (columns.size() != (source.columns().isEmpty() ? whitelist.size() : source.columns().size())) {
					errors.add("analysisConfig TABLE source " + source.id() + " has columns outside the whitelist");
					continue;
				}
				AnalysisSource resolvedSource = new AnalysisSource(source.id(), source.type(), source.datasourceId(),
						source.table(), columns, null, false);
				resolved.add(resolvedSource);
				columnsBySourceId.put(source.id(), columns);
				continue;
			}
			if (source.skillKnowledgeId() != null) {
				if (querySkill) {
					errors.add("analysisConfig file source " + source.id()
							+ " must use turnFile; skillKnowledgeId is not supported on QUERY Skills");
					continue;
				}
				if (!knowledgeIds.contains(source.skillKnowledgeId())) {
					errors.add("analysisConfig file source " + source.id() + " skillKnowledgeId does not belong to the Skill");
					continue;
				}
			}
			resolved.add(source);
		}
		for (AnalysisAssociation association : config.associations()) {
			validateAssociationField(association.left(), columnsBySourceId, errors);
			validateAssociationField(association.right(), columnsBySourceId, errors);
		}
		AnalysisConfig snapshot = new AnalysisConfig(resolved, config.associations(), config.grain(), config.nextFlows(),
				config.nextWriteTools());
		return snapshot.present() ? snapshot.toSnapshotMap() : Map.of();
	}

	private void validateAssociationField(Endpoint endpoint, Map<String, List<String>> columnsBySourceId,
			List<String> errors) {
		if (endpoint == null || !columnsBySourceId.containsKey(endpoint.source())) {
			return;
		}
		if (!containsIgnoreCase(columnsBySourceId.get(endpoint.source()), endpoint.field())) {
			errors.add("analysisConfig association field " + endpoint.field() + " is outside source " + endpoint.source()
					+ " columns");
		}
	}

	private List<String> tableWhitelist(Map<String, List<String>> tables, String table) {
		if (tables == null || !StringUtils.hasText(table)) {
			return List.of();
		}
		List<String> exact = tables.get(table);
		if (exact != null && !exact.isEmpty()) {
			return exact;
		}
		List<String> trimmed = tables.get(table.trim());
		if (trimmed != null && !trimmed.isEmpty()) {
			return trimmed;
		}
		for (Map.Entry<String, List<String>> entry : tables.entrySet()) {
			if (entry.getKey() != null && entry.getKey().trim().equalsIgnoreCase(table.trim())) {
				return entry.getValue() == null ? List.of() : entry.getValue();
			}
		}
		return List.of();
	}

	private Set<Long> skillKnowledgeIds(DataAgentSkill skill) {
		Set<Long> ids = new LinkedHashSet<>();
		for (com.sn68.agent.dataagent.entity.SkillKnowledge knowledge : skillKnowledgeMapper.selectBySkillId(skill.getId())) {
			if (knowledge != null && knowledge.getId() != null && !Boolean.TRUE.equals(knowledge.getDeleted())
					&& Objects.equals(skill.getId(), knowledge.getSkillId())) {
				ids.add(knowledge.getId());
			}
		}
		return ids;
	}

	private boolean containsIgnoreCase(List<String> values, String candidate) {
		if (candidate == null) {
			return false;
		}
		for (String value : values == null ? List.<String>of() : values) {
			if (value != null && value.trim().equalsIgnoreCase(candidate.trim())) {
				return true;
			}
		}
		return false;
	}

	private Map<String, Object> snapshotDatasource(DataAgentSkill skill, SkillDatasource relation,
			Map<String, List<String>> effectiveColumns, Map<String, Object> runtimeConfig, List<String> errors) {
		if (relation == null) {
			return Map.of();
		}
		Datasource datasource = relation.getDatasource();
		if (relation.getDatasourceId() == null || datasource == null) {
			errors.add("Enabled datasource binding is incomplete");
			return Map.of();
		}
		if (!Objects.equals(skill.getTenantId(), datasource.getTenantId())) {
			errors.add("Datasource does not belong to the Skill tenant");
		}
		List<String> tables = relation.getSelectTables() == null ? List.of() : relation.getSelectTables();
		List<Map<String, Object>> tableScopes = new ArrayList<>();
		Set<String> seenTables = new LinkedHashSet<>();
		for (String table : tables) {
			if (!StringUtils.hasText(table) || !seenTables.add(table.trim())) {
				continue;
			}
			List<String> tableColumns = effectiveColumns.getOrDefault(table,
				effectiveColumns.getOrDefault(table.trim(), List.of())).stream()
				.filter(StringUtils::hasText)
				.map(String::trim)
				.distinct()
				.toList();
			if (tableColumns.isEmpty()) {
				errors.add("Datasource table " + table.trim() + " has an empty column whitelist");
				continue;
			}
			Map<String, Object> tableScope = new LinkedHashMap<>();
			tableScope.put("table", table.trim());
			tableScope.put("columns", tableColumns);
			tableScopes.add(Map.copyOf(tableScope));
		}
		if (tables.isEmpty()) {
			errors.add("Enabled datasource has an empty table whitelist");
		}
		if (tableScopes.size() != seenTables.size()) {
			errors.add("Each datasource table must have a non-empty column whitelist");
		}
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("datasourceId", relation.getDatasourceId());
		snapshot.put("tables", List.copyOf(tableScopes));
		snapshot.put("readOnly", true);
		snapshot.put("maxRows", maxRows(runtimeConfig));
		snapshot.put("maskingPolicy", Map.of());
		snapshot.put("permissionPolicy", Map.of());
		return Map.copyOf(snapshot);
	}

	private Map<String, Object> snapshotSemanticModels(DataAgentSkill skill, List<SkillDatasource> activeDatasources,
			Map<Long, Map<String, List<String>>> columnsByDatasource, List<String> errors) {
		if (activeDatasources == null || activeDatasources.isEmpty()) {
			return Map.of();
		}
		List<SemanticModel> enabled = semanticModelMapper.selectEnabledBySkillId(skill.getId());
		Map<Long, Set<String>> allowedByDatasource = new LinkedHashMap<>();
		Set<Long> datasourceIds = new LinkedHashSet<>();
		for (SkillDatasource relation : activeDatasources) {
			if (relation == null || relation.getDatasourceId() == null) {
				continue;
			}
			datasourceIds.add(relation.getDatasourceId());
			allowedByDatasource.put(relation.getDatasourceId(),
					allowedColumns(columnsByDatasource.getOrDefault(relation.getDatasourceId(), Map.of())));
		}
		List<Long> ids = new ArrayList<>();
		List<Map<String, Object>> resources = new ArrayList<>();
		for (SemanticModel model : enabled) {
			if (model == null || model.getId() == null) {
				continue;
			}
			if (!Objects.equals(skill.getId(), model.getSkillId())) {
				errors.add("Semantic model " + model.getId() + " does not belong to the Skill");
				continue;
			}
			String key = columnKey(model.getTableName(), model.getColumnName());
			Set<String> allowedColumns = allowedByDatasource.getOrDefault(model.getDatasourceId(), Set.of());
			if (!datasourceIds.contains(model.getDatasourceId()) || !allowedColumns.contains(key)) {
				errors.add("Enabled semantic model " + model.getId() + " is outside the datasource whitelist");
				continue;
			}
			ids.add(model.getId());
			resources.add(resourceReference(model.getId(), checksum(model.getTableName(), model.getColumnName(),
					model.getBusinessName(), model.getSynonyms(), model.getBusinessDescription(), model.getDataType())));
		}
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("semanticModelIds", List.copyOf(ids));
		snapshot.put("resources", List.copyOf(resources));
		return Map.copyOf(snapshot);
	}

	private Map<String, Object> snapshotBusinessKnowledge(DataAgentSkill skill, Map<String, Object> draftConfig,
			List<String> errors) {
		List<Long> ids = new ArrayList<>();
		List<Map<String, Object>> resources = new ArrayList<>();
		for (BusinessKnowledge knowledge : businessKnowledgeMapper.selectBySkillId(skill.getId())) {
			if (knowledge == null || knowledge.getId() == null || Boolean.TRUE.equals(knowledge.getDeleted())) {
				continue;
			}
			if (!Objects.equals(skill.getId(), knowledge.getSkillId())) {
				errors.add("Business knowledge " + knowledge.getId() + " does not belong to the Skill");
				continue;
			}
			if (!Boolean.TRUE.equals(knowledge.getIsRecall())) {
				continue;
			}
			if (knowledge.getEmbeddingStatus() != EmbeddingStatus.COMPLETED) {
				errors.add("Business knowledge " + knowledge.getBusinessTerm() + " (id=" + knowledge.getId()
						+ ") is selected for recall but embedding is not completed: " + knowledge.getEmbeddingStatus());
				continue;
			}
			ids.add(knowledge.getId());
			resources.add(resourceReference(knowledge.getId(), checksum(knowledge.getBusinessTerm(),
					knowledge.getDescription(), knowledge.getSynonyms())));
		}
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("businessKnowledgeIds", List.copyOf(ids));
		snapshot.put("resources", List.copyOf(resources));
		copyNumber(draftConfig, snapshot, "topK", 1, 5);
		if (draftConfig != null && draftConfig.containsKey("topK") && !snapshot.containsKey("topK")) {
			errors.add("Business knowledge topK must be a number between 1 and 5");
		}
		copyNumber(draftConfig, snapshot, "similarityThreshold", 0, 1);
		return Map.copyOf(snapshot);
	}

	private Map<String, Object> snapshotSkillKnowledge(DataAgentSkill skill, Map<String, Object> draftConfig,
			List<String> warnings) {
		List<Long> ids = new ArrayList<>();
		List<Map<String, Object>> resources = new ArrayList<>();
		List<com.sn68.agent.dataagent.entity.SkillKnowledge> knowledgeList = skillKnowledgeMapper
			.selectBySkillId(skill.getId());
		for (com.sn68.agent.dataagent.entity.SkillKnowledge knowledge : knowledgeList) {
			if (knowledge == null || knowledge.getId() == null || Boolean.TRUE.equals(knowledge.getDeleted())
					|| !Objects.equals(skill.getId(), knowledge.getSkillId())
					|| !Boolean.TRUE.equals(knowledge.getIsRecall())) {
				continue;
			}
			if (knowledge.getEmbeddingStatus() != EmbeddingStatus.COMPLETED) {
				warnings.add("知识库资源 " + firstText(knowledge.getTitle(), knowledge.getQuestion(), String.valueOf(knowledge.getId()))
						+ " 尚未完成向量化，未纳入本次发布版本");
				continue;
			}
			ids.add(knowledge.getId());
			resources.add(resourceReference(knowledge.getId(), checksum(knowledge.getTitle(), knowledge.getQuestion(),
					knowledge.getContent(), knowledge.getSourceFilename())));
		}
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("skillKnowledgeIds", List.copyOf(ids));
		snapshot.put("businessKnowledgeIds", List.of());
		snapshot.put("resources", List.copyOf(resources));
		copyNumber(draftConfig, snapshot, "topK", 1, 5);
		copyNumber(draftConfig, snapshot, "similarityThreshold", 0, 1);
		return Map.copyOf(snapshot);
	}

	private String firstText(String... values) {
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value.trim();
			}
		}
		return "";
	}

	private boolean isFlowBusinessKnowledgeSkill(DataAgentSkill skill) {
		return skill != null && SkillExecutionMode.FLOW.name().equals(skill.getExecutionMode())
				&& (SkillKind.ACTION.name().equals(skill.getSkillKind())
						|| SkillKind.ORCHESTRATION.name().equals(skill.getSkillKind()));
	}

	private Set<String> allowedColumns(Map<String, List<String>> columnsByTable) {
		if (columnsByTable == null || columnsByTable.isEmpty()) {
			return Set.of();
		}
		Set<String> result = new LinkedHashSet<>();
		columnsByTable.forEach((table, columns) -> {
			for (String column : columns == null ? List.<String>of() : columns) {
				if (StringUtils.hasText(table) && StringUtils.hasText(column)) {
					result.add(columnKey(table, column));
				}
			}
		});
		return Set.copyOf(result);
	}

	private String message(Exception ex) {
		return StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName();
	}

	private String columnKey(String table, String column) {
		return (table == null ? "" : table.trim().toLowerCase()) + "."
				+ (column == null ? "" : column.trim().toLowerCase());
	}

	private Map<String, Object> resourceReference(Long id, String checksum) {
		Map<String, Object> reference = new LinkedHashMap<>();
		reference.put("id", id);
		reference.put("checksum", checksum);
		return Map.copyOf(reference);
	}

	private String checksum(String... values) {
		String joined = values == null ? "" : java.util.Arrays.stream(values)
			.map(value -> value == null ? "" : value)
			.collect(java.util.stream.Collectors.joining("\n"));
		return SecureUtil.sha256(joined);
	}

	private int maxRows(Map<String, Object> runtimeConfig) {
		Object value = runtimeConfig == null ? null : runtimeConfig.get("maxRows");
		if (value instanceof Number number) {
			return Math.max(1, Math.min(DEFAULT_MAX_ROWS, number.intValue()));
		}
		try {
			return Math.max(1, Math.min(DEFAULT_MAX_ROWS, Integer.parseInt(String.valueOf(value))));
		}
		catch (Exception ignored) {
			return DEFAULT_MAX_ROWS;
		}
	}

	private void copyNumber(Map<String, Object> source, Map<String, Object> target, String key, double min,
			double max) {
		Object value = source == null ? null : source.get(key);
		if (!(value instanceof Number number)) {
			return;
		}
		double normalized = number.doubleValue();
		if (normalized < min || normalized > max) {
			return;
		}
		target.put(key, value);
	}

	public record ResourceSnapshot(Map<String, Object> datasourceConfig, Map<String, Object> semanticConfig,
			Map<String, Object> knowledgeConfig, Map<String, Object> analysisConfig, List<String> errors,
			List<String> warnings) {

		public ResourceSnapshot(Map<String, Object> datasourceConfig, Map<String, Object> semanticConfig,
				Map<String, Object> knowledgeConfig, List<String> errors) {
			this(datasourceConfig, semanticConfig, knowledgeConfig, Map.of(), errors, List.of());
		}

		public ResourceSnapshot(Map<String, Object> datasourceConfig, Map<String, Object> semanticConfig,
				Map<String, Object> knowledgeConfig, List<String> errors, List<String> warnings) {
			this(datasourceConfig, semanticConfig, knowledgeConfig, Map.of(), errors, warnings);
		}

		static ResourceSnapshot invalid(String error) {
			return new ResourceSnapshot(Map.of(), Map.of(), Map.of(), List.of(error));
		}

		public boolean valid() {
			return errors == null || errors.isEmpty();
		}
	}
}
