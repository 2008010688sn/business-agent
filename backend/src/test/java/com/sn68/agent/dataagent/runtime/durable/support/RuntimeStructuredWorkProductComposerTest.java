/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.AnswerTraceExplainView;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStepExecutor.ExtraArtifact;
import com.sn68.agent.dataagent.service.report.AnalysisReportService;
import com.sn68.agent.dataagent.service.report.ChartCandidateBuilder;
import com.sn68.agent.dataagent.service.report.ReportDataSnapshot;
import com.sn68.agent.dataagent.service.security.DataAgentOutputSanitizer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RuntimeStructuredWorkProductComposerTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private AnswerTraceExplainStore explainStore;

	private AnalysisReportService analysisReportService;

	private RuntimeStructuredWorkProductComposer composer;

	@BeforeEach
	void setUp() {
		explainStore = mock(AnswerTraceExplainStore.class);
		analysisReportService = mock(AnalysisReportService.class);
		composer = new RuntimeStructuredWorkProductComposer(explainStore, new DataAgentOutputSanitizer(objectMapper),
				new ChartCandidateBuilder(new DataAgentProperties()), analysisReportService, objectMapper);
	}

	@Test
	void emptySnapshotsDoNotParseTablesFromModelProse() {
		AgentRequest request = request();
		when(explainStore.getExplain("runtime-run-1", "rr-1")).thenReturn(Optional.of(AnswerTraceExplainView.builder()
			.sessionId("runtime-run-1")
			.runtimeRequestId("rr-1")
			.answer("| 客户 | 金额 |\n| A | 100 |\n")
			.reportDataSnapshots(List.of())
			.build()));

		List<ExtraArtifact> extras = composer.compose(request);

		assertTrue(extras.isEmpty());
	}

	@Test
	void snapshotsProduceQueryResultWithoutSql() throws Exception {
		AgentRequest request = request();
		when(explainStore.getExplain("runtime-run-1", "rr-1")).thenReturn(Optional.of(explainWithSnapshot()));
		when(analysisReportService.generateDeterministicReport(org.mockito.ArgumentMatchers.any()))
			.thenReturn("## 核心结论\n昨日库存健康。");

		List<ExtraArtifact> extras = composer.compose(request);

		assertTrue(extras.stream().anyMatch(item -> RuntimeStructuredWorkProductComposer.QUERY_RESULT_SCHEMA
			.equals(item.schemaVersion())));
		assertTrue(extras.stream().anyMatch(item -> RuntimeStructuredWorkProductComposer.ANALYSIS_REPORT_SCHEMA
			.equals(item.schemaVersion())));
		String queryJson = extras.stream()
			.filter(item -> RuntimeStructuredWorkProductComposer.QUERY_RESULT_SCHEMA.equals(item.schemaVersion()))
			.findFirst()
			.orElseThrow()
			.artifactJson();
		JsonNode root = objectMapper.readTree(queryJson);
		assertEquals("rr-1", root.path("runtimeRequestId").asText());
		assertFalse(queryJson.toLowerCase().contains("select"));
		assertFalse(queryJson.contains("bill_cost"));
		assertTrue(queryJson.contains("客户A") || queryJson.contains("金额") || queryJson.contains("业务指标"));
	}

	@Test
	void missingExplainYieldsNoExtras() {
		when(explainStore.getExplain("runtime-run-1", "rr-1")).thenReturn(Optional.empty());
		assertTrue(composer.compose(request()).isEmpty());
	}

	private AgentRequest request() {
		return AgentRequest.builder().threadId("runtime-run-1").runtimeRequestId("rr-1").query("昨日库存").build();
	}

	private AnswerTraceExplainView explainWithSnapshot() {
		return AnswerTraceExplainView.builder()
			.sessionId("runtime-run-1")
			.runtimeRequestId("rr-1")
			.question("昨日库存")
			.answer("SELECT amount FROM bill_cost")
			.sql("SELECT amount FROM bill_cost")
			.usedTables(List.of("bill_cost"))
			.usedColumns(List.of("customer_name", "amount"))
			.reportDataSnapshots(List.of(ReportDataSnapshot.builder()
				.title("客户金额")
				.columns(List.of("customer_name", "amount"))
				.rows(List.of(Map.of("customer_name", "客户A", "amount", 100),
						Map.of("customer_name", "客户B", "amount", 80)))
				.totalRows(2)
				.truncated(false)
				.build()))
			.build();
	}

}
