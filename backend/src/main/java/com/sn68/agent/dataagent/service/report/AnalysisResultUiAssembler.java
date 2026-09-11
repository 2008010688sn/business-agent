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

import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.AnswerTraceExplainView;
import com.sn68.agent.dataagent.ui.AgentUiMessage;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * Packs InsightEngine findings into an {@code analysis-result} AgentUiMessage. Does not register execute MCP.
 */
final class AnalysisResultUiAssembler {

	static final String ACTION_DRILL = "DRILL";

	static final String ACTION_START_FLOW = "START_FLOW";

	static final String ACTION_ASK_WRITE = "ASK_WRITE";

	static final String ACTION_NONE = "NONE";

	private static final Pattern HIGHLIGHT = Pattern.compile("「([^」]{1,40})」");

	private final ReportSnapshotSelector snapshotSelector = new ReportSnapshotSelector();

	private final InsightEngine insightEngine = new InsightEngine();

	private final ReportNarrativeValidator narrativeValidator = new ReportNarrativeValidator();

	AgentUiMessage toMessage(AnswerTraceExplainView explain, String markdown, AnalysisUiOptions options) {
		AnalysisUiOptions safe = options == null ? AnalysisUiOptions.defaults() : options;
		ReportSnapshotSelector.Selection selection = snapshotSelector.select(explain);
		InsightEngine.InsightPack pack = insightEngine.analyze(selection, explain);
		List<Map<String, Object>> evidence = evidenceOf(selection, explain);
		List<Map<String, Object>> findings = findingsOf(pack);
		List<Map<String, Object>> rootCauses = rootCausesOf(pack, safe);
		Map<String, Object> slots = prefilledSlots(safe);
		List<Map<String, Object>> nextActions = nextActionsOf(safe, pack, slots, explain);
		List<AgentUiMessage.Action> actions = actionsOf(nextActions);
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("intent", safe.intent());
		values.put("evidence", evidence);
		values.put("findings", findings);
		values.put("rootCauses", rootCauses);
		values.put("nextActions", nextActions);
		if (StringUtils.hasText(safe.note())) {
			values.put("note", safe.note());
		}
		String text = markdown == null ? "" : markdown;
		return new AgentUiMessage(AgentUiMessage.SCHEMA_VERSION, AgentUiMessage.KIND_ANALYSIS_RESULT,
				explain == null ? null : explain.getRuntimeRequestId(),
				new AgentUiMessage.Source(explain == null ? null : explain.getAgentId(),
						explain == null ? null : explain.getRoutedSkillCode(), null, null),
				new AgentUiMessage.Content("markdown", text),
				new AgentUiMessage.Payload("ANALYSIS", values, List.of()), actions,
				new AgentUiMessage.Timing("ANALYSIS_DONE", null));
	}

	private List<Map<String, Object>> evidenceOf(ReportSnapshotSelector.Selection selection,
			AnswerTraceExplainView explain) {
		List<ReportDataSnapshot> snapshots = new ArrayList<>();
		if (selection != null && selection.primary().isPresent()) {
			snapshots.add(selection.primary().get());
		}
		if (explain != null && explain.getReportDataSnapshots() != null) {
			for (ReportDataSnapshot snapshot : explain.getReportDataSnapshots()) {
				if (snapshot != null && !snapshots.contains(snapshot)) {
					snapshots.add(snapshot);
				}
			}
		}
		List<Map<String, Object>> evidence = new ArrayList<>();
		for (ReportDataSnapshot snapshot : snapshots) {
			if (snapshot == null) {
				continue;
			}
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("evidenceId", evidenceId(snapshot));
			item.put("source", sourceOf(snapshot));
			String query = explain == null ? null : explain.getQuestion();
			if (StringUtils.hasText(query)) {
				item.put("query", query.trim());
			}
			String fileRef = fileRefOf(snapshot);
			if (StringUtils.hasText(fileRef)) {
				item.put("fileRef", fileRef);
			}
			if (snapshot.getCoverageStatus() != null) {
				item.put("coverage", snapshot.getCoverageStatus().name());
			}
			evidence.add(item);
		}
		return List.copyOf(evidence);
	}

	private List<Map<String, Object>> findingsOf(InsightEngine.InsightPack pack) {
		if (pack == null || pack.findings() == null) {
			return List.of();
		}
		List<Map<String, Object>> findings = new ArrayList<>();
		for (AnalysisFinding finding : pack.findings()) {
			if (finding == null || !StringUtils.hasText(finding.text())) {
				continue;
			}
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("text", finding.text());
			item.put("evidenceIds", List.copyOf(finding.evidenceIds()));
			item.put("numbers", finding.numbers().stream().map(this::plainNumber).toList());
			findings.add(item);
		}
		return List.copyOf(findings);
	}

	private List<Map<String, Object>> rootCausesOf(InsightEngine.InsightPack pack, AnalysisUiOptions options) {
		if (options.fileOnly() || pack == null || pack.rootCauses() == null) {
			return List.of();
		}
		List<Map<String, Object>> rootCauses = new ArrayList<>();
		for (AnalysisRootCause cause : narrativeValidator.retainCitedRootCauses(pack.rootCauses())) {
			if (cause == null || !StringUtils.hasText(cause.claim())) {
				continue;
			}
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("claim", cause.claim());
			item.put("evidenceIds", List.copyOf(cause.evidenceIds()));
			if (StringUtils.hasText(cause.confidence())) {
				item.put("confidence", cause.confidence());
			}
			if (StringUtils.hasText(cause.missingEvidence())) {
				item.put("missingEvidence", cause.missingEvidence());
			}
			rootCauses.add(item);
		}
		return List.copyOf(rootCauses);
	}

	private List<Map<String, Object>> nextActionsOf(AnalysisUiOptions options, InsightEngine.InsightPack pack,
			Map<String, Object> slots, AnswerTraceExplainView explain) {
		List<Map<String, Object>> nextActions = new ArrayList<>();
		String drillQuery = drillQuery(options, pack, slots, explain);
		if (StringUtils.hasText(drillQuery)) {
			Map<String, Object> drill = new LinkedHashMap<>();
			drill.put("type", ACTION_DRILL);
			drill.put("label", "按更细粒度继续分析");
			drill.put("query", drillQuery);
			if (StringUtils.hasText(options.grain())) {
				drill.put("grain", options.grain());
			}
			nextActions.add(drill);
		}
		for (String skillCode : options.nextFlows()) {
			Map<String, Object> startFlow = new LinkedHashMap<>();
			startFlow.put("type", ACTION_START_FLOW);
			startFlow.put("label", "按此办理 " + skillCode);
			startFlow.put("skillCode", skillCode);
			startFlow.put("slots", slots);
			startFlow.put("confirm", true);
			nextActions.add(startFlow);
		}
		for (String toolName : options.nextWriteTools()) {
			Map<String, Object> askWrite = new LinkedHashMap<>();
			askWrite.put("type", ACTION_ASK_WRITE);
			askWrite.put("label", "建议写操作 " + toolName);
			askWrite.put("toolName", toolName);
			askWrite.put("confirm", true);
			nextActions.add(askWrite);
		}
		if (!options.missing().isEmpty()) {
			Map<String, Object> none = new LinkedHashMap<>();
			none.put("type", ACTION_NONE);
			none.put("label", "待补充来源或键");
			none.put("missing", List.copyOf(options.missing()));
			nextActions.add(none);
		}
		return List.copyOf(nextActions);
	}

	private List<AgentUiMessage.Action> actionsOf(List<Map<String, Object>> nextActions) {
		List<AgentUiMessage.Action> actions = new ArrayList<>();
		int index = 0;
		for (Map<String, Object> nextAction : nextActions) {
			String type = String.valueOf(nextAction.get("type"));
			if (ACTION_NONE.equals(type)) {
				continue;
			}
			index++;
			String actionId = type.toLowerCase().replace('_', '-') + "-" + index;
			String label = String.valueOf(nextAction.getOrDefault("label", type));
			Object value = nextAction.get("skillCode");
			if (value == null) {
				value = nextAction.get("query");
			}
			if (value == null) {
				value = nextAction.get("toolName");
			}
			Map<String, Object> payload = new LinkedHashMap<>(nextAction);
			payload.remove("label");
			actions.add(new AgentUiMessage.Action(actionId, type, label, value, Map.copyOf(payload)));
		}
		return List.copyOf(actions);
	}

	private Map<String, Object> prefilledSlots(AnalysisUiOptions options) {
		if (options.prefilledSlots() != null && !options.prefilledSlots().isEmpty()) {
			return options.prefilledSlots();
		}
		return Map.of();
	}

	private String drillQuery(AnalysisUiOptions options, InsightEngine.InsightPack pack, Map<String, Object> slots,
			AnswerTraceExplainView explain) {
		String highlight = highlightOf(pack);
		String drill;
		if (StringUtils.hasText(options.grain()) && StringUtils.hasText(highlight)) {
			drill = "请在同一分析会话中按粒度「" + options.grain() + "」只看「" + highlight + "」的更细结果，保持只读，不要办理。";
		}
		else if (StringUtils.hasText(highlight)) {
			drill = "请在同一分析会话中只看「" + highlight + "」的更细粒度结果，保持只读，不要办理。";
		}
		else if (StringUtils.hasText(options.grain())) {
			drill = "请在同一分析会话中按粒度「" + options.grain() + "」继续下钻，保持只读，不要办理。";
		}
		else if (pack != null && ((pack.findings() != null && !pack.findings().isEmpty())
				|| (pack.kpiLines() != null && !pack.kpiLines().isEmpty()))) {
			drill = "请在同一分析会话中按更细粒度继续分析当前结果，保持只读，不要办理。";
		}
		else {
			return null;
		}
		return withOriginalQuestion(drill, explain);
	}

	private String withOriginalQuestion(String drill, AnswerTraceExplainView explain) {
		if (!StringUtils.hasText(drill) || explain == null || !StringUtils.hasText(explain.getQuestion())) {
			return drill;
		}
		String original = explain.getQuestion().trim();
		if (drill.contains(original)) {
			return drill;
		}
		return drill + "原问题：" + original;
	}

	private String highlightOf(InsightEngine.InsightPack pack) {
		if (pack == null) {
			return null;
		}
		for (String line : pack.factLines()) {
			String highlight = firstHighlight(line);
			if (highlight != null) {
				return highlight;
			}
		}
		for (AnalysisFinding finding : pack.findings()) {
			String highlight = firstHighlight(finding == null ? null : finding.text());
			if (highlight != null) {
				return highlight;
			}
		}
		return null;
	}

	private String firstHighlight(String text) {
		if (!StringUtils.hasText(text)) {
			return null;
		}
		Matcher matcher = HIGHLIGHT.matcher(text);
		return matcher.find() ? matcher.group(1) : null;
	}

	private String evidenceId(ReportDataSnapshot snapshot) {
		if (snapshot == null) {
			return "snapshot";
		}
		if (StringUtils.hasText(snapshot.getTitle())) {
			return "snapshot:" + snapshot.getTitle().trim();
		}
		return "snapshot-primary";
	}

	private String sourceOf(ReportDataSnapshot snapshot) {
		if (snapshot != null && StringUtils.hasText(snapshot.getTitle())) {
			return snapshot.getTitle().trim();
		}
		return "snapshot-primary";
	}

	private String fileRefOf(ReportDataSnapshot snapshot) {
		if (snapshot == null || !StringUtils.hasText(snapshot.getTitle())) {
			return null;
		}
		String title = snapshot.getTitle().trim();
		String lower = title.toLowerCase();
		if (lower.endsWith(".xlsx") || lower.endsWith(".xls") || lower.endsWith(".csv") || lower.endsWith(".pdf")
				|| lower.endsWith(".docx") || title.contains("附件") || title.contains("文件")) {
			return title;
		}
		return null;
	}

	private String plainNumber(BigDecimal value) {
		return value == null ? "" : value.stripTrailingZeros().toPlainString();
	}

}
