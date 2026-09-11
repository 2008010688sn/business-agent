/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.AnswerTraceExplainView;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStepExecutor.ExtraArtifact;
import com.sn68.agent.dataagent.service.report.AnalysisReportService;
import com.sn68.agent.dataagent.service.report.ChartCandidate;
import com.sn68.agent.dataagent.service.report.ChartCandidateBuilder;
import com.sn68.agent.dataagent.service.report.ReportDataSnapshot;
import com.sn68.agent.dataagent.service.security.DataAgentOutputSanitizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * 任务终态结构化交卷：只从工具快照取表，经消毒器后落 query-result / chart-candidate / analysis-report。
 * 禁止从模型散文反解析表格。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeStructuredWorkProductComposer {

	public static final String QUERY_RESULT_SCHEMA = "query-result/v1";

	public static final String CHART_CANDIDATE_SCHEMA = "chart-candidate/v1";

	public static final String ANALYSIS_REPORT_SCHEMA = "analysis-report/v1";

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private static final int MAX_SNAPSHOTS = 5;

	private final AnswerTraceExplainStore explainStore;

	private final DataAgentOutputSanitizer outputSanitizer;

	private final ChartCandidateBuilder chartCandidateBuilder;

	private final AnalysisReportService analysisReportService;

	private final ObjectMapper objectMapper;

	public List<ExtraArtifact> compose(AgentRequest request) {
		if (request == null || !StringUtils.hasText(request.getThreadId())
				|| !StringUtils.hasText(request.getRuntimeRequestId())) {
			return List.of();
		}
		AnswerTraceExplainView explain = explainStore.getExplain(request.getThreadId(), request.getRuntimeRequestId())
			.orElse(null);
		if (explain == null) {
			return List.of();
		}
		List<ReportDataSnapshot> sanitized = sanitizeSnapshots(explain);
		if (sanitized.isEmpty()) {
			return List.of();
		}
		List<ExtraArtifact> artifacts = new ArrayList<>();
		addIfPresent(artifacts, QUERY_RESULT_SCHEMA, queryResultJson(sanitized, request.getRuntimeRequestId()));
		addIfPresent(artifacts, CHART_CANDIDATE_SCHEMA, chartCandidateJson(sanitized));
		addIfPresent(artifacts, ANALYSIS_REPORT_SCHEMA, analysisReportJson(explain));
		return List.copyOf(artifacts);
	}

	private List<ReportDataSnapshot> sanitizeSnapshots(AnswerTraceExplainView explain) {
		List<ReportDataSnapshot> source = explain.getReportDataSnapshots();
		if (CollectionUtils.isEmpty(source)) {
			return List.of();
		}
		List<ReportDataSnapshot> sanitized = new ArrayList<>();
		for (ReportDataSnapshot snapshot : source) {
			if (sanitized.size() >= MAX_SNAPSHOTS) {
				break;
			}
			ReportDataSnapshot safe = sanitizeSnapshot(snapshot, explain);
			if (safe != null && !CollectionUtils.isEmpty(safe.getColumns())
					&& !CollectionUtils.isEmpty(safe.getRows())) {
				sanitized.add(safe);
			}
		}
		return List.copyOf(sanitized);
	}

	private ReportDataSnapshot sanitizeSnapshot(ReportDataSnapshot snapshot, AnswerTraceExplainView explain) {
		if (snapshot == null) {
			return null;
		}
		try {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("title", snapshot.getTitle());
			payload.put("summary", snapshot.getSummary());
			payload.put("columns", snapshot.getColumns() == null ? List.of() : snapshot.getColumns());
			payload.put("rows", snapshot.getRows() == null ? List.of() : snapshot.getRows());
			payload.put("totalRows", snapshot.getTotalRows());
			payload.put("truncated", snapshot.getTruncated());
			payload.put("ranking", snapshot.getRanking());
			String sanitizedJson = outputSanitizer.sanitizeResultSetJson(objectMapper.writeValueAsString(payload),
					explain);
			Map<String, Object> sanitized = objectMapper.readValue(sanitizedJson, MAP_TYPE);
			List<String> columns = stringList(sanitized.get("columns"));
			List<Map<String, Object>> rows = rowList(sanitized.get("rows"));
			if (columns.isEmpty() || rows.isEmpty()) {
				return null;
			}
			return ReportDataSnapshot.builder()
				.title(outputSanitizer.sanitizeText(textValue(sanitized.get("title"), snapshot.getTitle()), explain))
				.summary(outputSanitizer.sanitizeText(textValue(sanitized.get("summary"), snapshot.getSummary()),
						explain))
				.columns(columns)
				.rows(rows)
				.totalRows(snapshot.getTotalRows())
				.truncated(snapshot.getTruncated())
				.ranking(snapshot.getRanking())
				.build();
		}
		catch (Exception ex) {
			log.warn("结构化查询快照消毒失败, 跳过该快照. title={}", snapshot.getTitle(), ex);
			return null;
		}
	}

	private String queryResultJson(List<ReportDataSnapshot> snapshots, String runtimeRequestId) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("runtimeRequestId", runtimeRequestId);
		payload.put("snapshots", snapshots);
		return writeJson(payload);
	}

	private String chartCandidateJson(List<ReportDataSnapshot> snapshots) {
		List<ChartCandidate> candidates = chartCandidateBuilder.build(snapshots);
		if (CollectionUtils.isEmpty(candidates)) {
			return null;
		}
		List<Map<String, Object>> items = new ArrayList<>();
		for (ChartCandidate candidate : candidates) {
			if (candidate == null) {
				continue;
			}
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("title", candidate.getTitle());
			item.put("chartType", candidate.getChartType());
			item.put("dimensionName", candidate.getDimensionName());
			item.put("metricName", candidate.getMetricName());
			item.put("metricNames", candidate.getMetricNames());
			item.put("data", candidate.getData());
			item.put("note", candidate.getNote());
			chartCandidateBuilder.toEchartsOption(candidate).ifPresent(option -> item.put("echartsOption", option));
			items.add(item);
		}
		if (items.isEmpty()) {
			return null;
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("candidates", items);
		return writeJson(payload);
	}

	private String analysisReportJson(AnswerTraceExplainView explain) {
		try {
			String markdown = analysisReportService.generateDeterministicReport(explain);
			if (!StringUtils.hasText(markdown)) {
				return null;
			}
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("source", "deterministic");
			payload.put("markdown", outputSanitizer.sanitizeTextPreservingEcharts(markdown, explain));
			return writeJson(payload);
		}
		catch (RuntimeException ex) {
			log.warn("任务终态分析报告生成失败, 跳过 analysis-report 产物. runtimeRequestId={}",
					explain.getRuntimeRequestId(), ex);
			return null;
		}
	}

	private void addIfPresent(List<ExtraArtifact> artifacts, String schemaVersion, String json) {
		if (StringUtils.hasText(json)) {
			artifacts.add(new ExtraArtifact(schemaVersion, json));
		}
	}

	private String writeJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (Exception ex) {
			log.error("结构化交卷产物序列化失败, 跳过该产物. type={}", value == null ? "null" : value.getClass().getName(),
					ex);
			return null;
		}
	}

	private String textValue(Object value, String fallback) {
		if (value instanceof String text && StringUtils.hasText(text)) {
			return text;
		}
		return fallback;
	}

	private List<String> stringList(Object value) {
		if (!(value instanceof List<?> list) || list.isEmpty()) {
			return List.of();
		}
		List<String> columns = new ArrayList<>();
		for (Object item : list) {
			if (item instanceof String text && StringUtils.hasText(text)) {
				columns.add(text);
			}
			else if (item instanceof Map<?, ?> map && map.get("name") != null) {
				String name = String.valueOf(map.get("name"));
				if (StringUtils.hasText(name) && !"null".equals(name)) {
					columns.add(name);
				}
			}
		}
		return List.copyOf(columns);
	}

	private List<Map<String, Object>> rowList(Object value) {
		if (!(value instanceof List<?> list) || list.isEmpty()) {
			return List.of();
		}
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Object item : list) {
			if (item instanceof Map<?, ?> map) {
				Map<String, Object> row = new LinkedHashMap<>();
				map.forEach((key, cell) -> row.put(String.valueOf(key), cell));
				if (!row.isEmpty()) {
					rows.add(row);
				}
			}
		}
		return List.copyOf(rows);
	}

}
