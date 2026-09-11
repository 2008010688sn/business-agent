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

import com.sn68.agent.dataagent.properties.DataAgentProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * 图表候选构建器：从结构化查询结果推导可行的图表候选列表。
 */
@Component
@RequiredArgsConstructor
public class ChartCandidateBuilder {

	private static final String OTHER_NAME = "其他";

	private final DataAgentProperties properties;

	public List<ChartCandidate> build(List<ReportDataSnapshot> snapshots) {
		if (CollectionUtils.isEmpty(snapshots) || !properties.getReportVisualization().isEnabled()) {
			return List.of();
		}
		List<ChartCandidate> candidates = new ArrayList<>();
		for (ReportDataSnapshot snapshot : snapshots) {
			if (snapshot == null || CollectionUtils.isEmpty(snapshot.getColumns())
					|| CollectionUtils.isEmpty(snapshot.getRows())) {
				continue;
			}
			buildOne(snapshot).ifPresent(candidates::add);
			if (candidates.size() >= properties.getReportVisualization().getMaxCharts()) {
				break;
			}
		}
		return List.copyOf(candidates);
	}

	public Optional<Map<String, Object>> toEchartsOption(ChartCandidate candidate) {
		if (candidate == null || CollectionUtils.isEmpty(candidate.getData())
				|| !StringUtils.hasText(candidate.getChartType())) {
			return Optional.empty();
		}
		return switch (candidate.getChartType()) {
			case "line" -> Optional.of(lineOption(candidate));
			case "pie" -> Optional.of(pieOption(candidate));
			case "bar-multi" -> Optional.of(multiBarOption(candidate));
			case "bar" -> Optional.of(barOption(candidate));
			default -> Optional.empty();
		};
	}

	private Optional<ChartCandidate> buildOne(ReportDataSnapshot snapshot) {
		List<String> columns = snapshot.getColumns();
		List<String> numericColumns = ReportColumnSemantics.orderMetrics(columns.stream()
			.filter(column -> !isDisplayDimensionColumn(column))
			.filter(column -> isNumericColumn(snapshot.getRows(), column))
			.filter(this::isUsefulMetricName)
			.toList(), List.of());
		if (numericColumns.isEmpty()) {
			return Optional.empty();
		}
		if (snapshot.getRows().size() == 1) {
			return numericColumns.size() > 1 && hasBusinessMetricNames(numericColumns)
					? buildMetricNameBar(snapshot, numericColumns) : Optional.empty();
		}
		String dimension = ReportColumnSemantics.pickDimension(columns, snapshot.getRows(), numericColumns, List.of());
		if (!StringUtils.hasText(dimension)) {
			Optional<String> timeColumn = columns.stream()
				.filter(column -> !numericColumns.contains(column))
				.filter(column -> isTimeColumn(snapshot.getRows(), column))
				.filter(column -> hasMultipleDistinctTimeValues(snapshot.getRows(), column))
				.findFirst();
			return timeColumn.flatMap(column -> buildLine(snapshot, column, numericColumns.get(0)));
		}
		if (numericColumns.size() > 1) {
			return buildMultiBar(snapshot, dimension, numericColumns);
		}
		if (!Boolean.TRUE.equals(snapshot.getRanking()) && shouldUsePie(snapshot, dimension, numericColumns.get(0))) {
			return buildPie(snapshot, dimension, numericColumns.get(0));
		}
		return buildBar(snapshot, dimension, numericColumns.get(0));
	}

	private Optional<ChartCandidate> buildMetricNameBar(ReportDataSnapshot snapshot, List<String> metrics) {
		Map<String, Object> row = snapshot.getRows().get(0);
		List<Map<String, Object>> data = metrics.stream()
			.map(metric -> dataPoint(metric, "统计值", parseNumber(row.get(metric))))
			.filter(point -> point.get("统计值") instanceof Number)
			.toList();
		if (data.size() < 2) {
			return Optional.empty();
		}
		return Optional.of(ChartCandidate.builder()
			.title(defaultTitle(snapshot, "统计项对比"))
			.chartType("bar")
			.dimensionName("统计项")
			.metricName("统计值")
			.metricNames(List.of("统计值"))
			.data(data)
			.note("基于本次已返回结果生成统计项对比图。")
			.build());
	}

	private Optional<ChartCandidate> buildLine(ReportDataSnapshot snapshot, String dimension, String metric) {
		List<Map<String, Object>> data = snapshot.getRows()
			.stream()
			.filter(row -> StringUtils.hasText(displayValue(row.get(dimension))) && parseNumber(row.get(metric)) != null)
			.collect(LinkedHashMap<String, BigDecimal>::new,
					(map, row) -> map.merge(normalizeTimeValue(row.get(dimension)), parseNumber(row.get(metric)),
							BigDecimal::add),
					Map::putAll)
			.entrySet()
			.stream()
			.sorted(Map.Entry.comparingByKey())
			.limit(properties.getReportVisualization().getMaxTrendPoints())
			.map(entry -> dataPoint(entry.getKey(), metric, entry.getValue()))
			.toList();
		if (data.size() < 2) {
			return Optional.empty();
		}
		return Optional.of(ChartCandidate.builder()
			.title(defaultTitle(snapshot, metric + "趋势"))
			.chartType("line")
			.dimensionName(dimension)
			.metricName(metric)
			.metricNames(List.of(metric))
			.data(data)
			.note("基于本次已返回结果生成趋势图。")
			.build());
	}

	private Optional<ChartCandidate> buildBar(ReportDataSnapshot snapshot, String dimension, String metric) {
		List<Map<String, Object>> data = topNWithOther(snapshot, dimension, List.of(metric));
		if (data.size() < 2) {
			return Optional.empty();
		}
		return Optional.of(ChartCandidate.builder()
			.title(defaultTitle(snapshot, metric + "对比"))
			.chartType("bar")
			.dimensionName(dimension)
			.metricName(metric)
			.metricNames(List.of(metric))
			.data(data)
			.note("基于本次已返回结果生成分类对比图。")
			.build());
	}

	private Optional<ChartCandidate> buildPie(ReportDataSnapshot snapshot, String dimension, String metric) {
		List<Map<String, Object>> data = topNWithOther(snapshot, dimension, List.of(metric));
		if (data.size() < 2) {
			return Optional.empty();
		}
		return Optional.of(ChartCandidate.builder()
			.title(defaultTitle(snapshot, metric + "占比"))
			.chartType("pie")
			.dimensionName(dimension)
			.metricName(metric)
			.metricNames(List.of(metric))
			.data(data)
			.note("基于本次已返回结果生成占比图。")
			.build());
	}

	private Optional<ChartCandidate> buildMultiBar(ReportDataSnapshot snapshot, String dimension,
			List<String> metrics) {
		List<String> keptMetrics = metrics.stream()
			.limit(properties.getReportVisualization().getMaxMetricsPerChart())
			.toList();
		List<Map<String, Object>> data = topNWithOther(snapshot, dimension, keptMetrics);
		if (data.size() < 2 || keptMetrics.size() < 2) {
			return Optional.empty();
		}
		return Optional.of(ChartCandidate.builder()
			.title(defaultTitle(snapshot, "多指标对比"))
			.chartType("bar-multi")
			.dimensionName(dimension)
			.metricNames(keptMetrics)
			.data(data)
			.note("基于本次已返回结果生成多指标对比图。")
			.build());
	}

	private List<Map<String, Object>> topNWithOther(ReportDataSnapshot snapshot, String dimension,
			List<String> metrics) {
		if (Boolean.TRUE.equals(snapshot.getRanking())) {
			return rankingData(snapshot.getRows(), dimension, metrics);
		}
		List<Map<String, Object>> data = aggregateByDimension(snapshot.getRows(), dimension, metrics).stream()
			.sorted(Comparator.comparing(row -> totalMetric(row, metrics), Comparator.reverseOrder()))
			.toList();
		int topN = properties.getReportVisualization().getTopN();
		if (data.size() <= topN) {
			return data;
		}
		List<Map<String, Object>> kept = new ArrayList<>(data.subList(0, topN));
		Map<String, Object> other = new LinkedHashMap<>();
		other.put("name", OTHER_NAME);
		for (String metric : metrics) {
			BigDecimal sum = data.subList(topN, data.size())
				.stream()
				.map(row -> parseNumber(row.get(metric)))
				.filter(value -> value != null)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
			other.put(metric, sum);
		}
		kept.add(other);
		return List.copyOf(kept);
	}

	private List<Map<String, Object>> rankingData(List<Map<String, Object>> rows, String dimension,
			List<String> metrics) {
		List<Map<String, Object>> data = new ArrayList<>();
		for (Map<String, Object> row : rows) {
			String name = displayValue(row.get(dimension));
			if (!StringUtils.hasText(name)) {
				continue;
			}
			Map<String, Object> point = new LinkedHashMap<>();
			point.put("name", name);
			for (String metric : metrics) {
				BigDecimal value = parseNumber(row.get(metric));
				if (value != null) {
					point.put(metric, value);
				}
			}
			if (point.size() > 1) {
				data.add(point);
			}
		}
		return List.copyOf(data);
	}

	private List<Map<String, Object>> aggregateByDimension(List<Map<String, Object>> rows, String dimension,
			List<String> metrics) {
		Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
		for (Map<String, Object> row : rows) {
			String name = displayValue(row.get(dimension));
			if (!StringUtils.hasText(name)) {
				continue;
			}
			Map<String, Object> point = grouped.computeIfAbsent(name, key -> {
				Map<String, Object> value = new LinkedHashMap<>();
				value.put("name", key);
				return value;
			});
			for (String metric : metrics) {
				BigDecimal value = parseNumber(row.get(metric));
				if (value != null) {
					BigDecimal current = parseNumber(point.get(metric));
					point.put(metric, current == null ? value : current.add(value));
				}
			}
		}
		return grouped.values()
			.stream()
			.filter(row -> metrics.stream().anyMatch(metric -> row.get(metric) instanceof Number))
			.toList();
	}

	private Map<String, Object> dataPoint(String name, String metric, BigDecimal value) {
		Map<String, Object> point = new LinkedHashMap<>();
		point.put("name", name);
		point.put(metric, value);
		return point;
	}

	private BigDecimal totalMetric(Map<String, Object> row, List<String> metrics) {
		return metrics.stream()
			.map(metric -> parseNumber(row.get(metric)))
			.filter(value -> value != null)
			.reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	private Map<String, Object> barOption(ChartCandidate candidate) {
		String metric = candidate.getMetricName();
		String seriesName = displayMetricName(metric);
		Map<String, Object> option = axisOption(candidate);
		option.put("series",
				List.of(Map.of("name", seriesName, "type", "bar", "data", values(candidate, metric))));
		return option;
	}

	private Map<String, Object> lineOption(ChartCandidate candidate) {
		String metric = candidate.getMetricName();
		String seriesName = displayMetricName(metric);
		return Map.of("title", Map.of("text", candidate.getTitle()), "tooltip", Map.of("trigger", "axis"), "grid",
				axisGrid(), "xAxis", Map.of("type", "category", "data", names(candidate)), "yAxis",
				Map.of("type", "value"), "series",
				List.of(Map.of("name", seriesName, "type", "line", "smooth", true, "data", values(candidate, metric))));
	}

	private Map<String, Object> pieOption(ChartCandidate candidate) {
		String metric = candidate.getMetricName();
		String seriesName = displayMetricName(metric);
		List<Map<String, Object>> seriesData = candidate.getData()
			.stream()
			.map(row -> Map.of("name", row.get("name"), "value", row.get(metric)))
			.toList();
		return Map.of("title", Map.of("text", candidate.getTitle()), "tooltip", Map.of("trigger", "item"), "legend",
				Map.of("type", "scroll", "orient", "vertical", "right", 0, "top", 30, "bottom", 20), "series",
				List.of(Map.of("name", seriesName, "type", "pie", "radius", "60%", "data", seriesData)));
	}

	private Map<String, Object> multiBarOption(ChartCandidate candidate) {
		List<Map<String, Object>> series = candidate.getMetricNames()
			.stream()
			.map(metric -> Map.of("name", displayMetricName(metric), "type", "bar", "data", values(candidate, metric)))
			.toList();
		Map<String, Object> option = axisOption(candidate);
		option.put("legend", Map.of("type", "scroll"));
		option.put("series", series);
		return option;
	}

	private Map<String, Object> axisOption(ChartCandidate candidate) {
		Map<String, Object> option = new LinkedHashMap<>();
		option.put("title", Map.of("text", candidate.getTitle()));
		option.put("tooltip", Map.of("trigger", "axis"));
		option.put("grid", axisGrid());
		option.put("xAxis", Map.of("type", "category", "data", names(candidate)));
		option.put("yAxis", Map.of("type", "value"));
		if (candidate.getData().size() > properties.getReportVisualization().getTopN()) {
			option.put("dataZoom", List.of(Map.of("type", "inside"),
					Map.of("type", "slider", "start", 0, "end", Math.max(5,
							properties.getReportVisualization().getTopN() * 100 / candidate.getData().size()))));
		}
		return option;
	}

	private Map<String, Object> axisGrid() {
		return Map.of("left", 24, "right", 24, "bottom", 40, "containLabel", true);
	}

	private String displayMetricName(String metric) {
		return ReportColumnSemantics.isGenericMetricName(metric) ? "统计值" : metric;
	}

	private List<Object> names(ChartCandidate candidate) {
		return candidate.getData().stream().map(row -> row.get("name")).toList();
	}

	private List<Object> values(ChartCandidate candidate, String metric) {
		return candidate.getData().stream().map(row -> row.get(metric)).toList();
	}

	private boolean isNumericColumn(List<Map<String, Object>> rows, String column) {
		long checked = rows.stream().filter(row -> row.get(column) != null).limit(8).count();
		if (checked == 0) {
			return false;
		}
		long numeric = rows.stream()
			.map(row -> row.get(column))
			.filter(value -> value != null)
			.limit(8)
			.filter(value -> parseNumber(value) != null)
			.count();
		return numeric == checked;
	}

	private boolean isDisplayDimensionColumn(String column) {
		if (!StringUtils.hasText(column)) {
			return false;
		}
		String normalized = column.toLowerCase(Locale.ROOT);
		return normalized.startsWith("维度") || normalized.startsWith("时间") || normalized.contains("type")
				|| normalized.contains("status") || normalized.contains("category") || normalized.contains("类型")
				|| normalized.contains("状态") || normalized.contains("分类");
	}

	private boolean isTimeColumn(List<Map<String, Object>> rows, String column) {
		long checked = rows.stream().filter(row -> StringUtils.hasText(displayValue(row.get(column)))).limit(8).count();
		if (checked == 0) {
			return false;
		}
		long timeLike = rows.stream()
			.map(row -> row.get(column))
			.filter(value -> StringUtils.hasText(displayValue(value)))
			.limit(8)
			.filter(value -> parseDateLike(displayValue(value)).isPresent())
			.count();
		return timeLike == checked;
	}

	private boolean hasMultipleDistinctTimeValues(List<Map<String, Object>> rows, String column) {
		return rows.stream()
			.map(row -> normalizeTimeValue(row.get(column)))
			.filter(StringUtils::hasText)
			.distinct()
			.limit(2)
			.count() >= 2;
	}

	private boolean hasBusinessMetricNames(List<String> columns) {
		return columns.stream().allMatch(this::isBusinessMetricName);
	}

	private boolean isBusinessMetricName(String column) {
		if (!StringUtils.hasText(column) || column.trim().matches("指标\\d+")) {
			return false;
		}
		String normalized = column.toLowerCase(Locale.ROOT).trim();
		return !(normalized.contains("id") || normalized.contains("code") || normalized.contains("no")
				|| normalized.contains("time") || normalized.contains("date") || normalized.contains("状态")
				|| normalized.contains("编号") || normalized.contains("编码") || normalized.contains("日期")
				|| normalized.contains("周期"));
	}

	private boolean isUsefulMetricName(String column) {
		if (!StringUtils.hasText(column)) {
			return false;
		}
		String normalized = column.toLowerCase(Locale.ROOT).trim();
		if (ReportColumnSemantics.isGenericMetricName(column)) {
			return true;
		}
		return !(normalized.contains("id") || normalized.contains("编号") || normalized.contains("编码")
				|| normalized.contains("code") || normalized.contains("no") || normalized.contains("状态")
				|| normalized.contains("类型") || normalized.contains("日期") || normalized.contains("时间")
				|| normalized.contains("周期"));
	}

	private boolean shouldUsePie(ReportDataSnapshot snapshot, String dimension, String metric) {
		String title = Optional.ofNullable(snapshot.getTitle()).orElse("").toLowerCase(Locale.ROOT);
		String metricName = Optional.ofNullable(metric).orElse("").toLowerCase(Locale.ROOT);
		long count = snapshot.getRows()
			.stream()
			.map(row -> displayValue(row.get(dimension)))
			.filter(StringUtils::hasText)
			.distinct()
			.count();
		return count > 1 && count <= properties.getReportVisualization().getPieMaxCategories()
				&& (title.contains("占比") || title.contains("比例") || metricName.contains("占比")
						|| metricName.contains("比例") || metricName.contains("率"));
	}

	private BigDecimal parseNumber(Object value) {
		if (value instanceof Number number) {
			return new BigDecimal(number.toString());
		}
		String text = displayValue(value);
		if (!StringUtils.hasText(text)) {
			return null;
		}
		String normalized = text.replace(",", "").replace("%", "").trim();
		try {
			return new BigDecimal(normalized);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private String normalizeTimeValue(Object value) {
		return parseDateLike(displayValue(value)).orElse(displayValue(value));
	}

	private Optional<String> parseDateLike(String text) {
		if (!StringUtils.hasText(text)) {
			return Optional.empty();
		}
		String value = text.trim();
		Optional<String> dateTime = parseDateTime(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
		if (dateTime.isPresent()) {
			return dateTime;
		}
		for (DateTimeFormatter formatter : List.of(DateTimeFormatter.ISO_LOCAL_DATE,
				DateTimeFormatter.ofPattern("yyyy/MM/dd"))) {
			Optional<String> date = parseDate(value, formatter);
			if (date.isPresent()) {
				return date;
			}
		}
		for (DateTimeFormatter formatter : List.of(DateTimeFormatter.ofPattern("yyyy-MM"),
				DateTimeFormatter.ofPattern("yyyy/MM"))) {
			Optional<String> month = parseYearMonth(value, formatter);
			if (month.isPresent()) {
				return month;
			}
		}
		return Optional.empty();
	}

	private Optional<String> parseDateTime(String value, DateTimeFormatter formatter) {
		try {
			return Optional.of(LocalDateTime.parse(value, formatter).toString());
		}
		catch (DateTimeParseException ex) {
			return Optional.empty();
		}
	}

	private Optional<String> parseDate(String value, DateTimeFormatter formatter) {
		try {
			return Optional.of(LocalDate.parse(value, formatter).toString());
		}
		catch (DateTimeParseException ex) {
			return Optional.empty();
		}
	}

	private Optional<String> parseYearMonth(String value, DateTimeFormatter formatter) {
		try {
			return Optional.of(YearMonth.parse(value, formatter).toString());
		}
		catch (DateTimeParseException ex) {
			return Optional.empty();
		}
	}

	private String displayValue(Object value) {
		return value == null ? "" : String.valueOf(value).trim();
	}

	private String defaultTitle(ReportDataSnapshot snapshot, String fallback) {
		String title = snapshot.getTitle();
		if (!StringUtils.hasText(title) || "本次分析结果".equals(title.trim())) {
			return fallback;
		}
		return title;
	}

}
