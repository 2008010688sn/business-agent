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
package com.sn68.agent.dataagent.agentscope.tool.sqlguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.tool.datasource.DatasourceExplorerAction;
import com.sn68.agent.dataagent.agentscope.tool.datasource.DatasourceExplorerRequest;
import com.sn68.agent.dataagent.agentscope.tool.datasource.DatasourceExplorerResult;
import com.sn68.agent.dataagent.agentscope.tool.datasource.DatasourceExplorerService;
import com.sn68.agent.dataagent.agentscope.tool.datasource.DatasourceRuntimeContextCache;
import com.sn68.agent.dataagent.agentscope.tool.datasource.permission.DataAgentSqlPermissionRewriteService;
import com.sn68.agent.dataagent.bo.DbConfigBO;
import com.sn68.agent.dataagent.bo.schema.ResultSetBO;
import com.sn68.agent.dataagent.connector.accessor.Accessor;
import com.sn68.agent.dataagent.connector.accessor.AccessorFactory;
import com.sn68.agent.dataagent.entity.Datasource;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.service.datasource.DatasourceService;
import com.sn68.agent.dataagent.service.schema.SchemaService;
import com.sn68.agent.dataagent.skill.execution.SkillVersionResources;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 账单属期 SQL 模板契约：jsqlparser 解析、SEARCH 守卫、SQL_VERIFY、脏值字典序边界。
 */
class BillCostSettlementPeriodSqlContractTest {

	private static final long DATASOURCE_ID = 10L;

	private static final long SKILL_ID = 2L;

	private static final long SKILL_VERSION_ID = 3L;

	private static final String TENANT_ID = "tenant-1";

	static final String SEPTEMBER_PERIOD_SQL = """
			SELECT one_project_name, COUNT(*) AS bill_count, SUM(amount) AS total_amount
			FROM bill_cost
			WHERE deleted = false
			  AND is_show = '1'
			  AND (
			    settlement_date = '2026-09'
			    OR (
			      settlement_date LIKE '%--%'
			      AND split_part(settlement_date, '--', 1) < '2026-10-01'
			      AND split_part(settlement_date, '--', 2) >= '2026-09-01'
			      AND split_part(settlement_date, '--', 1) BETWEEN '2000-01-01' AND '2999-12-31'
			      AND split_part(settlement_date, '--', 2) BETWEEN '2000-01-01' AND '2999-12-31'
			    )
			  )
			GROUP BY one_project_name
			""";

	static final String SEPTEMBER_CALENDAR_SQL = """
			SELECT one_project_name, COUNT(*) AS bill_count, SUM(amount) AS total_amount
			FROM bill_cost
			WHERE deleted = false
			  AND settlement_date >= '2026-09-01'
			  AND settlement_date < '2026-10-01'
			GROUP BY one_project_name
			""";

	private static final List<String> FULL_WHITELIST = List.of("one_project_name", "amount", "settlement_date",
			"deleted", "is_show");

	private static final List<String> WHITELIST_WITHOUT_IS_SHOW = List.of("one_project_name", "amount",
			"settlement_date", "deleted");

	private final DatasourceService datasourceService = mock(DatasourceService.class);

	private final SchemaService schemaService = mock(SchemaService.class);

	private final Accessor accessor = mock(Accessor.class);

	private final DataAgentSqlPermissionRewriteService sqlPermissionRewriteService = mock(
			DataAgentSqlPermissionRewriteService.class);

	private DatasourceExplorerService explorerService;

	private final SqlVerifyExplainService sqlVerifyExplainService = new SqlVerifyExplainService(
			mock(DatasourceService.class), new AccessorFactory(List.of()), new DataAgentProperties());

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
		when(accessor.showForeignKeys(any(), any())).thenReturn(List.of());
		when(accessor.executeSqlAndReturnObject(any(), any())).thenReturn(ResultSetBO.builder()
			.column(List.of("one_project_name", "bill_count", "total_amount"))
			.data(List.of(Map.of("one_project_name", "绿循环", "bill_count", "2", "total_amount", "10")))
			.build());
		explorerService = new DatasourceExplorerService(datasourceService, schemaService,
				new AccessorFactory(List.of(accessor)), new ObjectMapper(), new AnswerTraceExplainStore(),
				sqlPermissionRewriteService, new DatasourceRuntimeContextCache(new MockEnvironment()),
				new DataAgentProperties(), null);
	}

	@Test
	void periodTemplateParsesAsSingleSelect() throws Exception {
		Statement statement = CCJSqlParserUtil.parse(SEPTEMBER_PERIOD_SQL);
		assertTrue(statement instanceof Select);
		assertEquals(statement.toString(), CCJSqlParserUtil.parse(statement.toString()).toString());
	}

	@Test
	void periodTemplatePassesReadonlyGuardWhenIsShowIsWhitelisted() throws Exception {
		DatasourceExplorerResult result = explorerService.execute(searchRequest(SEPTEMBER_PERIOD_SQL),
				graphRequest(FULL_WHITELIST));

		assertEquals(List.of("bill_cost"), result.getUsedTables());
		assertFalse(Boolean.TRUE.equals(result.getEmptyResult()));
	}

	@Test
	void periodTemplateIsRejectedWhenIsShowMissingFromWhitelist() {
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
				() -> explorerService.execute(searchRequest(SEPTEMBER_PERIOD_SQL),
						graphRequest(WHITELIST_WITHOUT_IS_SHOW)));

		assertTrue(failure.getMessage().contains("不被允许"), failure.getMessage());
		assertTrue(failure.getMessage().toLowerCase(Locale.ROOT).contains("is_show"), failure.getMessage());
	}

	@Test
	void sqlVerifyAlignsPeriodTemplateForThisMonthAndLastMonthQueries() {
		SqlGuardCheckResult thisMonth = explain("这个月各项目的账单情况", SEPTEMBER_PERIOD_SQL);
		assertTrue(thisMonth.getIsAligned(), thisMonth.getSummary());
		assertTrue(thisMonth.getRuleChecks().stream().noneMatch(rule -> "TIME_FILTER_REQUIRED".equals(rule.getCode())),
				"这个月不在 SQL_VERIFY 时间意图词表里，不会强制时间过滤");

		SqlGuardCheckResult lastMonth = explain("上个月各项目的账单情况", SEPTEMBER_PERIOD_SQL);
		assertTrue(lastMonth.getIsAligned(), lastMonth.getSummary());
		assertTrue(lastMonth.getRuleChecks().stream().noneMatch(rule -> "TIME_FILTER_REQUIRED".equals(rule.getCode())),
				"上个月中间有「个」，不能命中关键词「上月」");

		SqlGuardCheckResult canonicalMonth = explain("本月各项目的账单情况", SEPTEMBER_PERIOD_SQL);
		assertTrue(canonicalMonth.getIsAligned(), canonicalMonth.getSummary());
		assertTrue(canonicalMonth.getRuleChecks()
			.stream()
			.anyMatch(rule -> "TIME_FILTER_REQUIRED".equals(rule.getCode()) && "PASSED".equals(rule.getStatus())));
	}

	@Test
	void sqlVerifyAlsoAlignsWrongCalendarRangeOnSettlementDate() {
		SqlGuardCheckResult thisMonth = explain("这个月各项目的账单情况", SEPTEMBER_CALENDAR_SQL);
		assertTrue(thisMonth.getIsAligned(), thisMonth.getSummary());

		SqlGuardCheckResult thisCalendarMonth = explain("本月各项目的账单情况", SEPTEMBER_CALENDAR_SQL);
		assertTrue(thisCalendarMonth.getIsAligned(),
				"SQL_VERIFY 只认 settlement_date 含 date，不区分日历区间与 text 属期：" + thisCalendarMonth.getSummary());
		assertTrue(thisCalendarMonth.getRuleChecks()
			.stream()
			.anyMatch(rule -> "TIME_FILTER_REQUIRED".equals(rule.getCode()) && "PASSED".equals(rule.getStatus())));
	}

	@Test
	void lexicographicBetweenDropsPlusDirtyValueButKeepsInvalidCalendarDigits() {
		assertTrue(matchesSeptemberPeriod("2026-09"));
		assertTrue(matchesSeptemberPeriod("2026-08-19--2026-09-18"));
		assertFalse(matchesSeptemberPeriod("+57349-12-13--+57432-02-02"));
		assertFalse(matchesSeptemberPeriod("xxx"));
		assertFalse(matchesSeptemberPeriod("2026-09-01--oops"));
		assertTrue(inDateDomain("2026-13-45"),
				"BETWEEN '2000-01-01' AND '2999-12-31' 是字典序，不是日期合法性；2026-13-45 仍落在域内");
		assertFalse(inDateDomain("+57349-12-13"));
	}

	private SqlGuardCheckResult explain(String query, String sql) {
		SqlGuardCheckRequest request = new SqlGuardCheckRequest();
		request.setQuery(query);
		request.setSql(sql);
		return sqlVerifyExplainService.explain(request);
	}

	private DatasourceExplorerRequest searchRequest(String sql) {
		DatasourceExplorerRequest request = new DatasourceExplorerRequest();
		request.setAction(DatasourceExplorerAction.SEARCH);
		request.setSql(sql);
		return request;
	}

	private AgentRequest graphRequest(List<String> columns) {
		SkillVersionResources resources = new SkillVersionResources(SKILL_ID, SKILL_VERSION_ID,
				new SkillVersionResources.DatasourceResource(DATASOURCE_ID,
						List.of(new SkillVersionResources.TableScope("bill_cost", columns)), true, 200, Map.of(),
						Map.of()),
				List.of(), List.of(), Map.of());
		return AgentRequest.builder()
			.agentId("1")
			.tenantIdSnapshot(TENANT_ID)
			.routedSkillId(SKILL_ID)
			.routedSkillVersionId(SKILL_VERSION_ID)
			.routedSkillResources(resources)
			.build();
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

	/**
	 * 与模板谓词同构的 PostgreSQL text 字典序，不是 {@code ::date}。
	 */
	private static boolean matchesSeptemberPeriod(String settlementDate) {
		if ("2026-09".equals(settlementDate)) {
			return true;
		}
		int separator = settlementDate.indexOf("--");
		if (separator < 0) {
			return false;
		}
		String start = settlementDate.substring(0, separator);
		String end = settlementDate.substring(separator + 2);
		return start.compareTo("2026-10-01") < 0 && end.compareTo("2026-09-01") >= 0 && inDateDomain(start)
				&& inDateDomain(end);
	}

	private static boolean inDateDomain(String value) {
		return value.compareTo("2000-01-01") >= 0 && value.compareTo("2999-12-31") <= 0;
	}

}
