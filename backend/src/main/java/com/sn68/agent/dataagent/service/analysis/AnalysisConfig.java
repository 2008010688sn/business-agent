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
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * Parsed Skill analysis source graph. Empty {@code sources} means the draft has no analysisConfig.
 */
public record AnalysisConfig(List<AnalysisSource> sources, List<AnalysisAssociation> associations, String grain,
		List<String> nextFlows, List<String> nextWriteTools) {

	public static final String TYPE_TABLE = "TABLE";

	public static final String TYPE_FILE_TABLE = "FILE_TABLE";

	public static final String TYPE_FILE_DOC = "FILE_DOC";

	public static final String MATCH_EXACT = "EXACT";

	public static final String MATCH_NORMALIZED = "NORMALIZED";

	public AnalysisConfig {
		sources = sources == null ? List.of() : List.copyOf(sources);
		associations = associations == null ? List.of() : List.copyOf(associations);
		nextFlows = nextFlows == null ? List.of() : List.copyOf(nextFlows);
		nextWriteTools = nextWriteTools == null ? List.of() : List.copyOf(nextWriteTools);
		grain = StringUtils.hasText(grain) ? grain.trim() : null;
	}

	public static AnalysisConfig empty() {
		return new AnalysisConfig(List.of(), List.of(), null, List.of(), List.of());
	}

	public boolean present() {
		return !sources.isEmpty();
	}

	public boolean hasTableSource() {
		return sources.stream().anyMatch(AnalysisSource::isTable);
	}

	public boolean fileOnly() {
		return present() && !hasTableSource();
	}

	public record AnalysisSource(String id, String type, Long datasourceId, String table, List<String> columns,
			Long skillKnowledgeId, boolean turnFile) {

		public AnalysisSource {
			id = StringUtils.hasText(id) ? id.trim() : "";
			type = type == null ? "" : type.trim().toUpperCase();
			table = StringUtils.hasText(table) ? table.trim() : null;
			columns = columns == null ? List.of() : columns.stream().filter(StringUtils::hasText).map(String::trim)
				.distinct()
				.toList();
		}

		public boolean isTable() {
			return TYPE_TABLE.equals(type);
		}

		public boolean isFile() {
			return TYPE_FILE_TABLE.equals(type) || TYPE_FILE_DOC.equals(type);
		}
	}

	public record AnalysisAssociation(Endpoint left, Endpoint right, String match) {

		public AnalysisAssociation {
			match = StringUtils.hasText(match) ? match.trim().toUpperCase() : MATCH_EXACT;
		}

		public record Endpoint(String source, String field) {

			public Endpoint {
				source = StringUtils.hasText(source) ? source.trim() : "";
				field = StringUtils.hasText(field) ? field.trim() : "";
			}
		}
	}

	public Map<String, Object> toSnapshotMap() {
		return AnalysisConfigParser.toSnapshotMap(this);
	}
}
