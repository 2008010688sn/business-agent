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

import com.sn68.agent.dataagent.service.analysis.AnalysisConfig.AnalysisAssociation;
import com.sn68.agent.dataagent.service.analysis.AnalysisConfig.AnalysisAssociation.Endpoint;
import com.sn68.agent.dataagent.service.analysis.AnalysisConfig.AnalysisSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * Structural parser for draft {@code analysisConfig}. Binding/whitelist checks stay in the snapshot service.
 */
public final class AnalysisConfigParser {

	public record ParseResult(AnalysisConfig config, List<String> errors) {

		public boolean present() {
			return config != null && config.present();
		}

		public boolean valid() {
			return present() && (errors == null || errors.isEmpty());
		}
	}

	public ParseResult parse(Map<String, Object> draft) {
		if (draft == null || draft.isEmpty()) {
			return new ParseResult(AnalysisConfig.empty(), List.of());
		}
		List<String> errors = new ArrayList<>();
		Object rawSources = draft.get("sources");
		if (!(rawSources instanceof List<?> sourceList) || sourceList.isEmpty()) {
			errors.add("analysisConfig.sources must be a non-empty list");
			return new ParseResult(AnalysisConfig.empty(), List.copyOf(errors));
		}
		List<AnalysisSource> sources = new ArrayList<>();
		Set<String> sourceIds = new LinkedHashSet<>();
		int index = 0;
		for (Object raw : sourceList) {
			index++;
			AnalysisSource source = parseSource(raw, index, errors);
			if (source == null) {
				continue;
			}
			if (!StringUtils.hasText(source.id())) {
				errors.add("analysisConfig source[" + index + "] is missing id");
				continue;
			}
			if (!sourceIds.add(source.id())) {
				errors.add("analysisConfig source id is duplicated: " + source.id());
				continue;
			}
			sources.add(source);
		}
		List<AnalysisAssociation> associations = parseAssociations(draft.get("associations"), sourceIds, errors);
		String grain = text(draft.get("grain"));
		List<String> nextFlows = stringList(draft.get("nextFlows"));
		List<String> nextWriteTools = stringList(draft.get("nextWriteTools"));
		AnalysisConfig config = new AnalysisConfig(sources, associations, grain, nextFlows, nextWriteTools);
		if (config.sources().isEmpty() && errors.isEmpty()) {
			errors.add("analysisConfig.sources must contain at least one valid source");
		}
		return new ParseResult(config, List.copyOf(errors));
	}

	private AnalysisSource parseSource(Object raw, int index, List<String> errors) {
		if (!(raw instanceof Map<?, ?> map)) {
			errors.add("analysisConfig source[" + index + "] must be an object");
			return null;
		}
		String type = text(map.get("type")).toUpperCase();
		if (!Set.of(AnalysisConfig.TYPE_TABLE, AnalysisConfig.TYPE_FILE_TABLE, AnalysisConfig.TYPE_FILE_DOC)
			.contains(type)) {
			errors.add("analysisConfig source[" + index + "] type must be TABLE, FILE_TABLE or FILE_DOC");
			return null;
		}
		String id = text(map.get("id"));
		if (!StringUtils.hasText(id)) {
			errors.add("analysisConfig source[" + index + "] is missing id");
			return null;
		}
		if (AnalysisConfig.TYPE_TABLE.equals(type)) {
			Long datasourceId = longValue(map.get("datasourceId"));
			String table = text(map.get("table"));
			if (datasourceId == null || !StringUtils.hasText(table)) {
				errors.add("analysisConfig source[" + index + "] TABLE requires datasourceId and table");
				return null;
			}
			return new AnalysisSource(id, type, datasourceId, table, stringList(map.get("columns")), null, false);
		}
		Long skillKnowledgeId = longValue(map.get("skillKnowledgeId"));
		boolean turnFile = isTurnFile(map.get("turnFile"));
		if (skillKnowledgeId == null && !turnFile) {
			errors.add("analysisConfig source[" + index + "] file source requires skillKnowledgeId or turnFile");
			return null;
		}
		return new AnalysisSource(id, type, null, null, List.of(), skillKnowledgeId, turnFile);
	}

	private List<AnalysisAssociation> parseAssociations(Object raw, Set<String> sourceIds, List<String> errors) {
		if (raw == null) {
			return List.of();
		}
		if (!(raw instanceof List<?> list)) {
			errors.add("analysisConfig.associations must be a list");
			return List.of();
		}
		List<AnalysisAssociation> associations = new ArrayList<>();
		int index = 0;
		for (Object item : list) {
			index++;
			if (!(item instanceof Map<?, ?> map)) {
				errors.add("analysisConfig association[" + index + "] must be an object");
				continue;
			}
			Endpoint left = parseEndpoint(map.get("left"), "left", index, sourceIds, errors);
			Endpoint right = parseEndpoint(map.get("right"), "right", index, sourceIds, errors);
			String match = text(map.get("match"));
			if (!StringUtils.hasText(match)) {
				match = AnalysisConfig.MATCH_EXACT;
			}
			else {
				match = match.toUpperCase();
			}
			if (!AnalysisConfig.MATCH_EXACT.equals(match) && !AnalysisConfig.MATCH_NORMALIZED.equals(match)) {
				errors.add("analysisConfig association[" + index + "] match must be EXACT or NORMALIZED");
				continue;
			}
			if (left == null || right == null) {
				continue;
			}
			associations.add(new AnalysisAssociation(left, right, match));
		}
		return associations;
	}

	private Endpoint parseEndpoint(Object raw, String side, int index, Set<String> sourceIds, List<String> errors) {
		if (!(raw instanceof Map<?, ?> map)) {
			errors.add("analysisConfig association[" + index + "]." + side + " must be {source, field}");
			return null;
		}
		String source = text(map.get("source"));
		String field = text(map.get("field"));
		if (!StringUtils.hasText(source) || !StringUtils.hasText(field)) {
			errors.add("analysisConfig association[" + index + "]." + side + " requires source and field");
			return null;
		}
		if (!sourceIds.contains(source)) {
			errors.add("analysisConfig association[" + index + "]." + side + " references unknown source " + source);
			return null;
		}
		return new Endpoint(source, field);
	}

	static Map<String, Object> toSnapshotMap(AnalysisConfig config) {
		if (config == null || !config.present()) {
			return Map.of();
		}
		List<Map<String, Object>> sources = new ArrayList<>();
		for (AnalysisSource source : config.sources()) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", source.id());
			item.put("type", source.type());
			if (source.isTable()) {
				item.put("datasourceId", source.datasourceId());
				item.put("table", source.table());
				item.put("columns", List.copyOf(source.columns()));
			}
			else {
				if (source.skillKnowledgeId() != null) {
					item.put("skillKnowledgeId", source.skillKnowledgeId());
				}
				if (source.turnFile()) {
					item.put("turnFile", true);
				}
			}
			sources.add(Map.copyOf(item));
		}
		List<Map<String, Object>> associations = new ArrayList<>();
		for (AnalysisAssociation association : config.associations()) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("left", Map.of("source", association.left().source(), "field", association.left().field()));
			item.put("right", Map.of("source", association.right().source(), "field", association.right().field()));
			item.put("match", association.match());
			associations.add(Map.copyOf(item));
		}
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("sources", List.copyOf(sources));
		snapshot.put("associations", List.copyOf(associations));
		if (StringUtils.hasText(config.grain())) {
			snapshot.put("grain", config.grain());
		}
		snapshot.put("nextFlows", List.copyOf(config.nextFlows()));
		snapshot.put("nextWriteTools", List.copyOf(config.nextWriteTools()));
		return Map.copyOf(snapshot);
	}

	private boolean isTurnFile(Object value) {
		if (value instanceof Boolean flag) {
			return flag;
		}
		if (value instanceof Map<?, ?> map) {
			return !map.isEmpty();
		}
		return StringUtils.hasText(text(value)) && !"false".equalsIgnoreCase(text(value));
	}

	private List<String> stringList(Object value) {
		if (!(value instanceof List<?> list)) {
			return List.of();
		}
		return list.stream()
			.filter(item -> item != null && StringUtils.hasText(String.valueOf(item)))
			.map(item -> String.valueOf(item).trim())
			.distinct()
			.toList();
	}

	private Long longValue(Object value) {
		if (value instanceof Number number) {
			return number.longValue();
		}
		if (!StringUtils.hasText(text(value))) {
			return null;
		}
		try {
			return Long.valueOf(text(value));
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private String text(Object value) {
		return value == null ? "" : String.valueOf(value).trim();
	}
}
