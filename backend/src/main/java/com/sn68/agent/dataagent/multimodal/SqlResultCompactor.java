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
package com.sn68.agent.dataagent.multimodal;

import com.sn68.agent.dataagent.agentscope.tool.datasource.DatasourceExplorerResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * SQL 工具结果形态压缩。不超过阈值则全表转 markdown；超出只保留前 N 行并声明 hasMore，禁止对截断集合计。
 */
@Component
public class SqlResultCompactor {

	public static final String TRUNCATION_NOTICE = "结果已截断，不要对截断表做合计/平均；需要精确聚合请重新查询（SUM/GROUP BY 或收窄条件）。";

	public static final int MODEL_PREVIEW_ROWS = 3;

	public static final String MODEL_PREVIEW_NOTICE = "完整结果已发给用户界面，不要复述表格或编号明细；只需一两句结论。不要对预览行做合计。";

	public DatasourceExplorerResult compactForModel(DatasourceExplorerResult result, int maxCellChars) {
		if (result == null || result.getRows() == null || result.getRows().isEmpty()) {
			return result;
		}
		int originalRows = result.getReturnedRows() == null ? result.getRows().size() : result.getReturnedRows();
		int cellLimit = Math.max(8, maxCellChars);
		List<Map<String, Object>> clipped = clipCells(result.getRows(), cellLimit);
		boolean truncated = clipped.size() > MODEL_PREVIEW_ROWS;
		List<Map<String, Object>> kept = truncated ? new ArrayList<>(clipped.subList(0, MODEL_PREVIEW_ROWS)) : clipped;
		result.setRows(kept);
		if (truncated) {
			result.setHasMore(Boolean.TRUE);
		}
		result.setReturnedRows(originalRows);
		appendSummary(result, toMarkdown(kept));
		appendSummary(result, MODEL_PREVIEW_NOTICE);
		if (truncated) {
			appendSummary(result, "预览前 %d 行，实际返回 %d 行。".formatted(kept.size(), originalRows));
		}
		return result;
	}

	public DatasourceExplorerResult compact(DatasourceExplorerResult result, int keepAllRows, int maxCellChars) {
		if (result == null || result.getRows() == null || result.getRows().isEmpty()) {
			return result;
		}
		int keep = Math.max(1, keepAllRows);
		int cellLimit = Math.max(8, maxCellChars);
		List<Map<String, Object>> clipped = clipCells(result.getRows(), cellLimit);
		boolean truncated = clipped.size() > keep;
		List<Map<String, Object>> kept = truncated ? new ArrayList<>(clipped.subList(0, keep)) : clipped;
		String markdown = toMarkdown(kept);
		if (truncated) {
			result.setRows(kept);
			result.setHasMore(Boolean.TRUE);
			result.setReturnedRows(kept.size());
			appendSummary(result, markdown + "\n" + TRUNCATION_NOTICE);
		}
		else {
			result.setRows(kept);
			appendSummary(result, markdown);
		}
		return result;
	}

	static String toMarkdown(List<Map<String, Object>> rows) {
		if (rows == null || rows.isEmpty()) {
			return "";
		}
		List<String> headers = new ArrayList<>(rows.get(0).keySet());
		StringBuilder builder = new StringBuilder();
		builder.append('|').append(String.join("|", headers)).append('|').append('\n');
		builder.append('|');
		for (int i = 0; i < headers.size(); i++) {
			builder.append("---|");
		}
		builder.append('\n');
		for (Map<String, Object> row : rows) {
			builder.append('|');
			for (String header : headers) {
				builder.append(stringify(row.get(header))).append('|');
			}
			builder.append('\n');
		}
		return builder.toString().trim();
	}

	private static List<Map<String, Object>> clipCells(List<Map<String, Object>> rows, int maxCellChars) {
		List<Map<String, Object>> clipped = new ArrayList<>(rows.size());
		for (Map<String, Object> row : rows) {
			if (row == null) {
				continue;
			}
			Map<String, Object> copy = new LinkedHashMap<>();
			row.forEach((key, value) -> copy.put(key, clipCell(value, maxCellChars)));
			clipped.add(copy);
		}
		return clipped;
	}

	private static Object clipCell(Object value, int maxCellChars) {
		if (value == null) {
			return "";
		}
		String text = String.valueOf(value);
		if (text.length() <= maxCellChars) {
			return text;
		}
		return text.substring(0, maxCellChars) + "...";
	}

	private static void appendSummary(DatasourceExplorerResult result, String extra) {
		if (!StringUtils.hasText(extra)) {
			return;
		}
		String current = result.getSummary();
		result.setSummary(StringUtils.hasText(current) ? current + "\n" + extra : extra);
	}

	private static String stringify(Object value) {
		if (value == null) {
			return "";
		}
		return String.valueOf(value).replace("|", "\\|").replace("\n", " ");
	}

}
