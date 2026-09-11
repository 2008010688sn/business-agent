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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * 报告列语义：优先选业务名称维和金额指标，避开 ID/hash。
 */
public final class ReportColumnSemantics {

	private ReportColumnSemantics() {
	}

	public static boolean isIdentifierColumn(String column) {
		if (!StringUtils.hasText(column)) {
			return true;
		}
		String normalized = column.toLowerCase(Locale.ROOT).trim();
		return normalized.equals("id") || normalized.endsWith("_id") || normalized.endsWith(".id")
				|| normalized.contains("编号") || normalized.contains("编码") || normalized.equals("code")
				|| normalized.endsWith("_code") || normalized.equals("no") || normalized.endsWith("_no");
	}

	public static boolean isInternalColumn(String column) {
		if (!StringUtils.hasText(column)) {
			return true;
		}
		String normalized = column.toLowerCase(Locale.ROOT).trim();
		int dot = normalized.lastIndexOf('.');
		String leaf = dot >= 0 ? normalized.substring(dot + 1) : normalized;
		return leaf.equals("deleted") || leaf.equals("删除标识") || leaf.equals("tenant_id")
				|| leaf.equals("create_by") || leaf.equals("create_name") || leaf.equals("last_modify_by")
				|| leaf.equals("last_modify_name") || leaf.equals("last_modify_time");
	}

	public static boolean isIdentifierValueColumn(List<Map<String, Object>> rows, String column) {
		if (rows == null || !StringUtils.hasText(column)) {
			return true;
		}
		List<String> values = rows.stream()
			.map(row -> display(row.get(column)))
			.filter(StringUtils::hasText)
			.distinct()
			.toList();
		if (values.isEmpty()) {
			return true;
		}
		long identifiers = values.stream().filter(ReportColumnSemantics::isIdentifierValue).count();
		return identifiers * 2 >= values.size();
	}

	public static boolean isGenericPlaceholder(String column) {
		return StringUtils.hasText(column)
				&& column.trim().matches("(?:业务维度|业务指标|时间维度|维度|指标|时间)\\d+");
	}

	static boolean isGenericMetricName(String column) {
		return StringUtils.hasText(column) && column.trim().matches("(?:业务)?指标\\d+");
	}

	public static boolean isMetricColumn(String column) {
		return isGenericMetricName(column) || isAmountMetric(column) || isCountMetric(column);
	}

	public static boolean isBusinessNameColumn(String column) {
		if (!StringUtils.hasText(column) || isIdentifierColumn(column)) {
			return false;
		}
		String normalized = column.toLowerCase(Locale.ROOT);
		return normalized.contains("名称") || normalized.contains("name") || normalized.contains("客户")
				|| normalized.contains("网点") || normalized.contains("产品") || normalized.contains("项目")
				|| normalized.contains("分类");
	}

	public static String metricSnapshotTitle(List<String> columns) {
		if (columns == null || columns.isEmpty()) {
			return "本次分析结果";
		}
		List<String> metrics = columns.stream().filter(ReportColumnSemantics::isMetricColumn).limit(2).toList();
		if (metrics.isEmpty()) {
			return "本次分析结果";
		}
		return String.join("、", metrics);
	}

	public static boolean isDimensionColumn(String column) {
		return StringUtils.hasText(column) && !isIdentifierColumn(column) && !isMetricColumn(column);
	}

	static boolean isAmountMetric(String column) {
		if (!StringUtils.hasText(column)) {
			return false;
		}
		String normalized = column.toLowerCase(Locale.ROOT);
		// “cost 且 total 同时出现才视为金额指标”是有意为之的组合条件（&& 优先级高于 ||），勿改成 cost 单独命中。
		return normalized.contains("金额") || normalized.contains("amount") || normalized.contains("amt")
				|| normalized.contains("收入") || (normalized.contains("cost") && normalized.contains("total"));
	}

	static boolean isCountMetric(String column) {
		if (!StringUtils.hasText(column)) {
			return false;
		}
		String normalized = column.toLowerCase(Locale.ROOT);
		return normalized.contains("账单数") || normalized.contains("订单数") || normalized.contains("数量")
				|| normalized.contains("count") || normalized.contains("cnt") || normalized.endsWith("数")
				|| normalized.endsWith("量");
	}

	static boolean isIdentifierValue(Object value) {
		String text = display(value);
		if (!StringUtils.hasText(text)) {
			return true;
		}
		if (text.matches("\\d{15,}")) {
			return true;
		}
		if (text.matches("[0-9a-fA-F]{32}")) {
			return true;
		}
		return text.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
	}

	static boolean looksLikeTestName(String name) {
		if (!StringUtils.hasText(name)) {
			return false;
		}
		String normalized = name.toLowerCase(Locale.ROOT);
		return normalized.contains("测试") || normalized.contains("演示") || normalized.contains("demo")
				|| normalized.contains("test");
	}

	static String pickDimension(List<String> columns, List<Map<String, Object>> rows, List<String> metrics,
			List<String> preferred) {
		if (columns == null || rows == null || rows.isEmpty()) {
			return "";
		}
		List<String> candidates = new ArrayList<>();
		for (String column : columns) {
			if (metrics != null && metrics.contains(column)) {
				continue;
			}
			if (!isUsableDimension(rows, column)) {
				continue;
			}
			candidates.add(column);
		}
		if (candidates.isEmpty()) {
			return "";
		}
		if (preferred != null) {
			for (String want : preferred) {
				for (String candidate : candidates) {
					if (candidate.equals(want) || candidate.contains(want) || want.contains(candidate)) {
						return candidate;
					}
				}
			}
		}
		return candidates.stream()
			.max(Comparator.comparingInt((String column) -> dimensionScore(rows, column)))
			.orElse(candidates.get(0));
	}

	static String pickMainMetric(List<String> metrics, List<String> preferred) {
		List<String> ordered = orderMetrics(metrics, preferred);
		return ordered.isEmpty() ? "" : ordered.get(0);
	}

	static List<String> orderMetrics(List<String> metrics, List<String> preferred) {
		if (metrics == null || metrics.isEmpty()) {
			return List.of();
		}
		List<String> named = metrics.stream().filter(metric -> !isGenericMetricName(metric)).toList();
		List<String> source = named.isEmpty() ? List.copyOf(metrics) : named;
		return source.stream()
			.sorted(Comparator.comparingInt((String metric) -> metricScore(metric, preferred)).reversed())
			.toList();
	}

	static boolean isUsableDimension(List<Map<String, Object>> rows, String column) {
		if (!StringUtils.hasText(column) || isIdentifierColumn(column) || rows == null) {
			return false;
		}
		List<String> values = rows.stream()
			.map(row -> display(row.get(column)))
			.filter(StringUtils::hasText)
			.distinct()
			.toList();
		if (values.size() < 2) {
			return false;
		}
		if (values.stream().allMatch(ReportColumnSemantics::isTimeValue)) {
			return false;
		}
		long identifierValues = values.stream().filter(ReportColumnSemantics::isIdentifierValue).count();
		return identifierValues * 2 < values.size();
	}

	private static int dimensionScore(List<Map<String, Object>> rows, String column) {
		int score = 0;
		if (isBusinessNameColumn(column)) {
			score += 80;
		}
		if (column.startsWith("维度")) {
			score -= 20;
		}
		long distinct = rows.stream()
			.map(row -> display(row.get(column)))
			.filter(StringUtils::hasText)
			.distinct()
			.count();
		if (distinct == rows.size()) {
			score += 60;
		}
		else if (distinct < rows.size()) {
			score -= 20;
		}
		long chinese = rows.stream()
			.map(row -> display(row.get(column)))
			.filter(value -> value.matches(".*[\\u4e00-\\u9fa5].*"))
			.distinct()
			.limit(8)
			.count();
		score += (int) chinese * 10;
		return score;
	}

	private static int metricScore(String metric, List<String> preferred) {
		int score = 0;
		if (preferred != null) {
			for (String want : preferred) {
				if (metric.equals(want) || metric.contains(want) || want.contains(metric)) {
					score += 100;
					break;
				}
			}
		}
		if (isAmountMetric(metric)) {
			score += 50;
		}
		else if (isCountMetric(metric)) {
			score += 20;
		}
		if (isGenericMetricName(metric)) {
			score -= 40;
		}
		return score;
	}

	private static boolean isTimeValue(String value) {
		return value != null && (value.matches("\\d{4}-\\d{1,2}(?:-\\d{1,2})?.*")
				|| value.matches("\\d{4}/\\d{1,2}(?:/\\d{1,2})?.*"));
	}

	private static boolean isIdentifierValue(String text) {
		return isIdentifierValue((Object) text);
	}

	private static String display(Object value) {
		return value == null ? "" : String.valueOf(value).trim();
	}

}
