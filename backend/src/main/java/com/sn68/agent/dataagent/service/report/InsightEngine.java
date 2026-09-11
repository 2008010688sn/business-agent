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
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.AnswerTraceExplainView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * 基于权威快照一遍扫描产出确定性洞察，供报告填空，不调用模型。
 */
final class InsightEngine {

	static final String NO_SNAPSHOT = "NO_SNAPSHOT";

	static final String SINGLE_ROW_DETAIL = "SINGLE_ROW_DETAIL";

	static final String NO_NUMERIC_METRIC = "NO_NUMERIC_METRIC";

	static final int MAX_ACTION_DIRECTIONS = 3;

	record InsightPack(List<String> factLines, List<String> kpiLines, List<String> interpretationLines,
			List<String> riskLines, List<String> actionLines, String skipReason, String chartSkipReason,
			List<AnalysisFinding> findings, List<AnalysisRootCause> rootCauses) {

		InsightPack(List<String> factLines, List<String> kpiLines, List<String> interpretationLines,
				List<String> riskLines, List<String> actionLines, String skipReason, String chartSkipReason) {
			this(factLines, kpiLines, interpretationLines, riskLines, actionLines, skipReason, chartSkipReason,
					List.of(), List.of());
		}
	}

	private final ReportNarrativeValidator narrativeValidator = new ReportNarrativeValidator();

	InsightPack analyze(ReportSnapshotSelector.Selection selection, AnswerTraceExplainView explain) {
		if (selection == null || selection.primary().isEmpty()) {
			return cite(emptyPack(NO_SNAPSHOT), null);
		}
		ReportDataSnapshot snapshot = selection.primary().get();
		List<String> numericColumns = numericColumns(snapshot);
		if (numericColumns.isEmpty()) {
			return cite(emptyPack(NO_NUMERIC_METRIC), snapshot);
		}
		if (snapshot.getRows().size() == 1 && numericColumns.size() == 1 && !hasBusinessMetricName(numericColumns.get(0))) {
			return cite(detailPack(snapshot, numericColumns.get(0)), snapshot);
		}
		List<String> preferredMetrics = profileMetrics(explain == null ? null : explain.getSkillReportProfile());
		return cite(summarize(snapshot, ReportColumnSemantics.orderMetrics(numericColumns, preferredMetrics),
				explain == null ? null : explain.getSkillReportProfile()), snapshot);
	}

	private InsightPack cite(InsightPack pack, ReportDataSnapshot snapshot) {
		String evidenceId = evidenceId(snapshot);
		Set<BigDecimal> evidenceNumbers = evidenceNumbers(snapshot);
		List<AnalysisFinding> findings = new ArrayList<>();
		for (String line : pack.kpiLines()) {
			findings.add(new AnalysisFinding(line, List.of(evidenceId),
					narrativeValidator.businessNumbersIn(line)));
		}
		for (String line : pack.factLines()) {
			findings.add(new AnalysisFinding(line, List.of(evidenceId),
					narrativeValidator.businessNumbersIn(line)));
		}
		List<AnalysisRootCause> rootCauses = new ArrayList<>();
		rootCauses.add(new AnalysisRootCause("可能存在尚未核对的口径差异。", List.of(), "low", "missingEvidence"));
		for (String risk : pack.riskLines()) {
			if (StringUtils.hasText(risk) && !risk.contains("未见需单独提示")) {
				rootCauses.add(new AnalysisRootCause(risk, List.of(evidenceId), "medium", null));
			}
		}
		return new InsightPack(pack.factLines(), pack.kpiLines(), pack.interpretationLines(), pack.riskLines(),
				pack.actionLines(), pack.skipReason(), pack.chartSkipReason(),
				narrativeValidator.retainEvidencedFindings(findings, evidenceNumbers),
				narrativeValidator.retainCitedRootCauses(rootCauses));
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

	private Set<BigDecimal> evidenceNumbers(ReportDataSnapshot snapshot) {
		Set<BigDecimal> values = new LinkedHashSet<>();
		if (snapshot == null) {
			return values;
		}
		for (Map<String, Object> row : snapshot.getRows()) {
			for (Object cell : row.values()) {
				addEvidence(values, decimal(cell));
			}
		}
		if (snapshot.getReturnedRows() != null) {
			addEvidence(values, BigDecimal.valueOf(snapshot.getReturnedRows()));
		}
		List<String> metrics = numericColumns(snapshot);
		String dimension = ReportColumnSemantics.pickDimension(snapshot.getColumns(), snapshot.getRows(), metrics, List.of());
		for (String metric : metrics) {
			MetricStats stats = stats(snapshot, dimension, metric);
			addEvidence(values, stats.sum());
			addEvidence(values, stats.avg());
			addEvidence(values, stats.min());
			addEvidence(values, stats.max());
			addEvidence(values, stats.topShare());
			addEvidence(values, stats.top3Share());
			addEvidence(values, stats.firstValue());
			addEvidence(values, stats.lastValue());
			addEvidence(values, stats.delta());
		}
		return values;
	}

	private void addEvidence(Set<BigDecimal> values, BigDecimal number) {
		if (number != null) {
			values.add(number);
		}
	}

	private InsightPack summarize(ReportDataSnapshot snapshot, List<String> metrics, SkillReportProfile profile) {
		String dimension = ReportColumnSemantics.pickDimension(snapshot.getColumns(), snapshot.getRows(), metrics,
				profile == null ? List.of() : profile.getDimensions());
		Map<String, MetricStats> statsByMetric = new LinkedHashMap<>();
		for (String metric : metrics) {
			statsByMetric.put(metric, stats(snapshot, dimension, metric));
		}
		String mainMetric = ReportColumnSemantics.pickMainMetric(metrics,
				profile == null ? List.of() : profile.getMetrics());
		MetricStats main = statsByMetric.getOrDefault(mainMetric, MetricStats.empty());
		int returned = snapshot.getReturnedRows() == null ? snapshot.getRows().size() : snapshot.getReturnedRows();
		List<String> kpiLines = new ArrayList<>();
		kpiLines.add("返回结果 " + returned + " 条" + coverageSuffix(snapshot));
		for (String metric : metrics) {
			MetricStats stats = statsByMetric.get(metric);
			kpiLines.add(metric + "：合计=" + format(stats.sum()) + "，均值=" + format(stats.avg()) + "，最小="
					+ format(stats.min()) + "，最大=" + format(stats.max()));
		}
		String chartSkip = snapshot.getRows().size() < 2 && metrics.size() < 2 ? SINGLE_ROW_DETAIL : "";
		return new InsightPack(buildFacts(main, mainMetric, dimension), kpiLines,
				buildInterpretation(main, mainMetric, dimension, metrics.size()),
				buildRisks(snapshot, main, mainMetric, returned), buildActions(profile, snapshot, main, mainMetric), "",
				chartSkip);
	}

	private List<String> buildFacts(MetricStats main, String mainMetric, String dimension) {
		List<String> facts = new ArrayList<>();
		String dim = StringUtils.hasText(dimension) ? dimension : "分类";
		if (StringUtils.hasText(main.topName())) {
			String fact = "「" + main.topName() + "」的" + mainMetric + "最高（" + format(main.max()) + "）";
			if (main.topShare() != null) {
				fact += "，占合计 " + format(main.topShare()) + "%";
			}
			facts.add(fact + "。");
		}
		if (main.top3Share() != null) {
			facts.add("按" + dim + "看，前 3 项合计占" + mainMetric + "的 " + format(main.top3Share()) + "%。");
		}
		if (main.delta() != null && StringUtils.hasText(main.firstName()) && StringUtils.hasText(main.lastName())) {
			facts.add(mainMetric + " 从 " + main.firstName() + " 的 " + format(main.firstValue()) + " 变化到 "
					+ main.lastName() + " 的 " + format(main.lastValue()) + "。");
		}
		return facts;
	}

	private List<String> buildInterpretation(MetricStats main, String mainMetric, String dimension, int metricCount) {
		List<String> interpretation = new ArrayList<>();
		String dim = StringUtils.hasText(dimension) ? dimension : "分类";
		if (StringUtils.hasText(main.topName()) && main.topShare() != null) {
			interpretation.add("「" + main.topName() + "」贡献了 " + format(main.topShare()) + "% 的" + mainMetric
					+ "，显著高于其他" + dim + "。");
		}
		else if (StringUtils.hasText(main.topName())) {
			interpretation.add("「" + main.topName() + "」的" + mainMetric + "最高（" + format(main.max()) + "），「"
					+ (StringUtils.hasText(main.bottomName()) ? main.bottomName() : "其余项") + "」相对偏低。");
		}
		if (main.top3Share() != null) {
			interpretation.add("结果高度集中：前 3 项合计占" + mainMetric + "的 " + format(main.top3Share()) + "%。");
		}
		if (ReportColumnSemantics.looksLikeTestName(main.topName())) {
			interpretation.add("「" + main.topName() + "」名称像测试或演示数据，计入汇总会放大集中度，需要按正式口径确认。");
		}
		if (main.delta() != null && StringUtils.hasText(main.firstName()) && StringUtils.hasText(main.lastName())) {
			interpretation.add("按时间顺序观察，" + mainMetric + "从 " + main.firstName() + " 到 " + main.lastName()
					+ " 的变化量为 " + format(main.delta()) + "。");
		}
		if (interpretation.isEmpty()) {
			interpretation.add("已按本轮结构化结果汇总 " + metricCount + " 个指标，详见关键指标。");
		}
		return interpretation;
	}

	private List<String> buildRisks(ReportDataSnapshot snapshot, MetricStats main, String mainMetric, int returned) {
		List<String> risks = new ArrayList<>();
		if (snapshot.getCoverageStatus() == ResultCoverageStatus.PLATFORM_LIMITED) {
			risks.add("结果达到平台单次查询上限，仅覆盖前 " + returned + " 条，完整范围可能更大。");
		}
		else if (snapshot.getCoverageStatus() == ResultCoverageStatus.LIMIT_REACHED_UNKNOWN) {
			risks.add("本次结果达到 " + returned + " 条限制，无法确认限制之后是否还有数据。");
		}
		if (main.topShare() != null && main.topShare().compareTo(new BigDecimal("50")) >= 0
				&& StringUtils.hasText(main.topName())) {
			risks.add("「" + main.topName() + "」占" + mainMetric + "的 " + format(main.topShare()) + "%，存在单点依赖。");
		}
		else if (main.top3Share() != null && main.top3Share().compareTo(new BigDecimal("50")) >= 0) {
			risks.add("前 3 项合计占" + mainMetric + "的 " + format(main.top3Share()) + "%，集中度偏高。");
		}
		if (ReportColumnSemantics.looksLikeTestName(main.topName()) && main.topShare() != null
				&& main.topShare().compareTo(new BigDecimal("30")) >= 0) {
			risks.add("「" + main.topName() + "」名称像测试或演示数据且占比较高，正式口径可能被放大。");
		}
		if (risks.isEmpty()) {
			risks.add("本次结果未见需单独提示的数据风险。");
		}
		return risks;
	}

	private List<String> buildActions(SkillReportProfile profile, ReportDataSnapshot snapshot, MetricStats main,
			String mainMetric) {
		List<String> actions = new ArrayList<>();
		if (profile != null && profile.getActionDirections() != null && !profile.getActionDirections().isEmpty()) {
			appendSkillActionLines(actions, profile.getActionDirections(), main.topName());
		}
		else if (ReportColumnSemantics.looksLikeTestName(main.topName())) {
			actions.add("确认「" + main.topName() + "」是否属于正式业务口径；若为测试或演示数据，建议剔除后重新汇总。");
		}
		else if (StringUtils.hasText(main.topName())) {
			actions.add("优先复核「" + main.topName() + "」的" + mainMetric + "及构成，确认异常值是否应计入本期。");
		}
		else {
			actions.add("按关键指标复核异常值，并结合覆盖范围判断结论是否完整。");
		}
		if (snapshot.getCoverageStatus() == ResultCoverageStatus.PLATFORM_LIMITED
				|| snapshot.getCoverageStatus() == ResultCoverageStatus.LIMIT_REACHED_UNKNOWN) {
			actions.add("本次结果受查询上限影响，扩大范围后再对比。");
		}
		return actions;
	}

	// 首条建议保留既有“优先关注头部项”提示，其余按原句补齐；整体最多取 MAX_ACTION_DIRECTIONS 条。
	private void appendSkillActionLines(List<String> actions, List<String> directions, String topName) {
		int appended = 0;
		for (String direction : directions) {
			if (appended >= MAX_ACTION_DIRECTIONS) {
				break;
			}
			if (!StringUtils.hasText(direction)) {
				continue;
			}
			if (appended == 0 && StringUtils.hasText(topName)) {
				actions.add(direction + "，优先关注「" + topName + "」。");
			}
			else {
				actions.add(direction + "。");
			}
			appended++;
		}
	}

	private List<String> profileMetrics(SkillReportProfile profile) {
		return profile == null || profile.getMetrics() == null ? List.of() : profile.getMetrics();
	}

	private InsightPack detailPack(ReportDataSnapshot snapshot, String metric) {
		List<String> kpi = new ArrayList<>();
		kpi.add(metric + "=" + format(decimal(snapshot.getRows().get(0).get(metric))));
		return new InsightPack(List.of(), kpi, List.of("本次结果是单条明细，不适合做分类或趋势对比。"),
				List.of("本次结果未见需单独提示的数据风险。"), List.of("如需对比或趋势，请补充可聚合的结构化结果。"),
				SINGLE_ROW_DETAIL, SINGLE_ROW_DETAIL);
	}

	private InsightPack emptyPack(String reason) {
		String message = switch (reason) {
			case NO_NUMERIC_METRIC -> "本轮结构化结果没有可汇总的数值指标。";
			default -> "本轮没有可确认的结构化结果。";
		};
		return new InsightPack(List.of(), List.of(message), List.of(message + "无法给出业务解读。"),
				List.of("本次结果未见需单独提示的数据风险。"), List.of("补充可查询的结构化结果后重新生成报告。"),
				reason, reason);
	}

	private MetricStats stats(ReportDataSnapshot snapshot, String dimension, String metric) {
		List<NamedValue> values = new ArrayList<>();
		for (Map<String, Object> row : snapshot.getRows()) {
			BigDecimal number = decimal(row.get(metric));
			if (number == null) {
				continue;
			}
			String name = StringUtils.hasText(dimension) ? display(row.get(dimension)) : "";
			values.add(new NamedValue(name, number));
		}
		if (values.isEmpty()) {
			return MetricStats.empty();
		}
		BigDecimal sum = values.stream().map(NamedValue::value).reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal min = values.stream().map(NamedValue::value).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
		BigDecimal max = values.stream().map(NamedValue::value).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
		BigDecimal avg = sum.divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
		NamedValue top = values.stream().max(Comparator.comparing(NamedValue::value)).orElse(values.get(0));
		NamedValue bottom = values.stream().min(Comparator.comparing(NamedValue::value)).orElse(values.get(0));
		BigDecimal topShare = null;
		BigDecimal top3Share = null;
		if (sum.compareTo(BigDecimal.ZERO) > 0 && values.size() >= 2
				&& values.stream().allMatch(item -> item.value().compareTo(BigDecimal.ZERO) >= 0)) {
			topShare = top.value().multiply(new BigDecimal("100")).divide(sum, 1, RoundingMode.HALF_UP);
			BigDecimal top3 = values.stream()
				.sorted(Comparator.comparing(NamedValue::value).reversed())
				.limit(3)
				.map(NamedValue::value)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
			top3Share = top3.multiply(new BigDecimal("100")).divide(sum, 1, RoundingMode.HALF_UP);
		}
		NamedValue first = values.get(0);
		NamedValue last = values.get(values.size() - 1);
		BigDecimal delta = values.size() >= 2 && looksLikeTime(first.name()) && looksLikeTime(last.name())
				? last.value().subtract(first.value()) : null;
		return new MetricStats(sum, avg, min, max, top.name(), bottom.name(), topShare, top3Share, first.name(),
				first.value(), last.name(), last.value(), delta);
	}

	private List<String> numericColumns(ReportDataSnapshot snapshot) {
		List<String> columns = new ArrayList<>();
		for (String column : snapshot.getColumns()) {
			if (ReportColumnSemantics.isIdentifierColumn(column)) {
				continue;
			}
			long checked = snapshot.getRows().stream().filter(row -> row.get(column) != null).limit(8).count();
			if (checked == 0) {
				continue;
			}
			long numeric = snapshot.getRows()
				.stream()
				.map(row -> row.get(column))
				.filter(value -> value != null)
				.limit(8)
				.filter(value -> decimal(value) != null)
				.count();
			if (numeric == checked) {
				columns.add(column);
			}
		}
		return columns;
	}

	private String coverageSuffix(ReportDataSnapshot snapshot) {
		if (snapshot.getCoverageStatus() == null) {
			return "";
		}
		return switch (snapshot.getCoverageStatus()) {
			case FULL -> "，已完整覆盖本次请求";
			case SOURCE_SHORT -> "，已覆盖当前查询范围内的全部结果";
			case PLATFORM_LIMITED -> "，受平台上限截断";
			case LIMIT_REACHED_UNKNOWN -> "，达到限制且后续未知";
		};
	}

	private boolean hasBusinessMetricName(String column) {
		return StringUtils.hasText(column) && !ReportColumnSemantics.isGenericMetricName(column)
				&& !ReportColumnSemantics.isIdentifierColumn(column);
	}

	private boolean looksLikeTime(String value) {
		return value != null && (value.matches("\\d{4}-\\d{1,2}(?:-\\d{1,2})?.*")
				|| value.matches("\\d{4}/\\d{1,2}(?:/\\d{1,2})?.*"));
	}

	private BigDecimal decimal(Object value) {
		if (value instanceof Number number) {
			return new BigDecimal(number.toString());
		}
		String text = display(value).replace(",", "").replace("%", "").trim();
		if (!StringUtils.hasText(text)) {
			return null;
		}
		try {
			return new BigDecimal(text);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private String format(BigDecimal value) {
		return value == null ? "" : value.stripTrailingZeros().toPlainString();
	}

	private String display(Object value) {
		return value == null ? "" : String.valueOf(value).trim();
	}

	private record NamedValue(String name, BigDecimal value) {
	}

	private record MetricStats(BigDecimal sum, BigDecimal avg, BigDecimal min, BigDecimal max, String topName,
			String bottomName, BigDecimal topShare, BigDecimal top3Share, String firstName, BigDecimal firstValue,
			String lastName, BigDecimal lastValue, BigDecimal delta) {

		static MetricStats empty() {
			return new MetricStats(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "", "", null,
					null, "", null, "", null, null);
		}

	}

}
