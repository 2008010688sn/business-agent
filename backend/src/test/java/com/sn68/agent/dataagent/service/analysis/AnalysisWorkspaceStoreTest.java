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
package com.sn68.agent.dataagent.service.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sn68.agent.dataagent.agentscope.tool.datasource.ResultCoverageStatus;
import com.sn68.agent.dataagent.service.analysis.AnalysisWorkspaceStore.SessionWorkspace;
import com.sn68.agent.dataagent.service.analysis.AnalysisWorkspaceStore.WorkspaceSnapshot;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnalysisWorkspaceStoreTest {

	private final AnalysisWorkspaceStore store = new AnalysisWorkspaceStore();

	@Test
	void workspacesAreKeyedByTenantAndSession() {
		SessionWorkspace first = store.getOrCreate("tenant-a", "sess-1");
		first.putTable("file", List.of("id"), List.of(Map.of("id", "1")), 1);

		assertEquals(1, store.require("tenant-a", "sess-1").snapshots().size());
		assertThrows(CheckedException.class, () -> store.require("tenant-b", "sess-1"));
	}

	@Test
	void exceedingRowCapMarksPlatformLimitedAndDoesNotSilentDropCoverage() {
		SessionWorkspace workspace = store.getOrCreate("tenant-a", "sess-2");
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int i = 0; i < AnalysisWorkspaceStore.MAX_ROWS_PER_SESSION + 5; i++) {
			rows.add(Map.of("id", i));
		}

		WorkspaceSnapshot snapshot = workspace.putTable("t1", List.of("id"), rows, 1);

		assertEquals(ResultCoverageStatus.PLATFORM_LIMITED, snapshot.coverageStatus());
		assertEquals(AnalysisWorkspaceStore.MAX_ROWS_PER_SESSION, snapshot.rowCount());
		assertTrue(snapshot.evidenceId().startsWith("ev-t1-"));
	}

	@Test
	void putTablePreservesNullCells() {
		SessionWorkspace workspace = store.getOrCreate("tenant-a", "sess-3");
		Map<String, Object> row = new java.util.LinkedHashMap<>();
		row.put("id", 1);
		row.put("name", null);

		WorkspaceSnapshot snapshot = workspace.putTable("file", List.of("id", "name"), List.of(row), 1);

		assertEquals(1, snapshot.rowCount());
		assertEquals(1, snapshot.rows().get(0).get("id"));
		org.junit.jupiter.api.Assertions.assertNull(snapshot.rows().get(0).get("name"));
	}

	@Test
	void skippingNullRowObjectsDoesNotMarkPlatformLimited() {
		SessionWorkspace workspace = store.getOrCreate("tenant-a", "sess-5");
		List<Map<String, Object>> rows = new ArrayList<>();
		rows.add(null);
		rows.add(Map.of("id", 1));

		WorkspaceSnapshot snapshot = workspace.putTable("t1", List.of("id"), rows, 1);

		assertEquals(ResultCoverageStatus.FULL, snapshot.coverageStatus());
		assertEquals(1, snapshot.rowCount());
	}

	@Test
	void handlesAreTenantAndSessionScopedAndDoNotCreate() {
		assertTrue(store.handles("tenant-a", "sess-handles").isEmpty());
		SessionWorkspace workspace = store.getOrCreate("tenant-a", "sess-handles");
		workspace.putTable("orders", List.of("id"), List.of(Map.of("id", "1")), 1);
		workspace.putTable("file", List.of("id"), List.of(Map.of("id", "2")), 1);

		assertEquals(List.of("ev-orders-1", "ev-file-2"), store.handles("tenant-a", "sess-handles"));
		assertTrue(store.handles("tenant-b", "sess-handles").isEmpty());
		assertTrue(store.handles("tenant-a", "other").isEmpty());
	}

	@Test
	void emptyRowsWithKeyListOverflowStayPlatformLimited() {
		SessionWorkspace workspace = store.getOrCreate("tenant-a", "sess-4");

		WorkspaceSnapshot snapshot = workspace.putTable("t1", List.of("id"), List.of(),
				AnalysisWorkspaceStore.MAX_IN_LIST_KEYS + 1);

		assertEquals(ResultCoverageStatus.PLATFORM_LIMITED, snapshot.coverageStatus());
		assertEquals(0, snapshot.rowCount());
	}
}
