/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.agentscope.tool.datasource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.tool.datasource.permission.DataAgentSqlPermissionRewriteService;
import com.sn68.agent.dataagent.bo.DbConfigBO;
import com.sn68.agent.dataagent.bo.schema.ColumnInfoBO;
import com.sn68.agent.dataagent.bo.schema.ResultSetBO;
import com.sn68.agent.dataagent.connector.DbQueryParameter;
import com.sn68.agent.dataagent.connector.accessor.Accessor;
import com.sn68.agent.dataagent.connector.accessor.AccessorFactory;
import com.sn68.agent.dataagent.entity.Datasource;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.service.datasource.DatasourceService;
import com.sn68.agent.dataagent.service.schema.SchemaService;
import com.sn68.agent.dataagent.skill.execution.SkillVersionResources;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DatasourceExplorerServiceTest {

	private static final long DATASOURCE_ID = 10L;

	private static final long SKILL_ID = 2L;

	private static final long SKILL_VERSION_ID = 3L;

	private static final String TENANT_ID = "tenant-1";

	/** 探索工具自身允许的最大 limit，用于验证它仍会被平台上限与 Skill 快照行数进一步收敛。 */
	private static final int REQUESTED_PREVIEW_LIMIT = 200;

	private final DatasourceService datasourceService = mock(DatasourceService.class);

	private final SchemaService schemaService = mock(SchemaService.class);

	private final Accessor accessor = mock(Accessor.class);

	private final DataAgentSqlPermissionRewriteService sqlPermissionRewriteService = mock(
			DataAgentSqlPermissionRewriteService.class);

	private final DataAgentProperties dataAgentProperties = new DataAgentProperties();

	private DatasourceExplorerService service;

	@BeforeEach
	void setUp() throws Exception {
		when(accessor.getAccessorType()).thenReturn("test");
		when(accessor.supportedDataSourceType("postgresql")).thenReturn(true);
		when(datasourceService.requireDatasourceForTenant(DATASOURCE_ID, TENANT_ID)).thenReturn(datasource());
		when(datasourceService.getDbConfig(any(Datasource.class))).thenReturn(dbConfig());
		when(datasourceService.getLogicalRelationsForTenant(DATASOURCE_ID, TENANT_ID)).thenReturn(List.of());
		when(datasourceService.getPermissionRules(DATASOURCE_ID)).thenReturn(List.of());
		when(schemaService.getTableDocuments(eq(String.valueOf(SKILL_ID)), eq(DATASOURCE_ID), any())).thenReturn(List.of());
		when(schemaService.getColumnDocumentsByTableName(eq(String.valueOf(SKILL_ID)), eq(DATASOURCE_ID), any()))
			.thenReturn(List.of());
		when(sqlPermissionRewriteService.rewrite(eq(DATASOURCE_ID), any(), any())).thenAnswer(invocation ->
				new DataAgentSqlPermissionRewriteService.PermissionRewriteResult(invocation.getArgument(1), false,
						List.of(), List.of(), List.of(), false, null));
		when(accessor.showForeignKeys(any(DbConfigBO.class), any(DbQueryParameter.class))).thenReturn(List.of());
		when(accessor.executeSqlAndReturnObject(any(DbConfigBO.class), any(DbQueryParameter.class)))
			.thenReturn(ResultSetBO.builder().column(List.of("count")).data(List.of(Map.of("count", "3"))).build());

		service = new DatasourceExplorerService(datasourceService, schemaService, new AccessorFactory(List.of(accessor)),
				new ObjectMapper(), new AnswerTraceExplainStore(), sqlPermissionRewriteService,
				new DatasourceRuntimeContextCache(new MockEnvironment()), dataAgentProperties, null);
	}

	@Test
	void runtimeRequestWithoutSkillResourcesDoesNotFallbackToSkillDatasource() {
		DatasourceExplorerRequest request = searchRequest("select count(*) from orders");

		assertThrows(IllegalArgumentException.class, () -> service.execute(request, new AgentRequest()));
	}

	@Test
	void searchUsesOnlyTheRoutedSkillDatasourceSnapshot() throws Exception {
		DatasourceExplorerResult result = service.execute(searchRequest("select count(*) from orders"),
				graphRequest(defaultTables()));

		assertEquals(List.of("orders"), result.getUsedTables());
		assertEquals(List.of("count"), result.getUsedColumns());
		assertEquals("3", result.getRows().get(0).get("count"));
		verify(datasourceService).getLogicalRelationsForTenant(DATASOURCE_ID, TENANT_ID);
		verify(datasourceService, never()).getLogicalRelations(any());
	}

	@Test
	void collaboratorSearchDoesNotInheritParentTopNFromPredecessorText() throws Exception {
		AgentRequest request = graphRequest(defaultTables());
		request.setQuery("协作任务：这些客户应收金额的情况\n前置步骤结果：本月客户用箱量Top10已查询完成");
		request.setRankingIntentQuery("这些客户应收金额的情况");

		DatasourceExplorerResult result = service.execute(searchRequest("select customer_name from orders limit 2"),
				request);

		assertEquals(List.of("orders"), result.getUsedTables());
	}

	@Test
	void deterministicSearchRejectsQueriesWithoutAuthorizedBaseTables() {
		DatasourceExplorerRequest request = searchRequest("select 1");
		request.setRequireAuthorizedBaseTable(true);

		DeterministicSqlGuardRejectedException failure = assertThrows(DeterministicSqlGuardRejectedException.class,
				() -> service.execute(request, graphRequest(defaultTables())));

		assertEquals("只读查询必须引用至少一张当前 Skill 已授权的基础表", failure.getCause().getMessage());
	}

	@Test
	void reactSearchAlsoRejectsQueriesWithoutAuthorizedBaseTables() {
		AtomicInteger callbackCalls = new AtomicInteger();
		DatasourceExplorerRequest request = searchRequest("select 1");
		request.setStaticGuardPassedCallback(callbackCalls::incrementAndGet);

		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
				() -> service.execute(request, graphRequest(defaultTables())));

		assertEquals("只读查询必须引用至少一张当前 Skill 已授权的基础表", failure.getMessage());
		assertEquals(0, callbackCalls.get());
	}

	@Test
	void deterministicSearchRejectsTableFunctions() {
		DatasourceExplorerRequest request = searchRequest("select 1 from generate_series(1, 3) series");
		request.setRequireAuthorizedBaseTable(true);

		DeterministicSqlGuardRejectedException failure = assertThrows(DeterministicSqlGuardRejectedException.class,
				() -> service.execute(request, graphRequest(defaultTables())));

		assertEquals("只读查询不允许使用表函数", failure.getCause().getMessage());
	}

	@Test
	void reactSearchAlsoRejectsTableFunctions() {
		assertEquals("只读查询不允许使用表函数",
				searchFailureMessage("select 1 from generate_series(1, 3) series"));
	}

	@Test
	void searchRejectsProjectionWithoutFromClause() {
		assertEquals("只读查询必须引用至少一张当前 Skill 已授权的基础表",
				searchFailureMessage("select 'x' as label, 2 as num"));
	}

	@Test
	void searchRejectsPostgresFileReadFunctionEvenOnAuthorizedTable() {
		assertDeniedFunction("select id, pg_read_file('/etc/passwd') from orders", "pg_read_file");
	}

	@Test
	void searchRejectsQueryToXmlTableWhitelistBypass() {
		assertDeniedFunction("select query_to_xml('select * from other_table', true, false, '')", "query_to_xml");
	}

	@Test
	void searchRejectsPostgresDirectoryListingTableFunction() {
		assertEquals("只读查询不允许使用表函数", searchFailureMessage("select 1 from pg_ls_dir('/') entries"));
	}

	@Test
	void searchRejectsMysqlLoadFileFunction() {
		assertDeniedFunction("select load_file('/etc/passwd') from orders", "load_file");
	}

	@Test
	void searchRejectsHiveReflectFunction() {
		assertDeniedFunction("select reflect('java.lang.Runtime', 'getRuntime') from orders", "reflect");
	}

	@Test
	void searchRejectsOraclePackageQualifiedXmlgenFunction() {
		assertDeniedFunction("select DBMS_XMLGEN.getxml('select * from other_table') from orders",
				"dbms_xmlgen.getxml");
	}

	@Test
	void searchExecutesReSerializedStatementForLegitimateCteJoinQuery() throws Exception {
		AtomicReference<String> executedSql = new AtomicReference<>();
		when(accessor.executeSqlAndReturnObject(any(DbConfigBO.class), any(DbQueryParameter.class)))
			.thenAnswer(invocation -> {
				executedSql.set(((DbQueryParameter) invocation.getArgument(1)).getSql());
				return ResultSetBO.builder()
					.column(List.of("id", "name"))
					.data(List.of(Map.of("id", "1", "name", "ACME")))
					.build();
			});

		DatasourceExplorerResult result = service.execute(
				searchRequest("with recent_orders as (select id, customer_name from orders) "
						+ "select r.id, c.name from recent_orders r join customers c on c.name = r.customer_name"),
				graphRequest(Map.of("orders", List.of("id", "customer_name"), "customers", List.of("id", "name"))));

		assertTrue(result.getUsedTables().containsAll(List.of("orders", "customers")),
				String.valueOf(result.getUsedTables()));
		assertEquals("ACME", result.getRows().get(0).get("name"));
		// 执行的必须是 jsqlparser 重新序列化后的语句（关键字被规范成大写），而不是调用方传入的原始小写文本。
		assertTrue(executedSql.get().contains("WITH recent_orders AS"), executedSql.get());
		assertTrue(executedSql.get().contains("dataagent_safe_limit"), executedSql.get());
	}

	@Test
	void deterministicSearchAllowsCteBackedByAuthorizedTable() throws Exception {
		DatasourceExplorerRequest request = searchRequest(
				"with visible_orders as (select id from orders) select id from visible_orders");
		request.setRequireAuthorizedBaseTable(true);

		DatasourceExplorerResult result = service.execute(request, graphRequest(defaultTables()));

		assertEquals(List.of("orders"), result.getUsedTables());
	}

	@Test
	void deterministicSearchInvokesStaticGuardPassedCallbackAfterStaticGuardAndTopNValidation() throws Exception {
		when(accessor.executeSqlAndReturnObject(any(DbConfigBO.class), any(DbQueryParameter.class)))
			.thenReturn(ResultSetBO.builder().column(List.of("id")).data(List.of(Map.of("id", "1"))).build());
		AtomicInteger callbackCalls = new AtomicInteger();
		DatasourceExplorerRequest request = searchRequest("select id from orders limit 10");
		request.setQuery("查询订单前10条");
		request.setRequireAuthorizedBaseTable(true);
		request.setStaticGuardPassedCallback(callbackCalls::incrementAndGet);

		service.execute(request, graphRequest(defaultTables()));

		assertEquals(1, callbackCalls.get());
	}

	@Test
	void deterministicSearchDoesNotInvokeStaticGuardPassedCallbackAfterStaticGuardRejection() {
		AtomicInteger callbackCalls = new AtomicInteger();
		DatasourceExplorerRequest request = searchRequest("select secret from orders");
		request.setRequireAuthorizedBaseTable(true);
		request.setStaticGuardPassedCallback(callbackCalls::incrementAndGet);

		assertThrows(DeterministicSqlGuardRejectedException.class,
				() -> service.execute(request, graphRequest(defaultTables())));

		assertEquals(0, callbackCalls.get());
	}

	@Test
	void deterministicSearchPropagatesStatementTimeout() throws Exception {
		when(accessor.executeSqlAndReturnObject(any(DbConfigBO.class), any(DbQueryParameter.class)))
			.thenAnswer(invocation -> {
				DbQueryParameter parameter = invocation.getArgument(1);
				assertEquals(6, parameter.getStatementTimeoutSeconds());
				assertEquals(true, parameter.isSuppressErrorDetails());
				return ResultSetBO.builder().column(List.of("count")).data(List.of(Map.of("count", "3"))).build();
			});
		DatasourceExplorerRequest request = searchRequest("select count(*) from orders");
		request.setRequireAuthorizedBaseTable(true);
		request.setStatementTimeoutSeconds(6);

		service.execute(request, graphRequest(defaultTables()));
	}

	@Test
	void deterministicSearchSkipsUnboundedPhysicalRelationMetadata() throws Exception {
		DatasourceExplorerRequest request = searchRequest("select count(*) from orders");
		request.setRequireAuthorizedBaseTable(true);
		request.setStatementTimeoutSeconds(6);
		request.setSkipPhysicalRelationMetadata(true);

		service.execute(request, graphRequest(defaultTables()));

		verify(accessor, never()).showForeignKeys(any(DbConfigBO.class), any(DbQueryParameter.class));
	}

	@Test
	void reactSearchLeavesStatementTimeoutUnset() throws Exception {
		when(accessor.executeSqlAndReturnObject(any(DbConfigBO.class), any(DbQueryParameter.class)))
			.thenAnswer(invocation -> {
				DbQueryParameter parameter = invocation.getArgument(1);
				assertNull(parameter.getStatementTimeoutSeconds());
				assertFalse(parameter.isSuppressErrorDetails());
				return ResultSetBO.builder().column(List.of("count")).data(List.of(Map.of("count", "3"))).build();
			});

		service.execute(searchRequest("select count(*) from orders"), graphRequest(defaultTables()));

		verify(accessor).showForeignKeys(any(DbConfigBO.class), any(DbQueryParameter.class));
	}

	@Test
	void searchPreservesSqlStateFromDatasourceFailure() throws Exception {
		SQLException datasourceFailure = new SQLException("column does not exist", "42703");
		when(accessor.executeSqlAndReturnObject(any(DbConfigBO.class), any(DbQueryParameter.class)))
			.thenThrow(datasourceFailure);

		SQLException failure = assertThrows(SQLException.class,
				() -> service.execute(searchRequest("select id from orders"), graphRequest(defaultTables())));

		assertSame(datasourceFailure, failure);
		assertEquals("42703", failure.getSQLState());
	}

	@Test
	void searchRewritesPostgresBooleanComparedWithIntegerBeforeJdbc() throws Exception {
		when(accessor.showColumns(any(DbConfigBO.class), any(DbQueryParameter.class)))
			.thenReturn(List.of(ColumnInfoBO.builder().name("deleted").type("boolean").build(),
					ColumnInfoBO.builder().name("customer_name").type("text").build()));
		AtomicReference<String> executedSql = captureExecutedSql(List.of("customer_name"),
				List.of(Map.of("customer_name", "ACME")));

		service.execute(searchRequest("select customer_name from orders where deleted = 0"),
				graphRequest(Map.of("orders", List.of("id", "customer_name", "deleted"))));

		String sql = executedSql.get();
		assertTrue(sql.toLowerCase(Locale.ROOT).contains("deleted = false"), sql);
		assertFalse(sql.contains("deleted = 0"), sql);
	}

	@Test
	void searchFallsBackToDeletedRewriteWhenColumnTypesMissing() throws Exception {
		when(accessor.showColumns(any(DbConfigBO.class), any(DbQueryParameter.class))).thenReturn(List.of());
		AtomicReference<String> executedSql = captureExecutedSql(List.of("id"), List.of(Map.of("id", "1")));

		service.execute(searchRequest("select id from orders where deleted = 0"),
				graphRequest(Map.of("orders", List.of("id", "deleted"))));

		assertTrue(executedSql.get().toLowerCase(Locale.ROOT).contains("deleted = false"), executedSql.get());
	}

	@Test
	void searchDoesNotRewriteNumericZeroComparisons() throws Exception {
		when(accessor.showColumns(any(DbConfigBO.class), any(DbQueryParameter.class)))
			.thenReturn(List.of(ColumnInfoBO.builder().name("amount").type("number").build()));
		AtomicReference<String> executedSql = captureExecutedSql(List.of("amount"), List.of(Map.of("amount", "0")));

		service.execute(searchRequest("select amount from orders where amount = 0"),
				graphRequest(Map.of("orders", List.of("id", "amount"))));

		assertTrue(executedSql.get().contains("amount = 0") || executedSql.get().contains("amount =0"),
				executedSql.get());
		assertFalse(executedSql.get().toLowerCase(Locale.ROOT).contains("amount = false"), executedSql.get());
	}

	@Test
	void searchDoesNotRewriteBooleanIntegerOnMysql() throws Exception {
		when(accessor.supportedDataSourceType("mysql")).thenReturn(true);
		when(datasourceService.getDbConfig(any(Datasource.class)))
			.thenReturn(DbConfigBO.builder().dialectType("MySQL").connectionType("jdbc").build());
		AtomicReference<String> executedSql = captureExecutedSql(List.of("id"), List.of(Map.of("id", "1")));

		service.execute(searchRequest("select id from orders where deleted = 0"),
				graphRequest(Map.of("orders", List.of("id", "deleted"))));

		assertTrue(executedSql.get().contains("deleted = 0"), executedSql.get());
		verify(accessor, never()).showColumns(any(DbConfigBO.class), any(DbQueryParameter.class));
	}

	@Test
	void searchMapsPostgresBooleanIntegerJdbcErrorWithoutLeakingSql() throws Exception {
		when(accessor.executeSqlAndReturnObject(any(DbConfigBO.class), any(DbQueryParameter.class)))
			.thenThrow(new SQLException("错误: 操作符不存在: boolean = integer", "42883"));

		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> service.execute(searchRequest("select customer_name from orders"), graphRequest(defaultTables())));

		assertEquals(PostgresBooleanLiteralNormalizer.MODEL_HINT, failure.getMessage());
		assertFalse(failure.getMessage().contains("orders"));
	}

	@Test
	void internalDeterministicControlsCannotBeSetThroughToolJson() throws Exception {
		DatasourceExplorerRequest request = new ObjectMapper().readValue("""
				{"action":"SEARCH","sql":"select 1","requireAuthorizedBaseTable":true,"statementTimeoutSeconds":1,"skipPhysicalRelationMetadata":true}
				""", DatasourceExplorerRequest.class);

		assertFalse(request.isRequireAuthorizedBaseTable());
		assertNull(request.getStatementTimeoutSeconds());
		assertFalse(request.isSkipPhysicalRelationMetadata());
	}

	@Test
	void searchRejectsColumnsOutsideTheSnapshotWhitelist() {
		assertThrows(IllegalArgumentException.class,
				() -> service.execute(searchRequest("select secret from orders"), graphRequest(defaultTables())));
	}

	@Test
	void searchRejectsEmptySnapshotColumnWhitelist() {
		assertThrows(IllegalArgumentException.class, () -> service.execute(searchRequest("select * from orders"),
				graphRequest(Map.of("orders", List.of()))));
	}

	@Test
	void previewRowsIsCappedBySkillSnapshotMaxRows() throws Exception {
		AtomicReference<String> executedSql = captureExecutedSql(List.of("id"), List.of(Map.of("id", "1")));

		service.execute(previewRequest("orders", REQUESTED_PREVIEW_LIMIT), graphRequest(defaultTables(), 5));

		assertTrue(executedSql.get().endsWith("LIMIT 5"), executedSql.get());
	}

	@Test
	void previewRowsIsCappedByPlatformMaxResultRows() throws Exception {
		dataAgentProperties.getRuntime().setMaxResultRows(7);
		AtomicReference<String> executedSql = captureExecutedSql(List.of("id"), List.of(Map.of("id", "1")));

		service.execute(previewRequest("orders", REQUESTED_PREVIEW_LIMIT), graphRequest(defaultTables(), 200));

		assertTrue(executedSql.get().endsWith("LIMIT 7"), executedSql.get());
	}

	@Test
	void previewRowsTruncatesOversizedCellValuesWithExplicitMarker() throws Exception {
		captureExecutedSql(List.of("customer_name"), List.of(Map.of("customer_name", "值".repeat(300))));

		DatasourceExplorerResult result = service.execute(previewRequest("orders", 10), graphRequest(defaultTables()));

		assertEquals("值".repeat(122) + "…（已截断）", result.getRows().get(0).get("customer_name"));
	}

	@Test
	void searchKeepsCellValuesWithinTheLimitIntact() throws Exception {
		String withinLimit = "值".repeat(128);
		captureExecutedSql(List.of("customer_name"), List.of(Map.of("customer_name", withinLimit)));

		DatasourceExplorerResult result = service.execute(searchRequest("select customer_name from orders"),
				graphRequest(defaultTables()));

		assertEquals(withinLimit, result.getRows().get(0).get("customer_name"));
	}

	@Test
	void emptySearchSuggestsReviewingFilterInsteadOfDataProfile() throws Exception {
		captureExecutedSql(List.of("id"), List.of());

		DatasourceExplorerResult result = service.execute(searchRequest("select id from orders"),
				graphRequest(defaultTables()));

		assertEquals(Boolean.TRUE, result.getEmptyResult());
		assertEquals("核对过滤条件是否用错列语义或值格式；链接键未命中时改用材料，不要编造；不要把 DATA_PROFILE 当默认下一步。",
				result.getSuggestedNextAction());
		assertFalse(result.getSuggestedNextAction().startsWith("检查过滤字段值"));
		assertFalse(result.getSummary().contains("不要继续探表"));
	}

	@Test
	void emptySearchOnSettlementDateAddsPeriodCopy() throws Exception {
		captureExecutedSql(List.of("settlement_date"), List.of());

		DatasourceExplorerResult result = service.execute(
				searchRequest("select settlement_date from bill_cost where settlement_date = '2026-09'"),
				graphRequest(Map.of("bill_cost", List.of("settlement_date", "deleted"))));

		assertEquals(Boolean.TRUE, result.getEmptyResult());
		assertTrue(result.getSuggestedNextAction().contains("不要把 DATA_PROFILE 当默认下一步"));
		assertTrue(result.getSuggestedNextAction().contains("属期是 text"));
		assertTrue(result.getSuggestedNextAction().contains("按月等值"));
		assertTrue(result.getSuggestedNextAction().contains("不要 DATA_PROFILE"));
	}

	@Test
	void nonEmptySearchKeepsStopExploringCopyAndNoSuggestedAction() throws Exception {
		captureExecutedSql(List.of("customer_name"), List.of(Map.of("customer_name", "ACME")));

		DatasourceExplorerResult result = service.execute(searchRequest("select customer_name from orders"),
				graphRequest(defaultTables()));

		assertNull(result.getEmptyResult());
		assertNull(result.getSuggestedNextAction());
		assertTrue(result.getSummary().contains("不要继续探表"));
	}

	@Test
	void listTablesReusesExplorerContextWithinSameRuntimeRequest() throws Exception {
		DatasourceExplorerRequest request = new DatasourceExplorerRequest();
		request.setAction(DatasourceExplorerAction.LIST_TABLES);
		AgentRequest graphRequest = graphRequest(defaultTables());
		graphRequest.setThreadId("thread-1");
		graphRequest.setRuntimeRequestId("run-1");

		service.execute(request, graphRequest);
		service.execute(request, graphRequest);

		verify(datasourceService, times(1)).requireDatasourceForTenant(DATASOURCE_ID, TENANT_ID);
	}

	private String searchFailureMessage(String sql) {
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
				() -> service.execute(searchRequest(sql), graphRequest(defaultTables())));
		return failure.getMessage();
	}

	private void assertDeniedFunction(String sql, String expectedNormalizedFunctionName) {
		String message = searchFailureMessage(sql);
		assertTrue(message.contains("高危函数"), message);
		assertTrue(message.toLowerCase(Locale.ROOT).contains(expectedNormalizedFunctionName), message);
	}

	private DatasourceExplorerRequest searchRequest(String sql) {
		DatasourceExplorerRequest request = new DatasourceExplorerRequest();
		request.setAction(DatasourceExplorerAction.SEARCH);
		request.setSql(sql);
		return request;
	}

	private DatasourceExplorerRequest previewRequest(String tableName, int limit) {
		DatasourceExplorerRequest request = new DatasourceExplorerRequest();
		request.setAction(DatasourceExplorerAction.PREVIEW_ROWS);
		request.setTableName(tableName);
		request.setLimit(limit);
		return request;
	}

	private AtomicReference<String> captureExecutedSql(List<String> columns, List<Map<String, String>> rows)
			throws Exception {
		AtomicReference<String> executedSql = new AtomicReference<>();
		when(accessor.executeSqlAndReturnObject(any(DbConfigBO.class), any(DbQueryParameter.class)))
			.thenAnswer(invocation -> {
				executedSql.set(((DbQueryParameter) invocation.getArgument(1)).getSql());
				return ResultSetBO.builder().column(columns).data(rows).build();
			});
		return executedSql;
	}

	private AgentRequest graphRequest(Map<String, List<String>> columnsByTable) {
		return graphRequest(columnsByTable, 200);
	}

	private AgentRequest graphRequest(Map<String, List<String>> columnsByTable, int snapshotMaxRows) {
		List<SkillVersionResources.TableScope> tables = columnsByTable.entrySet()
			.stream()
			.map(entry -> new SkillVersionResources.TableScope(entry.getKey(), entry.getValue()))
			.toList();
		SkillVersionResources resources = new SkillVersionResources(SKILL_ID, SKILL_VERSION_ID,
				new SkillVersionResources.DatasourceResource(DATASOURCE_ID, tables, true, snapshotMaxRows, Map.of(),
						Map.of()),
				List.of(), List.of(), Map.of());
		return AgentRequest.builder().agentId("1").tenantIdSnapshot(TENANT_ID).routedSkillId(SKILL_ID)
			.routedSkillVersionId(SKILL_VERSION_ID).routedSkillResources(resources).build();
	}

	private Map<String, List<String>> defaultTables() {
		return Map.of("orders", List.of("id", "customer_name"));
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
