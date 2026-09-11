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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * 报告专用快照选择：禁止 last-write-wins，避免复核详情覆盖可图表的主结果。
 */
final class ReportSnapshotSelector {

	private static final int MAX_CHARTS = 1;

	record Selection(Optional<ReportDataSnapshot> primary, List<ReportDataSnapshot> forCharts,
			Optional<ReportDataSnapshot> ranking) {
	}

	Selection select(AnswerTraceExplainView explain) {
		List<ReportDataSnapshot> snapshots = explain == null || explain.getReportDataSnapshots() == null
				? List.of() : explain.getReportDataSnapshots();
		return select(snapshots);
	}

	Selection select(List<ReportDataSnapshot> snapshots) {
		if (CollectionUtils.isEmpty(snapshots)) {
			return new Selection(Optional.empty(), List.of(), Optional.empty());
		}
		List<ScoredSnapshot> scored = new ArrayList<>();
		for (int index = 0; index < snapshots.size(); index++) {
			ReportDataSnapshot snapshot = snapshots.get(index);
			int score = score(snapshot);
			if (score < 0) {
				continue;
			}
			scored.add(new ScoredSnapshot(snapshot, score, index));
		}
		if (scored.isEmpty()) {
			return new Selection(Optional.empty(), List.of(), Optional.empty());
		}
		scored.sort(Comparator.comparingInt(ScoredSnapshot::score)
			.reversed()
			.thenComparing(Comparator.comparingInt(ScoredSnapshot::rowCount).reversed())
			.thenComparing(Comparator.comparingInt(ScoredSnapshot::index).reversed()));
		ReportDataSnapshot primary = scored.get(0).snapshot();
		List<ReportDataSnapshot> forCharts = new ArrayList<>();
		for (ScoredSnapshot item : scored) {
			if (item.score() < 200) {
				continue;
			}
			if (forCharts.stream().anyMatch(existing -> sameRows(existing, item.snapshot())
					|| dominates(existing, item.snapshot()))) {
				continue;
			}
			forCharts.add(item.snapshot());
			if (forCharts.size() >= MAX_CHARTS) {
				break;
			}
		}
		Optional<ReportDataSnapshot> ranking = scored.stream()
			.map(ScoredSnapshot::snapshot)
			.filter(snapshot -> Boolean.TRUE.equals(snapshot.getRanking()))
			.findFirst();
		return new Selection(Optional.of(primary), List.copyOf(forCharts), ranking);
	}

	private int score(ReportDataSnapshot snapshot) {
		if (snapshot == null || CollectionUtils.isEmpty(snapshot.getRows())
				|| CollectionUtils.isEmpty(snapshot.getColumns())) {
			return -1;
		}
		int rows = snapshot.getRows().size();
		int numeric = countNumericColumns(snapshot);
		int dimensions = countUsableDimensions(snapshot);
		int columnBonus = snapshot.getColumns().size();
		int nameBonus = hasBusinessNameDimension(snapshot) ? 80 : 0;
		int amountBonus = hasAmountMetric(snapshot) ? 40 : 0;
		if (Boolean.TRUE.equals(snapshot.getRanking()) && rows >= 2 && numeric > 0) {
			return 500 + rows + columnBonus + nameBonus + amountBonus;
		}
		if (rows >= 2 && numeric > 0 && dimensions > 0) {
			return 400 + rows + columnBonus + nameBonus + amountBonus;
		}
		if (rows >= 2 && numeric > 0) {
			return 300 + rows + columnBonus + amountBonus;
		}
		if (rows == 1 && numeric > 1) {
			return 200 + numeric;
		}
		return 100;
	}

	private boolean hasBusinessNameDimension(ReportDataSnapshot snapshot) {
		return snapshot.getColumns()
			.stream()
			.anyMatch(column -> ReportColumnSemantics.isBusinessNameColumn(column)
					&& ReportColumnSemantics.isUsableDimension(snapshot.getRows(), column));
	}

	private boolean hasAmountMetric(ReportDataSnapshot snapshot) {
		return snapshot.getColumns().stream().anyMatch(ReportColumnSemantics::isAmountMetric);
	}

	private int countNumericColumns(ReportDataSnapshot snapshot) {
		int count = 0;
		for (String column : snapshot.getColumns()) {
			if (isNumericColumn(snapshot, column) && !looksLikeIdentifier(column)) {
				count++;
			}
		}
		return count;
	}

	private int countUsableDimensions(ReportDataSnapshot snapshot) {
		int count = 0;
		for (String column : snapshot.getColumns()) {
			if (isNumericColumn(snapshot, column)) {
				continue;
			}
			long distinct = snapshot.getRows()
				.stream()
				.map(row -> display(row.get(column)))
				.filter(StringUtils::hasText)
				.distinct()
				.count();
			if (distinct >= 2) {
				count++;
			}
		}
		return count;
	}

	private boolean isNumericColumn(ReportDataSnapshot snapshot, String column) {
		long checked = snapshot.getRows().stream().filter(row -> row.get(column) != null).limit(8).count();
		if (checked == 0) {
			return false;
		}
		long numeric = snapshot.getRows()
			.stream()
			.map(row -> row.get(column))
			.filter(value -> value != null)
			.limit(8)
			.filter(value -> parseNumber(value) != null)
			.count();
		return numeric == checked;
	}

	private boolean looksLikeIdentifier(String column) {
		if (!StringUtils.hasText(column)) {
			return true;
		}
		String normalized = column.toLowerCase();
		return normalized.contains("id") || normalized.contains("编号") || normalized.contains("编码")
				|| normalized.contains("code") || normalized.contains("no");
	}

	private boolean sameRows(ReportDataSnapshot left, ReportDataSnapshot right) {
		return left == right || left.getRows() == right.getRows();
	}

	private boolean dominates(ReportDataSnapshot existing, ReportDataSnapshot candidate) {
		if (existing == null || candidate == null || existing.getRows() == null || candidate.getRows() == null) {
			return false;
		}
		if (existing.getRows().size() != candidate.getRows().size()) {
			return false;
		}
		int existingColumns = existing.getColumns() == null ? 0 : existing.getColumns().size();
		int candidateColumns = candidate.getColumns() == null ? 0 : candidate.getColumns().size();
		return existingColumns > candidateColumns;
	}

	private BigDecimal parseNumber(Object value) {
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

	private String display(Object value) {
		return value == null ? "" : String.valueOf(value).trim();
	}

	private record ScoredSnapshot(ReportDataSnapshot snapshot, int score, int index) {

		int rowCount() {
			return snapshot.getRows() == null ? 0 : snapshot.getRows().size();
		}

	}

}
