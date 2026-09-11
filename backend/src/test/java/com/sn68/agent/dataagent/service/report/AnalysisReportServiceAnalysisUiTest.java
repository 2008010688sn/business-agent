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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.tool.datasource.ResultCoverageStatus;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.AnswerTraceExplainView;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.service.analysis.AnalysisConfig;
import com.sn68.agent.dataagent.service.analysis.AnalysisConfig.AnalysisAssociation;
import com.sn68.agent.dataagent.service.analysis.AnalysisConfig.AnalysisAssociation.Endpoint;
import com.sn68.agent.dataagent.service.analysis.AnalysisConfig.AnalysisSource;
import com.sn68.agent.dataagent.service.analysis.AnalysisResultEmitPolicy;
import com.sn68.agent.dataagent.service.llm.LlmService;
import com.sn68.agent.dataagent.service.security.DataAgentOutputSanitizer;
import com.sn68.agent.dataagent.skill.SkillExecutionMode;
import com.sn68.agent.dataagent.ui.AgentUiMessage;
import com.sn68.agent.dataagent.util.ChatResponseUtil;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisReportServiceAnalysisUiTest {

	@Test
	void reportPackEmitsAnalysisResultUiAndDropsUncitedRootCauses() {
		AgentUiMessage ui = service().buildAnalysisUiMessage(explainWithSnapshot(),
				new AnalysisUiOptions("TABLE_QUERY", List.of("demand-create"), List.of(),
						Map.of("projectName", "太阳食品"), List.of(), null, null));

		assertEquals(AgentUiMessage.SCHEMA_VERSION, ui.schemaVersion());
		assertEquals(AgentUiMessage.KIND_ANALYSIS_RESULT, ui.kind());
		assertEquals("ANALYSIS", ui.payload().action());
		assertEquals("TABLE_QUERY", ui.payload().values().get("intent"));
		assertFalse(((List<?>) ui.payload().values().get("evidence")).isEmpty());
		assertFalse(((List<?>) ui.payload().values().get("findings")).isEmpty());
		List<Map<String, Object>> rootCauses = castList(ui.payload().values().get("rootCauses"));
		assertTrue(rootCauses.stream().noneMatch(cause -> {
			Object ids = cause.get("evidenceIds");
			return !(ids instanceof List<?> list) || list.isEmpty();
		}));
		assertTrue(ui.actions().stream().anyMatch(action -> "START_FLOW".equals(action.type())));
		assertTrue(ui.actions().stream().anyMatch(action -> "DRILL".equals(action.type())));
		AgentUiMessage.Action drill = ui.actions()
			.stream()
			.filter(action -> "DRILL".equals(action.type()))
			.findFirst()
			.orElseThrow();
		assertTrue(String.valueOf(drill.value()).contains("同一分析会话"));
		assertTrue(String.valueOf(drill.value()).contains("这个月各项目的账单情况"));
		List<Map<String, Object>> followUps = service().buildAnalysisFollowUps(explainWithSnapshot(),
				new AnalysisUiOptions("TABLE_QUERY", List.of("demand-create"), List.of(),
						Map.of("projectName", "太阳食品"), List.of(), null, null));
		assertTrue(followUps.stream().anyMatch(item -> "DRILL".equals(item.get("type"))));
		assertTrue(followUps.stream().noneMatch(item -> item.containsKey("findings")));
		assertTrue(ui.actions().stream().noneMatch(action -> "ASK_WRITE".equals(action.type())));
		assertEquals(List.of(), ui.payload().options());
		AgentUiMessage.Action startFlow = ui.actions().stream()
			.filter(action -> "START_FLOW".equals(action.type()))
			.findFirst()
			.orElseThrow();
		assertEquals("demand-create", startFlow.payload().get("skillCode"));
		assertEquals(true, startFlow.payload().get("confirm"));
		assertEquals("太阳食品", ((Map<?, ?>) startFlow.payload().get("slots")).get("projectName"));
	}

	@Test
	void fileOnlyOmitsRootCauses() {
		AgentUiMessage ui = service().buildAnalysisUiMessage(explainWithSnapshot(),
				new AnalysisUiOptions("FILE_ONLY", List.of(), List.of(), Map.of(), List.of(), null, null));

		assertEquals("FILE_ONLY", ui.payload().values().get("intent"));
		assertTrue(((List<?>) ui.payload().values().get("rootCauses")).isEmpty());
		assertTrue(ui.actions().stream().noneMatch(action -> "START_FLOW".equals(action.type())));
	}

	@Test
	void startFlowAbsentWhenNextFlowsEmpty() {
		AgentUiMessage ui = service().buildAnalysisUiMessage(explainWithSnapshot(),
				new AnalysisUiOptions("FILE_JOIN", List.of(), List.of(), Map.of(), List.of("order_no"), "单据", null));

		assertTrue(ui.actions().stream().noneMatch(action -> "START_FLOW".equals(action.type())));
		List<Map<String, Object>> nextActions = castList(ui.payload().values().get("nextActions"));
		assertTrue(nextActions.stream().anyMatch(action -> "NONE".equals(action.get("type"))));
		assertEquals(List.of("order_no"), nextActions.stream()
			.filter(action -> "NONE".equals(action.get("type")))
			.findFirst()
			.map(action -> action.get("missing"))
			.orElse(List.of()));
		assertTrue(nextActions.stream().noneMatch(action -> "ASK_WRITE".equals(action.get("type"))));
		assertTrue(nextActions.stream().noneMatch(action -> "START_FLOW".equals(action.get("type"))));
	}

	@Test
	void askWritePresentOnlyWhenNextWriteToolsNonEmpty() {
		AgentUiMessage without = service().buildAnalysisUiMessage(explainWithSnapshot(),
				new AnalysisUiOptions("TABLE_QUERY", List.of("demand-create"), List.of(), Map.of(), List.of(), null,
						null));
		assertTrue(without.actions().stream().noneMatch(action -> "ASK_WRITE".equals(action.type())));
		List<Map<String, Object>> withoutNext = castList(without.payload().values().get("nextActions"));
		assertTrue(withoutNext.stream().noneMatch(action -> "ASK_WRITE".equals(action.get("type"))));

		AgentUiMessage with = service().buildAnalysisUiMessage(explainWithSnapshot(),
				new AnalysisUiOptions("TABLE_QUERY", List.of("demand-create"), List.of("demand_create_execute"),
						Map.of(), List.of(), null, null));
		AgentUiMessage.Action askWrite = with.actions()
			.stream()
			.filter(action -> "ASK_WRITE".equals(action.type()))
			.findFirst()
			.orElseThrow();
		assertEquals("demand_create_execute", askWrite.value());
		assertEquals("demand_create_execute", askWrite.payload().get("toolName"));
		assertEquals(true, askWrite.payload().get("confirm"));
		assertEquals("建议写操作 demand_create_execute", askWrite.label());
		List<Map<String, Object>> nextActions = castList(with.payload().values().get("nextActions"));
		assertTrue(nextActions.stream()
			.anyMatch(action -> "ASK_WRITE".equals(action.get("type"))
					&& "demand_create_execute".equals(action.get("toolName"))
					&& Boolean.TRUE.equals(action.get("confirm"))));
		assertEquals(List.of(), with.payload().options());
		assertTrue(with.actions()
			.stream()
			.noneMatch(action -> "APPROVE".equals(action.type()) || "DENY".equals(action.type())));
		assertFalse(with.payload().values().containsKey("toolCallId"));
		assertFalse(with.payload().values().containsKey("paramFingerprint"));
		assertFalse(askWrite.payload().containsKey("execute"));
	}

	@Test
	void fileOnlyCanSuggestAskWriteWhenConfigListsTools() {
		AgentUiMessage ui = service().buildAnalysisUiMessage(explainWithSnapshot(),
				new AnalysisUiOptions("FILE_ONLY", List.of(), List.of("demand_create_execute"), Map.of(), List.of(),
						null, null));

		assertEquals("FILE_ONLY", ui.payload().values().get("intent"));
		assertTrue(((List<?>) ui.payload().values().get("rootCauses")).isEmpty());
		assertTrue(ui.actions().stream().noneMatch(action -> "START_FLOW".equals(action.type())));
		assertTrue(ui.actions().stream().anyMatch(action -> "ASK_WRITE".equals(action.type())));
		assertEquals(AgentUiMessage.KIND_ANALYSIS_RESULT, ui.kind());
		assertEquals("ANALYSIS", ui.payload().action());
		assertEquals(List.of(), ui.payload().options());
	}

	@Test
	void optionsForTurnCopiesNextWriteToolsFromConfig() {
		AnalysisConfig config = new AnalysisConfig(
				List.of(new AnalysisSource("t1", "TABLE", 3L, "dis_demand", List.of("order_no"), null, false)),
				List.of(), "order", List.of("demand-create"), List.of("demand_create_execute"));
		AnalysisUiOptions options = service().optionsForTurn("这个月各项目的账单情况", false, config, explainWithSnapshot());

		assertEquals(List.of("demand_create_execute"), options.nextWriteTools());
		assertEquals(List.of("demand-create"), options.nextFlows());
	}

	@Test
	void emptyPrefilledSlotsDoesNotCopySnapshotColumnNames() {
		AgentUiMessage ui = service().buildAnalysisUiMessage(explainWithSnapshot(),
				new AnalysisUiOptions("TABLE_QUERY", List.of("demand-create"), List.of(), Map.of(), List.of(), null,
						null));

		AgentUiMessage.Action startFlow = ui.actions().stream()
			.filter(action -> "START_FLOW".equals(action.type()))
			.findFirst()
			.orElseThrow();
		assertEquals(true, startFlow.payload().get("confirm"));
		assertEquals("demand-create", startFlow.payload().get("skillCode"));
		Object slots = startFlow.payload().get("slots");
		assertTrue(slots instanceof Map<?, ?> map && map.isEmpty());
		assertFalse(String.valueOf(startFlow.payload()).contains("维度1"));
		assertFalse(String.valueOf(startFlow.payload()).contains("指标1"));
	}

	@Test
	void classifierNotesAreCopyNotMissingKeys() {
		AnalysisConfig config = new AnalysisConfig(
				List.of(new AnalysisSource("t1", "TABLE", 3L, "dis_demand", List.of("order_no"), null, false),
						new AnalysisSource("f1", "FILE_TABLE", null, null, List.of(), null, true)),
				List.of(new AnalysisAssociation(new Endpoint("f1", "bill_no"), new Endpoint("t1", "order_no"), "EXACT")),
				"order", List.of(), List.of());
		AnalysisUiOptions options = service().optionsForTurn("帮我看下", true, config, explainWithSnapshot());

		assertEquals("FILE_ONLY", options.intent());
		assertTrue(options.missing().isEmpty());
		assertEquals("是否要对业务表", options.note());
	}

	@Test
	void fileJoinReusesExtractedSnapshotJoinKeys() {
		AnalysisConfig config = new AnalysisConfig(
				List.of(new AnalysisSource("t1", "TABLE", 3L, "dis_demand", List.of("order_no"), null, false),
						new AnalysisSource("f1", "FILE_TABLE", null, null, List.of("bill_no"), null, true)),
				List.of(new AnalysisAssociation(new Endpoint("f1", "bill_no"), new Endpoint("t1", "order_no"), "EXACT")),
				"order", List.of("demand-create"), List.of());
		AnswerTraceExplainView explain = AnswerTraceExplainView.builder()
			.question("用这份对账单对一下系统里的单")
			.reportDataSnapshots(List.of(ReportDataSnapshot.builder()
				.title("对账单.xlsx")
				.columns(List.of("bill_no", "amount"))
				.rows(List.of(Map.of("bill_no", "B-1", "amount", 10)))
				.build()))
			.build();
		AnalysisUiOptions options = service().optionsForTurn(explain.getQuestion(), true, config, explain);

		assertEquals("FILE_JOIN", options.intent());
		assertTrue(options.missing().isEmpty());
		AgentUiMessage ui = service().buildAnalysisUiMessage(explain, "", options);
		assertTrue(ui.actions().stream().anyMatch(action -> "START_FLOW".equals(action.type())));
		assertTrue(((List<?>) ui.payload().values().get("nextActions")).stream()
			.noneMatch(item -> item instanceof Map<?, ?> map && "NONE".equals(map.get("type"))));
	}

	@Test
	void fileJoinMissingUsesFieldNameNotSentinelOrCopyNote() {
		AnalysisConfig config = new AnalysisConfig(
				List.of(new AnalysisSource("t1", "TABLE", 3L, "dis_demand", List.of("order_no"), null, false),
						new AnalysisSource("f1", "FILE_TABLE", null, null, List.of("bill_no"), null, true)),
				List.of(new AnalysisAssociation(new Endpoint("f1", "bill_no"), new Endpoint("t1", "order_no"), "EXACT")),
				"order", List.of(), List.of());
		AnswerTraceExplainView explain = AnswerTraceExplainView.builder()
			.question("用这份对账单对一下系统里的单")
			.reportDataSnapshots(List.of(ReportDataSnapshot.builder()
				.title("对账单.xlsx")
				.columns(List.of("amount"))
				.rows(List.of(Map.of("amount", 10)))
				.build()))
			.build();
		AnalysisUiOptions options = service().optionsForTurn(explain.getQuestion(), true, config, explain);

		assertEquals("FILE_JOIN", options.intent());
		assertEquals(List.of("bill_no"), options.missing());
		assertEquals(null, options.note());
	}

	@Test
	void ordinaryReactTableQueryDoesNotPassEmitGate() {
		assertFalse(AnalysisResultEmitPolicy.shouldEmit(SkillExecutionMode.REACT, false, AnalysisConfig.empty()));
		assertFalse(AnalysisResultEmitPolicy.shouldEmit(SkillExecutionMode.DETERMINISTIC, false, AnalysisConfig.empty()));
		assertFalse(AnalysisResultEmitPolicy.shouldEmit(SkillExecutionMode.FLOW, true, tableAnalysisConfig()));
		assertTrue(AnalysisResultEmitPolicy.shouldEmit(SkillExecutionMode.REACT, true, AnalysisConfig.empty()));
		assertTrue(AnalysisResultEmitPolicy.shouldEmit(SkillExecutionMode.REACT, false, tableAnalysisConfig()));
	}

	private AnalysisConfig tableAnalysisConfig() {
		return new AnalysisConfig(
				List.of(new AnalysisSource("t1", "TABLE", 3L, "dis_demand", List.of("order_no"), null, false)),
				List.of(), "order", List.of("demand-create"), List.of());
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> castList(Object value) {
		return (List<Map<String, Object>>) value;
	}

	private AnalysisReportService service() {
		DataAgentProperties properties = new DataAgentProperties();
		return new AnalysisReportService(new LlmService() {
			@Override
			public Flux<ChatResponse> call(String system, String user) {
				return Flux.error(new AssertionError("analysis UI pack must not call model"));
			}

			@Override
			public Flux<ChatResponse> callSystem(String system) {
				return call(system, "");
			}

			@Override
			public Flux<ChatResponse> callUser(String user) {
				return Flux.just(ChatResponseUtil.createPureResponse("LLM should not be used"));
			}
		}, new ChartCandidateBuilder(properties), new ObjectMapper(), new DataAgentOutputSanitizer(new ObjectMapper()),
				properties, null);
	}

	private AnswerTraceExplainView explainWithSnapshot() {
		return AnswerTraceExplainView.builder()
			.sessionId("100")
			.runtimeRequestId("runtime-analysis")
			.question("这个月各项目的账单情况")
			.answer("合计 350")
			.reportDataSnapshots(List.of(ReportDataSnapshot.builder()
				.title("费用分类对比")
				.columns(List.of("维度1", "指标1"))
				.rows(List.of(Map.of("维度1", "A类", "指标1", new BigDecimal("100")),
						Map.of("维度1", "B类", "指标1", new BigDecimal("200")),
						Map.of("维度1", "C类", "指标1", new BigDecimal("50"))))
				.totalRows(3)
				.returnedRows(3)
				.coverageStatus(ResultCoverageStatus.FULL)
				.build()))
			.build();
	}

}
