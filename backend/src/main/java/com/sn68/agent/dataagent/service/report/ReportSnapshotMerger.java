/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * 多份查询快照合成一张表：优先同名维列，否则用名称维单元格值的交集对齐。
 * 不把展示列名（客户名称/企业名称）当作是否可合的依据。
 */
final class ReportSnapshotMerger {

	private static final int MAX_COLUMNS = 12;

	private static final int MIN_VALUE_OVERLAP = 2;

	private ReportSnapshotMerger() {
	}

	static Optional<ReportDataSnapshot> merge(List<ReportDataSnapshot> snapshots) {
		if (snapshots == null || snapshots.size() < 2) {
			return Optional.empty();
		}
		JoinPlan plan = resolveJoinPlan(snapshots);
		if (plan == null) {
			return Optional.empty();
		}
		List<MetricBinding> metrics = metricBindings(snapshots, plan);
		if (metrics.isEmpty()) {
			return Optional.empty();
		}
		List<String> columns = new ArrayList<>(plan.outputKeys());
		for (MetricBinding metric : metrics) {
			columns.add(metric.uniqueName());
			if (columns.size() >= MAX_COLUMNS) {
				break;
			}
		}
		List<Map<String, Object>> rows = mergedRows(snapshots, plan, columns, metrics);
		if (rows.size() < 2) {
			return Optional.empty();
		}
		return Optional.of(ReportDataSnapshot.builder()
			.title("本次分析结果")
			.columns(columns)
			.rows(rows)
			.totalRows(rows.size())
			.returnedRows(rows.size())
			.truncated(snapshots.stream().anyMatch(item -> Boolean.TRUE.equals(item.getTruncated())))
			.ranking(snapshots.stream().anyMatch(item -> Boolean.TRUE.equals(item.getRanking())))
			.build());
	}

	private static JoinPlan resolveJoinPlan(List<ReportDataSnapshot> snapshots) {
		List<String> shared = sharedDimensionColumns(snapshots);
		if (!shared.isEmpty()) {
			return JoinPlan.sameKeys(snapshots.size(), shared);
		}
		return valueOverlapPlan(snapshots);
	}

	private static List<String> sharedDimensionColumns(List<ReportDataSnapshot> snapshots) {
		Set<String> shared = new LinkedHashSet<>(dimensionColumns(snapshots.get(0)));
		for (int index = 1; index < snapshots.size(); index++) {
			shared.retainAll(dimensionColumns(snapshots.get(index)));
		}
		return List.copyOf(shared);
	}

	private static JoinPlan valueOverlapPlan(List<ReportDataSnapshot> snapshots) {
		JoinPlan best = null;
		int bestScore = 0;
		for (String leftColumn : nameDimensionColumns(snapshots.get(0))) {
			JoinPlan candidate = valueOverlapPlanForLeftColumn(snapshots, leftColumn);
			if (candidate == null) {
				continue;
			}
			int score = overlapScore(snapshots, candidate);
			if (score > bestScore) {
				bestScore = score;
				best = candidate;
			}
		}
		return best;
	}

	private static JoinPlan valueOverlapPlanForLeftColumn(List<ReportDataSnapshot> snapshots, String leftColumn) {
		Set<String> leftValues = cellValues(snapshots.get(0), leftColumn);
		if (leftValues.size() < MIN_VALUE_OVERLAP) {
			return null;
		}
		List<List<String>> localKeys = new ArrayList<>();
		localKeys.add(List.of(leftColumn));
		for (int index = 1; index < snapshots.size(); index++) {
			String matched = bestOverlappingNameColumn(snapshots.get(index), leftValues);
			if (matched == null) {
				return null;
			}
			localKeys.add(List.of(matched));
		}
		return new JoinPlan(List.of(leftColumn), List.copyOf(localKeys));
	}

	private static String bestOverlappingNameColumn(ReportDataSnapshot snapshot, Set<String> leftValues) {
		String best = null;
		int bestOverlap = 0;
		int bestRightSize = 0;
		for (String column : nameDimensionColumns(snapshot)) {
			Set<String> values = cellValues(snapshot, column);
			int overlap = overlapSize(leftValues, values);
			if (overlap > bestOverlap) {
				bestOverlap = overlap;
				best = column;
				bestRightSize = values.size();
			}
		}
		if (best == null || !enoughOverlap(bestOverlap, leftValues.size(), bestRightSize)) {
			return null;
		}
		return best;
	}

	private static int overlapScore(List<ReportDataSnapshot> snapshots, JoinPlan plan) {
		Set<String> left = cellValues(snapshots.get(0), plan.localKeys().get(0).get(0));
		int score = Integer.MAX_VALUE;
		for (int index = 1; index < snapshots.size(); index++) {
			score = Math.min(score, overlapSize(left, cellValues(snapshots.get(index), plan.localKeys().get(index).get(0))));
		}
		return score == Integer.MAX_VALUE ? 0 : score;
	}

	private static boolean enoughOverlap(int overlap, int leftSize, int rightSize) {
		if (overlap < MIN_VALUE_OVERLAP) {
			return false;
		}
		int smaller = Math.min(leftSize, rightSize);
		return smaller > 0 && overlap * 2 >= smaller;
	}

	private static int overlapSize(Set<String> left, Set<String> right) {
		int count = 0;
		for (String value : left) {
			if (right.contains(value)) {
				count++;
			}
		}
		return count;
	}

	private static List<String> dimensionColumns(ReportDataSnapshot snapshot) {
		if (snapshot == null || snapshot.getColumns() == null) {
			return List.of();
		}
		return snapshot.getColumns()
			.stream()
			.filter(ReportColumnSemantics::isDimensionColumn)
			.filter(column -> !ReportColumnSemantics.isIdentifierValueColumn(snapshot.getRows(), column))
			.toList();
	}

	private static List<String> nameDimensionColumns(ReportDataSnapshot snapshot) {
		return dimensionColumns(snapshot).stream().filter(ReportColumnSemantics::isBusinessNameColumn).toList();
	}

	private static Set<String> cellValues(ReportDataSnapshot snapshot, String column) {
		Set<String> values = new LinkedHashSet<>();
		if (snapshot == null || snapshot.getRows() == null || !StringUtils.hasText(column)) {
			return values;
		}
		for (Map<String, Object> row : snapshot.getRows()) {
			if (row == null) {
				continue;
			}
			Object value = row.get(column);
			if (value == null) {
				continue;
			}
			String text = String.valueOf(value).trim();
			if (StringUtils.hasText(text)) {
				values.add(text);
			}
		}
		return values;
	}

	private static List<MetricBinding> metricBindings(List<ReportDataSnapshot> snapshots, JoinPlan plan) {
		Set<String> used = new LinkedHashSet<>(plan.outputKeys());
		List<MetricBinding> bindings = new ArrayList<>();
		for (int snapshotIndex = 0; snapshotIndex < snapshots.size(); snapshotIndex++) {
			ReportDataSnapshot snapshot = snapshots.get(snapshotIndex);
			if (snapshot.getColumns() == null) {
				continue;
			}
			Set<String> skip = joinColumnsOf(plan, snapshotIndex);
			for (String column : snapshot.getColumns()) {
				if (skip.contains(column) || ReportColumnSemantics.isIdentifierColumn(column)
						|| ReportColumnSemantics.isIdentifierValueColumn(snapshot.getRows(), column)) {
					continue;
				}
				String unique = uniqueColumnName(column, snapshot.getTitle(), used);
				used.add(unique);
				bindings.add(new MetricBinding(snapshotIndex, column, unique));
			}
		}
		return List.copyOf(bindings);
	}

	private static Set<String> joinColumnsOf(JoinPlan plan, int snapshotIndex) {
		Set<String> skip = new LinkedHashSet<>(plan.outputKeys());
		skip.addAll(plan.localKeys().get(snapshotIndex));
		return skip;
	}

	private static List<Map<String, Object>> mergedRows(List<ReportDataSnapshot> snapshots, JoinPlan plan,
			List<String> columns, List<MetricBinding> metrics) {
		Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
		for (int snapshotIndex = 0; snapshotIndex < snapshots.size(); snapshotIndex++) {
			ReportDataSnapshot snapshot = snapshots.get(snapshotIndex);
			if (snapshot.getRows() == null) {
				continue;
			}
			List<String> localKeys = plan.localKeys().get(snapshotIndex);
			for (Map<String, Object> row : snapshot.getRows()) {
				putMergedRow(byKey, columns, plan.outputKeys(), localKeys, metrics, snapshotIndex, row);
			}
		}
		return List.copyOf(byKey.values());
	}

	private static void putMergedRow(Map<String, Map<String, Object>> byKey, List<String> columns,
			List<String> outputKeys, List<String> localKeys, List<MetricBinding> metrics, int snapshotIndex,
			Map<String, Object> row) {
		if (row == null) {
			return;
		}
		Map<String, Object> aligned = alignJoinValues(row, outputKeys, localKeys);
		String key = joinKey(aligned, outputKeys);
		if (!StringUtils.hasText(key)) {
			return;
		}
		Map<String, Object> merged = byKey.computeIfAbsent(key, ignored -> emptyRow(columns));
		for (String joinKey : outputKeys) {
			Object value = aligned.get(joinKey);
			if (value != null && StringUtils.hasText(String.valueOf(value))) {
				merged.put(joinKey, value);
			}
		}
		for (MetricBinding metric : metrics) {
			if (metric.snapshotIndex() != snapshotIndex) {
				continue;
			}
			Object value = row.get(metric.originalName());
			merged.put(metric.uniqueName(), value == null ? "" : value);
		}
	}

	private static Map<String, Object> alignJoinValues(Map<String, Object> row, List<String> outputKeys,
			List<String> localKeys) {
		Map<String, Object> aligned = new LinkedHashMap<>();
		int count = Math.min(outputKeys.size(), localKeys.size());
		for (int index = 0; index < count; index++) {
			aligned.put(outputKeys.get(index), row.get(localKeys.get(index)));
		}
		return aligned;
	}

	private static Map<String, Object> emptyRow(List<String> columns) {
		Map<String, Object> created = new LinkedHashMap<>();
		for (String column : columns) {
			created.put(column, "");
		}
		return created;
	}

	private static String uniqueColumnName(String column, String title, Set<String> used) {
		if (!used.contains(column)) {
			return column;
		}
		if (StringUtils.hasText(title)) {
			String prefixed = title.trim() + "·" + column;
			if (!used.contains(prefixed)) {
				return prefixed;
			}
		}
		int index = 2;
		String candidate = column + index;
		while (used.contains(candidate)) {
			index++;
			candidate = column + index;
		}
		return candidate;
	}

	private static String joinKey(Map<String, Object> row, List<String> joinKeys) {
		StringBuilder builder = new StringBuilder();
		for (String joinKey : joinKeys) {
			Object value = row.get(joinKey);
			if (value == null || !StringUtils.hasText(String.valueOf(value))) {
				return "";
			}
			if (!builder.isEmpty()) {
				builder.append('\u0001');
			}
			builder.append(String.valueOf(value).trim());
		}
		return builder.toString();
	}

	private record JoinPlan(List<String> outputKeys, List<List<String>> localKeys) {

		static JoinPlan sameKeys(int snapshotCount, List<String> keys) {
			List<List<String>> local = new ArrayList<>();
			for (int index = 0; index < snapshotCount; index++) {
				local.add(keys);
			}
			return new JoinPlan(keys, List.copyOf(local));
		}

	}

	private record MetricBinding(int snapshotIndex, String originalName, String uniqueName) {
	}

}
