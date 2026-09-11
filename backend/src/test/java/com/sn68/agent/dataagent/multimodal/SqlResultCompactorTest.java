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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sn68.agent.dataagent.agentscope.tool.datasource.DatasourceExplorerResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SqlResultCompactorTest {

	private final SqlResultCompactor compactor = new SqlResultCompactor();

	@Test
	void keepsAllRowsWhenWithinBudget() {
		DatasourceExplorerResult result = resultWithRows(3);
		compactor.compact(result, 30, 80);
		assertEquals(3, result.getRows().size());
		assertTrue(result.getSummary().contains("name"));
		assertFalse(Boolean.TRUE.equals(result.getHasMore()));
		assertFalse(result.getSummary().contains(SqlResultCompactor.TRUNCATION_NOTICE));
	}

	@Test
	void compactForModelKeepsActualReturnedRowsAndShortPreview() {
		DatasourceExplorerResult result = resultWithRows(10);
		result.setReturnedRows(10);
		compactor.compactForModel(result, 80);
		assertEquals(3, result.getRows().size());
		assertEquals(10, result.getReturnedRows());
		assertEquals(Boolean.TRUE, result.getHasMore());
		assertTrue(result.getSummary().contains(SqlResultCompactor.MODEL_PREVIEW_NOTICE));
		assertTrue(result.getSummary().contains("实际返回 10 行"));
	}

	@Test
	void truncatesWithoutSumming() {
		DatasourceExplorerResult result = resultWithRows(35);
		compactor.compact(result, 30, 80);
		assertEquals(30, result.getRows().size());
		assertEquals(Boolean.TRUE, result.getHasMore());
		assertTrue(result.getSummary().contains(SqlResultCompactor.TRUNCATION_NOTICE));
		assertFalse(result.getSummary().contains("合计："));
	}

	private static DatasourceExplorerResult resultWithRows(int count) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("name", "c" + i);
			row.put("amount", i);
			rows.add(row);
		}
		return DatasourceExplorerResult.builder().summary("query").rows(rows).build();
	}

}
