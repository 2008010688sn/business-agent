/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.controller;

import com.sn68.agent.dataagent.dto.datasource.TableColumnsSelectionDTO;
import com.sn68.agent.dataagent.dto.skill.SkillDatasourceBindReq;
import com.sn68.agent.dataagent.dto.skill.SkillDatasourceColumnsReq;
import com.sn68.agent.dataagent.dto.skill.SkillDatasourceEnabledReq;
import com.sn68.agent.dataagent.dto.skill.SkillDatasourceTablesReq;
import com.sn68.agent.dataagent.entity.Datasource;
import com.sn68.agent.dataagent.entity.LogicalRelation;
import com.sn68.agent.dataagent.entity.SkillDatasource;
import com.sn68.agent.dataagent.service.datasource.SkillDatasourceService;
import com.sn68.agent.dataagent.service.skill.SkillResourceAccessService;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Skill 数据源配置接口：维护 Skill 绑定的数据源、表/字段白名单与启停状态。
 */
@RestController
@RequestMapping("/skills/{skillId}/resources/datasources")
@Tag(name = "Skill数据源配置", description = "维护Skill数据源、表字段白名单和启停状态")
@RequiredArgsConstructor
public class SkillDatasourceController {

	private final SkillDatasourceService skillDatasourceService;

	private final SkillResourceAccessService skillResourceAccessService;

	@Operation(summary = "初始化Skill数据源schema")
	@PostMapping("/init")
	public void initSchema(@PathVariable Long skillId) {
		skillResourceAccessService.requireQueryResourceSkill(skillId);
		skillDatasourceService.initializeSchemaForCurrentSkillDatasource(skillId);
	}

	@Operation(summary = "查询Skill数据源")
	@GetMapping
	public List<SkillDatasource> list(@PathVariable Long skillId) {
		skillResourceAccessService.requireQueryResourceSkill(skillId);
		return skillDatasourceService.listSkillDatasources(skillId);
	}

	@Operation(summary = "查询Skill可绑定的数据源")
	@GetMapping("/candidates")
	public List<Datasource> candidates(@PathVariable Long skillId) {
		skillResourceAccessService.requireQueryResourceSkill(skillId);
		return skillDatasourceService.listDatasourceCandidates(skillId);
	}

	@Operation(summary = "查询Skill活跃数据源")
	@GetMapping("/active")
	public SkillDatasource active(@PathVariable Long skillId) {
		skillResourceAccessService.requireQueryResourceSkill(skillId);
		return skillDatasourceService.getCurrentSkillDatasource(skillId);
	}

	@Operation(summary = "绑定Skill数据源")
	@AccessLog(module = "Skill 数据源", description = "绑定Skill数据源")
	@PostMapping
	public SkillDatasource create(@PathVariable Long skillId, @RequestBody @Valid SkillDatasourceBindReq request) {
		requireSkillDatasource(skillId, request.datasourceId());
		return skillDatasourceService.addDatasourceToSkill(skillId, request.datasourceId());
	}

	@Operation(summary = "更新Skill数据源表白名单")
	@AccessLog(module = "Skill 数据源", description = "更新Skill数据源表白名单")
	@PutMapping("/{datasourceId}/tables")
	public SkillDatasource updateTables(@PathVariable Long skillId, @PathVariable Long datasourceId,
			@RequestBody SkillDatasourceTablesReq request) {
		requireSkillDatasource(skillId, datasourceId);
		return skillDatasourceService.updateSkillDatasourceTables(skillId, datasourceId,
				request == null || request.tables() == null ? List.of() : request.tables());
	}

	@Operation(summary = "更新Skill数据源字段白名单")
	@AccessLog(module = "Skill 数据源", description = "更新Skill数据源字段白名单")
	@PutMapping("/{datasourceId}/columns")
	public SkillDatasource updateColumns(@PathVariable Long skillId, @PathVariable Long datasourceId,
			@RequestBody SkillDatasourceColumnsReq request) throws Exception {
		requireSkillDatasource(skillId, datasourceId);
		return skillDatasourceService.updateSkillDatasourceColumns(skillId, datasourceId,
				toColumnsByTable(request == null ? null : request.tables()));
	}

	@Operation(summary = "删除Skill数据源绑定")
	@AccessLog(module = "Skill 数据源", description = "删除Skill数据源绑定")
	@DeleteMapping("/{datasourceId}")
	public void delete(@PathVariable Long skillId, @PathVariable Long datasourceId) {
		requireSkillDatasource(skillId, datasourceId);
		skillDatasourceService.removeDatasourceFromSkill(skillId, datasourceId);
	}

	@Operation(summary = "启停Skill数据源")
	@AccessLog(module = "Skill 数据源", description = "启停Skill数据源")
	@PutMapping("/{datasourceId}/enabled")
	public SkillDatasource updateEnabled(@PathVariable Long skillId, @PathVariable Long datasourceId,
			@RequestBody @Valid SkillDatasourceEnabledReq request) {
		requireSkillDatasource(skillId, datasourceId);
		return skillDatasourceService.toggleDatasourceForSkill(skillId, datasourceId, request.enabled());
	}

	@Operation(summary = "测试Skill数据源连接")
	@PostMapping("/{datasourceId}/connection-test")
	public boolean testConnection(@PathVariable Long skillId, @PathVariable Long datasourceId) {
		requireSkillDatasource(skillId, datasourceId);
		return skillDatasourceService.testSkillDatasourceConnection(skillId, datasourceId);
	}

	@Operation(summary = "查询Skill数据源可用表")
	@GetMapping("/{datasourceId}/available-tables")
	public List<String> availableTables(@PathVariable Long skillId, @PathVariable Long datasourceId) throws Exception {
		requireSkillDatasource(skillId, datasourceId);
		return skillDatasourceService.getAvailableSkillDatasourceTables(skillId, datasourceId);
	}

	@Operation(summary = "查询Skill数据源可用字段")
	@GetMapping("/{datasourceId}/available-tables/{tableName}/columns")
	public List<String> availableColumns(@PathVariable Long skillId, @PathVariable Long datasourceId,
			@PathVariable String tableName) throws Exception {
		requireSkillDatasource(skillId, datasourceId);
		return skillDatasourceService.getAvailableSkillDatasourceColumns(skillId, datasourceId, tableName);
	}

	@Operation(summary = "查询Skill数据源逻辑关系")
	@GetMapping("/{datasourceId}/logical-relations")
	public List<LogicalRelation> logicalRelations(@PathVariable Long skillId, @PathVariable Long datasourceId) {
		requireSkillDatasource(skillId, datasourceId);
		return skillDatasourceService.getSkillLogicalRelations(skillId, datasourceId);
	}

	@Operation(summary = "保存Skill数据源逻辑关系")
	@AccessLog(module = "Skill 数据源", description = "保存Skill数据源逻辑关系")
	@PutMapping("/{datasourceId}/logical-relations")
	public List<LogicalRelation> saveLogicalRelations(@PathVariable Long skillId, @PathVariable Long datasourceId,
			@RequestBody List<LogicalRelation> logicalRelations) {
		requireSkillDatasource(skillId, datasourceId);
		return skillDatasourceService.saveSkillLogicalRelations(skillId, datasourceId,
				logicalRelations == null ? List.of() : logicalRelations);
	}

	@Operation(summary = "查询Skill数据源表字段")
	@GetMapping("/{datasourceId}/tables/{tableName}/columns")
	public List<String> columns(@PathVariable Long skillId, @PathVariable Long datasourceId,
			@PathVariable String tableName) throws Exception {
		requireSkillDatasource(skillId, datasourceId);
		return skillDatasourceService.getVisibleSkillTableColumns(skillId, datasourceId, tableName);
	}

	private void requireSkillDatasource(Long skillId, Long datasourceId) {
		skillResourceAccessService.requireQueryResourceSkill(skillId);
	}

	private Map<String, List<String>> toColumnsByTable(List<TableColumnsSelectionDTO> tables) {
		Map<String, List<String>> result = new LinkedHashMap<>();
		for (TableColumnsSelectionDTO table : tables == null ? List.<TableColumnsSelectionDTO>of() : tables) {
			if (table != null) {
				result.put(table.getTableName(), table.getColumns() == null ? List.of() : table.getColumns());
			}
		}
		return result;
	}

}
