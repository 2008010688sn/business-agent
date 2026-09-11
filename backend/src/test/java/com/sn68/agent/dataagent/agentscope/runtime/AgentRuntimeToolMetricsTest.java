/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.agentscope.runtime;

import com.sn68.agent.dataagent.agentscope.tool.AgentModelToolName;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AgentRuntimeToolMetricsTest {

	private static final String DATASOURCE_TOOL = AgentModelToolName.DATASOURCE_SKILL_SEARCH;

	private static final String KNOWLEDGE_TOOL = AgentModelToolName.DOMAIN_BUSINESS_KNOWLEDGE_SEARCH;

	private static final String SEMANTIC_TOOL = AgentModelToolName.SEMANTIC_MODEL_SEARCH;

	@Test
	void identicalReadOnlyCallAllowsTwoAttemptsAndBlocksThird() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics(2);
		String tool = DATASOURCE_TOOL;
		String first = "{\"sql\":\"select id from orders\",\"limit\":20}";
		String reordered = "{\"limit\":20,\"sql\":\"select id from orders\"}";

		assertTrue(metrics.allowReadOnlyCall(tool, first));
		metrics.recordReadOnlyCallCompleted(tool, first, "EMPTY");
		assertTrue(metrics.allowReadOnlyCall(tool, reordered));
		metrics.recordReadOnlyCallCompleted(tool, reordered, "EMPTY");
		assertFalse(metrics.allowReadOnlyCall(tool, first));
	}

	@Test
	void whitespaceAndKeywordCaseJitterStillCountsAsTheSameCall() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics(2);
		String tool = DATASOURCE_TOOL;
		String first = "{\"sql\":\"select id from orders where status = 'A' limit 10\",\"limit\":20}";
		String spaced = "{\"sql\":\"select   id\\n  from orders\\n where status = 'A'  limit 10\",\"limit\":20}";
		String upperCased = "{\"sql\":\"SELECT id FROM orders WHERE status = 'A' LIMIT 10;\",\"limit\":20}";

		assertTrue(metrics.allowReadOnlyCall(tool, first));
		metrics.recordReadOnlyCallCompleted(tool, first, "EMPTY");
		assertTrue(metrics.allowReadOnlyCall(tool, spaced));
		metrics.recordReadOnlyCallCompleted(tool, spaced, "EMPTY");

		assertFalse(metrics.allowReadOnlyCall(tool, upperCased));
	}

	@Test
	void differentLimitStaysADifferentCall() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics(2);
		String tool = DATASOURCE_TOOL;
		String tenRows = "{\"sql\":\"select id from orders limit 10\"}";
		String elevenRows = "{\"sql\":\"select id from orders limit 11\"}";

		metrics.recordReadOnlyCallCompleted(tool, tenRows, "NON_EMPTY");
		metrics.recordReadOnlyCallCompleted(tool, tenRows, "NON_EMPTY");

		assertFalse(metrics.allowReadOnlyCall(tool, tenRows));
		assertTrue(metrics.allowReadOnlyCall(tool, elevenRows));
	}

	@Test
	void unparsableSqlKeepsTheLiteralComparison() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics(2);
		String tool = DATASOURCE_TOOL;
		String broken = "{\"sql\":\"select id from\"}";
		String otherBroken = "{\"sql\":\"select name from\"}";

		metrics.recordReadOnlyCallCompleted(tool, broken, "ERROR");
		metrics.recordReadOnlyCallCompleted(tool, broken, "ERROR");

		assertFalse(metrics.allowReadOnlyCall(tool, broken));
		assertTrue(metrics.allowReadOnlyCall(tool, otherBroken));
	}

	@Test
	void resultCategoryChangeResetsNoProgressCount() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics(2);
		String tool = DATASOURCE_TOOL;
		String input = "{\"sql\":\"select id from orders\"}";

		metrics.recordReadOnlyCallCompleted(tool, input, "EMPTY");
		metrics.recordReadOnlyCallCompleted(tool, input, "NON_EMPTY");
		assertTrue(metrics.allowReadOnlyCall(tool, input));
	}

	@Test
	void writeToolIsNeverBlockedByReadOnlyProtection() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics(1);
		assertTrue(metrics.allowReadOnlyCall("skill.demand_create", "{}"));
	}

	@Test
	void modelMetricsTrackRetriesAndTokenPeak() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics();
		metrics.recordModelCall("same");
		metrics.recordModelFailure("same", 100, 20);
		metrics.recordModelCall("same");
		metrics.recordModelUsage(150, 30, 40);

		assertEquals(2, metrics.modelCallCount());
		assertEquals(1, metrics.modelRetryCount());
		assertEquals(250, metrics.promptTokens());
		assertEquals(150, metrics.peakPromptTokens());
		assertEquals(30, metrics.completionTokens());
		assertEquals(60, metrics.modelDurationMs());
	}

	@Test
	void configuredModelAndPromptBudgetsBlockBeforeNextCall() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics(2, 2, 0, 150L);

		assertTrue(metrics.tryRecordModelCall("first", 80L));
		metrics.recordModelUsage(80L, 10L, 5L);
		assertFalse(metrics.tryRecordModelCall("second", 71L));
		assertTrue(metrics.tryRecordModelCall("second", 70L));
		metrics.recordModelUsage(70L, 10L, 5L);
		assertFalse(metrics.tryRecordModelCall("third", 1L));
	}

	@Test
	void configuredToolBudgetBlocksBeforeNextCall() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics(2, 0, 2, 0L);

		assertTrue(metrics.tryRecordCall());
		assertTrue(metrics.tryRecordCall());
		assertFalse(metrics.tryRecordCall());
		assertEquals(2, metrics.toolCount());
	}

	@Test
	void modelCallBudgetCreatesTypedFailureWithDiagnostics() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics(2, 1, 0, 0L);

		assertTrue(metrics.tryRecordModelCall("first", 10L));
		assertFalse(metrics.tryRecordModelCall("second", 10L));
		AgentRuntimeBudgetExceededException failure = metrics.modelBudgetExceededException(10L);

		assertEquals(AgentRuntimeBudgetExceededException.Reason.MODEL_CALLS, failure.getReason());
		assertEquals(1L, failure.getLimit());
		assertEquals(1L, failure.getCurrent());
		assertEquals(1L, failure.getAttempted());
	}

	@Test
	void promptTokenBudgetCreatesTypedFailureWithDiagnostics() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics(2, 0, 0, 100L);
		metrics.recordModelUsage(80L, 10L, 5L);

		assertFalse(metrics.tryRecordModelCall("next", 21L));
		AgentRuntimeBudgetExceededException failure = metrics.modelBudgetExceededException(21L);

		assertEquals(AgentRuntimeBudgetExceededException.Reason.PROMPT_TOKENS, failure.getReason());
		assertEquals(100L, failure.getLimit());
		assertEquals(80L, failure.getCurrent());
		assertEquals(21L, failure.getAttempted());
	}

	@Test
	void toolCallBudgetCreatesTypedFailureWithDiagnostics() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics(2, 0, 1, 0L);

		assertTrue(metrics.tryRecordCall());
		assertFalse(metrics.tryRecordCall());
		AgentRuntimeBudgetExceededException failure = metrics.toolBudgetExceededException();

		assertEquals(AgentRuntimeBudgetExceededException.Reason.TOOL_CALLS, failure.getReason());
		assertEquals(1L, failure.getLimit());
		assertEquals(1L, failure.getCurrent());
		assertEquals(1L, failure.getAttempted());
	}

	@Test
	void protocolRepairCanStartOnlyOnce() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics();

		assertTrue(metrics.tryStartProtocolRepair(10, null, Duration.ZERO));
		assertFalse(metrics.tryStartProtocolRepair(10, null, Duration.ZERO));
		assertTrue(metrics.protocolRepairAttempted());
		assertEquals(1, metrics.protocolRepairCount());
	}

	@Test
	void protocolRepairCannotBypassMaximumIterations() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics();
		metrics.recordReactIteration(1);

		assertFalse(metrics.tryStartProtocolRepair(1, null, Duration.ZERO));
		assertEquals(0, metrics.protocolRepairCount());
	}

	@Test
	void lastFailureUsesExecutionSequenceInsteadOfCompletionOrder() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics();

		metrics.recordFailure(2, "SECOND", "second invocation failed");
		metrics.recordFailure(1, "FIRST", "first invocation completed later");

		assertEquals(2, metrics.lastFailure().sequenceNo());
		assertEquals("SECOND", metrics.lastFailure().errorCode());
		assertEquals("second invocation failed", metrics.lastFailureMessage());
	}

	@Test
	void unknownBusinessTermClarifiesAfterOneControlledEmptySearch() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics();
		metrics.setOriginalQuery("绿循环订单情况");
		metrics.recordToolResult(KNOWLEDGE_TOOL, "{\"query\":\"绿循环\"}",
				"{\"resolution\":\"matched\",\"hits\":[{\"title\":\"网点\",\"snippet\":\"网点定义\"}]}");
		metrics.recordToolResult(SEMANTIC_TOOL, "{\"query\":\"绿循环\"}",
				"{\"resolution\":\"no_match\",\"hits\":[]}");
		String fieldSearch = "{\"action\":\"SEARCH\",\"sql\":\"select project_name from orders\"}";

		assertTrue(metrics.allowControlledFieldSearch(DATASOURCE_TOOL, fieldSearch));
		metrics.recordToolResult(DATASOURCE_TOOL, fieldSearch,
				"{\"action\":\"SEARCH\",\"emptyResult\":true,\"rows\":[]}");

		assertEquals("UNKNOWN_BUSINESS_TERM_UNRESOLVED", metrics.clarificationReason());
		assertFalse(metrics.allowControlledFieldSearch(DATASOURCE_TOOL, fieldSearch));
	}

	@Test
	void explicitProjectNameIsNotBlockedByUnknownTermGate() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics();
		metrics.setOriginalQuery("项目名称为绿循环的订单");
		metrics.recordToolResult(KNOWLEDGE_TOOL, "{\"query\":\"绿循环\"}",
				"{\"resolution\":\"no_match\",\"hits\":[]}");
		metrics.recordToolResult(SEMANTIC_TOOL, "{\"query\":\"绿循环\"}",
				"{\"resolution\":\"no_match\",\"hits\":[]}");
		String fieldSearch = "{\"action\":\"SEARCH\",\"sql\":\"select project_name from orders\"}";

		assertTrue(metrics.allowControlledFieldSearch(DATASOURCE_TOOL, fieldSearch));
		assertTrue(metrics.allowControlledFieldSearch(DATASOURCE_TOOL, fieldSearch));
		assertEquals(null, metrics.clarificationReason());
	}

	@Test
	void resolvedControlledFieldSearchAllowsFollowupQueries() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics();
		metrics.setOriginalQuery("未知业务词订单情况");
		metrics.recordToolResult(KNOWLEDGE_TOOL, "{\"query\":\"未知业务词\"}",
				"{\"resolution\":\"no_match\",\"hits\":[]}");
		metrics.recordToolResult(SEMANTIC_TOOL, "{\"query\":\"未知业务词\"}",
				"{\"resolution\":\"no_match\",\"hits\":[]}");
		String fieldSearch = "{\"action\":\"SEARCH\",\"sql\":\"select project_name from orders\"}";

		assertTrue(metrics.allowControlledFieldSearch(DATASOURCE_TOOL, fieldSearch));
		metrics.recordToolResult(DATASOURCE_TOOL, fieldSearch,
				"{\"action\":\"SEARCH\",\"rows\":[{\"project_name\":\"matched\"}]}");

		assertTrue(metrics.allowControlledFieldSearch(DATASOURCE_TOOL, fieldSearch));
		assertEquals(null, metrics.clarificationReason());
	}

	@Test
	void consecutiveEmptySearchIsIgnoredWhenDisabled() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics();
		String first = "{\"action\":\"SEARCH\",\"sql\":\"select 1 from bill_cost where settlement_date = '2026-09'\"}";
		String second = "{\"action\":\"SEARCH\",\"sql\":\"select 1 from bill_cost where settlement_date = '2026-08'\"}";

		metrics.recordToolResult(DATASOURCE_TOOL, first, "{\"emptyResult\":true,\"rows\":[],\"sql\":\"select 1\"}");
		metrics.recordToolResult(DATASOURCE_TOOL, second, "{\"emptyResult\":true,\"rows\":[],\"sql\":\"select 2\"}");

		assertTrue(metrics.allowDatasourceSearch(DATASOURCE_TOOL, first));
	}

	@Test
	void consecutiveEmptySearchBlocksAfterThresholdAndResetsOnHit() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics();
		metrics.configureEmptySearchNoProgress(true, 2);
		String first = "{\"action\":\"SEARCH\",\"sql\":\"select 1 from bill_cost where settlement_date >= '2026-09-01'\"}";
		String second = "{\"action\":\"SEARCH\",\"sql\":\"select 1 from bill_cost where settlement_date = '2026-09'\"}";
		String third = "{\"action\":\"SEARCH\",\"sql\":\"select 1 from bill_cost group by settlement_date\"}";
		String empty = "{\"action\":\"SEARCH\",\"emptyResult\":true,\"rows\":[],\"sql\":\"select 1 from bill_cost where settlement_date >= '2026-09-01'\"}";
		String stillEmpty = "{\"action\":\"SEARCH\",\"emptyResult\":true,\"rows\":[],\"sql\":\"select 1 from bill_cost where settlement_date = '2026-09'\"}";
		String hit = "{\"action\":\"SEARCH\",\"emptyResult\":false,\"rows\":[{\"n\":1}],\"sql\":\"select 1\"}";

		assertTrue(metrics.allowDatasourceSearch(DATASOURCE_TOOL, first));
		metrics.recordToolResult(DATASOURCE_TOOL, first, empty);
		assertTrue(metrics.allowDatasourceSearch(DATASOURCE_TOOL, second));
		metrics.recordToolResult(DATASOURCE_TOOL, second, stillEmpty);
		assertFalse(metrics.allowDatasourceSearch(DATASOURCE_TOOL, third));
		assertTrue(metrics.emptySearchNoProgressMessage().contains("暂无数据"));
		assertTrue(metrics.emptySearchNoProgressMessage().contains("settlement_date = '2026-09'"));

		metrics.recordToolResult(DATASOURCE_TOOL, third, hit);
		assertTrue(metrics.allowDatasourceSearch(DATASOURCE_TOOL, first));
	}

	@Test
	void dataProfileDoesNotCountTowardEmptySearchNoProgress() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics();
		metrics.configureEmptySearchNoProgress(true, 1);
		String search = "{\"action\":\"SEARCH\",\"sql\":\"select 1 from bill_cost\"}";
		String profile = "{\"action\":\"DATA_PROFILE\",\"sql\":\"select 1 from bill_cost\"}";

		metrics.recordToolResult(DATASOURCE_TOOL, profile, "{\"emptyResult\":true,\"rows\":[]}");
		assertTrue(metrics.allowDatasourceSearch(DATASOURCE_TOOL, search));
	}

	@Test
	void ordinaryDatasourceSearchIsNotConsumedAsControlledFieldSearch() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics();
		metrics.setOriginalQuery("未知业务词订单情况");
		metrics.recordToolResult(KNOWLEDGE_TOOL, "{\"query\":\"未知业务词\"}",
				"{\"resolution\":\"no_match\",\"hits\":[]}");
		metrics.recordToolResult(SEMANTIC_TOOL, "{\"query\":\"未知业务词\"}",
				"{\"resolution\":\"no_match\",\"hits\":[]}");
		String orderSearch = "{\"action\":\"SEARCH\",\"sql\":\"select order_no from orders\"}";

		assertTrue(metrics.allowControlledFieldSearch(DATASOURCE_TOOL, orderSearch));
		assertTrue(metrics.allowControlledFieldSearch(DATASOURCE_TOOL, orderSearch));
		assertEquals(null, metrics.clarificationReason());
	}

	@Test
	void consecutiveSearchExecutionFailuresBlockAfterTwo() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics();
		String first = "{\"action\":\"SEARCH\",\"sql\":\"select demand_id from bill_cost\"}";
		String second = "{\"action\":\"SEARCH\",\"sql\":\"select order_no from bill_cost\"}";
		String third = "{\"action\":\"SEARCH\",\"sql\":\"select id from bill_cost\"}";

		assertTrue(metrics.allowSearchExecution(DATASOURCE_TOOL, first));
		metrics.recordSearchExecutionOutcome(DATASOURCE_TOOL, first, true);
		assertTrue(metrics.allowSearchExecution(DATASOURCE_TOOL, second));
		metrics.recordSearchExecutionOutcome(DATASOURCE_TOOL, second, true);
		assertFalse(metrics.allowSearchExecution(DATASOURCE_TOOL, third));
		assertTrue(metrics.searchExecutionNoProgressMessage().contains("不要继续探表"));
	}

	@Test
	void searchExecutionWrappedUpOnlyWhenLastFailureIsNoProgressSearchFailed() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics();
		assertFalse(metrics.searchExecutionWrappedUp());
		metrics.recordFailure(1, "TABLE_NOT_VISIBLE", "表 additional_cost 对当前 Agent 不可见");
		assertFalse(metrics.searchExecutionWrappedUp());
		metrics.recordFailure(2, "NO_PROGRESS_SEARCH_FAILED", metrics.searchExecutionNoProgressMessage());
		assertTrue(metrics.searchExecutionWrappedUp());
	}

	@Test
	void searchExecutionFailureResetsOnSuccessfulSearchIncludingEmptyRows() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics();
		String failed = "{\"action\":\"SEARCH\",\"sql\":\"select demand_id from bill_cost\"}";
		String emptyHit = "{\"action\":\"SEARCH\",\"sql\":\"select id from bill_cost where id = 1\"}";
		String followUp = "{\"action\":\"SEARCH\",\"sql\":\"select order_no from bill_cost\"}";

		metrics.recordSearchExecutionOutcome(DATASOURCE_TOOL, failed, true);
		assertTrue(metrics.allowSearchExecution(DATASOURCE_TOOL, emptyHit));
		metrics.recordSearchExecutionOutcome(DATASOURCE_TOOL, emptyHit, false);
		assertTrue(metrics.allowSearchExecution(DATASOURCE_TOOL, followUp));
		metrics.recordSearchExecutionOutcome(DATASOURCE_TOOL, followUp, true);
		assertTrue(metrics.allowSearchExecution(DATASOURCE_TOOL, failed));
	}

	@Test
	void getTableSchemaDoesNotCountTowardSearchExecutionNoProgress() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics();
		String schema = "{\"action\":\"GET_TABLE_SCHEMA\",\"sql\":\"select * from bill_cost\"}";
		String search = "{\"action\":\"SEARCH\",\"sql\":\"select id from bill_cost\"}";

		metrics.recordSearchExecutionOutcome(DATASOURCE_TOOL, schema, true);
		metrics.recordSearchExecutionOutcome(DATASOURCE_TOOL, schema, true);
		assertTrue(metrics.allowSearchExecution(DATASOURCE_TOOL, search));
	}

}
