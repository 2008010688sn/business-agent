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

import java.util.List;
import org.springframework.util.StringUtils;

/**
 * Structured FILE_ONLY vs FILE_JOIN decision. JDBC tools stay off unless JOIN is explicit and keyed.
 */
public record AnalysisTurnDecision(Intent intent, boolean jdbcToolsEnabled, boolean askJoin, String missingJoinKey,
		String note) {

	public enum Intent {
		FILE_ONLY,
		FILE_JOIN,
		TABLE_QUERY
	}

	public static AnalysisTurnDecision tableQuery() {
		return new AnalysisTurnDecision(Intent.TABLE_QUERY, true, false, null, null);
	}

	public static AnalysisTurnDecision fileOnly(String note, boolean askJoin) {
		return new AnalysisTurnDecision(Intent.FILE_ONLY, false, askJoin, null, note);
	}

	public static AnalysisTurnDecision fileJoin(String missingJoinKey, String note) {
		boolean keyed = missingJoinKey == null || missingJoinKey.isBlank();
		return new AnalysisTurnDecision(Intent.FILE_JOIN, keyed, false, blankToNull(missingJoinKey), note);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	/**
	 * Join-key field names still missing. Classifier sentinels and copy notes are not keys.
	 */
	public List<String> missingJoinKeyFields() {
		if (!StringUtils.hasText(missingJoinKey)
				|| AnalysisTurnIntentClassifier.MISSING_JOIN_KEY.equals(missingJoinKey.trim())) {
			return List.of();
		}
		return List.of(missingJoinKey.trim());
	}

	/**
	 * Ask-join / no-table copy. Not a missing key.
	 */
	public String copyNote() {
		if (!StringUtils.hasText(note) || AnalysisTurnIntentClassifier.MISSING_JOIN_KEY.equals(note.trim())) {
			return null;
		}
		return note.trim();
	}
}
