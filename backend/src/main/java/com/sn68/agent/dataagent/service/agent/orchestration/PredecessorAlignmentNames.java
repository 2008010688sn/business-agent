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
package com.sn68.agent.dataagent.service.agent.orchestration;

import com.sn68.agent.dataagent.service.report.ReportColumnSemantics;
import com.sn68.agent.dataagent.service.report.ReportDataSnapshot;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * 从前置 SEARCH 快照抽出下游对齐用的业务名称（展示名），不带物理表名/字段名。
 */
public final class PredecessorAlignmentNames {

	public static final int MAX_NAMES = 20;

	private PredecessorAlignmentNames() {
	}

	public static List<String> fromSnapshots(List<ReportDataSnapshot> snapshots) {
		if (snapshots == null || snapshots.isEmpty()) {
			return List.of();
		}
		Set<String> names = new LinkedHashSet<>();
		for (ReportDataSnapshot snapshot : snapshots) {
			if (snapshot == null || snapshot.getRows() == null || snapshot.getRows().isEmpty()) {
				continue;
			}
			List<String> columns = snapshot.getColumns() == null ? List.of() : snapshot.getColumns();
			List<String> dimensionColumns = columns.stream().filter(ReportColumnSemantics::isDimensionColumn).toList();
			List<String> nameColumns = dimensionColumns.stream().filter(PredecessorAlignmentNames::looksLikeBusinessName)
				.toList();
			List<String> usedColumns = nameColumns.isEmpty() ? dimensionColumns : nameColumns;
			for (Map<String, Object> row : snapshot.getRows()) {
				if (row == null) {
					continue;
				}
				for (String column : usedColumns) {
					Object value = row.get(column);
					if (value == null) {
						continue;
					}
					String text = String.valueOf(value).trim();
					if (isUsableName(text)) {
						names.add(text);
					}
					if (names.size() >= MAX_NAMES) {
						return List.copyOf(names);
					}
				}
			}
		}
		return List.copyOf(names);
	}

	private static boolean looksLikeBusinessName(String column) {
		if (!StringUtils.hasText(column) || ReportColumnSemantics.isIdentifierColumn(column)) {
			return false;
		}
		String normalized = column.toLowerCase();
		return normalized.contains("名称") || normalized.contains("name") || normalized.contains("客户")
				|| normalized.contains("网点") || normalized.contains("产品") || normalized.contains("项目")
				|| normalized.contains("分类");
	}

	private static boolean isUsableName(String text) {
		return StringUtils.hasText(text) && text.length() <= 80;
	}

}
