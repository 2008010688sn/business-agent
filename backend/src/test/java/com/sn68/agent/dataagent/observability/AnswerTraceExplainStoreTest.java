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
package com.sn68.agent.dataagent.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.tool.datasource.DatasourceExplorerResult;
import com.sn68.agent.dataagent.agentscope.tool.datasource.ResultCoverageStatus;
import com.sn68.agent.dataagent.agentscope.tool.semantic.SemanticModelSearchHit;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.ToolExecutionRecord;
import com.sn68.agent.dataagent.service.report.ReportDataSnapshot;
import com.sn68.agent.dataagent.service.report.SkillReportProfile;
import com.sn68.agent.dataagent.skill.SkillExecutionMode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnswerTraceExplainStoreTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void reportSnapshotKeepsAllSearchRowsAndCoverageMetadata() {
		AnswerTraceExplainStore store = new AnswerTraceExplainStore();
		AgentRequest request = AgentRequest.builder()
			.threadId("top-session")
			.runtimeRequestId("top-runtime")
			.agentId("1")
			.query("查询客户订单量前300名")
			.build();
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int index = 1; index <= 200; index++) {
			rows.add(Map.of("customer_name", "客户" + index, "order_count", 201 - index));
		}

		store.recordDatasourceResult(request, DatasourceExplorerResult.builder()
			.action("SEARCH")
			.searchReady(true)
			.columns(List.of(Map.of("name", "customer_name"), Map.of("name", "order_count")))
			.rows(rows)
			.requestedRows(300)
			.returnedRows(200)
			.appliedLimit(200)
			.hasMore(true)
			.coverageStatus(ResultCoverageStatus.PLATFORM_LIMITED)
			.build());

		ReportDataSnapshot snapshot = store.getExplain("top-session", "top-runtime")
			.orElseThrow()
			.getReportDataSnapshots()
			.get(0);
		assertEquals(200, snapshot.getRows().size());
		assertEquals(300, snapshot.getRequestedRows());
		assertEquals(ResultCoverageStatus.PLATFORM_LIMITED, snapshot.getCoverageStatus());
		assertTrue(snapshot.getRanking());
	}

	@Test
	void rankingSnapshotKeepsBoxUsageMetricDespiteBlankFirstCell() {
		AnswerTraceExplainStore store = new AnswerTraceExplainStore();
		AgentRequest request = AgentRequest.builder()
			.threadId("box-session")
			.runtimeRequestId("box-runtime")
			.agentId("5")
			.query("这个月用箱量top10客户排行")
			.build();
		List<Map<String, Object>> rows = new ArrayList<>();
		Map<String, Object> first = new LinkedHashMap<>();
		first.put("company_name", "肇庆焕发生物科技有限公司");
		first.put("use_box_count", "");
		rows.add(first);
		rows.add(Map.of("company_name", "API_TEST_11_24", "use_box_count", "304954"));
		rows.add(Map.of("company_name", "上海箱箱物流", "use_box_count", "1391"));

		store.recordDatasourceResult(request, DatasourceExplorerResult.builder()
			.action("SEARCH")
			.searchReady(true)
			.columns(List.of(Map.of("name", "company_name"), Map.of("name", "use_box_count")))
			.rows(rows)
			.requestedRows(10)
			.returnedRows(10)
			.coverageStatus(ResultCoverageStatus.FULL)
			.build());

		ReportDataSnapshot snapshot = store.getExplain("box-session", "box-runtime")
			.orElseThrow()
			.getReportDataSnapshots()
			.get(0);
		assertTrue(snapshot.getRanking());
		assertEquals("用箱量", snapshot.getTitle());
		assertTrue(snapshot.getColumns().contains("客户名称"));
		assertTrue(snapshot.getColumns().contains("用箱量"));
		assertFalse(snapshot.getColumns().contains("维度1"));
		assertFalse(snapshot.getColumns().contains("指标1"));
		assertEquals(304954, ((Number) snapshot.getRows().get(1).get("用箱量")).intValue());
	}

	@Test
	void reportSnapshotUsesBusinessNamesForBillProjectColumns() {
		AnswerTraceExplainStore store = new AnswerTraceExplainStore();
		AgentRequest request = AgentRequest.builder()
			.threadId("bill-session")
			.runtimeRequestId("bill-runtime")
			.agentId("4")
			.query("这个月各项目的账单情况")
			.build();
		store.recordDatasourceResult(request, DatasourceExplorerResult.builder()
			.action("SEARCH")
			.searchReady(true)
			.columns(List.of(Map.of("name", "one_project_id"), Map.of("name", "one_project_name"),
					Map.of("name", "two_project_id"), Map.of("name", "two_project_name"),
					Map.of("name", "bill_count"), Map.of("name", "total_amount"), Map.of("name", "total_fare"),
					Map.of("name", "total_service_fee"), Map.of("name", "total_maintenance_fee"),
					Map.of("name", "total_return_fare"), Map.of("name", "total_consumable_fare"),
					Map.of("name", "total_price")))
			.rows(List.of(billProjectRow()))
			.requestedRows(20)
			.returnedRows(1)
			.coverageStatus(ResultCoverageStatus.SOURCE_SHORT)
			.build());

		ReportDataSnapshot snapshot = store.getExplain("bill-session", "bill-runtime")
			.orElseThrow()
			.getReportDataSnapshots()
			.get(0);
		assertEquals("账单数、总金额", snapshot.getTitle());
		assertTrue(snapshot.getColumns().contains("一级项目"));
		assertTrue(snapshot.getColumns().contains("二级项目"));
		assertTrue(snapshot.getColumns().contains("总金额"));
		assertTrue(snapshot.getColumns().contains("服务费"));
		assertTrue(snapshot.getColumns().contains("维修基金"));
		assertTrue(snapshot.getColumns().contains("回箱运费"));
		assertFalse(snapshot.getColumns().contains("指标1"));
		assertFalse(snapshot.getColumns().contains("维度1"));
		assertFalse(snapshot.getColumns().contains("一级项目编号"));
		assertFalse(snapshot.getColumns().contains("二级项目编号"));
		assertFalse(snapshot.getColumns().stream().anyMatch(column -> column.contains("编号") || column.contains("id")));
		assertFalse(snapshot.getRows().get(0).containsKey("one_project_id"));
		assertEquals("测试一级项目", snapshot.getRows().get(0).get("一级项目"));
	}

	@Test
	void reportSnapshotNamesComputedMetricsFromSqlAndFillsEmptyNumeric() {
		AnswerTraceExplainStore store = new AnswerTraceExplainStore();
		AgentRequest request = AgentRequest.builder()
			.threadId("bill-avg-session")
			.runtimeRequestId("bill-avg-runtime")
			.agentId("4")
			.query("上个月各项目的账单情况")
			.build();
		Map<String, Object> first = new LinkedHashMap<>();
		first.put("one_project_name", "测试一级项目");
		first.put("two_project_name", "测试二级项目");
		first.put("bill_count", 62);
		first.put("total_amount", new BigDecimal("97000"));
		first.put("avg_amount", new BigDecimal("1564.51"));
		first.put("approved_amount", null);
		first.put("rejected_amount", "");
		store.recordDatasourceResult(request, DatasourceExplorerResult.builder()
			.action("SEARCH")
			.searchReady(true)
			.sql("""
					SELECT one_project_name, two_project_name, COUNT(*) AS bill_count,
					SUM(amount) AS total_amount, AVG(amount) AS avg_amount,
					SUM(CASE WHEN approval_status IN ('已审批') THEN amount END) AS approved_amount,
					SUM(CASE WHEN approval_status IN ('已拒绝') THEN amount END) AS rejected_amount
					FROM bill_cost GROUP BY one_project_name, two_project_name
					""")
			.columns(List.of(Map.of("name", "one_project_name"), Map.of("name", "two_project_name"),
					Map.of("name", "bill_count"), Map.of("name", "total_amount"), Map.of("name", "avg_amount"),
					Map.of("name", "approved_amount"), Map.of("name", "rejected_amount")))
			.rows(List.of(first))
			.requestedRows(20)
			.returnedRows(1)
			.coverageStatus(ResultCoverageStatus.SOURCE_SHORT)
			.build());

		ReportDataSnapshot snapshot = store.getExplain("bill-avg-session", "bill-avg-runtime")
			.orElseThrow()
			.getReportDataSnapshots()
			.get(0);
		assertEquals(List.of("一级项目", "二级项目", "账单数", "总金额", "平均金额", "已审批金额", "已拒绝金额"),
				snapshot.getColumns());
		assertEquals(0, ((Number) snapshot.getRows().get(0).get("已审批金额")).intValue());
		assertEquals(0, ((Number) snapshot.getRows().get(0).get("已拒绝金额")).intValue());
		assertFalse(snapshot.getColumns().contains("指标1"));
		assertFalse(snapshot.getColumns().contains("指标2"));
		assertFalse(snapshot.getColumns().contains("指标3"));
	}

	private Map<String, Object> billProjectRow() {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("one_project_id", 2038477980368474114L);
		row.put("one_project_name", "测试一级项目");
		row.put("two_project_id", 2035911100838264834L);
		row.put("two_project_name", "测试二级项目");
		row.put("bill_count", 55);
		row.put("total_amount", new BigDecimal("167650"));
		row.put("total_fare", new BigDecimal("2040"));
		row.put("total_service_fee", new BigDecimal("1020"));
		row.put("total_maintenance_fee", new BigDecimal("1020"));
		row.put("total_return_fare", new BigDecimal("1020"));
		row.put("total_consumable_fare", BigDecimal.ZERO);
		row.put("total_price", new BigDecimal("1020"));
		return row;
	}

	@Test
	void attachSnapshotsCompactsAndKeepsCoverageOnParent() {
		AnswerTraceExplainStore store = new AnswerTraceExplainStore();
		AgentRequest parent = AgentRequest.builder()
			.threadId("parent-session")
			.runtimeRequestId("parent-runtime")
			.agentId("1")
			.query("生成分析报告")
			.responseMode("report")
			.build();
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int index = 1; index <= 220; index++) {
			rows.add(Map.of("客户名称", "客户" + index, "订单数", 220 - index));
		}

		store.attachSnapshots(parent, List.of(ReportDataSnapshot.builder()
			.title("协作者结果")
			.columns(List.of("客户名称", "订单数"))
			.rows(rows)
			.totalRows(220)
			.coverageStatus(ResultCoverageStatus.PLATFORM_LIMITED)
			.ranking(true)
			.build()));

		ReportDataSnapshot snapshot = store.getExplain("parent-session", "parent-runtime")
			.orElseThrow()
			.getReportDataSnapshots()
			.get(0);
		assertEquals(200, snapshot.getRows().size());
		assertEquals(ResultCoverageStatus.PLATFORM_LIMITED, snapshot.getCoverageStatus());
		assertTrue(snapshot.getRanking());
		assertTrue(snapshot.getTruncated());
	}

	@Test
	void previewRowsDoesNotBecomeReportDataset() {
		AnswerTraceExplainStore store = new AnswerTraceExplainStore();
		AgentRequest request = AgentRequest.builder()
			.threadId("preview-session")
			.runtimeRequestId("preview-runtime")
			.agentId("1")
			.query("预览订单")
			.build();

		store.recordDatasourceResult(request, DatasourceExplorerResult.builder()
			.action("PREVIEW_ROWS")
			.searchReady(true)
			.columns(List.of(Map.of("name", "order_no")))
			.rows(List.of(Map.of("order_no", "D001")))
			.build());

		assertTrue(store.getExplain("preview-session", "preview-runtime")
			.orElseThrow()
			.getReportDataSnapshots()
			.isEmpty());
	}

	@Test
	void routedSkillReportProfileIsPersistedWithAnswerExplain() {
		AnswerTraceExplainStore store = new AnswerTraceExplainStore();
		SkillReportProfile profile = SkillReportProfile.builder()
			.metrics(List.of("订单量", "完成率"))
			.risks(List.of("履约异常"))
			.build();
		AgentRequest request = AgentRequest.builder()
			.threadId("skill-session")
			.runtimeRequestId("skill-runtime")
			.agentId("1")
			.query("分析订单")
			.routedSkillCode("order-analysis")
			.routedSkillVersionId(12L)
			.routedSkillReportProfile(profile)
			.build();

		store.recordDatasourceResult(request, DatasourceExplorerResult.builder()
			.action("SEARCH")
			.searchReady(true)
			.columns(List.of(Map.of("name", "order_count")))
			.rows(List.of(Map.of("order_count", 10)))
			.returnedRows(1)
			.coverageStatus(ResultCoverageStatus.SOURCE_SHORT)
			.build());

		AnswerTraceExplainStore.AnswerTraceExplainView explain = store
			.getExplain("skill-session", "skill-runtime")
			.orElseThrow();
		assertEquals("order-analysis", explain.getRoutedSkillCode());
		assertEquals(12L, explain.getRoutedSkillVersionId());
		assertEquals(List.of("订单量", "完成率"), explain.getSkillReportProfile().getMetrics());
	}

	@Test
	void deterministicTraceRedactsDatasourceInternalsButKeepsReportSnapshot() {
		AnswerTraceExplainStore store = new AnswerTraceExplainStore();
		AgentRequest request = AgentRequest.builder()
			.threadId("deterministic-session")
			.runtimeRequestId("deterministic-runtime")
			.agentId("1")
			.query("查询账单金额")
			.routedSkillExecutionMode(SkillExecutionMode.DETERMINISTIC)
			.build();
		store.recordSemanticSearch(request, "SELECT bill_amount FROM bill_cost", "命中账单字段",
				List.of(SemanticModelSearchHit.builder()
					.tableName("bill_cost")
					.columnName("bill_amount")
					.build()));
		store.recordDatasourceResult(request, DatasourceExplorerResult.builder()
			.datasource("finance-datasource")
			.action("SEARCH")
			.summary("已完成受控查询")
			.searchReady(true)
			.sql("SELECT bill_amount FROM bill_cost")
			.usedTables(List.of("bill_cost"))
			.usedColumns(List.of("bill_amount"))
			.relationEvidence(List.of(Map.of("sourceTable", "bill_cost")))
			.toolDecisionReasons(List.of("权限范围已应用"))
			.resultScopeDetails(List.of("权限过滤仅允许当前租户"))
			.resultScope("已返回 1 行")
			.decisionReason("SQL 执行前已按数据权限判定当前无数据权限")
			.columns(List.of(Map.of("name", "bill_amount")))
			.rows(List.of(Map.of("bill_amount", new BigDecimal("80"))))
			.requestedRows(1)
			.returnedRows(1)
			.appliedLimit(1)
			.coverageStatus(ResultCoverageStatus.FULL)
			.build());
		store.recordToolExecution(request,
				new ToolExecutionRecord("datasource.explorer", "success", 1000L, 1010L, 10L,
						"{\"sql\":\"SELECT bill_amount FROM bill_cost\"}", "{\"rows\":1}", null,
						null, "工具执行成功", "SELECT bill_amount FROM bill_cost"));

		AnswerTraceExplainStore.AnswerTraceExplainView explain = store
			.getExplain("deterministic-session", "deterministic-runtime")
			.orElseThrow();
		assertNull(explain.getDatasource());
		assertNull(explain.getSql());
		assertNull(explain.getDecisionReason());
		assertTrue(explain.getUsedTables().isEmpty());
		assertTrue(explain.getUsedColumns().isEmpty());
		assertTrue(explain.getRelationEvidence().isEmpty());
		assertTrue(explain.getToolDecisionReasons().isEmpty());
		assertTrue(explain.getResultScopeDetails().isEmpty());
		assertTrue(explain.getSemanticHits().isEmpty());
		assertEquals("已返回 1 行", explain.getResultScope());
		assertEquals(ResultCoverageStatus.FULL, explain.getReportDataSnapshots().get(0).getCoverageStatus());
		assertEquals(1, explain.getReportDataSnapshots().get(0).getRows().size());
		assertTrue(explain.getToolSteps().stream().allMatch(step -> step.getDetail() == null
				&& step.getDatasource() == null && step.getInputSummary() == null && step.getOutputSummary() == null
				&& step.getErrorMessage() == null));
	}

	@Test
	void reactTraceKeepsDatasourceExplainFields() {
		AnswerTraceExplainStore store = new AnswerTraceExplainStore();
		AgentRequest request = AgentRequest.builder()
			.threadId("react-session")
			.runtimeRequestId("react-runtime")
			.agentId("1")
			.query("查询账单金额")
			.routedSkillExecutionMode(SkillExecutionMode.REACT)
			.build();
		store.recordDatasourceResult(request, DatasourceExplorerResult.builder()
			.datasource("finance-datasource")
			.action("SEARCH")
			.summary("已完成查询")
			.searchReady(true)
			.sql("SELECT bill_amount FROM bill_cost")
			.usedTables(List.of("bill_cost"))
			.usedColumns(List.of("bill_amount"))
			.relationEvidence(List.of(Map.of("sourceTable", "bill_cost")))
			.toolDecisionReasons(List.of("权限范围已应用"))
			.resultScopeDetails(List.of("权限过滤仅允许当前租户"))
			.decisionReason("直接查询账单表")
			.build());

		AnswerTraceExplainStore.AnswerTraceExplainView explain = store.getExplain("react-session", "react-runtime")
			.orElseThrow();
		assertEquals("finance-datasource", explain.getDatasource());
		assertEquals("SELECT bill_amount FROM bill_cost", explain.getSql());
		assertEquals(List.of("bill_cost"), explain.getUsedTables());
		assertEquals(List.of("bill_amount"), explain.getUsedColumns());
		assertEquals(List.of("权限范围已应用"), explain.getToolDecisionReasons());
		assertEquals(List.of("权限过滤仅允许当前租户"), explain.getResultScopeDetails());
		assertEquals("直接查询账单表", explain.getDecisionReason());
		assertEquals("SELECT bill_amount FROM bill_cost", explain.getToolSteps().get(0).getDetail());
		assertEquals("finance-datasource", explain.getToolSteps().get(0).getDatasource());
	}

	@Test
	void recordDatasourceResultKeepsSafeBusinessColumnNamesForReportSnapshot() {
		AnswerTraceExplainStore store = new AnswerTraceExplainStore();
		AgentRequest request = AgentRequest.builder()
			.threadId("100")
			.runtimeRequestId("runtime-1")
			.agentId("1")
			.query("统计收箱和发箱")
			.build();
		store.openScope(request);

		store.recordDatasourceResult(DatasourceExplorerResult.builder()
			.action("SEARCH")
			.searchReady(true)
			.columns(List.of(Map.of("name", "收箱"), Map.of("name", "发箱")))
			.rows(List.of(Map.of("收箱", new BigDecimal("80"), "发箱", new BigDecimal("120"))))
			.build());

		AnswerTraceExplainStore.AnswerTraceExplainView explain = store.getExplain("100", "runtime-1").orElseThrow();
		assertEquals(List.of("收箱", "发箱"), explain.getReportDataSnapshots().get(0).getColumns());
		assertTrue(explain.getReportDataSnapshots().get(0).getRows().get(0).containsKey("收箱"));
		assertTrue(explain.getReportDataSnapshots().get(0).getRows().get(0).containsKey("发箱"));
		store.closeScope();
	}

	@Test
	void recordDatasourceResultMapsCommonSqlAliasesForReportSnapshot() {
		AnswerTraceExplainStore store = new AnswerTraceExplainStore();
		AgentRequest request = AgentRequest.builder()
			.threadId("100")
			.runtimeRequestId("runtime-2")
			.agentId("1")
			.query("统计发箱收箱和金额")
			.build();
		store.openScope(request);

		store.recordDatasourceResult(DatasourceExplorerResult.builder()
			.action("SEARCH")
			.searchReady(true)
			.columns(List.of(Map.of("name", "receive_count"), Map.of("name", "send_count"),
					Map.of("name", "total_amount")))
			.rows(List.of(Map.of("receive_count", new BigDecimal("80"), "send_count", new BigDecimal("120"),
					"total_amount", new BigDecimal("274"))))
			.build());

		AnswerTraceExplainStore.AnswerTraceExplainView explain = store.getExplain("100", "runtime-2").orElseThrow();
		assertEquals(List.of("收箱数", "发箱数", "总金额"), explain.getReportDataSnapshots().get(0).getColumns());
		assertTrue(explain.getReportDataSnapshots().get(0).getRows().get(0).containsKey("收箱数"));
		assertTrue(explain.getReportDataSnapshots().get(0).getRows().get(0).containsKey("发箱数"));
		assertTrue(explain.getReportDataSnapshots().get(0).getRows().get(0).containsKey("总金额"));
		assertEquals(AnswerTraceExplainStore.STEP_TYPE_EXPLAIN, explain.getToolSteps().get(0).getStepType());
		assertNull(explain.getToolSteps().get(0).getDurationMs());
		store.closeScope();
	}

	@Test
	void recordDatasourceResultMarksDeletedPreviewAsProbeAndDropsInternalColumns() {
		AnswerTraceExplainStore store = new AnswerTraceExplainStore();
		AgentRequest request = AgentRequest.builder()
			.threadId("100")
			.runtimeRequestId("runtime-deleted")
			.agentId("1")
			.query("上个月各项目的账单情况")
			.build();
		store.openScope(request);

		store.recordDatasourceResult(DatasourceExplorerResult.builder()
			.action("SEARCH")
			.searchReady(true)
			.sql("SELECT settlement_date, amount, one_project_name, status, deleted FROM bill_cost LIMIT 5")
			.columns(List.of(Map.of("name", "settlement_date"), Map.of("name", "amount"),
					Map.of("name", "one_project_name"), Map.of("name", "status"), Map.of("name", "deleted")))
			.rows(List.of(
					Map.of("settlement_date", "2026-03", "amount", 651, "one_project_name", "咖啡", "status", 0,
							"deleted", "f"),
					Map.of("settlement_date", "2026-04", "amount", 1926, "one_project_name", "咖啡", "status", 0,
							"deleted", "t")))
			.returnedRows(5)
			.build());

		ReportDataSnapshot snapshot = store.getExplain("100", "runtime-deleted").orElseThrow()
			.getReportDataSnapshots()
			.get(0);
		assertTrue(Boolean.TRUE.equals(snapshot.getProbe()));
		assertFalse(snapshot.getColumns().contains("分类"));
		assertFalse(snapshot.getColumns().contains("deleted"));
		assertFalse(snapshot.getColumns().contains("删除标识"));
		store.closeScope();
	}

	@Test
	void recordDatasourceResultUsesSemanticColumnCommentsForReportSnapshot() {
		AnswerTraceExplainStore store = new AnswerTraceExplainStore();
		AgentRequest request = AgentRequest.builder()
			.threadId("100")
			.runtimeRequestId("runtime-3")
			.agentId("1")
			.query("统计项目账单")
			.build();
		store.openScope(request);
		store.recordSemanticSearch("统计项目账单", "命中项目账单字段",
				List.of(SemanticModelSearchHit.builder()
					.tableName("bill_cost")
					.columnName("project_id")
					.columnComment("项目编号")
					.build(),
						SemanticModelSearchHit.builder()
							.tableName("bill_cost")
							.columnName("bill_amount")
							.businessName("账单金额")
							.build()));

		store.recordDatasourceResult(DatasourceExplorerResult.builder()
			.action("SEARCH")
			.searchReady(true)
			.columns(List.of(Map.of("name", "project_id"), Map.of("name", "bill_amount"), Map.of("name", "项目名称")))
			.rows(List.of(Map.of("project_id", "P001", "bill_amount", new BigDecimal("33700"), "项目名称", "测试项目")))
			.build());

		AnswerTraceExplainStore.AnswerTraceExplainView explain = store.getExplain("100", "runtime-3").orElseThrow();
		assertEquals(List.of("账单金额", "项目名称"), explain.getReportDataSnapshots().get(0).getColumns());
		assertFalse(explain.getReportDataSnapshots().get(0).getRows().get(0).containsKey("项目编号"));
		assertFalse(explain.getReportDataSnapshots().get(0).getRows().get(0).containsKey("project_id"));
		assertTrue(explain.getReportDataSnapshots().get(0).getRows().get(0).containsKey("账单金额"));
		assertTrue(explain.getReportDataSnapshots().get(0).getRows().get(0).containsKey("项目名称"));
		assertEquals("项目编号", explain.getSemanticHits().get(0).getColumnComment());
		store.closeScope();
	}

	@Test
	void recordToolExecutionAddsTimingStatusAndSequence() {
		AnswerTraceExplainStore store = new AnswerTraceExplainStore();
		AgentRequest request = AgentRequest.builder()
			.threadId("100")
			.runtimeRequestId("runtime-4")
			.agentId("1")
			.query("查询订单")
			.build();

		store.recordToolExecution(request,
				new ToolExecutionRecord("datasource.dev.search", "success", 1000L, 1200L, 200L,
						"{\"query\":\"订单\"}", "{\"rows\":1}", null, null, "工具执行成功，耗时 200ms",
						"{\"rows\":1}"));
		store.recordToolExecution(request,
				new ToolExecutionRecord("sql_guard.check", "failed", 1300L, 1310L, 10L, "{\"sql\":\"select\"}",
						null, "BAD_SQL", "SQL 不合法", "工具执行失败，耗时 10ms", "SQL 不合法"));

		AnswerTraceExplainStore.AnswerTraceExplainView explain = store.getExplain("100", "runtime-4").orElseThrow();
		assertEquals(2, explain.getToolSteps().size());
		assertEquals(1, explain.getToolSteps().get(0).getSequenceNo());
		assertEquals(AnswerTraceExplainStore.STEP_TYPE_EXECUTION, explain.getToolSteps().get(0).getStepType());
		assertEquals("success", explain.getToolSteps().get(0).getStatus());
		assertEquals(200L, explain.getToolSteps().get(0).getDurationMs());
		assertEquals("{\"rows\":1}", explain.getToolSteps().get(0).getOutputSummary());
		assertEquals(2, explain.getToolSteps().get(1).getSequenceNo());
		assertEquals(AnswerTraceExplainStore.STEP_TYPE_EXECUTION, explain.getToolSteps().get(1).getStepType());
		assertEquals("failed", explain.getToolSteps().get(1).getStatus());
		assertEquals("BAD_SQL", explain.getToolSteps().get(1).getErrorCode());
		assertEquals("SQL 不合法", explain.getToolSteps().get(1).getErrorMessage());
	}

	@Test
	void getMirrorSummaryBuildsSnapshotWithoutCallingLombokBuilder() {
		AnswerTraceExplainStore store = new AnswerTraceExplainStore();
		AgentRequest request = AgentRequest.builder()
			.threadId("100")
			.runtimeRequestId("runtime-mirror")
			.agentId("1")
			.query("query orders")
			.build();
		store.openScope(request);
		store.recordToolExecution(request,
				new ToolExecutionRecord("datasource.dev.search", "success", 1000L, 1010L, 10L, "{}", "{}", null,
						null, "success", "{}"));

		AnswerTraceExplainStore.ExplainMirrorSummary summary = store.getMirrorSummary("100", "runtime-mirror")
			.orElseThrow();

		assertEquals(1, summary.getToolStepCount());
		assertEquals(0, summary.getSemanticHitCount());
		assertEquals(0, summary.getKnowledgeHitCount());
		store.closeScope();
	}

	@Test
	void reservedToolExecutionSequenceKeepsStartOrderWhenFinishOrderDiffers() {
		AnswerTraceExplainStore store = new AnswerTraceExplainStore();
		AgentRequest request = AgentRequest.builder()
			.threadId("100")
			.runtimeRequestId("runtime-5")
			.agentId("1")
			.query("查询订单")
			.build();

		Integer firstSequenceNo = store.reserveToolExecutionSequenceNo(request);
		Integer secondSequenceNo = store.reserveToolExecutionSequenceNo(request);
		store.recordToolExecution(request, secondSequenceNo,
				new ToolExecutionRecord("second.tool", "success", 1100L, 1110L, 10L, "{}", "{}",
						null, null, "工具执行成功，耗时 10ms", "{}"));
		store.recordToolExecution(request, firstSequenceNo,
				new ToolExecutionRecord("first.tool", "success", 1000L, 1300L, 300L, "{}", "{}",
						null, null, "工具执行成功，耗时 300ms", "{}"));

		AnswerTraceExplainStore.AnswerTraceExplainView explain = store.getExplain("100", "runtime-5").orElseThrow();
		assertEquals("second.tool", explain.getToolSteps().get(0).getToolName());
		assertEquals(2, explain.getToolSteps().get(0).getSequenceNo());
		assertEquals("first.tool", explain.getToolSteps().get(1).getToolName());
		assertEquals(1, explain.getToolSteps().get(1).getSequenceNo());
	}

	@Test
	void legacyToolStepSnapshotWithoutStepTypeCanDeserialize() throws Exception {
		String snapshot = """
				{
				  "sessionId": "100",
				  "runtimeRequestId": "runtime-5",
				  "agentId": "1",
				  "toolSteps": [
				    {
				      "toolName": "legacy.tool",
				      "summary": "旧快照",
				      "durationMs": 20
				    }
				  ],
				  "updatedAt": 1000
				}
				""";

		AnswerTraceExplainStore.AnswerTraceExplainView explain = objectMapper.readValue(snapshot,
				AnswerTraceExplainStore.AnswerTraceExplainView.class);

		assertEquals("legacy.tool", explain.getToolSteps().get(0).getToolName());
		assertNull(explain.getToolSteps().get(0).getStepType());
		assertEquals(20L, explain.getToolSteps().get(0).getDurationMs());
	}

	@Test
	void persistedAnswerExplainSnapshotWithReportDataCanDeserialize() throws Exception {
		String snapshot = """
				{
				  "sessionId": "100",
				  "runtimeRequestId": "runtime-6",
				  "agentId": "1",
				  "datasource": "dev",
				  "sql": "select count(*) from demo",
				  "reportDataSnapshots": [
				    {
				      "title": "demo",
				      "columns": ["total"],
				      "rows": [{"total": 1}],
				      "totalRows": 1,
				      "truncated": false
				    }
				  ],
				  "toolSteps": [
				    {
				      "toolName": "datasource.dev.search",
				      "status": "success",
				      "durationMs": 20
				    }
				  ],
				  "updatedAt": 1000
				}
				""";

		AnswerTraceExplainStore.AnswerTraceExplainView explain = objectMapper.readValue(snapshot,
				AnswerTraceExplainStore.AnswerTraceExplainView.class);

		assertEquals("dev", explain.getDatasource());
		assertEquals("select count(*) from demo", explain.getSql());
		assertEquals(List.of("total"), explain.getReportDataSnapshots().get(0).getColumns());
		assertEquals(1, explain.getReportDataSnapshots().get(0).getRows().get(0).get("total"));
		assertEquals("datasource.dev.search", explain.getToolSteps().get(0).getToolName());
	}

}
