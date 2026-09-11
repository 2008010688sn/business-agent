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

import com.sn68.agent.dataagent.service.analysis.AnalysisConfig;
import com.sn68.agent.dataagent.service.analysis.AnalysisTurnDecision;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * Inputs for packing an analysis-result Agent UI message. {@code actionDirections} stay out of nextActions.
 */
public record AnalysisUiOptions(String intent, List<String> nextFlows, List<String> nextWriteTools,
		Map<String, Object> prefilledSlots, List<String> missing, String grain, String note) {

	public static final String INTENT_FILE_ONLY = AnalysisTurnDecision.Intent.FILE_ONLY.name();

	public static final String INTENT_FILE_JOIN = AnalysisTurnDecision.Intent.FILE_JOIN.name();

	public static final String INTENT_TABLE_QUERY = AnalysisTurnDecision.Intent.TABLE_QUERY.name();

	public AnalysisUiOptions {
		intent = normalizeIntent(intent);
		nextFlows = nextFlows == null ? List.of()
				: nextFlows.stream().filter(StringUtils::hasText).map(String::trim).distinct().toList();
		nextWriteTools = nextWriteTools == null ? List.of()
				: nextWriteTools.stream().filter(StringUtils::hasText).map(String::trim).distinct().toList();
		prefilledSlots = prefilledSlots == null ? Map.of() : Map.copyOf(prefilledSlots);
		missing = missing == null ? List.of()
				: missing.stream().filter(StringUtils::hasText).map(String::trim).distinct().toList();
		grain = StringUtils.hasText(grain) ? grain.trim() : null;
		note = StringUtils.hasText(note) ? note.trim() : null;
	}

	public static AnalysisUiOptions defaults() {
		return new AnalysisUiOptions(INTENT_TABLE_QUERY, List.of(), List.of(), Map.of(), List.of(), null, null);
	}

	public static AnalysisUiOptions from(AnalysisTurnDecision decision, AnalysisConfig config) {
		return from(decision, config, Map.of());
	}

	public static AnalysisUiOptions from(AnalysisTurnDecision decision, AnalysisConfig config,
			Map<String, Object> prefilledSlots) {
		AnalysisConfig safeConfig = config == null ? AnalysisConfig.empty() : config;
		AnalysisTurnDecision safeDecision = decision == null ? AnalysisTurnDecision.tableQuery() : decision;
		return new AnalysisUiOptions(safeDecision.intent().name(), safeConfig.nextFlows(),
				safeConfig.nextWriteTools(), prefilledSlots, safeDecision.missingJoinKeyFields(), safeConfig.grain(),
				safeDecision.copyNote());
	}

	public boolean fileOnly() {
		return INTENT_FILE_ONLY.equals(intent);
	}

	private static String normalizeIntent(String intent) {
		if (!StringUtils.hasText(intent)) {
			return INTENT_TABLE_QUERY;
		}
		String normalized = intent.trim().toUpperCase();
		if (INTENT_FILE_ONLY.equals(normalized) || INTENT_FILE_JOIN.equals(normalized)
				|| INTENT_TABLE_QUERY.equals(normalized)) {
			return normalized;
		}
		return INTENT_TABLE_QUERY;
	}

}
