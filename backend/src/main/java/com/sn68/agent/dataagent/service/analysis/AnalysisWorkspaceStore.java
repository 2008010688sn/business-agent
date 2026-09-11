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

import com.sn68.agent.dataagent.agentscope.tool.datasource.ResultCoverageStatus;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Session-scoped analysis workspace. Tenant isolated, never written back to a business database.
 *
 * <p>This PR keeps snapshots in the in-memory session object. Multi-replica production would need shared
 * object storage (Suite file service / PG / Redis); local disk is not the source of truth and is not used
 * here. Overflow is truncated and marked {@link ResultCoverageStatus}, never silent.</p>
 */
@Component
public class AnalysisWorkspaceStore {

	static final int MAX_ROWS_PER_SESSION = 20_000;

	static final long MAX_BYTES_PER_SESSION = 8L * 1024 * 1024;

	static final int MAX_IN_LIST_KEYS = 500;

	private static final Duration TTL = Duration.ofHours(2);

	private final ConcurrentHashMap<String, SessionWorkspace> workspaces = new ConcurrentHashMap<>();

	public SessionWorkspace getOrCreate(String tenantId, String sessionId) {
		requireIdentity(tenantId, sessionId);
		purgeExpired();
		return workspaces.compute(key(tenantId, sessionId), (ignored, existing) -> {
			if (existing == null || existing.expired(Instant.now())) {
				return new SessionWorkspace(tenantId, sessionId);
			}
			existing.touch();
			return existing;
		});
	}

	public SessionWorkspace require(String tenantId, String sessionId) {
		requireIdentity(tenantId, sessionId);
		SessionWorkspace workspace = workspaces.get(key(tenantId, sessionId));
		if (workspace == null || !tenantId.equals(workspace.tenantId()) || workspace.expired(Instant.now())) {
			throw CheckedException.notFound("Analysis workspace does not exist");
		}
		workspace.touch();
		return workspace;
	}

	public List<String> handles(String tenantId, String sessionId) {
		if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(sessionId)) {
			return List.of();
		}
		SessionWorkspace workspace = workspaces.get(key(tenantId.trim(), sessionId.trim()));
		if (workspace == null || !tenantId.trim().equals(workspace.tenantId()) || workspace.expired(Instant.now())) {
			return List.of();
		}
		List<String> handles = new ArrayList<>();
		for (WorkspaceSnapshot snapshot : workspace.snapshots()) {
			if (snapshot != null && StringUtils.hasText(snapshot.evidenceId())) {
				handles.add(snapshot.evidenceId().trim());
			}
		}
		return List.copyOf(handles);
	}

	public void clear(String tenantId, String sessionId) {
		if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(sessionId)) {
			return;
		}
		workspaces.remove(key(tenantId, sessionId));
	}

	private void purgeExpired() {
		Instant now = Instant.now();
		workspaces.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().expired(now));
	}

	private void requireIdentity(String tenantId, String sessionId) {
		if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(sessionId)) {
			throw CheckedException.badRequest("Analysis workspace requires tenantId and sessionId");
		}
	}

	private String key(String tenantId, String sessionId) {
		return tenantId + '\0' + sessionId;
	}

	public static final class SessionWorkspace {

		private final String tenantId;

		private final String sessionId;

		private final List<WorkspaceSnapshot> snapshots = new ArrayList<>();

		private int totalRows;

		private long totalBytes;

		private Instant lastAccess = Instant.now();

		SessionWorkspace(String tenantId, String sessionId) {
			this.tenantId = tenantId;
			this.sessionId = sessionId;
		}

		public String tenantId() {
			return tenantId;
		}

		public String sessionId() {
			return sessionId;
		}

		public List<WorkspaceSnapshot> snapshots() {
			return List.copyOf(snapshots);
		}

		public int totalRows() {
			return totalRows;
		}

		public long totalBytes() {
			return totalBytes;
		}

		public synchronized WorkspaceSnapshot putTable(String sourceId, List<String> columns,
				List<Map<String, Object>> rows, int inListKeyCount) {
			String evidenceId = "ev-" + sourceId + "-" + (snapshots.size() + 1);
			List<Map<String, Object>> safeRows = rows == null ? List.of() : rows;
			List<String> safeColumns = columns == null ? List.of() : List.copyOf(columns);
			int accepted = 0;
			int nonNullRows = 0;
			long acceptedBytes = 0;
			List<Map<String, Object>> stored = new ArrayList<>();
			boolean keyListLimited = inListKeyCount > MAX_IN_LIST_KEYS;
			ResultCoverageStatus coverage = keyListLimited ? ResultCoverageStatus.PLATFORM_LIMITED
					: ResultCoverageStatus.FULL;
			for (Map<String, Object> row : safeRows) {
				if (row == null) {
					continue;
				}
				nonNullRows++;
				Map<String, Object> copied = copyRow(row);
				long rowBytes = rowBytes(copied);
				if (totalRows + accepted >= MAX_ROWS_PER_SESSION
						|| totalBytes + acceptedBytes + rowBytes > MAX_BYTES_PER_SESSION) {
					coverage = ResultCoverageStatus.PLATFORM_LIMITED;
					break;
				}
				stored.add(copied);
				accepted++;
				acceptedBytes += rowBytes;
			}
			if (accepted < nonNullRows && coverage == ResultCoverageStatus.FULL) {
				coverage = ResultCoverageStatus.PLATFORM_LIMITED;
			}
			if (nonNullRows == 0 && !keyListLimited) {
				coverage = ResultCoverageStatus.SOURCE_SHORT;
			}
			totalRows += accepted;
			totalBytes += acceptedBytes;
			WorkspaceSnapshot snapshot = new WorkspaceSnapshot(sourceId, evidenceId, accepted, acceptedBytes, coverage,
					safeColumns, List.copyOf(stored));
			snapshots.add(snapshot);
			touch();
			return snapshot;
		}

		private Map<String, Object> copyRow(Map<String, Object> row) {
			LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
			row.forEach((key, value) -> {
				if (key != null) {
					copy.put(key, value);
				}
			});
			return copy;
		}

		boolean expired(Instant now) {
			return lastAccess.plus(TTL).isBefore(now);
		}

		void touch() {
			lastAccess = Instant.now();
		}

		private long rowBytes(Map<String, Object> row) {
			if (row == null || row.isEmpty()) {
				return 1L;
			}
			return Math.max(1L, String.valueOf(row).getBytes(StandardCharsets.UTF_8).length);
		}
	}

	public record WorkspaceSnapshot(String sourceId, String evidenceId, int rowCount, long byteSize,
			ResultCoverageStatus coverageStatus, List<String> columns, List<Map<String, Object>> rows) {

		public WorkspaceSnapshot {
			columns = columns == null ? List.of() : List.copyOf(columns);
			rows = rows == null ? List.of() : List.copyOf(rows);
			coverageStatus = coverageStatus == null ? ResultCoverageStatus.LIMIT_REACHED_UNKNOWN : coverageStatus;
		}
	}
}
