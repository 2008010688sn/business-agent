/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.agentscope.tool.datasource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.multimodal.SqlResultCompactor;
import com.sn68.agent.dataagent.skill.execution.SkillVersionResources;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.mock.env.MockEnvironment;

class DatasourceExplorerToolProviderTest {

	private final DatasourceExplorerService explorerService = mock(DatasourceExplorerService.class);

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final com.sn68.agent.dataagent.properties.DataAgentProperties dataAgentProperties =
			new com.sn68.agent.dataagent.properties.DataAgentProperties();

	private DatasourceExplorerToolProvider provider;

	@BeforeEach
	void setUp() {
		provider = new DatasourceExplorerToolProvider(explorerService, objectMapper,
				new DatasourceRuntimeContextCache(new MockEnvironment()),
				new com.sn68.agent.dataagent.multimodal.SqlResultCompactor(), dataAgentProperties);
	}

	@Test
	void routedSchemaDoesNotExposeListTables() {
		assertFalse(callback().getToolDefinition().inputSchema().contains("LIST_TABLES"));
	}

	@Test
	void compactSchemaOmitsEmptyValuesButKeepsVisibleFields() throws Exception {
		when(explorerService.execute(any(), any())).thenReturn(schemaResult());
		ToolCallback callback = callback();

		JsonNode output = objectMapper.readTree(callback.call("{\"action\":\"GET_TABLE_SCHEMA\",\"tableName\":\"orders\"}",
				toolContext()));
		JsonNode column = output.path("columns").get(0);

		assertTrue(column.has("name"));
		assertTrue(column.has("type"));
		assertFalse(column.has("notnull"));
		assertFalse(column.has("description"));
		assertFalse(column.has("primary"));
		assertFalse(column.has("samples"));
	}

	@Test
	void compactSchemaKeepsEveryColumnNameAndDropsRelationNoise() throws Exception {
		List<Map<String, Object>> columns = new java.util.ArrayList<>();
		for (int i = 0; i < 40; i++) {
			columns.add(new java.util.LinkedHashMap<>(Map.of("name", "col_" + i, "type", "varchar",
					"description", "很长的字段说明".repeat(20), "primary", false, "notnull", true,
					"samples", List.of("a", "b", "c"))));
		}
		when(explorerService.execute(any(), any())).thenReturn(DatasourceExplorerResult.builder()
			.datasource("dev")
			.action("GET_TABLE_SCHEMA")
			.summary("schema")
			.searchReady(true)
			.tables(List.of(new java.util.LinkedHashMap<>(Map.of("name", "orders", "description", "订单主表说明".repeat(30),
					"foreignKeys", "fk_orders_customer"))))
			.columns(columns)
			.relations(List.of(new java.util.LinkedHashMap<>(Map.of("sourceTable", "orders", "sourceColumn", "customer_id",
					"targetTable", "customer", "targetColumn", "id", "description", "关联说明".repeat(40),
					"relationType", "FK", "virtual", false))))
			.relationEvidence(List.of(Map.of("note", "evidence".repeat(50))))
			.build());

		String raw = callback().call("{\"action\":\"GET_TABLE_SCHEMA\",\"tableName\":\"orders\"}", toolContext());
		JsonNode output = objectMapper.readTree(raw);

		assertEquals(40, output.path("columns").size());
		assertEquals("col_0", output.path("columns").get(0).path("name").asText());
		assertEquals("varchar", output.path("columns").get(0).path("type").asText());
		assertFalse(output.path("columns").get(0).has("description"));
		assertEquals("orders", output.path("tables").get(0).path("name").asText());
		assertFalse(output.path("tables").get(0).has("description"));
		assertEquals("customer_id", output.path("relations").get(0).path("sourceColumn").asText());
		assertFalse(output.path("relations").get(0).has("description"));
		assertTrue(output.path("relationEvidence").isMissingNode() || output.path("relationEvidence").isNull());
		assertTrue(raw.length() < 4_000);
	}

	@Test
	void fullSchemaRequestIsForcedCompact() throws Exception {
		when(explorerService.execute(any(), any())).thenReturn(schemaResult());
		ToolCallback callback = callback();

		JsonNode output = objectMapper.readTree(callback.call(
				"{\"action\":\"GET_TABLE_SCHEMA\",\"tableName\":\"orders\",\"detailLevel\":\"FULL\"}", toolContext()));
		JsonNode column = output.path("columns").get(0);

		assertTrue(column.has("name"));
		assertTrue(column.has("type"));
		assertFalse(column.has("description"));
		assertFalse(column.has("primary"));
		assertFalse(column.has("samples"));
	}

	@Test
	void schemaProbeAfterSuccessfulSearchReturnsHint() throws Exception {
		when(explorerService.execute(any(), any())).thenReturn(DatasourceExplorerResult.builder()
			.action("SEARCH")
			.summary("ok")
			.rows(List.of(Map.of("customer_name", "箱箱", "used_boxes", 10)))
			.build());
		ToolCallback callback = callback();
		ToolContext toolContext = toolContext();
		callback.call("{\"action\":\"SEARCH\",\"sql\":\"select 1\"}", toolContext);

		String output = callback.call("{\"action\":\"GET_TABLE_SCHEMA\",\"tableName\":\"orders\"}", toolContext);

		assertTrue(output.contains("已有查询结果"));
		verify(explorerService, times(1)).execute(any(), any());
	}

	@Test
	void searchRowsAreCompactedWithoutSumming() throws Exception {
		List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
		for (int i = 0; i < 35; i++) {
			rows.add(java.util.Map.of("name", "c" + i, "amount", i));
		}
		when(explorerService.execute(any(), any())).thenReturn(DatasourceExplorerResult.builder()
			.action("SEARCH")
			.summary("ok")
			.rows(rows)
			.build());

		JsonNode output = objectMapper.readTree(callback().call("{\"action\":\"SEARCH\",\"sql\":\"select 1\"}",
				toolContext()));

		assertEquals(3, output.path("rows").size());
		assertTrue(output.path("hasMore").asBoolean());
		assertEquals(35, output.path("returnedRows").asInt());
		assertTrue(output.path("summary").asText().contains(SqlResultCompactor.MODEL_PREVIEW_NOTICE));
		assertFalse(output.path("summary").asText().contains("合计："));
	}

	@Test
	void emptySearchResultCarriesGuidanceHint() throws Exception {
		when(explorerService.execute(any(), any())).thenReturn(DatasourceExplorerResult.builder()
			.action("SEARCH")
			.summary("ok")
			.rows(List.of())
			.build());

		JsonNode output = objectMapper.readTree(callback().call("{\"action\":\"SEARCH\",\"sql\":\"select 1\"}",
				toolContext()));

		assertTrue(output.path("empty").asBoolean());
		assertTrue(output.path("hint").asText().contains("结果为空"));
		assertTrue(output.path("hint").asText().contains("不要用完全相同的参数重复查询"));
	}

	@Test
	void searchPayloadCarriesQueryDurationMs() throws Exception {
		when(explorerService.execute(any(), any())).thenReturn(DatasourceExplorerResult.builder()
			.action("SEARCH")
			.summary("ok")
			.rows(List.of(Map.of("customer_name", "箱箱", "used_boxes", 10)))
			.build());

		JsonNode output = objectMapper.readTree(callback().call("{\"action\":\"SEARCH\",\"sql\":\"select 1\"}",
				toolContext()));

		assertTrue(output.has("queryDurationMs"));
		assertTrue(output.path("queryDurationMs").asLong() >= 0);
	}

	@Test
	void applyQueryDurationKeepsFastSummaryAndAnnotatesSlowOne() {
		DatasourceExplorerResult fastResult = DatasourceExplorerResult.builder().action("SEARCH").summary("ok").build();
		DatasourceExplorerToolProvider.applyQueryDuration(fastResult, 1_200L);
		assertEquals("ok", fastResult.getSummary());
		assertEquals(1_200L, fastResult.getQueryDurationMs());

		DatasourceExplorerResult slowResult = DatasourceExplorerResult.builder().action("SEARCH").summary("ok").build();
		DatasourceExplorerToolProvider.applyQueryDuration(slowResult, 17_100L);
		assertEquals(17_100L, slowResult.getQueryDurationMs());
		assertTrue(slowResult.getSummary().contains("数据库查询耗时"));
		assertTrue(slowResult.getSummary().contains("17.1"));
		assertTrue(slowResult.getSummary().startsWith("ok，"));
	}

	@Test
	void successfulSchemaReadCanRepeatWithinOneRequest() throws Exception {
		when(explorerService.execute(any(), any())).thenReturn(schemaResult());
		ToolCallback callback = callback();
		ToolContext toolContext = toolContext();
		String input = "{\"action\":\"GET_TABLE_SCHEMA\",\"tableName\":\"orders\"}";

		callback.call(input, toolContext);
		callback.call(input, toolContext);

		verify(explorerService, times(2)).execute(any(), any());
	}

	@Test
	void eighthDistinctSchemaReadReturnsBudgetHintWithoutThrowing() throws Exception {
		when(explorerService.execute(any(), any())).thenReturn(schemaResult());
		ToolCallback callback = callback();
		ToolContext toolContext = toolContext();
		for (int i = 1; i <= 7; i++) {
			callback.call("{\"action\":\"GET_TABLE_SCHEMA\",\"tableName\":\"t" + i + "\"}", toolContext);
		}

		String output = callback.call("{\"action\":\"GET_TABLE_SCHEMA\",\"tableName\":\"t8\"}", toolContext);

		assertTrue(output.contains("本轮已加载 7 张表结构"));
		assertTrue(output.contains("不要再探新表"));
		verify(explorerService, times(7)).execute(any(), any());
	}

	@Test
	void configuredMaxSchemaTablesOverridesDefaultBudget() throws Exception {
		dataAgentProperties.getRuntime().setMaxSchemaTablesPerRequest(2);
		when(explorerService.execute(any(), any())).thenReturn(schemaResult());
		ToolCallback callback = callback();
		ToolContext toolContext = toolContext();
		callback.call("{\"action\":\"GET_TABLE_SCHEMA\",\"tableName\":\"t1\"}", toolContext);
		callback.call("{\"action\":\"GET_TABLE_SCHEMA\",\"tableName\":\"t2\"}", toolContext);

		String output = callback.call("{\"action\":\"GET_TABLE_SCHEMA\",\"tableName\":\"t3\"}", toolContext);

		assertTrue(output.contains("本轮已加载 2 张表结构"));
		verify(explorerService, times(2)).execute(any(), any());
	}

	@Test
	void findTablesDoesNotConsumeSchemaReadBudget() throws Exception {
		when(explorerService.execute(any(), any())).thenReturn(schemaResult());
		ToolCallback callback = callback();
		ToolContext toolContext = toolContext();

		callback.call("{\"action\":\"FIND_TABLES\",\"query\":\"用箱量\"}", toolContext);
		callback.call("{\"action\":\"FIND_TABLES\",\"query\":\"客户\"}", toolContext);
		callback.call("{\"action\":\"GET_TABLE_SCHEMA\",\"tableName\":\"orders\"}", toolContext);

		verify(explorerService, times(3)).execute(any(), any());
	}

	private ToolCallback callback() {
		return provider.getSkillToolCallbacks(resources()).values().iterator().next();
	}

	private ToolContext toolContext() {
		AgentRequest request = AgentRequest.builder().agentId("1").threadId("thread-1").runtimeRequestId("run-1")
			.tenantIdSnapshot("tenant-1").routedSkillId(2L).routedSkillVersionId(3L)
			.routedSkillResources(resources()).build();
		return new ToolContext(Map.of("graphRequest", request));
	}

	private SkillVersionResources resources() {
		return new SkillVersionResources(2L, 3L,
				new SkillVersionResources.DatasourceResource(10L,
						List.of(new SkillVersionResources.TableScope("orders", List.of("id"))), true, 200, Map.of(),
						Map.of()),
				List.of(), List.of(), Map.of());
	}

	private DatasourceExplorerResult schemaResult() {
		return DatasourceExplorerResult.builder()
			.datasource("dev")
			.action("GET_TABLE_SCHEMA")
			.summary("schema")
			.searchReady(true)
			.tables(List.of(Map.of("name", "orders")))
			.columns(List.of(new java.util.LinkedHashMap<>(Map.of("name", "id", "type", "bigint",
					"description", "", "primary", false, "notnull", true, "samples", List.of()))))
			.relations(List.of())
			.build();
	}

}
