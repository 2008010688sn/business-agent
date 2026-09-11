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
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * Rule-based FILE_ONLY vs FILE_JOIN classifier. Uncertain turns stay FILE_ONLY and ask; never silent JOIN.
 *
 * <p>Join keys must be declared associations. Missing keys are {@code missingJoinKey}; no semantic-similarity
 * substitute is applied.</p>
 */
public final class AnalysisTurnIntentClassifier {

	static final String NO_TABLE_NOTE = "当前技能未绑定业务表";

	static final String ASK_JOIN_NOTE = "是否要对业务表";

	public static final String MISSING_JOIN_KEY = "missingJoinKey";

	private static final Pattern FILE_POINTER = Pattern.compile(
			"这份|这个表|这个文件|这个excel|这个表格|附件|统计附件|本轮文件|这个pdf|csv|xlsx|excel|对账单",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern FILE_STATS = Pattern.compile("合计|求和|分组|有几条|统计|汇总|平均|均值|top\\s*n",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern JOIN_HINT = Pattern.compile(
			"对一下|对照|核对|对账(?!单)|查系统|关联|系统里|系统单据|对比系统|和库存|跟库存|和订单|跟订单");

	private static final Pattern BUSINESS_TABLE = Pattern.compile("系统|库存|订单库|业务表|库里的|系统里");

	public Set<String> extractJoinKeys(String query, AnalysisConfig config, Set<String> snapshotColumns) {
		Set<String> keys = new LinkedHashSet<>();
		if (snapshotColumns != null) {
			for (String column : snapshotColumns) {
				if (StringUtils.hasText(column)) {
					keys.add(column.trim());
				}
			}
		}
		if (config == null || !StringUtils.hasText(query)) {
			return keys;
		}
		String text = query;
		for (AnalysisAssociation association : config.associations()) {
			addMentionedField(keys, text, association.left().field());
			addMentionedField(keys, text, association.right().field());
		}
		return keys;
	}

	private void addMentionedField(Set<String> keys, String query, String field) {
		if (!StringUtils.hasText(field)) {
			return;
		}
		String trimmed = field.trim();
		if (query.contains(trimmed) || query.toLowerCase(Locale.ROOT).contains(normalize(trimmed))) {
			keys.add(trimmed);
		}
	}

	public AnalysisTurnDecision classify(String query, boolean hasAttachment, AnalysisConfig config,
			Set<String> extractedJoinKeys) {
		if (!hasAttachment) {
			return AnalysisTurnDecision.tableQuery();
		}
		String text = query == null ? "" : query;
		boolean joinHint = JOIN_HINT.matcher(text).find() || (BUSINESS_TABLE.matcher(text).find() && !FILE_STATS.matcher(text).find());
		boolean fileOnlyHint = (FILE_POINTER.matcher(text).find() || FILE_STATS.matcher(text).find())
				&& !BUSINESS_TABLE.matcher(text).find() && !JOIN_HINT.matcher(text).find();
		if (joinHint) {
			return fileJoin(config, extractedJoinKeys);
		}
		if (fileOnlyHint) {
			return AnalysisTurnDecision.fileOnly(null, false);
		}
		return AnalysisTurnDecision.fileOnly(ASK_JOIN_NOTE, true);
	}

	private AnalysisTurnDecision fileJoin(AnalysisConfig config, Set<String> extractedJoinKeys) {
		if (config == null || !config.hasTableSource()) {
			return AnalysisTurnDecision.fileOnly(NO_TABLE_NOTE, false);
		}
		String missing = missingJoinKey(config, extractedJoinKeys);
		if (missing != null) {
			return AnalysisTurnDecision.fileJoin(missing, MISSING_JOIN_KEY);
		}
		return AnalysisTurnDecision.fileJoin(null, null);
	}

	/**
	 * Returns a missing field name when extracted keys cannot satisfy declared associations.
	 * Null or empty extracted keys are missing — never invent a semantic-similarity substitute.
	 * Matching is EXACT or NORMALIZED (trim/case/separators) only.
	 */
	String missingJoinKey(AnalysisConfig config, Set<String> extractedJoinKeys) {
		if (config == null) {
			return MISSING_JOIN_KEY;
		}
		if (extractedJoinKeys == null) {
			return firstAssociationField(config);
		}
		Set<String> exact = new LinkedHashSet<>();
		Set<String> normalized = new LinkedHashSet<>();
		for (String key : extractedJoinKeys) {
			if (!StringUtils.hasText(key)) {
				continue;
			}
			exact.add(key.trim());
			normalized.add(normalize(key));
		}
		if (config.associations().isEmpty()) {
			return MISSING_JOIN_KEY;
		}
		for (AnalysisAssociation association : config.associations()) {
			boolean leftFile = isFileSource(config, association.left().source());
			boolean rightFile = isFileSource(config, association.right().source());
			if (leftFile && !matches(association.left().field(), association.match(), exact, normalized)) {
				return StringUtils.hasText(association.left().field()) ? association.left().field() : MISSING_JOIN_KEY;
			}
			if (rightFile && !matches(association.right().field(), association.match(), exact, normalized)) {
				return StringUtils.hasText(association.right().field()) ? association.right().field() : MISSING_JOIN_KEY;
			}
			if (!leftFile && !rightFile) {
				continue;
			}
		}
		return null;
	}

	private boolean isFileSource(AnalysisConfig config, String sourceId) {
		if (config == null || !StringUtils.hasText(sourceId)) {
			return false;
		}
		return config.sources().stream().anyMatch(source -> sourceId.equals(source.id()) && source.isFile());
	}

	private boolean matches(String field, String match, Set<String> exact, Set<String> normalized) {
		if (!StringUtils.hasText(field)) {
			return false;
		}
		if (AnalysisConfig.MATCH_NORMALIZED.equals(match)) {
			return normalized.contains(normalize(field));
		}
		return exact.contains(field.trim());
	}

	private String firstAssociationField(AnalysisConfig config) {
		if (config == null || config.associations().isEmpty()) {
			return MISSING_JOIN_KEY;
		}
		for (AnalysisAssociation association : config.associations()) {
			if (isFileSource(config, association.left().source()) && StringUtils.hasText(association.left().field())) {
				return association.left().field();
			}
			if (isFileSource(config, association.right().source()) && StringUtils.hasText(association.right().field())) {
				return association.right().field();
			}
		}
		AnalysisAssociation first = config.associations().get(0);
		return StringUtils.hasText(first.left().field()) ? first.left().field() : MISSING_JOIN_KEY;
	}

	static String normalize(String value) {
		if (value == null) {
			return "";
		}
		return value.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "");
	}
}
