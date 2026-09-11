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
package com.sn68.agent.dataagent.service.report;

import com.sn68.agent.dataagent.agentscope.tool.datasource.ResultCoverageStatus;
import com.sn68.agent.dataagent.dto.chat.ChatReportGenerateReq;
import com.sn68.agent.dataagent.dto.chat.ChatReportGenerateResp;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.AnswerTraceExplainView;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.SemanticHitView;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.ToolStepView;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.service.llm.LlmService;
import com.sn68.agent.dataagent.service.security.DataAgentOutputSanitizer;
import com.sn68.agent.dataagent.util.ChatResponseUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalysisReportServiceTest {

	@Test
	void authoritativeTop10OverridesModelThreePointChartAndAddsFullTable() {
		AnalysisReportService service = service("LLM should not be used");
		String markdown = """
				# 分析报告
				## 核心结论
				客户订单量已完成排名。
				## 关键指标
				共返回排名结果。
				## 可视化图表
				客户订单量对比。
				## 业务解读
				头部客户订单量更高。
				## 风险提示
				关注集中风险。
				## 行动建议
				跟进重点客户。
				""";
		String modelChart = """
				[{"title":"模型缩减图","chartType":"bar","dimensionName":"客户名称","metricNames":["订单数"],"data":[{"name":"客户1","订单数":10},{"name":"客户2","订单数":9},{"name":"客户3","订单数":8}]}]
				""";

		String report = service.normalizeInlineReport(markdown, modelChart, authoritativeTopCustomers(10, 10,
				ResultCoverageStatus.FULL));

		assertTrue(report.contains("客户10"));
		assertTrue(report.contains("### 完整排名明细（10条）"));
		assertTrue(report.contains("| 10 | 客户10 |"));
		assertFalse(report.contains("模型缩减图"));
		assertFalse(report.contains("其他"));
	}

	@Test
	void authoritativeRankingOverTwelveKeepsAllPointsAndAddsDataZoom() {
		AnalysisReportService service = service("""
				# 分析报告
				## 核心结论
				完成。
				## 关键指标
				完成。
				## 可视化图表
				完成。
				## 业务解读
				完成。
				## 风险提示
				完成。
				## 行动建议
				完成。
				""");

		String report = service.generateReport(authoritativeTopCustomers(20, 20, ResultCoverageStatus.FULL));

		assertTrue(report.contains("客户20"));
		assertTrue(report.contains("\"dataZoom\""));
		assertFalse(report.contains("\"其他\""));
	}

	@Test
	void publicResultSetDropsMarkdownTablesAndRankingSection() {
		AnalysisReportService service = service("LLM should not be used");
		String answer = service.ensureCompleteTopNAnswer("""
				2026年8月用箱量Top 10客户排行如下（实际有9个客户产生用箱量）：

				| 排名 | 客户名称 | 订单数 |
				| ---: | --- | --- |
				| 1 | 客户1 | 10 |
				| 2 | 客户2 | 9 |

				### 完整排名明细（10条）

				已完整展示本次要求的 10 条排名结果。

				| 排名 | 客户名称 | 订单数 |
				| ---: | --- | --- |
				| 1 | 客户1 | 10 |
				""", authoritativeTopCustomers(10, 10, ResultCoverageStatus.FULL));

		assertEquals("2026年8月用箱量Top 10客户排行如下（实际有9个客户产生用箱量）：", answer);
		assertFalse(answer.contains("### 完整排名明细"));
		assertFalse(answer.contains("| 客户名称 |"));
		assertFalse(answer.contains("LLM should not be used"));
	}

	@Test
	void rankingFallbackTableWhenPublicResultSetNotEmitted() {
		AnalysisReportService service = service("LLM should not be used");

		String answer = service.ensureCompleteTopNAnswer("模型只概括了客户1。",
				authoritativeTopCustomers(1, 1, ResultCoverageStatus.FULL));

		assertTrue(answer.contains("客户1"));
		assertTrue(answer.contains("### 完整排名明细（1条）"));
		assertFalse(answer.contains("LLM should not be used"));
	}

	@Test
	void platformLimitedRankingStatesCoverageWithoutClaimingSourceOnlyHasTwoHundredRows() {
		AnalysisReportService service = service("LLM should not be used");

		String answer = service.ensureCompleteTopNAnswer("已完成排名。",
				authoritativeTopCustomers(1, 300, ResultCoverageStatus.PLATFORM_LIMITED));

		assertTrue(answer.contains("受平台单次查询上限限制，本次覆盖前 1 条"));
		assertFalse(answer.contains("实际数据仅有 1 条"));
	}

	@Test
	void searchRowsAtLeastTwoBecomePublicResultSetEvenWhenRankingFalse() throws Exception {
		AnalysisReportService service = service("LLM should not be used");
		AnswerTraceExplainView view = explain();
		view.setQuestion("这个月用箱量top10客户排行");
		Map<String, Object> first = new LinkedHashMap<>();
		first.put("客户名称", "肇庆焕发生物科技有限公司");
		first.put("维度1", "");
		view.setReportDataSnapshots(List.of(ReportDataSnapshot.builder()
			.title("本次分析结果")
			.columns(List.of("客户名称", "维度1"))
			.rows(List.of(first, Map.of("客户名称", "API_TEST_11_24", "维度1", 304954)))
			.totalRows(2)
			.coverageStatus(ResultCoverageStatus.FULL)
			.ranking(false)
			.build()));

		String json = service.buildPublicResultSetJson(view);
		JsonNode root = new ObjectMapper().readTree(json).path("resultSet");
		assertEquals("客户名称", root.path("column").get(0).asText());
		assertEquals(2, root.path("data").size());
		assertEquals("API_TEST_11_24", root.path("data").get(1).path("客户名称").asText());
	}

	@Test
	void singleRowSnapshotDoesNotBecomePublicResultSet() {
		AnalysisReportService service = service("LLM should not be used");
		assertEquals("", service.buildPublicResultSetJson(explainWithSingleRowMetricSnapshot()));
	}

	@Test
	void sharedDimensionSnapshotsMergeIntoOnePublicResultSet() throws Exception {
		AnalysisReportService service = service("LLM should not be used");
		AnswerTraceExplainView view = explain();
		view.setReportDataSnapshots(List.of(
				ReportDataSnapshot.builder()
					.title("订单")
					.columns(List.of("客户名称", "用箱量"))
					.rows(List.of(Map.of("客户名称", "客户A", "用箱量", 500), Map.of("客户名称", "客户B", "用箱量", 400)))
					.totalRows(2)
					.coverageStatus(ResultCoverageStatus.FULL)
					.build(),
				ReportDataSnapshot.builder()
					.title("账单")
					.columns(List.of("客户名称", "总金额"))
					.rows(List.of(Map.of("客户名称", "客户A", "总金额", 12), Map.of("客户名称", "客户B", "总金额", 8)))
					.totalRows(2)
					.coverageStatus(ResultCoverageStatus.FULL)
					.build()));

		List<String> jsons = service.buildPublicResultSetJsons(view);
		assertEquals(1, jsons.size());
		JsonNode root = new ObjectMapper().readTree(jsons.get(0)).path("resultSet");
		assertEquals("客户名称", root.path("column").get(0).asText());
		assertTrue(root.path("column").toString().contains("用箱量"));
		assertTrue(root.path("column").toString().contains("总金额"));
		assertEquals(2, root.path("data").size());
		assertEquals(500, root.path("data").get(0).path("用箱量").asInt());
		assertEquals(12, root.path("data").get(0).path("总金额").asInt());
	}

	@Test
	void snapshotsWithoutSharedDimensionStaySeparate() {
		AnalysisReportService service = service("LLM should not be used");
		AnswerTraceExplainView view = explain();
		view.setReportDataSnapshots(List.of(
				ReportDataSnapshot.builder()
					.title("订单")
					.columns(List.of("客户名称", "用箱量"))
					.rows(List.of(Map.of("客户名称", "客户A", "用箱量", 500), Map.of("客户名称", "客户B", "用箱量", 400)))
					.totalRows(2)
					.coverageStatus(ResultCoverageStatus.FULL)
					.build(),
				ReportDataSnapshot.builder()
					.title("网点")
					.columns(List.of("网点名称", "调度数"))
					.rows(List.of(Map.of("网点名称", "网点A", "调度数", 3), Map.of("网点名称", "网点B", "调度数", 2)))
					.totalRows(2)
					.coverageStatus(ResultCoverageStatus.FULL)
					.build()));

		List<String> jsons = service.buildPublicResultSetJsons(view);
		assertEquals(2, jsons.size());
		assertTrue(jsons.get(0).contains("用箱量"));
		assertTrue(jsons.get(1).contains("网点名称"));
	}

	@Test
	void overlappingNameValuesMergeEvenWhenHeadersDiffer() throws Exception {
		AnalysisReportService service = service("LLM should not be used");
		AnswerTraceExplainView view = explain();
		view.setReportDataSnapshots(List.of(
				ReportDataSnapshot.builder()
					.title("订单数")
					.columns(List.of("客户名称", "订单数"))
					.rows(List.of(
							Map.of("客户名称", "API_TEST_11_24", "订单数", 280),
							Map.of("客户名称", "上海箱箱物流", "订单数", 37),
							Map.of("客户名称", "太阳食品（天津）有限公司8111", "订单数", 23),
							Map.of("客户名称", "厦门欣欣带鱼有限公司", "订单数", 2),
							Map.of("客户名称", "上海箱箱物流科技有限公司", "订单数", 1),
							Map.of("客户名称", "威海新雅办公用品有限公司2222", "订单数", 1),
							Map.of("客户名称", "云南万绿生物股份有限公司", "订单数", 1)))
					.totalRows(7)
					.ranking(true)
					.coverageStatus(ResultCoverageStatus.SOURCE_SHORT)
					.build(),
				ReportDataSnapshot.builder()
					.title("总金额")
					.columns(List.of("企业名称", "总金额"))
					.rows(List.of(
							Map.of("企业名称", "API_TEST_11_24", "总金额", 543505),
							Map.of("企业名称", "云南万绿生物股份有限公司", "总金额", 502017),
							Map.of("企业名称", "厦门欣欣带鱼有限公司", "总金额", 9956),
							Map.of("企业名称", "太阳食品（天津）有限公司8111", "总金额", 1326)))
					.totalRows(4)
					.coverageStatus(ResultCoverageStatus.SOURCE_SHORT)
					.build()));

		List<String> jsons = service.buildPublicResultSetJsons(view);
		assertEquals(1, jsons.size());
		JsonNode payload = new ObjectMapper().readTree(jsons.get(0));
		assertEquals("本次分析结果", payload.path("title").asText());
		JsonNode root = payload.path("resultSet");
		assertEquals("客户名称", root.path("column").get(0).asText());
		assertTrue(root.path("column").toString().contains("订单数"));
		assertTrue(root.path("column").toString().contains("总金额"));
		assertFalse(root.path("column").toString().contains("企业名称"));
		assertEquals(7, root.path("data").size());
		assertEquals(280, root.path("data").get(0).path("订单数").asInt());
		assertEquals(543505, root.path("data").get(0).path("总金额").asInt());
		assertEquals(23, root.path("data").get(2).path("订单数").asInt());
		assertEquals(1326, root.path("data").get(2).path("总金额").asInt());
		assertEquals("", root.path("data").get(1).path("总金额").asText());
	}

	@Test
	void insufficientNameValueOverlapStaysSeparate() {
		AnalysisReportService service = service("LLM should not be used");
		AnswerTraceExplainView view = explain();
		view.setReportDataSnapshots(List.of(
				ReportDataSnapshot.builder()
					.title("订单数")
					.columns(List.of("客户名称", "订单数"))
					.rows(List.of(Map.of("客户名称", "客户A", "订单数", 10), Map.of("客户名称", "客户B", "订单数", 9),
							Map.of("客户名称", "客户C", "订单数", 8), Map.of("客户名称", "客户D", "订单数", 7)))
					.totalRows(4)
					.coverageStatus(ResultCoverageStatus.FULL)
					.build(),
				ReportDataSnapshot.builder()
					.title("总金额")
					.columns(List.of("企业名称", "总金额"))
					.rows(List.of(Map.of("企业名称", "客户A", "总金额", 12), Map.of("企业名称", "其他公司", "总金额", 8),
							Map.of("企业名称", "另一家", "总金额", 5), Map.of("企业名称", "第四家", "总金额", 3)))
					.totalRows(4)
					.coverageStatus(ResultCoverageStatus.FULL)
					.build()));

		List<String> jsons = service.buildPublicResultSetJsons(view);
		assertEquals(2, jsons.size());
		assertTrue(jsons.get(0).contains("订单数"));
		assertTrue(jsons.get(1).contains("企业名称"));
	}

	@Test
	void textChannelAnswerListsMergedSnapshotRowsWithoutMarkdownTable() {
		AnalysisReportService service = service("LLM should not be used");
		AnswerTraceExplainView view = explain();
		view.setReportDataSnapshots(List.of(
				ReportDataSnapshot.builder()
					.title("订单数")
					.columns(List.of("客户名称", "订单数"))
					.rows(List.of(
							Map.of("客户名称", "API_TEST_11_24", "订单数", 281),
							Map.of("客户名称", "上海箱箱物流", "订单数", 37),
							Map.of("客户名称", "太阳食品（天津）有限公司8111", "订单数", 23),
							Map.of("客户名称", "厦门欣欣带鱼有限公司", "订单数", 2),
							Map.of("客户名称", "威海新雅办公用品有限公司2222", "订单数", 1),
							Map.of("客户名称", "云南万绿生物股份有限公司", "订单数", 1),
							Map.of("客户名称", "上海箱箱物流科技有限公司", "订单数", 1)))
					.totalRows(7)
					.ranking(true)
					.coverageStatus(ResultCoverageStatus.SOURCE_SHORT)
					.build(),
				ReportDataSnapshot.builder()
					.title("总金额")
					.columns(List.of("企业名称", "总金额"))
					.rows(List.of(
							Map.of("企业名称", "API_TEST_11_24", "总金额", 543505),
							Map.of("企业名称", "云南万绿生物股份有限公司", "总金额", 502017),
							Map.of("企业名称", "厦门欣欣带鱼有限公司", "总金额", 9956),
							Map.of("企业名称", "太阳食品（天津）有限公司8111", "总金额", 1326)))
					.totalRows(4)
					.coverageStatus(ResultCoverageStatus.SOURCE_SHORT)
					.build()));

		String answer = service.completeTextChannelAnswer(
				"本月客户下单量Top10如下。厦门欣欣带鱼有限公司应收金额约为0.10万元。", view);

		assertTrue(answer.contains("API_TEST_11_24"));
		assertTrue(answer.contains("太阳食品（天津）有限公司8111：订单数 23，总金额 1326"));
		assertTrue(answer.contains("厦门欣欣带鱼有限公司：订单数 2，总金额 9956"));
		assertTrue(answer.contains("上海箱箱物流：订单数 37，总金额 暂无"));
		assertFalse(answer.contains("| 客户名称 |"));
		assertFalse(answer.contains("company_name"));
		assertFalse(answer.contains("total_amount"));
		assertTrue(answer.startsWith("本月客户下单量Top10如下。"));
	}

	@Test
	void probeSnapshotIsNotPublishedAndDifferentGrainsDoNotMerge() throws Exception {
		AnalysisReportService service = service("LLM should not be used");
		AnswerTraceExplainView view = explain();
		view.setReportDataSnapshots(List.of(
				ReportDataSnapshot.builder()
					.title("探路预览")
					.columns(List.of("结算周期", "账单总金额(元)", "一级项目名称", "状态", "分类"))
					.rows(List.of(
							Map.of("结算周期", "2026-03", "账单总金额(元)", 651, "一级项目名称", "咖啡", "状态", 0, "分类", "f"),
							Map.of("结算周期", "2026-04", "账单总金额(元)", 1926, "一级项目名称", "咖啡", "状态", 0, "分类", "t")))
					.totalRows(2)
					.probe(true)
					.build(),
				ReportDataSnapshot.builder()
					.title("2026年8月各项目账单")
					.columns(List.of("一级项目名称", "二级项目名称", "总金额", "账单数"))
					.rows(List.of(
							Map.of("一级项目名称", "测试一级项目", "二级项目名称", "测试二级项目", "总金额", 97000, "账单数", 62),
							Map.of("一级项目名称", "云南万绿", "二级项目名称", "云南万绿", "总金额", 4957, "账单数", 3)))
					.totalRows(2)
					.probe(false)
					.build()));

		List<String> jsons = service.buildPublicResultSetJsons(view);
		assertEquals(1, jsons.size());
		JsonNode root = new ObjectMapper().readTree(jsons.get(0));
		assertEquals("2026年8月各项目账单", root.path("title").asText());
		assertEquals("一级项目名称", root.path("resultSet").path("column").get(0).asText());
		assertTrue(root.path("resultSet").path("column").toString().contains("账单数"));
		assertFalse(root.toString().contains("分类"));
		assertFalse(root.toString().contains("状态"));
	}

	@Test
	void reportModelFailureReturnsDeterministicReportWithBackendData() {
		DataAgentProperties properties = new DataAgentProperties();
		AnalysisReportService service = new AnalysisReportService(new LlmService() {
			@Override
			public Flux<ChatResponse> call(String system, String user) {
				return Flux.error(new IllegalStateException("model unavailable"));
			}

			@Override
			public Flux<ChatResponse> callSystem(String system) {
				return callUser(system);
			}

			@Override
			public Flux<ChatResponse> callUser(String user) {
				return call("", user);
			}
		}, new ChartCandidateBuilder(properties), new ObjectMapper(), sanitizer(), properties, null);

		String report = service.generateReport(authoritativeTopCustomers(10, 10, ResultCoverageStatus.FULL));

		assertTrue(report.contains("# 分析报告"));
		assertTrue(report.contains("客户10"));
		assertTrue(report.contains("```echarts"));
	}

	@Test
	void skillReportProfileIsAddedToPromptWithoutChangingFixedSections() {
		StringBuilder prompt = new StringBuilder();
		AnalysisReportService service = promptCapturingService(prompt);
		AnswerTraceExplainView explain = authoritativeTopCustomers(3, 3, ResultCoverageStatus.FULL);
		explain.setRoutedSkillCode("order-analysis");
		explain.setRoutedSkillVersionId(12L);
		explain.setSkillReportProfile(SkillReportProfile.builder()
			.metrics(List.of("订单量", "完成率"))
			.risks(List.of("超期未完成"))
			.build());

		String report = service.generateReport(explain);

		assertTrue(prompt.toString().contains("关注指标：订单量；完成率"));
		assertTrue(prompt.toString().contains("重点风险：超期未完成"));
		assertTrue(report.contains("## 核心结论"));
		assertTrue(report.contains("## 行动建议"));
	}

	@Test
	void generateReportSanitizesInternalSqlTablesAndColumns() {
		AnalysisReportService service = service("""
				# 分析报告
				## 核心结论
				SELECT amount FROM bill_cost WHERE deleted = false，bill_cost.amount 异常。
				## 关键指标
				使用字段 amount。
				## 业务解读
				来自数据源 internal_ds。
				## 风险提示
				关注波动。
				## 行动建议
				继续跟进。
				""");

		String report = service.generateReport(explain());

		assertTrue(report.contains("# 分析报告"));
		assertFalse(report.toLowerCase().contains("select"));
		assertFalse(report.contains("bill_cost"));
		assertFalse(report.contains("amount"));
		assertFalse(report.contains("internal_ds"));
		assertFalse(report.contains("字段名"));
		assertFalse(report.contains("数据源"));
	}

	@Test
	void generateFallbackReportSanitizesOriginalAnswer() {
		AnalysisReportService service = service("");

		String report = service.generateFallbackReport(explain());

		assertTrue(report.contains("# 分析报告"));
		assertFalse(report.toLowerCase().contains("select"));
		assertFalse(report.contains("bill_cost"));
		assertFalse(report.contains("amount"));
		assertFalse(report.contains("internal_ds"));
	}

	@Test
	void professionalNarrativeFailureFallsBackToStandardWithDegradedFlag() {
		DataAgentProperties properties = new DataAgentProperties();
		AnalysisReportService service = new AnalysisReportService(new LlmService() {
			@Override
			public Flux<ChatResponse> call(String system, String user) {
				return Flux.error(new IllegalStateException("model unavailable"));
			}

			@Override
			public Flux<ChatResponse> callSystem(String system) {
				return callUser(system);
			}

			@Override
			public Flux<ChatResponse> callUser(String user) {
				return call("", user);
			}
		}, new ChartCandidateBuilder(properties), new ObjectMapper(), sanitizer(), properties, null);

		ChatReportGenerateResp response = service
			.generateProfessionalReportResponse(authoritativeTopCustomers(3, 3, ResultCoverageStatus.FULL));

		assertEquals(ChatReportGenerateReq.REPORT_LEVEL_STANDARD, response.getReportLevel());
		assertTrue(Boolean.TRUE.equals(response.getDegraded()));
		assertTrue(response.getContent().contains("# 分析报告"));
	}

	@Test
	void professionalNarrativeRunsOnCallingThreadSoTenantContextIsKept() {
		DataAgentProperties properties = new DataAgentProperties();
		AtomicReference<Thread> worker = new AtomicReference<>();
		AnalysisReportModelService reportModelService = mock(AnalysisReportModelService.class);
		when(reportModelService.generate(any(), any())).thenAnswer(invocation -> {
			worker.set(Thread.currentThread());
			return """
					# 分析报告
					## 核心结论
					整体表现平稳。
					## 关键指标
					规模保持稳定。
					## 可视化图表
					图表由后端插入。
					## 业务解读
					结构分布均衡。
					## 风险提示
					暂无显著风险。
					## 行动建议
					持续跟踪。
					""";
		});
		AnalysisReportService service = new AnalysisReportService(llmService("unused"),
				new ChartCandidateBuilder(properties), new ObjectMapper(), sanitizer(), properties, reportModelService);

		ChatReportGenerateResp response = service.generateProfessionalReportResponse(explain());

		assertSame(Thread.currentThread(), worker.get());
		assertEquals(ChatReportGenerateReq.REPORT_LEVEL_PROFESSIONAL, response.getReportLevel());
		assertFalse(Boolean.TRUE.equals(response.getDegraded()));
	}

	@Test
	void professionalNarrativeSuccessKeepsProfessionalLevelWhenNumbersConsistent() {
		DataAgentProperties properties = new DataAgentProperties();
		AnalysisReportService service = new AnalysisReportService(llmService("""
				# 分析报告
				## 核心结论
				整体表现平稳。
				## 关键指标
				规模保持稳定。
				## 可视化图表
				图表由后端插入。
				## 业务解读
				结构分布均衡。
				## 风险提示
				暂无显著风险。
				## 行动建议
				持续跟踪。
				"""), new ChartCandidateBuilder(properties), new ObjectMapper(), sanitizer(), properties, null);

		ChatReportGenerateResp response = service.generateProfessionalReportResponse(explain());

		assertEquals(ChatReportGenerateReq.REPORT_LEVEL_PROFESSIONAL, response.getReportLevel());
		assertFalse(Boolean.TRUE.equals(response.getDegraded()));
		assertTrue(response.getContent().contains("整体表现平稳"));
	}

	@Test
	void generateDeterministicReportUsesBusinessNamesAndKeepsChartFencesParseable() {
		AnalysisReportService service = service("LLM should not be used");
		AnswerTraceExplainView explain = explain();
		explain.setQuestion("这个月各项目的账单情况");
		explain.setAnswer("""
				2026年8月各项目账单情况如下：

				| 一级项目 | 总金额 |
				|---|---|
				| 测试一级项目 | 167650 |
				| 太阳食品 | 2730 |

				账单总金额 176057.00 元。
				""");
		explain.setReportDataSnapshots(List.of(incompleteBillSnapshot(), completeBillSnapshot()));

		String report = service.generateDeterministicReport(explain);

		assertTrue(report.contains("测试一级项目"));
		assertTrue(report.contains("太阳食品"));
		assertTrue(report.contains("总金额"));
		assertTrue(report.contains("服务费"));
		assertFalse(report.contains("2038477980368474114"));
		assertFalse(report.contains("头部项"));
		assertFalse(report.contains("覆盖范围是否被截断"));
		assertFalse(report.contains("``````echarts"));
		int firstFence = report.indexOf("````echarts");
		assertTrue(firstFence >= 0);
		int jsonStart = report.indexOf("\n", firstFence) + 1;
		int jsonEnd = report.indexOf("````", jsonStart);
		String json = report.substring(jsonStart, jsonEnd).trim();
		assertDoesNotThrow(() -> new ObjectMapper().readTree(json));
		assertTrue(json.contains("奶茶"));
		assertTrue(json.contains("星巴克咖啡"));
		assertFalse(json.contains("2038477980368474114"));
		assertFalse(report.contains("编号"));
		int fenceCursor = 0;
		while (true) {
			int fenceStart = report.indexOf("````echarts", fenceCursor);
			if (fenceStart < 0) {
				break;
			}
			int blockJsonStart = report.indexOf("\n", fenceStart) + 1;
			int blockJsonEnd = report.indexOf("````", blockJsonStart);
			String blockJson = report.substring(blockJsonStart, blockJsonEnd).trim();
			assertDoesNotThrow(() -> new ObjectMapper().readTree(blockJson));
			fenceCursor = blockJsonEnd + 4;
		}
	}

	@Test
	void generateDeterministicReportDoesNotCallModel() {
		AtomicBoolean modelCalled = new AtomicBoolean(false);
		LlmService llmService = new LlmService() {
			@Override
			public Flux<ChatResponse> call(String system, String user) {
				modelCalled.set(true);
				return Flux.error(new AssertionError("deterministic report must not call model"));
			}

			@Override
			public Flux<ChatResponse> callSystem(String system) {
				return call(system, "");
			}

			@Override
			public Flux<ChatResponse> callUser(String user) {
				return call("", user);
			}
		};
		DataAgentProperties properties = new DataAgentProperties();
		AnalysisReportService service = new AnalysisReportService(llmService, new ChartCandidateBuilder(properties),
				new ObjectMapper(), sanitizer(), properties, null);

		String report = service.generateDeterministicReport(explainWithSnapshot());

		assertFalse(modelCalled.get());
		assertTrue(report.contains("# 分析报告"));
		assertTrue(report.contains("```echarts"));
		assertTrue(report.contains("合计="));
		assertFalse(report.contains("本报告基于本次已完成分析结果生成"));
		assertFalse(report.contains("建议围绕核心结论继续跟进异常项、趋势变化和责任归属"));
	}

	@Test
	void generateDeterministicReportKeepsRankingChartWhenLaterDetailSnapshotExists() {
		AnalysisReportService service = service("LLM should not be used");
		AnswerTraceExplainView explain = authoritativeTopCustomers(10, 10, ResultCoverageStatus.FULL);
		List<ReportDataSnapshot> snapshots = new ArrayList<>(explain.getReportDataSnapshots());
		snapshots.add(ReportDataSnapshot.builder()
			.title("客户详情")
			.columns(List.of("客户名称", "订单数"))
			.rows(List.of(Map.of("客户名称", "客户1", "订单数", new BigDecimal("10"))))
			.totalRows(1)
			.returnedRows(1)
			.coverageStatus(ResultCoverageStatus.FULL)
			.build());
		explain.setReportDataSnapshots(snapshots);

		String report = service.generateDeterministicReport(explain);

		assertTrue(report.contains("```echarts"));
		assertTrue(report.contains("客户10"));
		assertTrue(report.contains("### 完整排名明细（10条）"));
	}

	@Test
	void generateDeterministicReportChartsCompletionRate() {
		AnalysisReportService service = service("LLM should not be used");
		AnswerTraceExplainView explain = explain();
		explain.setReportDataSnapshots(List.of(ReportDataSnapshot.builder()
			.title("完成率对比")
			.columns(List.of("网点", "完成率"))
			.rows(List.of(Map.of("网点", "华东", "完成率", new BigDecimal("92.5")),
					Map.of("网点", "华北", "完成率", new BigDecimal("81.0")),
					Map.of("网点", "华南", "完成率", new BigDecimal("76.5"))))
			.totalRows(3)
			.coverageStatus(ResultCoverageStatus.FULL)
			.build()));

		String report = service.generateDeterministicReport(explain);

		assertTrue(report.contains("```echarts"));
		assertTrue(report.contains("完成率"));
		assertTrue(report.contains("92.5"));
	}

	@Test
	void generateDeterministicReportKeepsEchartsJsonWhenDimensionContainsFrom() {
		AnalysisReportService service = service("LLM should not be used");
		AnswerTraceExplainView explain = explain();
		explain.setReportDataSnapshots(List.of(ReportDataSnapshot.builder()
			.title("流向对比")
			.columns(List.of("流向", "数量"))
			.rows(List.of(Map.of("流向", "from", "数量", new BigDecimal("10")),
					Map.of("流向", "join", "数量", new BigDecimal("8"))))
			.totalRows(2)
			.coverageStatus(ResultCoverageStatus.FULL)
			.build()));

		String report = service.generateDeterministicReport(explain);

		assertTrue(report.contains("```echarts"));
		int start = report.indexOf("```echarts");
		int jsonStart = report.indexOf("\n", start) + 1;
		int jsonEnd = report.indexOf("```", jsonStart);
		String json = report.substring(jsonStart, jsonEnd).trim();
		assertTrue(json.contains("\"from\"") || json.contains("from"));
		assertDoesNotThrow(() -> new ObjectMapper().readTree(json));
	}

	@Test
	void generateDeterministicReportExplainsSkipReasonWithoutCannedInterpretation() {
		AnalysisReportService service = service("LLM should not be used");

		String report = service.generateDeterministicReport(explain());

		assertTrue(report.contains("本轮没有可确认的结构化结果"));
		assertFalse(report.contains("本报告基于本次已完成分析结果生成"));
		assertFalse(report.contains("建议围绕核心结论继续跟进异常项、趋势变化和责任归属"));
	}

	@Test
	void normalizeInlineReportDoesNotGuessChartWithoutVerifiedChartIntent() {
		AnalysisReportService service = service("LLM should not be used");

		String report = service.normalizeInlineReport("""
				# 分析报告
				## 核心结论
				SELECT amount FROM bill_cost WHERE deleted = false。
				## 关键指标
				amount 存在差异。
				## 可视化图表
				暂无。
				## 业务解读
				来自 internal_ds。
				## 风险提示
				关注异常。
				## 行动建议
				继续跟进。
				""", explainWithSnapshot());

		assertTrue(report.contains("# 分析报告"));
		assertFalse(report.contains("```echarts"));
		assertFalse(report.toLowerCase().contains("select"));
		assertFalse(report.contains("bill_cost"));
		assertFalse(report.contains("amount"));
		assertFalse(report.contains("internal_ds"));
		assertFalse(report.contains("LLM should not be used"));
	}

	@Test
	void normalizeInlineReportAddsChartWhenChartIntentIsSafeAndStructured() {
		AnalysisReportService service = service("LLM should not be used");

		String report = service.normalizeInlineReport("""
				# 分析报告
				## 核心结论
				账单产品费用已汇总。
				## 关键指标
				OF330绿箱金额 54，330-NM2-JD 金额 20。
				## 可视化图表
				按产品展示金额。
				## 业务解读
				费用来自本次明细汇总。
				## 风险提示
				关注费用构成。
				## 行动建议
				继续跟进。
				""", """
				[{"title":"产品金额对比","chartType":"bar","dimensionName":"产品名称","metricNames":["金额"],"data":[{"name":"OF330绿箱","金额":54},{"name":"330-NM2-JD","金额":20}]}]
				""", explainWithBillDetailSnapshot());

		assertTrue(report.contains("```echarts"));
		assertTrue(report.contains("OF330绿箱"));
		assertTrue(report.contains("330-NM2-JD"));
		assertTrue(report.contains("54"));
		assertTrue(report.contains("20"));
		assertFalse(report.contains("\"type\":\"line"));
	}

	@Test
	void normalizeInlineReportKeepsModelProvidedBusinessLabels() {
		AnalysisReportService service = service("LLM should not be used");

		String report = service.normalizeInlineReport("""
				# 分析报告
				## 核心结论
				账单产品费用已汇总。
				## 关键指标
				OF330绿箱金额 54，330-NM2-JD 金额 20。
				## 可视化图表
				本次分析包含两款产品的费用明细数据，适合使用柱状图展示各产品的费用对比情况。
				## 业务解读
				费用来自本次明细汇总。
				## 风险提示
				关注费用构成。
				## 行动建议
				继续跟进。
				""", """
				[{"title":"产品费用对比","chartType":"bar","dimensionName":"产品","metricNames":["费用"],"data":[{"name":"OF330 绿箱","费用":54},{"name":"330-NM2-JD","费用":20}]}]
				""", explainWithBillDetailSnapshot());

		assertTrue(report.contains("```echarts"));
		assertTrue(report.contains("OF330 绿箱"));
		assertTrue(report.contains("\"name\":\"费用\""));
		assertTrue(report.contains("54"));
		assertTrue(report.contains("20"));
	}

	@Test
	void normalizeInlineReportDoesNotRejectSafeChartIntentOnlyBecauseSnapshotDoesNotMatch() {
		AnalysisReportService service = service("LLM should not be used");

		String report = service.normalizeInlineReport("""
				# 分析报告
				## 核心结论
				账单产品费用已汇总。
				## 关键指标
				OF330绿箱金额 54，330-NM2-JD 金额 20。
				## 可视化图表
				按产品展示金额。
				## 业务解读
				费用来自本次明细汇总。
				## 风险提示
				关注费用构成。
				## 行动建议
				继续跟进。
				""", """
				[{"title":"产品金额对比","chartType":"bar","dimensionName":"产品名称","metricNames":["金额"],"data":[{"name":"OF330绿箱","金额":999},{"name":"330-NM2-JD","金额":20}]}]
				""", explainWithBillDetailSnapshot());

		assertTrue(report.contains("```echarts"));
		assertTrue(report.contains("999"));
	}

	@Test
	void normalizeInlineReportRemovesChartClaimWhenChartIntentContainsInternalToken() {
		AnalysisReportService service = service("LLM should not be used");

		String report = service.normalizeInlineReport("""
				# 分析报告
				## 核心结论
				账单产品费用已汇总。
				## 关键指标
				OF330绿箱金额 54，330-NM2-JD 金额 20。
				## 可视化图表
				本次分析包含两款产品的费用明细数据，适合使用柱状图展示各产品的费用对比情况。
				## 业务解读
				费用来自本次明细汇总。
				## 风险提示
				关注费用构成。
				## 行动建议
				继续跟进。
				""", """
				[{"title":"bill_cost 费用对比","chartType":"bar","dimensionName":"产品","metricNames":["费用"],"data":[{"name":"OF330绿箱","费用":999},{"name":"330-NM2-JD","费用":20}]}]
				""", explainWithBillDetailSnapshot());

		assertFalse(report.contains("```echarts"));
		assertFalse(report.contains("bill_cost"));
		assertFalse(report.contains("适合使用柱状图"));
		assertTrue(report.contains("本次未生成可渲染图表"));
	}

	@Test
	void generateReportAddsFallbackChartWhenCandidateExists() {
		AnalysisReportService service = service("""
				# 分析报告
				## 核心结论
				费用存在分类差异。
				## 关键指标
				指标1 有明显差异。
				## 可视化图表
				暂无。
				## 业务解读
				按分类对比查看。
				## 风险提示
				关注异常。
				## 行动建议
				继续跟进。
				""");

		String report = service.generateReport(explainWithSnapshot());

		assertTrue(report.contains("```echarts"));
		assertTrue(report.contains("\"series\""));
		assertTrue(report.contains("\"name\":\"统计值\""));
		assertFalse(report.contains("\"name\":\"指标1\""));
		assertFalse(report.contains("bill_cost"));
		assertFalse(report.contains("amount"));
	}

	@Test
	void generateReportDoesNotAddChartWhenNoCandidateExists() {
		AnalysisReportService service = service("""
				# 分析报告
				## 核心结论
				本次为解释型回答。
				## 关键指标
				暂无。
				## 可视化图表
				暂无。
				## 业务解读
				暂无。
				## 风险提示
				暂无。
				## 行动建议
				暂无。
				""");

		String report = service.generateReport(explain());

		assertFalse(report.contains("```echarts"));
		assertTrue(report.contains("## 可视化图表"));
	}

	@Test
	void generateReportReplacesInvalidChartWhenCandidateExists() {
		AnalysisReportService service = service("""
				# 分析报告
				## 核心结论
				费用存在分类差异。
				## 关键指标
				指标1 有明显差异。
				## 可视化图表
				```echarts
				{ invalid json }
				```
				## 业务解读
				按分类对比查看。
				## 风险提示
				关注异常。
				## 行动建议
				继续跟进。
				""");

		String report = service.generateReport(explainWithSnapshot());

		assertTrue(report.contains("```echarts"));
		assertFalse(report.contains("{ invalid json }"));
		assertTrue(report.contains("\"series\""));
	}

	@Test
	void generateReportReplacesChartThatDoesNotMatchCandidateData() {
		AnalysisReportService service = service("""
				# 分析报告
				## 核心结论
				费用存在分类差异。
				## 关键指标
				指标1 有明显差异。
				## 可视化图表
				```echarts
				{"xAxis":{"type":"category","data":["虚构分类"]},"yAxis":{"type":"value"},"series":[{"type":"bar","data":[999]}]}
				```
				## 业务解读
				按分类对比查看。
				## 风险提示
				关注异常。
				## 行动建议
				继续跟进。
				""");

		String report = service.generateReport(explainWithSnapshot());

		assertTrue(report.contains("A类"));
		assertTrue(report.contains("B类"));
		assertFalse(report.contains("虚构分类"));
		assertFalse(report.contains("999"));
	}

	@Test
	void generateReportBuildsChartFromExplicitAnswerMetricsWhenSnapshotMissing() {
		AnalysisReportService service = service("""
				# 分析报告
				## 核心结论
				今年客户需求下单呈现高度集中的特征。
				## 关键指标
				- **发箱**：4,523 单
				- **收箱**：869 单
				- **重箱**：295 单
				## 可视化图表
				暂无。
				## 业务解读
				发箱占据主导。
				## 风险提示
				关注高峰履约。
				## 行动建议
				优化调度。
				""");

		String report = service.generateReport(explain());

		assertTrue(report.contains("```echarts"));
		assertTrue(report.contains("发箱"));
		assertTrue(report.contains("4523"));
		assertFalse(report.contains("\"name\":\"指标1\""));
	}

	@Test
	void generateReportUsesBusinessMetricNamesForSingleRowMetricSnapshot() {
		AnalysisReportService service = service("""
				# 分析报告
				## 核心结论
				收箱和发箱均有统计结果。
				## 关键指标
				收箱 80，发箱 120。
				## 可视化图表
				暂无。
				## 业务解读
				按统计项对比。
				## 风险提示
				关注波动。
				## 行动建议
				继续跟进。
				""");

		String report = service.generateReport(explainWithSingleRowMetricSnapshot());

		assertTrue(report.contains("```echarts"));
		assertTrue(report.contains("收箱"));
		assertTrue(report.contains("发箱"));
		assertTrue(report.contains("80"));
		assertFalse(report.contains("\"name\":\"指标1\""));
	}

	@Test
	void generateReportDoesNotChartSingleDetailSnapshotWithGenericMetrics() {
		AnalysisReportService service = service("""
				# 分析报告
				## 核心结论
				已查询到账单详情。
				## 关键指标
				- 账单编号：D020260528-689
				- 客户名称：北京华北包装有限公司
				- 结算周期：2026-05
				- 账单总金额：274.00 元
				- 明细条目数：7 条
				## 可视化图表
				暂无。
				## 业务解读
				本次结果是单条账单明细，不适合做分类或趋势对比。
				## 风险提示
				关注审批状态。
				## 行动建议
				继续跟进。
				""");

		String report = service.generateReport(explainWithSingleDetailGenericSnapshot());

		assertFalse(report.contains("```echarts"));
		assertFalse(report.contains("\"name\":\"指标1\""));
		assertFalse(report.contains("\"指标1\""));
	}

	@Test
	void generateReportAddsGridContainLabelForBackendChart() {
		AnalysisReportService service = service("""
				# 分析报告
				## 核心结论
				费用存在分类差异。
				## 关键指标
				指标存在差异。
				## 可视化图表
				暂无。
				## 业务解读
				按分类对比查看。
				## 风险提示
				关注异常。
				## 行动建议
				继续跟进。
				""");

		String report = service.generateReport(explainWithSnapshot());

		assertTrue(report.contains("```echarts"));
		assertTrue(report.contains("\"grid\""));
		assertTrue(report.contains("\"containLabel\":true"));
	}

	@Test
	void generateReportRemovesNoChartClaimWhenBackendChartIsAdded() {
		AnalysisReportService service = service("""
				# 分析报告
				## 核心结论
				今年客户需求下单呈现高度集中的特征。
				## 关键指标
				- **发箱**：4,523 单
				- **收箱**：869 单
				- **重箱**：295 单
				## 可视化图表
				本期分析暂无可用图表候选数据，故不展示可视化图表。
				## 业务解读
				发箱占据主导。
				## 风险提示
				关注高峰履约。
				## 行动建议
				优化调度。
				""");

		String report = service.generateReport(explain());

		assertTrue(report.contains("```echarts"));
		assertFalse(report.contains("暂无可用图表候选"));
		assertFalse(report.contains("不展示可视化图表"));
	}

	@Test
	void generateReportBuildsChartWhenDimensionIsNumericCode() {
		AnalysisReportService service = service("""
				# 分析报告
				## 核心结论
				类型 11 和类型 12 有一定需求量。
				## 关键指标
				分类指标。
				## 可视化图表
				暂无。
				## 业务解读
				按类型对比。
				## 风险提示
				关注分类治理。
				## 行动建议
				完善需求字典。
				""");

		String report = service.generateReport(explainWithNumericDimensionSnapshot());

		assertTrue(report.contains("```echarts"));
		assertTrue(report.contains("\"11\""));
		assertTrue(report.contains("133"));
	}

	@Test
	void generateReportAggregatesBillDetailRowsInsteadOfRepeatedDateLineChart() {
		AnalysisReportService service = service("""
				# 分析报告
				## 核心结论
				账单包含多条产品明细。
				## 关键指标
				产品费用存在差异。
				## 可视化图表
				暂无。
				## 业务解读
				应按产品汇总查看。
				## 风险提示
				关注费用构成。
				## 行动建议
				继续跟进。
				""");

		String report = service.generateReport(explainWithBillDetailSnapshot());

		assertTrue(report.contains("```echarts"));
		assertTrue(report.contains("\"type\":\"bar"));
		assertFalse(report.contains("\"type\":\"line"));
		assertTrue(report.contains("OF330绿箱"));
		assertTrue(report.contains("330-NM2-JD"));
		assertTrue(report.contains("54"));
		assertTrue(report.contains("20"));
		assertTrue(report.contains("\"name\":\"金额\""));
		assertFalse(report.contains("2026-05-01"));
	}

	@Test
	void generateReportPromptAsksTextNotToRepeatChartData() {
		StringBuilder prompt = new StringBuilder();
		AnalysisReportService service = new AnalysisReportService(new LlmService() {
			@Override
			public Flux<ChatResponse> call(String system, String user) {
				prompt.append(user);
				return Flux.just(ChatResponseUtil.createPureResponse("""
						# 分析报告
						## 核心结论
						完成。
						## 关键指标
						完成。
						## 可视化图表
						完成。
						## 业务解读
						完成。
						## 风险提示
						完成。
						## 行动建议
						完成。
						"""));
			}

			@Override
			public Flux<ChatResponse> callSystem(String system) {
				return callUser(system);
			}

			@Override
			public Flux<ChatResponse> callUser(String user) {
				return call("", user);
			}
		}, new ChartCandidateBuilder(new DataAgentProperties()), new ObjectMapper(), sanitizer(),
				new DataAgentProperties(), null);

		service.generateReport(explainWithBillDetailSnapshot());

		assertTrue(prompt.toString().contains("图表用于承载适合可视化的明细、分类对比、占比或趋势"));
		assertTrue(prompt.toString().contains("如果某组数据已经适合由“可用图表候选”表达"));
	}

	@Test
	void generateReportPromptPrefersAndLimitsStructuredSnapshots() {
		StringBuilder prompt = new StringBuilder();
		AnalysisReportService service = new AnalysisReportService(new LlmService() {
			@Override
			public Flux<ChatResponse> call(String system, String user) {
				prompt.append(user);
				return Flux.just(ChatResponseUtil.createPureResponse("""
						# 分析报告
						## 核心结论
						完成。
						## 关键指标
						完成。
						## 可视化图表
						完成。
						## 业务解读
						完成。
						## 风险提示
						完成。
						## 行动建议
						完成。
						"""));
			}

			@Override
			public Flux<ChatResponse> callSystem(String system) {
				return callUser(system);
			}

			@Override
			public Flux<ChatResponse> callUser(String user) {
				return call("", user);
			}
		}, new ChartCandidateBuilder(new DataAgentProperties()), new ObjectMapper(), sanitizer(),
				new DataAgentProperties(), null);

		service.generateReport(explainWithManyCustomerSnapshots());

		String capturedPrompt = prompt.toString();
		assertTrue(capturedPrompt.contains("可用结构化结果摘要（优先参考）"));
		int structuredSectionIndex = capturedPrompt.indexOf("可用结构化结果摘要（优先参考）：");
		assertTrue(structuredSectionIndex >= 0);
		assertTrue(structuredSectionIndex < capturedPrompt.indexOf("已完成分析结果：", structuredSectionIndex));
		assertTrue(capturedPrompt.contains("优先以该摘要为准"));
		assertTrue(capturedPrompt.contains("客户50"));
		assertFalse(capturedPrompt.contains("客户51"));
		assertTrue(capturedPrompt.contains("第二快照客户"));
		assertFalse(capturedPrompt.contains("第三快照客户"));
		assertTrue(capturedPrompt.contains("必须列满 N 条"));
	}

	private AnswerTraceExplainView explain() {
		return AnswerTraceExplainView.builder()
			.sessionId("100")
			.runtimeRequestId("runtime-1")
			.question("请生成分析报告")
			.answer("SELECT amount FROM bill_cost WHERE deleted = false；bill_cost.amount 为 100。")
			.datasource("internal_ds")
			.sql("SELECT amount FROM bill_cost WHERE deleted = false")
			.usedTables(List.of("bill_cost"))
			.usedColumns(List.of("amount", "deleted"))
			.semanticHits(List.of(SemanticHitView.builder()
				.tableName("bill_cost")
				.columnName("amount")
				.businessName("费用金额")
				.businessDescription("费用金额")
				.build()))
			.toolSteps(List.of(ToolStepView.builder()
				.toolName("datasource.query")
				.summary("已得到费用金额结果")
				.detail("SELECT amount FROM bill_cost WHERE deleted = false")
				.datasource("internal_ds")
				.build()))
			.build();
	}

	private ReportDataSnapshot incompleteBillSnapshot() {
		return ReportDataSnapshot.builder()
			.title("本次分析结果")
			.columns(List.of("维度1", "维度2", "账单数", "总金额"))
			.rows(List.of(billRow(Map.of("维度1", "2038477980368474114", "维度2", "测试一级项目", "账单数",
					new BigDecimal("55"), "总金额", new BigDecimal("86250"))),
					billRow(Map.of("维度1", "ed4645ecb54f4e7da2f542bc267a7fe4", "维度2", "太阳食品", "账单数",
							new BigDecimal("1"), "总金额", new BigDecimal("910")))))
			.requestedRows(20)
			.returnedRows(2)
			.coverageStatus(ResultCoverageStatus.SOURCE_SHORT)
			.build();
	}

	private ReportDataSnapshot completeBillSnapshot() {
		return ReportDataSnapshot.builder()
			.title("本次分析结果")
			.columns(List.of("一级项目", "二级项目", "账单数", "总金额", "总运费", "服务费", "维修基金", "回箱运费",
					"耗材费", "租金/商品价"))
			.rows(List.of(completeBillRow("测试一级项目", "测试二级项目", "55", "167650", "2040", "1020"),
					completeBillRow("太阳食品", "太阳饮料", "1", "2730", "13", "12"),
					completeBillRow("欣欣黄花菜1", "河南黄花菜", "2", "2400", "20", "20"),
					completeBillRow("咖啡", "奶茶", "2", "2240", "56", "21"),
					completeBillRow("咖啡", "星巴克咖啡", "3", "725", "40", "9"),
					completeBillRow("统万", "昆山统万", "1", "292", "10", "4")))
			.requestedRows(20)
			.returnedRows(6)
			.coverageStatus(ResultCoverageStatus.SOURCE_SHORT)
			.build();
	}

	private Map<String, Object> completeBillRow(String projectName, String twoName, String count, String amount,
			String fare, String serviceFee) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("一级项目", projectName);
		row.put("二级项目", twoName);
		row.put("账单数", new BigDecimal(count));
		row.put("总金额", new BigDecimal(amount));
		row.put("总运费", new BigDecimal(fare));
		row.put("服务费", new BigDecimal(serviceFee));
		row.put("维修基金", new BigDecimal(serviceFee));
		row.put("回箱运费", new BigDecimal(serviceFee));
		row.put("耗材费", BigDecimal.ZERO);
		row.put("租金/商品价", new BigDecimal(serviceFee));
		return row;
	}

	private Map<String, Object> billRow(Map<String, Object> values) {
		return new LinkedHashMap<>(values);
	}

	private AnswerTraceExplainView explainWithSnapshot() {
		AnswerTraceExplainView view = explain();
		view.setReportDataSnapshots(List.of(ReportDataSnapshot.builder()
			.title("费用分类对比")
			.columns(List.of("维度1", "指标1"))
			.rows(List.of(row("A类", "100"), row("B类", "200"), row("C类", "50")))
			.totalRows(3)
			.truncated(false)
			.build()));
		return view;
	}

	private AnswerTraceExplainView explainWithNumericDimensionSnapshot() {
		AnswerTraceExplainView view = explain();
		view.setReportDataSnapshots(List.of(ReportDataSnapshot.builder()
			.title("需求类型对比")
			.columns(List.of("维度1", "指标1"))
			.rows(List.of(row("11", "133"), row("12", "119"), row("9", "4523")))
			.totalRows(3)
			.truncated(false)
			.build()));
		return view;
	}

	private AnswerTraceExplainView explainWithSingleRowMetricSnapshot() {
		AnswerTraceExplainView view = explain();
		view.setReportDataSnapshots(List.of(ReportDataSnapshot.builder()
			.title("统计项对比")
			.columns(List.of("收箱", "发箱"))
			.rows(List.of(Map.of("收箱", new BigDecimal("80"), "发箱", new BigDecimal("120"))))
			.totalRows(1)
			.truncated(false)
			.build()));
		return view;
	}

	private AnswerTraceExplainView explainWithSingleDetailGenericSnapshot() {
		AnswerTraceExplainView view = explain();
		view.setReportDataSnapshots(List.of(ReportDataSnapshot.builder()
			.title("本次分析结果")
			.columns(List.of("维度1", "维度2", "指标1", "指标2", "指标3"))
			.rows(List.of(Map.of("维度1", "D020260528-689", "维度2", "北京华北包装有限公司", "指标1",
					new BigDecimal("2052605286890000"), "指标2", new BigDecimal("274.00"), "指标3",
					new BigDecimal("7"))))
			.totalRows(1)
			.truncated(false)
			.build()));
		return view;
	}

	private AnswerTraceExplainView explainWithBillDetailSnapshot() {
		AnswerTraceExplainView view = explain();
		view.setReportDataSnapshots(List.of(ReportDataSnapshot.builder()
			.title("账单产品明细")
			.columns(List.of("产品名称", "结算周期", "数量", "金额"))
			.rows(List.of(Map.of("产品名称", "OF330绿箱", "结算周期", "2026-05-01", "数量", new BigDecimal("5"), "金额",
					new BigDecimal("15")), Map.of("产品名称", "OF330绿箱", "结算周期", "2026-05-01", "数量",
							new BigDecimal("8"), "金额", new BigDecimal("24")),
					Map.of("产品名称", "330-NM2-JD", "结算周期", "2026-05-01", "数量", new BigDecimal("5"), "金额",
							new BigDecimal("10")),
					Map.of("产品名称", "OF330绿箱", "结算周期", "2026-05-01", "数量", new BigDecimal("3"), "金额",
							new BigDecimal("9")),
					Map.of("产品名称", "330-NM2-JD", "结算周期", "2026-05-01", "数量", new BigDecimal("3"), "金额",
							new BigDecimal("6")),
					Map.of("产品名称", "OF330绿箱", "结算周期", "2026-05-01", "数量", new BigDecimal("2"), "金额",
							new BigDecimal("6")),
					Map.of("产品名称", "330-NM2-JD", "结算周期", "2026-05-01", "数量", new BigDecimal("2"), "金额",
							new BigDecimal("4"))))
			.totalRows(7)
			.truncated(false)
			.build()));
		return view;
	}

	private AnswerTraceExplainView explainWithManyCustomerSnapshots() {
		AnswerTraceExplainView view = explain();
		view.setQuestion("查询客户订单金额前10名");
		view.setAnswer("""
				1. 客户1：100
				2. 客户2：90
				3. 客户3：80
				4. 客户4：70
				5. 客户5：60
				""");
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int i = 1; i <= 60; i++) {
			rows.add(Map.of("客户名称", "客户" + i, "订单金额", new BigDecimal(String.valueOf(1000 - i))));
		}
		view.setReportDataSnapshots(List.of(ReportDataSnapshot.builder()
			.title("客户订单金额排名")
			.columns(List.of("客户名称", "订单金额"))
			.rows(rows)
			.totalRows(60)
			.truncated(false)
			.build(), ReportDataSnapshot.builder()
				.title("第二快照")
				.columns(List.of("客户名称", "订单金额"))
				.rows(List.of(Map.of("客户名称", "第二快照客户", "订单金额", new BigDecimal("1"))))
				.totalRows(1)
				.truncated(false)
				.build(),
				ReportDataSnapshot.builder()
					.title("第三快照")
					.columns(List.of("客户名称", "订单金额"))
					.rows(List.of(Map.of("客户名称", "第三快照客户", "订单金额", new BigDecimal("1"))))
					.totalRows(1)
					.truncated(false)
					.build()));
		return view;
	}

	private AnswerTraceExplainView authoritativeTopCustomers(int returnedRows, int requestedRows,
			ResultCoverageStatus coverageStatus) {
		AnswerTraceExplainView view = explain();
		view.setQuestion("查询客户订单量前" + requestedRows + "名");
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int index = 1; index <= returnedRows; index++) {
			rows.add(Map.of("客户名称", "客户" + index, "订单数",
					new BigDecimal(String.valueOf(returnedRows - index + 1))));
		}
		view.setReportDataSnapshots(List.of(ReportDataSnapshot.builder()
			.title("客户订单量排名")
			.columns(List.of("客户名称", "订单数"))
			.rows(rows)
			.totalRows(returnedRows)
			.requestedRows(requestedRows)
			.returnedRows(returnedRows)
			.appliedLimit(Math.min(requestedRows, 200))
			.hasMore(coverageStatus == ResultCoverageStatus.PLATFORM_LIMITED)
			.coverageStatus(coverageStatus)
			.ranking(true)
			.truncated(coverageStatus == ResultCoverageStatus.PLATFORM_LIMITED)
			.build()));
		return view;
	}

	private AnalysisReportService promptCapturingService(StringBuilder prompt) {
		DataAgentProperties properties = new DataAgentProperties();
		return new AnalysisReportService(new LlmService() {
			@Override
			public Flux<ChatResponse> call(String system, String user) {
				prompt.append(user);
				return Flux.just(ChatResponseUtil.createPureResponse("# 分析报告\n\n## 核心结论\n完成。"));
			}

			@Override
			public Flux<ChatResponse> callSystem(String system) {
				return callUser(system);
			}

			@Override
			public Flux<ChatResponse> callUser(String user) {
				return call("", user);
			}
		}, new ChartCandidateBuilder(properties), new ObjectMapper(), sanitizer(), properties, null);
	}

	private Map<String, Object> row(String dimension, String metric) {
		return Map.of("维度1", dimension, "指标1", new BigDecimal(metric));
	}

	private AnalysisReportService service(String responseText) {
		DataAgentProperties properties = new DataAgentProperties();
		ChartCandidateBuilder chartCandidateBuilder = new ChartCandidateBuilder(properties);
		return new AnalysisReportService(llmService(responseText), chartCandidateBuilder, new ObjectMapper(), sanitizer(),
				properties, null);
	}

	private DataAgentOutputSanitizer sanitizer() {
		return new DataAgentOutputSanitizer(new ObjectMapper());
	}

	private LlmService llmService(String responseText) {
		return new LlmService() {
			@Override
			public Flux<ChatResponse> call(String system, String user) {
				return callUser(user);
			}

			@Override
			public Flux<ChatResponse> callSystem(String system) {
				return callUser(system);
			}

			@Override
			public Flux<ChatResponse> callUser(String user) {
				return Flux.just(ChatResponseUtil.createPureResponse(responseText));
			}
		};
	}

}
