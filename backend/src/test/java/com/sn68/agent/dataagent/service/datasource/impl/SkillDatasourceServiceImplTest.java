/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.datasource.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.Datasource;
import com.sn68.agent.dataagent.entity.SkillDatasource;
import com.sn68.agent.dataagent.entity.SkillDatasourceColumn;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.repository.DataAgentSkillMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.repository.SkillDatasourceColumnsMapper;
import com.sn68.agent.dataagent.repository.SkillDatasourceMapper;
import com.sn68.agent.dataagent.repository.SkillDatasourceTablesMapper;
import com.sn68.agent.dataagent.service.datasource.DatasourceService;
import com.sn68.agent.dataagent.service.schema.SchemaService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SkillDatasourceServiceImplTest {

	private final DatasourceService datasourceService = org.mockito.Mockito.mock(DatasourceService.class);

	private final DataAgentSkillMapper skillMapper = org.mockito.Mockito.mock(DataAgentSkillMapper.class);

	private final SkillDatasourceMapper skillDatasourceMapper = org.mockito.Mockito.mock(SkillDatasourceMapper.class);

	private final SkillDatasourceTablesMapper tablesMapper = org.mockito.Mockito.mock(SkillDatasourceTablesMapper.class);

	private final SkillDatasourceColumnsMapper columnsMapper = org.mockito.Mockito.mock(SkillDatasourceColumnsMapper.class);

	private final DataAgentSkillVersionMapper skillVersionMapper = org.mockito.Mockito.mock(DataAgentSkillVersionMapper.class);

	private final SkillDatasourceServiceImpl service = new SkillDatasourceServiceImpl(datasourceService, skillMapper,
			org.mockito.Mockito.mock(SchemaService.class), skillDatasourceMapper, tablesMapper, columnsMapper,
			skillVersionMapper);

	@Test
	void effectiveColumnsUseConfiguredColumnsBeforeExpandingAllFields() throws Exception {
		stubSkillDatasource();
		when(columnsMapper.getSkillDatasourceColumns(8L)).thenReturn(List.of(
			new SkillDatasourceColumn(8L, 8L, "orders", "id")));
		when(datasourceService.getCatalogColumnNames(3L, "customers")).thenReturn(List.of("id", "name"));

		Map<String, List<String>> columns = service.getEffectiveSkillDatasourceColumns(1L, 3L);

		assertEquals(List.of("id"), columns.get("orders"));
		assertEquals(List.of("id", "name"), columns.get("customers"));
		verify(datasourceService, never()).getCatalogColumnNames(3L, "orders");
	}

	@Test
	void effectiveColumnsRejectsAnUnrestrictedTableWithoutAvailableColumns() throws Exception {
		stubSkillDatasource();
		when(columnsMapper.getSkillDatasourceColumns(8L)).thenReturn(List.of());
		when(datasourceService.getCatalogColumnNames(3L, "orders")).thenReturn(List.of());

		IllegalStateException exception = assertThrows(IllegalStateException.class,
			() -> service.getEffectiveSkillDatasourceColumns(1L, 3L));

		assertEquals("Datasource table orders has no available columns", exception.getMessage());
	}

	@Test
	void effectiveColumnsIdentifiesTableWhenFieldLookupFails() throws Exception {
		stubSkillDatasource();
		when(columnsMapper.getSkillDatasourceColumns(8L)).thenReturn(List.of());
		when(datasourceService.getCatalogColumnNames(3L, "orders"))
			.thenThrow(new IllegalStateException("catalog unavailable"));

		IllegalStateException exception = assertThrows(IllegalStateException.class,
			() -> service.getEffectiveSkillDatasourceColumns(1L, 3L));

		assertEquals("Unable to resolve columns for datasource table orders: catalog unavailable", exception.getMessage());
	}

	@Test
	void addDatasourceReusesBlankTenantBindingWithoutInsert() {
		stubTenantSkill();
		SkillDatasource leftover = SkillDatasource.builder()
			.id(8L)
			.skillId(1L)
			.datasourceId(3L)
			.isActive(true)
			.tenantId(null)
			.build();
		when(skillDatasourceMapper.selectBySkillId(1L)).thenReturn(List.of(leftover));
		when(skillDatasourceMapper.selectBySkillIdAndDatasourceId(1L, 3L)).thenReturn(leftover);
		when(tablesMapper.getSkillDatasourceTables(8L)).thenReturn(List.of());

		service.addDatasourceToSkill(1L, 3L);

		verify(skillDatasourceMapper).patchActiveAndTenant(8L, true, "1");
		verify(skillDatasourceMapper, never()).createNewRelationEnabled(1L, 3L, "1");
	}

	@Test
	void addDatasourceDisablesBlankTenantActiveBindingBeforeInsertingAnother() {
		stubTenantSkill();
		SkillDatasource leftover = SkillDatasource.builder()
			.id(8L)
			.skillId(1L)
			.datasourceId(3L)
			.isActive(true)
			.tenantId("")
			.build();
		SkillDatasource created = SkillDatasource.builder().id(9L).skillId(1L).datasourceId(4L).isActive(true).tenantId("1")
			.build();
		when(skillDatasourceMapper.selectBySkillId(1L)).thenReturn(List.of(leftover));
		when(skillDatasourceMapper.selectBySkillIdAndDatasourceId(1L, 4L)).thenReturn(created);
		when(tablesMapper.getSkillDatasourceTables(9L)).thenReturn(List.of());
		when(datasourceService.requireDatasourceForTenant(4L, "1"))
			.thenReturn(Datasource.builder().id(4L).tenantId("1").build());

		service.addDatasourceToSkill(1L, 4L);

		verify(skillDatasourceMapper).patchActiveAndTenant(8L, false, "1");
		verify(skillDatasourceMapper).createNewRelationEnabled(1L, 4L, "1");
	}

	@Test
	void addDatasourceKeepsExistingActiveBindingWhenAnalysisConfigPresent() {
		stubTenantSkill();
		stubAnalysisConfig("""
				{"sources":[{"id":"t1","type":"TABLE","datasourceId":3,"table":"dis_demand","columns":["id"]}]}
				""");
		SkillDatasource existing = SkillDatasource.builder()
			.id(8L)
			.skillId(1L)
			.datasourceId(3L)
			.isActive(true)
			.tenantId("1")
			.build();
		SkillDatasource created = SkillDatasource.builder().id(9L).skillId(1L).datasourceId(4L).isActive(true).tenantId("1")
			.build();
		when(skillDatasourceMapper.selectBySkillId(1L)).thenReturn(List.of(existing));
		when(skillDatasourceMapper.selectBySkillIdAndDatasourceId(1L, 4L)).thenReturn(created);
		when(tablesMapper.getSkillDatasourceTables(9L)).thenReturn(List.of());
		when(datasourceService.requireDatasourceForTenant(4L, "1"))
			.thenReturn(Datasource.builder().id(4L).tenantId("1").build());

		service.addDatasourceToSkill(1L, 4L);

		verify(skillDatasourceMapper, never()).patchActiveAndTenant(8L, false, "1");
		verify(skillDatasourceMapper).createNewRelationEnabled(1L, 4L, "1");
	}

	@Test
	void toggleAllowsSecondActiveDatasourceWhenAnalysisConfigPresent() {
		stubTenantSkill();
		stubAnalysisConfig("""
				{"sources":[{"id":"t1","type":"TABLE","datasourceId":3,"table":"dis_demand","columns":["id"]},
				{"id":"t2","type":"TABLE","datasourceId":4,"table":"stock","columns":["id"]}]}
				""");
		SkillDatasource existing = SkillDatasource.builder()
			.id(9L)
			.skillId(1L)
			.datasourceId(4L)
			.isActive(false)
			.tenantId("1")
			.build();
		when(skillDatasourceMapper.selectBySkillIdAndDatasourceId(1L, 4L)).thenReturn(existing);
		when(skillDatasourceMapper.countActiveBySkillIdExcluding(1L, 4L)).thenReturn(1);
		when(skillDatasourceMapper.updateRelation(1L, 4L, true)).thenReturn(1);
		when(tablesMapper.getSkillDatasourceTables(9L)).thenReturn(List.of());
		when(datasourceService.requireDatasourceForTenant(4L, "1"))
			.thenReturn(Datasource.builder().id(4L).tenantId("1").build());

		service.toggleDatasourceForSkill(1L, 4L, true);

		verify(skillDatasourceMapper).updateRelation(1L, 4L, true);
		verify(skillDatasourceMapper, never()).countActiveBySkillIdExcluding(1L, 4L);
	}

	@Test
	void fileOnlyAllowsRemovingLastActiveDatasource() {
		stubTenantSkill();
		stubAnalysisConfig("""
				{"sources":[{"id":"f1","type":"FILE_TABLE","turnFile":true}]}
				""");
		SkillDatasource existing = SkillDatasource.builder()
			.id(8L)
			.skillId(1L)
			.datasourceId(3L)
			.isActive(true)
			.tenantId("1")
			.build();
		when(skillDatasourceMapper.selectBySkillIdAndDatasourceId(1L, 3L)).thenReturn(existing);
		when(skillDatasourceMapper.countActiveBySkillId(1L)).thenReturn(1);

		service.removeDatasourceFromSkill(1L, 3L);

		verify(skillDatasourceMapper).removeRelation(1L, 3L);
	}

	@Test
	void addDatasourceTranslatesQuerySingleActiveConstraint() {
		stubTenantSkill();
		when(skillDatasourceMapper.selectBySkillId(1L)).thenReturn(List.of());
		when(skillDatasourceMapper.createNewRelationEnabled(1L, 3L, "1"))
			.thenThrow(new RuntimeException(new SQLException("QUERY Skill can have only one active datasource, skillId=1",
					"P0001")));

		CheckedException exception = assertThrows(CheckedException.class, () -> service.addDatasourceToSkill(1L, 3L));

		assertEquals("QUERY 类型技能只能启用一个数据源, skillId=1", exception.getMessage());
	}

	private void stubTenantSkill() {
		DataAgentSkill skill = DataAgentSkill.builder()
			.id(1L)
			.tenantId("1")
			.scope("TENANT")
			.skillKind("QUERY")
			.latestDraftVersionId(10L)
			.build();
		when(skillMapper.selectById(1L)).thenReturn(skill);
		when(datasourceService.requireDatasourceForTenant(3L, "1"))
			.thenReturn(Datasource.builder().id(3L).tenantId("1").build());
	}

	private void stubAnalysisConfig(String json) {
		when(skillVersionMapper.selectById(10L)).thenReturn(DataAgentSkillVersion.builder()
			.id(10L)
			.skillId(1L)
			.status("DRAFT")
			.analysisConfig(json)
			.build());
	}

	private void stubSkillDatasource() {
		DataAgentSkill skill = DataAgentSkill.builder().id(1L).tenantId("1").scope("TENANT").build();
		SkillDatasource relation = SkillDatasource.builder().id(8L).skillId(1L).datasourceId(3L).build();
		when(skillMapper.selectById(1L)).thenReturn(skill);
		when(datasourceService.requireDatasourceForTenant(3L, "1"))
			.thenReturn(Datasource.builder().id(3L).tenantId("1").build());
		when(skillDatasourceMapper.selectBySkillIdAndDatasourceId(1L, 3L)).thenReturn(relation);
		when(tablesMapper.getSkillDatasourceTables(8L)).thenReturn(List.of("orders", "customers"));
		when(datasourceService.hasDatasourceCatalog(3L)).thenReturn(true);
		when(datasourceService.getCatalogTableNames(3L)).thenReturn(List.of("orders", "customers"));
	}

}
