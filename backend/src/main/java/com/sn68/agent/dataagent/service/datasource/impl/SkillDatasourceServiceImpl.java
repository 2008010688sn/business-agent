/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sn68.agent.dataagent.service.datasource.impl;

import com.sn68.agent.dataagent.bo.DbConfigBO;
import com.sn68.agent.dataagent.dto.datasource.SchemaInitReq;
import com.sn68.agent.dataagent.entity.SkillDatasource;
import com.sn68.agent.dataagent.entity.SkillDatasourceColumn;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.Datasource;
import com.sn68.agent.dataagent.entity.LogicalRelation;
import com.sn68.agent.dataagent.repository.SkillDatasourceMapper;
import com.sn68.agent.dataagent.repository.SkillDatasourceColumnsMapper;
import com.sn68.agent.dataagent.repository.SkillDatasourceTablesMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.service.analysis.AnalysisBindingSupport;
import com.sn68.agent.dataagent.service.analysis.AnalysisBindingSupport.Mode;
import com.sn68.agent.dataagent.service.datasource.SkillDatasourceService;
import com.sn68.agent.dataagent.service.datasource.DatasourceService;
import com.sn68.agent.dataagent.service.schema.SchemaService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.utils.TenantHelper;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * 技能与数据源关联管理实现：维护技能可用数据源的绑定、查询与校验。
 */
@Slf4j
@Service
@AllArgsConstructor
public class SkillDatasourceServiceImpl implements SkillDatasourceService {

	private final DatasourceService datasourceService;

	private final DataAgentSkillMapper skillMapper;

	private final SchemaService schemaService;

	private final SkillDatasourceMapper skillDatasourceMapper;

	private final SkillDatasourceTablesMapper tablesMapper;

	private final SkillDatasourceColumnsMapper columnsMapper;

	private final DataAgentSkillVersionMapper skillVersionMapper;

	@Override
	public Boolean initializeSchemaForSkillWithDatasource(Long skillId, Long datasourceId, List<String> tables) {
		return initializeSchema(requireTenantSkill(skillId), datasourceId, tables);
	}

	@Override
	public Boolean initializeSchemaForPublishedSkill(DataAgentSkill skill, Long datasourceId, List<String> tables) {
		if (skill == null || skill.getId() == null) {
			throw CheckedException.badRequest("Skill 不能为空");
		}
		return initializeSchema(skill, datasourceId, tables);
	}

	private Boolean initializeSchema(DataAgentSkill skill, Long datasourceId, List<String> tables) {
		Assert.notNull(skill.getId(), "Skill ID cannot be null");
		Assert.notNull(datasourceId, "Datasource ID cannot be null");
		Assert.notEmpty(tables, "Tables cannot be empty");
		Long skillId = skill.getId();
		try {
			String skillIdStr = String.valueOf(skillId);
			log.info("Initializing schema for skill: {} with datasource: {}, tables: {}", skillIdStr, datasourceId,
					tables);

			Datasource datasource = datasourceService.requireDatasourceForTenant(datasourceId, skill.getTenantId());

			// Create database configuration
			DbConfigBO dbConfig = datasourceService.getDbConfig(datasource);
			SkillDatasource skillDatasource = skillDatasourceMapper.selectBySkillIdAndDatasourceId(skillId,
					datasourceId);
			if (skillDatasource == null) {
				throw CheckedException.notFound("未找到 Skill 与数据源的关联记录, skillId=%s, datasourceId=%s"
					.formatted(skillId, datasourceId));
			}

			// Create SchemaInitReq
			SchemaInitReq schemaInitRequest = new SchemaInitReq();
			schemaInitRequest.setDbConfig(dbConfig);
			schemaInitRequest.setSkillId(skillId);
			schemaInitRequest.setTables(tables);
			schemaInitRequest.setVisibleColumnsByTable(loadSelectedColumns(skillDatasource.getId()));

			log.info("Created SchemaInitReq for skill: {}, dbConfig: {}, tables: {}", skillIdStr, dbConfig, tables);

			// Call the original initialization method
			return schemaService.schema(datasourceId, schemaInitRequest);

		}
		catch (Exception e) {
			log.error("Failed to initialize schema for skill: {} with datasource: {}", skillId, datasourceId, e);
			throw CheckedException.fail("Skill 表结构初始化失败, skillId=" + skillId + ": " + e.getMessage());
		}
	}

	@Override
	public void initializeSchemaForCurrentSkillDatasource(Long skillId) {
		SkillDatasource skillDatasource = getCurrentSkillDatasource(skillId);
		Long datasourceId = skillDatasource.getDatasourceId();
		List<String> tables = Optional.ofNullable(skillDatasource.getSelectTables()).orElse(List.of());
		if (datasourceId == null) {
			throw new IllegalArgumentException("datasourceId cannot be null");
		}
		if (tables.isEmpty()) {
			throw new IllegalArgumentException("tables cannot be empty");
		}
		Boolean result = initializeSchemaForSkillWithDatasource(skillId, datasourceId, tables);
		if (!Boolean.TRUE.equals(result)) {
			throw new IllegalStateException("Schema initialization failed");
		}
		log.info("Successfully initialized schema for skill: {}, tables: {}", skillId, tables.size());
	}

	@Override
	public List<SkillDatasource> listSkillDatasources(Long skillId) {
		Assert.notNull(skillId, "Skill ID cannot be null");
		DataAgentSkill skill = requireTenantSkill(skillId);
		List<SkillDatasource> skillDatasources = TenantHelper.withIgnoreStrategy(
				() -> skillDatasourceMapper.selectBySkillIdWithDatasource(skillId, skill.getTenantId()));

		for (SkillDatasource skillDatasource : skillDatasources) {
			enrichSkillDatasource(skillDatasource, skill.getTenantId());
		}

		return skillDatasources;
	}

	@Override
	public List<Datasource> listDatasourceCandidates(Long skillId) {
		return datasourceService.getAllDatasourceForTenant(requireTenantSkill(skillId).getTenantId());
	}

	@Override
	public boolean testSkillDatasourceConnection(Long skillId, Long datasourceId) {
		DataAgentSkill skill = requireTenantSkill(skillId);
		requireSkillDatasourceRelation(skillId, datasourceId, skill.getTenantId());
		return datasourceService.testConnectionForTenant(datasourceId, skill.getTenantId());
	}

	@Override
	public List<String> getAvailableSkillDatasourceTables(Long skillId, Long datasourceId) throws Exception {
		DataAgentSkill skill = requireTenantSkill(skillId);
		requireSkillDatasourceRelation(skillId, datasourceId, skill.getTenantId());
		return datasourceService.getDatasourceTablesForTenant(datasourceId, skill.getTenantId());
	}

	@Override
	public List<String> getAvailableSkillDatasourceColumns(Long skillId, Long datasourceId, String tableName)
			throws Exception {
		DataAgentSkill skill = requireTenantSkill(skillId);
		requireSkillDatasourceRelation(skillId, datasourceId, skill.getTenantId());
		return datasourceService.getTableColumnsForTenant(datasourceId, skill.getTenantId(), tableName);
	}

	@Override
	public List<LogicalRelation> getSkillLogicalRelations(Long skillId, Long datasourceId) {
		DataAgentSkill skill = requireTenantSkill(skillId);
		requireSkillDatasourceRelation(skillId, datasourceId, skill.getTenantId());
		return datasourceService.getLogicalRelationsForTenant(datasourceId, skill.getTenantId());
	}

	@Override
	public List<LogicalRelation> saveSkillLogicalRelations(Long skillId, Long datasourceId,
			List<LogicalRelation> logicalRelations) {
		DataAgentSkill skill = requireTenantSkill(skillId);
		requireSkillDatasourceRelation(skillId, datasourceId, skill.getTenantId());
		return datasourceService.saveLogicalRelationsForTenant(datasourceId, skill.getTenantId(), logicalRelations);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public SkillDatasource addDatasourceToSkill(Long skillId, Long datasourceId) {
		DataAgentSkill skill = requireTenantSkill(skillId);
		datasourceService.requireDatasourceForTenant(datasourceId, skill.getTenantId());
		try {
			TenantHelper.withIgnoreStrategy(() -> {
				replaceSkillDatasourceBinding(skill, datasourceId);
				return null;
			});
		}
		catch (RuntimeException ex) {
			throw translateSkillDatasourceConstraint(ex, skillId);
		}
		SkillDatasource result = refreshSkillDatasource(skillId, datasourceId, skill.getTenantId());
		if (result == null) {
			throw CheckedException
				.notFound("未找到对应的数据源关联记录, skillId=" + skillId + ", datasourceId=" + datasourceId);
		}
		return result;
	}

	private void replaceSkillDatasourceBinding(DataAgentSkill skill, Long datasourceId) {
		Long skillId = skill.getId();
		String tenantId = skill.getTenantId();
		List<SkillDatasource> relations = Optional.ofNullable(skillDatasourceMapper.selectBySkillId(skillId))
			.orElse(List.of());
		if (!AnalysisBindingSupport.allowsMultipleJdbc(bindingMode(skill))) {
			deactivateOtherBindings(relations, datasourceId, tenantId);
		}
		SkillDatasource existing = findBindingForDatasource(relations, datasourceId, tenantId);
		if (existing != null) {
			skillDatasourceMapper.patchActiveAndTenant(existing.getId(), true, tenantId);
			return;
		}
		skillDatasourceMapper.createNewRelationEnabled(skillId, datasourceId, tenantId);
	}

	private void deactivateOtherBindings(List<SkillDatasource> relations, Long datasourceId, String tenantId) {
		for (SkillDatasource relation : relations) {
			if (relation == null || !belongsToSkillTenant(relation, tenantId)
					|| datasourceId.equals(relation.getDatasourceId())) {
				continue;
			}
			if (!Boolean.TRUE.equals(relation.getIsActive()) && StringUtils.hasText(relation.getTenantId())) {
				continue;
			}
			skillDatasourceMapper.patchActiveAndTenant(relation.getId(), false, tenantId);
		}
	}

	private SkillDatasource findBindingForDatasource(List<SkillDatasource> relations, Long datasourceId,
			String tenantId) {
		return relations.stream()
			.filter(relation -> relation != null && datasourceId.equals(relation.getDatasourceId())
					&& belongsToSkillTenant(relation, tenantId))
			.findFirst()
			.orElse(null);
	}

	private boolean belongsToSkillTenant(SkillDatasource relation, String tenantId) {
		if (relation == null) {
			return false;
		}
		if (!StringUtils.hasText(relation.getTenantId())) {
			return true;
		}
		return tenantId.equals(relation.getTenantId());
	}

	private RuntimeException translateSkillDatasourceConstraint(RuntimeException ex, Long skillId) {
		if (ex instanceof CheckedException) {
			return ex;
		}
		for (Throwable current = ex; current != null; current = current.getCause()) {
			if (current instanceof SQLException sql && "P0001".equals(sql.getSQLState()) && sql.getMessage() != null
					&& sql.getMessage().contains("only one active datasource")) {
				return CheckedException.badRequest("QUERY 类型技能只能启用一个数据源, skillId=" + skillId);
			}
		}
		return ex;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void removeDatasourceFromSkill(Long skillId, Long datasourceId) {
		DataAgentSkill skill = requireTenantSkill(skillId);
		SkillDatasource skillDatasource = requireSkillDatasourceRelation(skillId, datasourceId, skill.getTenantId());
		if (Boolean.TRUE.equals(skillDatasource.getIsActive())) {
			int activeCount = skillDatasourceMapper.countActiveBySkillId(skillId);
			if (activeCount <= 1 && requiresActiveDatasource(skill)) {
				throw CheckedException.badRequest("当前Skill必须至少保留一个启用中的数据源, skillId=" + skillId);
			}
		}
		skillDatasourceMapper.removeRelation(skillId, datasourceId);
	}

	@Override
	public SkillDatasource toggleDatasourceForSkill(Long skillId, Long datasourceId, Boolean isActive) {
		DataAgentSkill skill = requireTenantSkill(skillId);
		SkillDatasource existingRelation = requireSkillDatasourceRelation(skillId, datasourceId, skill.getTenantId());

		Mode bindingMode = bindingMode(skill);
		if (isActive && !AnalysisBindingSupport.allowsMultipleJdbc(bindingMode)) {
			int activeCount = skillDatasourceMapper.countActiveBySkillIdExcluding(skillId, datasourceId);
			if (activeCount > 0) {
				throw CheckedException
					.badRequest("同一智能体下只能启用一个数据源，请先禁用其他数据源后再启用此数据源, skillId=" + skillId);
			}
		}
		else if (!Boolean.TRUE.equals(isActive) && Boolean.TRUE.equals(existingRelation.getIsActive())) {
			int activeCount = skillDatasourceMapper.countActiveBySkillId(skillId);
			if (activeCount <= 1 && requiresActiveDatasource(skill)) {
				throw CheckedException.badRequest("当前Skill必须至少保留一个启用中的数据源, skillId=" + skillId);
			}
		}

		// Update data source status
		int updated = skillDatasourceMapper.updateRelation(skillId, datasourceId, isActive);

		if (updated == 0) {
			throw CheckedException
				.notFound("未找到相关的数据源关联记录, skillId=" + skillId + ", datasourceId=" + datasourceId);
		}

		// Return the updated association record
		SkillDatasource skillDatasource = skillDatasourceMapper.selectBySkillIdAndDatasourceId(skillId, datasourceId);
		enrichSkillDatasource(skillDatasource, skill.getTenantId());
		return skillDatasource;
	}

	private boolean requiresActiveDatasource(DataAgentSkill skill) {
		return AnalysisBindingSupport.requiresJdbc(skill == null ? null : skill.getSkillKind(), bindingMode(skill));
	}

	private Mode bindingMode(DataAgentSkill skill) {
		if (skill == null) {
			return Mode.STANDARD;
		}
		return AnalysisBindingSupport.mode(skill.getSkillKind(), analysisConfigJson(skill));
	}

	private String analysisConfigJson(DataAgentSkill skill) {
		if (skill == null || skillVersionMapper == null) {
			return null;
		}
		DataAgentSkillVersion version = null;
		if (skill.getLatestDraftVersionId() != null) {
			version = skillVersionMapper.selectById(skill.getLatestDraftVersionId());
		}
		if (version == null && skill.getPublishedVersionId() != null) {
			version = skillVersionMapper.selectById(skill.getPublishedVersionId());
		}
		if (version == null && skill.getId() != null) {
			version = skillVersionMapper.findLatestDraft(skill.getId());
		}
		return version == null ? null : version.getAnalysisConfig();
	}

	private DataAgentSkill requireTenantSkill(Long skillId) {
		DataAgentSkill skill = skillMapper.selectById(skillId);
		if (skill == null || Boolean.TRUE.equals(skill.getDeleted()) || !"TENANT".equals(skill.getScope())
				|| !StringUtils.hasText(skill.getTenantId())) {
			throw new IllegalArgumentException("Skill does not belong to a tenant");
		}
		return skill;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public SkillDatasource updateSkillDatasourceTables(Long skillId, Long datasourceId, List<String> tables) {
		if (skillId == null || datasourceId == null || tables == null) {
			throw CheckedException
				.badRequest("参数不能为空, skillId=" + skillId + ", datasourceId=" + datasourceId);
		}
		DataAgentSkill skill = requireTenantSkill(skillId);
		SkillDatasource datasource = requireSkillDatasourceRelation(skillId, datasourceId, skill.getTenantId());
		List<String> normalizedTables;
		try {
			List<String> datasourceTables = loadDatasourceTableNames(datasourceId, skill.getTenantId());
			TableResolutionIndex datasourceTableIndex = buildTableResolutionIndex(datasourceTables);
			normalizedTables = sanitizeRequestedTables(tables, datasourceTableIndex);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Failed to validate datasource tables: %s".formatted(ex.getMessage()),
					ex);
		}
		if (normalizedTables.isEmpty()) {
			tablesMapper.removeAllTables(datasource.getId());
			columnsMapper.removeAllColumns(datasource.getId());
		}
		else {
			tablesMapper.updateSkillDatasourceTables(datasource.getId(), normalizedTables);
			columnsMapper.removeColumnsOutsideTables(datasource.getId(), normalizedTables);
		}
		return refreshSkillDatasource(skillId, datasourceId, skill.getTenantId());
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public SkillDatasource updateSkillDatasourceColumns(Long skillId, Long datasourceId,
			Map<String, List<String>> columnsByTable) throws Exception {
		if (skillId == null || datasourceId == null || columnsByTable == null) {
			throw CheckedException
				.badRequest("参数不能为空, skillId=" + skillId + ", datasourceId=" + datasourceId);
		}
		DataAgentSkill skill = requireTenantSkill(skillId);
		SkillDatasource skillDatasource = requireSkillDatasourceRelation(skillId, datasourceId, skill.getTenantId());

		TableResolutionIndex allowedTables = loadAllowedTables(skillDatasource, datasourceId, skill.getTenantId());
		Map<String, List<String>> sanitizedColumnsByTable = sanitizeColumnsByTable(datasourceId, skill.getTenantId(), columnsByTable,
				allowedTables);

		columnsMapper.removeAllColumns(skillDatasource.getId());
		List<SkillDatasourceColumn> rows = new ArrayList<>();
		sanitizedColumnsByTable.forEach((tableName, columns) -> columns.forEach(columnName -> rows
			.add(SkillDatasourceColumn.builder()
				.skillDatasourceId(skillDatasource.getId())
				.tableName(tableName)
				.columnName(columnName)
				.build())));
		if (!rows.isEmpty()) {
			columnsMapper.insertColumns(rows);
		}
		return refreshSkillDatasource(skillId, datasourceId, skill.getTenantId());
	}

	@Override
	public Map<String, List<String>> getEffectiveSkillDatasourceColumns(Long skillId, Long datasourceId)
			throws Exception {
		DataAgentSkill skill = requireTenantSkill(skillId);
		SkillDatasource skillDatasource = requireSkillDatasourceRelation(skillId, datasourceId, skill.getTenantId());
		List<String> selectedTables = Optional.ofNullable(tablesMapper.getSkillDatasourceTables(skillDatasource.getId()))
			.orElse(List.of());
		if (selectedTables.isEmpty()) {
			throw new IllegalStateException("Skill数据源未配置数据表白名单");
		}
		List<String> datasourceTables = loadDatasourceTableNames(datasourceId, skill.getTenantId());
		List<String> effectiveTables = sanitizeRequestedTables(selectedTables,
				buildTableResolutionIndex(datasourceTables), true);
		Map<String, List<String>> selectedColumns = loadSelectedColumns(skillDatasource.getId());
		Map<String, List<String>> result = new LinkedHashMap<>();
		for (String tableName : effectiveTables) {
			List<String> configuredColumns = selectedColumns.getOrDefault(tableName, List.of());
			if (!configuredColumns.isEmpty()) {
				result.put(tableName, configuredColumns);
				continue;
			}
			List<String> availableColumns;
			try {
				availableColumns = Optional.ofNullable(loadDatasourceColumnNames(datasourceId, skill.getTenantId(), tableName))
					.orElse(List.of())
					.stream()
					.filter(StringUtils::hasText)
					.map(String::trim)
					.distinct()
					.toList();
			}
			catch (Exception ex) {
				throw new IllegalStateException("Unable to resolve columns for datasource table " + tableName + ": "
						+ exceptionMessage(ex), ex);
			}
			if (availableColumns.isEmpty()) {
				throw new IllegalStateException("Datasource table " + tableName + " has no available columns");
			}
			result.put(tableName, availableColumns);
		}
		return Map.copyOf(result);
	}

	private String exceptionMessage(Exception exception) {
		return StringUtils.hasText(exception.getMessage()) ? exception.getMessage() : exception.getClass().getSimpleName();
	}

	@Override
	public List<String> getVisibleSkillTableColumns(Long skillId, Long datasourceId, String tableName) throws Exception {
		if (skillId == null || datasourceId == null || tableName == null || tableName.isBlank()) {
			throw CheckedException.badRequest(
					"skillId、datasourceId和tableName不能为空, skillId=" + skillId + ", datasourceId=" + datasourceId);
		}
		DataAgentSkill skill = requireTenantSkill(skillId);
		SkillDatasource skillDatasource = requireSkillDatasourceRelation(skillId, datasourceId, skill.getTenantId());
		TableResolutionIndex allowedTables = loadAllowedTables(skillDatasource, datasourceId, skill.getTenantId());
		String actualTableName = resolveTableName(tableName, allowedTables, false);
		if (actualTableName == null) {
			throw CheckedException
				.badRequest("数据表 '%s' 不存在或在当前 Skill 数据源中不可见".formatted(tableName));
		}
		return loadDatasourceColumnNames(datasourceId, skill.getTenantId(), actualTableName);
	}

	private SkillDatasource requireSkillDatasourceRelation(Long skillId, Long datasourceId, String tenantId) {
		datasourceService.requireDatasourceForTenant(datasourceId, tenantId);
		SkillDatasource relation = skillDatasourceMapper.selectBySkillIdAndDatasourceId(skillId, datasourceId);
		if (relation == null) {
			throw CheckedException
				.notFound("未找到对应的数据源关联记录, skillId=" + skillId + ", datasourceId=" + datasourceId);
		}
		return relation;
	}

	private void enrichSkillDatasource(SkillDatasource skillDatasource, String tenantId) {
		if (skillDatasource == null) {
			return;
		}
		if (skillDatasource.getDatasourceId() != null && skillDatasource.getDatasource() == null) {
			Datasource datasource = datasourceService.requireDatasourceForTenant(skillDatasource.getDatasourceId(), tenantId);
			skillDatasource.setDatasource(datasource);
		}
		if (skillDatasource.getDatasource() != null) {
			Long datasourceId = skillDatasource.getDatasource().getId();
			if (datasourceId != null) {
				Datasource datasource = datasourceService.requireDatasourceForTenant(datasourceId, tenantId);
				skillDatasource.setDatasource(datasource);
			}
		}
		List<String> tables = tablesMapper.getSkillDatasourceTables(skillDatasource.getId());
		skillDatasource.setSelectTables(Optional.ofNullable(tables).orElse(List.of()));
		skillDatasource.setSelectColumns(loadSelectedColumns(skillDatasource.getId()));
	}

	private SkillDatasource refreshSkillDatasource(Long skillId, Long datasourceId, String tenantId) {
		SkillDatasource refreshed = skillDatasourceMapper.selectBySkillIdAndDatasourceId(skillId, datasourceId);
		enrichSkillDatasource(refreshed, tenantId);
		return refreshed;
	}

	private Map<String, List<String>> loadSelectedColumns(Long skillDatasourceId) {
		List<SkillDatasourceColumn> rows = Optional
			.ofNullable(columnsMapper.getSkillDatasourceColumns(skillDatasourceId))
			.orElse(List.of());
		Map<String, List<String>> columnsByTable = new LinkedHashMap<>();
		for (SkillDatasourceColumn row : rows) {
			if (row == null) {
				continue;
			}
			columnsByTable.computeIfAbsent(row.getTableName(), key -> new ArrayList<>()).add(row.getColumnName());
		}
		columnsByTable.replaceAll((tableName, columns) -> List.copyOf(columns));
		return Map.copyOf(columnsByTable);
	}

	private TableResolutionIndex loadAllowedTables(SkillDatasource skillDatasource, Long datasourceId, String tenantId)
			throws Exception {
		List<String> datasourceTables = loadDatasourceTableNames(datasourceId, tenantId);
		TableResolutionIndex datasourceTableIndex = buildTableResolutionIndex(datasourceTables);
		List<String> selectedTables = Optional
			.ofNullable(tablesMapper.getSkillDatasourceTables(skillDatasource.getId()))
			.orElse(List.of());
		if (selectedTables.isEmpty()) {
			throw new IllegalStateException("Skill数据源未配置数据表白名单");
		}
		List<String> visibleTables = sanitizeRequestedTables(selectedTables, datasourceTableIndex, true);
		return buildTableResolutionIndex(visibleTables);
	}

	private Map<String, List<String>> sanitizeColumnsByTable(Long datasourceId, String tenantId,
			Map<String, List<String>> columnsByTable, TableResolutionIndex allowedTables) throws Exception {
		Map<String, List<String>> sanitized = new LinkedHashMap<>();
		for (Map.Entry<String, List<String>> entry : columnsByTable.entrySet()) {
			String requestedTableName = entry.getKey();
			String actualTableName = resolveTableName(requestedTableName, allowedTables, false);
			if (actualTableName == null) {
				throw CheckedException.badRequest("字段白名单配置包含当前 agent 不可见的数据表: " + requestedTableName);
			}

			Map<String, String> actualColumns = loadDatasourceColumnNames(datasourceId, tenantId, actualTableName)
				.stream()
				.collect(LinkedHashMap::new, (map, columnName) -> map.put(normalizeIdentifier(columnName), columnName),
						Map::putAll);
			LinkedHashSet<String> dedupedColumns = new LinkedHashSet<>();
			for (String requestedColumn : Optional.ofNullable(entry.getValue()).orElse(List.of())) {
				String normalizedColumn = normalizeIdentifier(requestedColumn);
				String actualColumnName = actualColumns.get(normalizedColumn);
				if (actualColumnName == null) {
					throw CheckedException.badRequest(
							"表 '%s' 中不存在字段 '%s'，无法保存字段级可见性配置".formatted(actualTableName, requestedColumn));
				}
				dedupedColumns.add(actualColumnName);
			}
			if (!dedupedColumns.isEmpty()) {
				sanitized.put(actualTableName, List.copyOf(dedupedColumns));
			}
		}
		return sanitized;
	}

	private List<String> loadDatasourceTableNames(Long datasourceId, String tenantId) throws Exception {
		if (datasourceService.hasDatasourceCatalog(datasourceId)) {
			return datasourceService.getCatalogTableNames(datasourceId);
		}
		return datasourceService.getDatasourceTablesForTenant(datasourceId, tenantId);
	}

	private List<String> loadDatasourceColumnNames(Long datasourceId, String tenantId, String tableName) throws Exception {
		if (datasourceService.hasDatasourceCatalog(datasourceId)) {
			return datasourceService.getCatalogColumnNames(datasourceId, tableName);
		}
		return datasourceService.getTableColumnsForTenant(datasourceId, tenantId, tableName);
	}

	private List<String> normalizeTableNames(List<String> tables) {
		return tables.stream()
			.map(String::trim)
			.filter(tableName -> !tableName.isEmpty())
			.collect(Collectors.toCollection(LinkedHashSet::new))
			.stream()
			.toList();
	}

	private List<String> sanitizeRequestedTables(List<String> tables, TableResolutionIndex tableIndex) {
		return sanitizeRequestedTables(tables, tableIndex, false);
	}

	private List<String> sanitizeRequestedTables(List<String> tables, TableResolutionIndex tableIndex,
			boolean allowQualifiedFallback) {
		LinkedHashSet<String> resolvedTables = new LinkedHashSet<>();
		for (String tableName : normalizeTableNames(tables)) {
			String resolvedTableName = resolveTableName(tableName, tableIndex, allowQualifiedFallback);
			if (resolvedTableName == null) {
				throw CheckedException
					.badRequest("数据表 '%s' 不存在或在当前数据源中不可见".formatted(tableName));
			}
			resolvedTables.add(resolvedTableName);
		}
		return List.copyOf(resolvedTables);
	}

	private TableResolutionIndex buildTableResolutionIndex(List<String> tableNames) {
		return new TableResolutionIndex(indexTableNames(tableNames, false), indexTableNames(tableNames, true));
	}

	private Map<String, List<String>> indexTableNames(List<String> tableNames, boolean leafOnly) {
		Map<String, LinkedHashSet<String>> index = new LinkedHashMap<>();
		for (String tableName : Optional.ofNullable(tableNames).orElse(List.of())) {
			if (tableName == null || tableName.isBlank()) {
				continue;
			}
			String normalizedTableName = leafOnly ? normalizeLeafIdentifier(tableName) : normalizeIdentifier(tableName);
			index.computeIfAbsent(normalizedTableName, key -> new LinkedHashSet<>()).add(tableName);
		}
		Map<String, List<String>> immutableIndex = new LinkedHashMap<>();
		index.forEach((key, value) -> immutableIndex.put(key, List.copyOf(value)));
		return Map.copyOf(immutableIndex);
	}

	private String resolveTableName(String requestedTableName, TableResolutionIndex tableIndex,
			boolean allowQualifiedFallback) {
		String normalizedTableName = normalizeIdentifier(requestedTableName);
		List<String> exactMatches = tableIndex.exactTables().getOrDefault(normalizedTableName, List.of());
		if (exactMatches.size() == 1) {
			return exactMatches.get(0);
		}
		if (exactMatches.size() > 1) {
			throw CheckedException.badRequest(
					"数据表 '%s' 匹配到多张数据源表: %s".formatted(requestedTableName, exactMatches));
		}
		if (isQualifiedIdentifier(requestedTableName) && !allowQualifiedFallback) {
			return null;
		}
		List<String> leafMatches = tableIndex.leafTables()
			.getOrDefault(normalizeLeafIdentifier(requestedTableName), List.of());
		if (leafMatches.size() == 1) {
			return leafMatches.get(0);
		}
		if (leafMatches.size() > 1) {
			throw CheckedException.badRequest(
					"数据表 '%s' 在数据源表中存在歧义: %s".formatted(requestedTableName, leafMatches));
		}
		return null;
	}

	private boolean isQualifiedIdentifier(String value) {
		return normalizeIdentifier(value).contains(".");
	}

	private String normalizeIdentifier(String value) {
		String normalized = Optional.ofNullable(value).orElse("").trim();
		normalized = stripWrapping(normalized, "`");
		normalized = stripWrapping(normalized, "\"");
		normalized = stripWrapping(normalized, "[", "]");
		return normalized.toLowerCase(Locale.ROOT);
	}

	private String normalizeLeafIdentifier(String value) {
		String normalized = normalizeIdentifier(value);
		if (normalized.contains(".")) {
			return normalized.substring(normalized.lastIndexOf('.') + 1);
		}
		return normalized;
	}

	private String stripWrapping(String value, String wrapper) {
		return stripWrapping(value, wrapper, wrapper);
	}

	private String stripWrapping(String value, String prefix, String suffix) {
		String normalized = value;
		if (normalized.startsWith(prefix)) {
			normalized = normalized.substring(prefix.length());
		}
		if (normalized.endsWith(suffix)) {
			normalized = normalized.substring(0, normalized.length() - suffix.length());
		}
		return normalized;
	}

	private record TableResolutionIndex(Map<String, List<String>> exactTables, Map<String, List<String>> leafTables) {
	}

}
