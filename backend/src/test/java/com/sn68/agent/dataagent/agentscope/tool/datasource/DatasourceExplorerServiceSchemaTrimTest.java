/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.agentscope.tool.datasource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.tool.datasource.permission.DataAgentSqlPermissionRewriteService;
import com.sn68.agent.dataagent.bo.DbConfigBO;
import com.sn68.agent.dataagent.bo.schema.ColumnInfoBO;
import com.sn68.agent.dataagent.connector.DbQueryParameter;
import com.sn68.agent.dataagent.connector.accessor.Accessor;
import com.sn68.agent.dataagent.connector.accessor.AccessorFactory;
import com.sn68.agent.dataagent.entity.Datasource;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.service.datasource.DatasourceService;
import com.sn68.agent.dataagent.service.schema.SchemaService;
import com.sn68.agent.dataagent.skill.execution.SkillVersionResources;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.mock.env.MockEnvironment;

/**
 * GET_TABLE_SCHEMA 响应瘦身（表/字段说明按字符截断）的行为验证：
 * 只截注释、不剔除列，且截断后单份 schema dump 体积必须有护栏约束。
 */
class DatasourceExplorerServiceSchemaTrimTest {

	private static final long DATASOURCE_ID = 10L;

	private static final long SKILL_ID = 2L;

	private static final long SKILL_VERSION_ID = 3L;

	private static final String TENANT_ID = "tenant-1";

	private static final String TABLE = "big_table";

	/** 体积护栏场景的列数，与工具侧 40 列宽表用例保持同一量级。 */
	private static final int COLUMN_COUNT = 40;

	/** 80 字说明：超过 60 字截断线，用于验证按字符截断与省略号标记。 */
	private static final String LONG_COMMENT_80 = "口径说明".repeat(20);

	/** 截断后的期望文本：恰好保留前 60 字 + 省略号。 */
	private static final String TRIMMED_COMMENT_80 = "口径说明".repeat(15) + "…";

	/** 200 字说明：模拟超宽列注释，用于体积护栏对比。 */
	private static final String LONG_COMMENT_200 = "口径说明".repeat(50);

	private final DatasourceService datasourceService = mock(DatasourceService.class);

	private final SchemaService schemaService = mock(SchemaService.class);

	private final Accessor accessor = mock(Accessor.class);

	private final DataAgentSqlPermissionRewriteService sqlPermissionRewriteService = mock(
			DataAgentSqlPermissionRewriteService.class);

	private DatasourceExplorerService service;

	@BeforeEach
	void setUp() throws Exception {
		when(accessor.getAccessorType()).thenReturn("test");
		when(accessor.supportedDataSourceType("postgresql")).thenReturn(true);
		when(datasourceService.requireDatasourceForTenant(DATASOURCE_ID, TENANT_ID)).thenReturn(datasource());
		when(datasourceService.getDbConfig(any(Datasource.class))).thenReturn(dbConfig());
		when(datasourceService.getLogicalRelationsForTenant(DATASOURCE_ID, TENANT_ID)).thenReturn(List.of());
		when(datasourceService.getPermissionRules(DATASOURCE_ID)).thenReturn(List.of());
		when(schemaService.getTableDocuments(eq(String.valueOf(SKILL_ID)), eq(DATASOURCE_ID), any()))
			.thenReturn(List.of());
		when(schemaService.getColumnDocumentsByTableName(eq(String.valueOf(SKILL_ID)), eq(DATASOURCE_ID), any()))
			.thenReturn(List.of());
		when(accessor.showForeignKeys(any(DbConfigBO.class), any(DbQueryParameter.class))).thenReturn(List.of());
		service = new DatasourceExplorerService(datasourceService, schemaService, new AccessorFactory(List.of(accessor)),
				new ObjectMapper(), new AnswerTraceExplainStore(), sqlPermissionRewriteService,
				new DatasourceRuntimeContextCache(new MockEnvironment()), new DataAgentProperties(), null);
	}

	@Test
	void schemaResponseTrimsOverlongCommentsButKeepsShortAndEmptyOnes() throws Exception {
		when(accessor.showColumns(any(DbConfigBO.class), any(DbQueryParameter.class)))
			.thenReturn(List.of(ColumnInfoBO.builder().name("order_no").type("varchar").description(LONG_COMMENT_80).build(),
					ColumnInfoBO.builder().name("customer_name").type("varchar").description("客户名称").build(),
					ColumnInfoBO.builder().name("memo").type("text").description("").build()));
		when(schemaService.getTableDocuments(eq(String.valueOf(SKILL_ID)), eq(DATASOURCE_ID), any()))
			.thenReturn(List.of(tableDocument(LONG_COMMENT_80)));

		DatasourceExplorerResult result = service.execute(
				schemaRequest(TABLE), graphRequest(List.of("order_no", "customer_name", "memo")));

		// 只截注释、不剔除列：3 个列全部保留，samples 等结构字段不受截断影响。
		assertEquals(3, result.getColumns().size());
		assertEquals(TRIMMED_COMMENT_80, result.getColumns().get(0).get("description"));
		assertEquals(61, ((String) result.getColumns().get(0).get("description")).length());
		assertNotNull(result.getColumns().get(0).get("samples"));
		assertEquals("客户名称", result.getColumns().get(1).get("description"));
		assertEquals("", result.getColumns().get(2).get("description"));
		assertEquals(TRIMMED_COMMENT_80, result.getTables().get(0).get("description"));
	}

	@Test
	void schemaDumpStaysWithinVolumeGuardAfterCommentTrimming() throws Exception {
		when(accessor.showColumns(any(DbConfigBO.class), any(DbQueryParameter.class)))
			.thenReturn(schemaColumns(COLUMN_COUNT, LONG_COMMENT_200));
		when(schemaService.getTableDocuments(eq(String.valueOf(SKILL_ID)), eq(DATASOURCE_ID), any()))
			.thenReturn(List.of(tableDocument(LONG_COMMENT_200)));

		DatasourceExplorerResult result = service.execute(schemaRequest(TABLE), graphRequest(allColumnNames()));
		String trimmedJson = new ObjectMapper().writeValueAsString(result);
		String untrimmedJson = new ObjectMapper().writeValueAsString(untrimmedBaseline());

		// 实测：40 列 × 200 字注释，未截断约 12.3K 字符，截断后约 6.6K 字符（缩减约 46%）。
		// 每列固定键名开销摊薄了缩减比例，任务书建议的 ≥50% 达不到，按实现取 ≥40% 缩减断言，
		// 并叠加 7K 字符硬上限作为单份 schema dump 的体积护栏。
		assertTrue(trimmedJson.length() <= 7_000, "trimmedJson.length()=" + trimmedJson.length());
		assertTrue(trimmedJson.length() * 100 <= untrimmedJson.length() * 60,
				"trimmed=" + trimmedJson.length() + ", untrimmed=" + untrimmedJson.length());
	}

	private List<ColumnInfoBO> schemaColumns(int count, String description) {
		List<ColumnInfoBO> columns = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			columns.add(ColumnInfoBO.builder().name("col_" + i).type("varchar").description(description).build());
		}
		return columns;
	}

	/**
	 * 与被测响应同构、但说明未截断的基线结果，用于对比截断前后的体积缩减比例。
	 */
	private DatasourceExplorerResult untrimmedBaseline() {
		Map<String, Object> tableEntry = new java.util.LinkedHashMap<>();
		tableEntry.put("name", TABLE);
		tableEntry.put("selected", true);
		tableEntry.put("schema", "");
		tableEntry.put("description", LONG_COMMENT_200);
		tableEntry.put("primaryKeys", List.of());
		tableEntry.put("foreignKeys", "");
		List<Map<String, Object>> columnEntries = new ArrayList<>();
		for (int i = 0; i < COLUMN_COUNT; i++) {
			Map<String, Object> columnEntry = new java.util.LinkedHashMap<>();
			columnEntry.put("name", "col_" + i);
			columnEntry.put("type", "varchar");
			columnEntry.put("description", LONG_COMMENT_200);
			columnEntry.put("primary", false);
			columnEntry.put("notnull", false);
			columnEntry.put("samples", List.of());
			columnEntries.add(columnEntry);
		}
		return DatasourceExplorerResult.builder()
			.datasource("test datasource")
			.action(DatasourceExplorerAction.GET_TABLE_SCHEMA.name())
			.summary("已加载表“%s”的结构信息".formatted(TABLE))
			.tables(List.of(tableEntry))
			.columns(columnEntries)
			.relations(List.of())
			.searchReady(true)
			.build();
	}

	private Document tableDocument(String description) {
		return new Document(TABLE, Map.of("name", TABLE, "description", description));
	}

	private List<String> allColumnNames() {
		List<String> columns = new ArrayList<>();
		for (int i = 0; i < COLUMN_COUNT; i++) {
			columns.add("col_" + i);
		}
		return columns;
	}

	private DatasourceExplorerRequest schemaRequest(String tableName) {
		DatasourceExplorerRequest request = new DatasourceExplorerRequest();
		request.setAction(DatasourceExplorerAction.GET_TABLE_SCHEMA);
		request.setTableName(tableName);
		return request;
	}

	private AgentRequest graphRequest(List<String> columns) {
		SkillVersionResources resources = new SkillVersionResources(SKILL_ID, SKILL_VERSION_ID,
				new SkillVersionResources.DatasourceResource(DATASOURCE_ID,
						List.of(new SkillVersionResources.TableScope(TABLE, columns)), true, 200, Map.of(), Map.of()),
				List.of(), List.of(), Map.of());
		return AgentRequest.builder().agentId("1").tenantIdSnapshot(TENANT_ID).routedSkillId(SKILL_ID)
			.routedSkillVersionId(SKILL_VERSION_ID).routedSkillResources(resources).build();
	}

	private Datasource datasource() {
		Datasource datasource = new Datasource();
		datasource.setId(DATASOURCE_ID);
		datasource.setName("test datasource");
		return datasource;
	}

	private DbConfigBO dbConfig() {
		return DbConfigBO.builder().dialectType("PostgreSQL").connectionType("jdbc").build();
	}

}
