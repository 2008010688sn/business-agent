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
package com.sn68.agent.dataagent.agentscope.tool.semantic;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.entity.SkillDatasource;
import com.sn68.agent.dataagent.entity.SemanticModel;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore;
import com.sn68.agent.dataagent.service.semantic.SemanticModelService;
import com.sn68.agent.dataagent.skill.execution.SkillVersionResources;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * 语义模型检索服务：在技能版本绑定的语义模型内按关键词检索表/列业务语义，
 * 命中结果同时写入答案追踪存储用于可解释性回放。
 */
@Service
@RequiredArgsConstructor
public class SemanticModelSearchService {

	private static final int DEFAULT_MAX_HITS = 8;

	private final SemanticModelService semanticModelService;

	private final AnswerTraceExplainStore answerTraceExplainStore;

	public SemanticModelSearchResult search(SemanticModelSearchRequest request, SkillVersionResources resources,
			@Nullable AgentRequest agentRequest) {
		String query = request == null ? null : request.getQuery();
		if (!StringUtils.hasText(query)) {
			throw new IllegalArgumentException("语义模型检索工具需要 query 参数");
		}
		resources = requireResources(resources);
		validateAgentRequest(agentRequest, resources);
		Long skillId = resources.skillId();
		SkillDatasource activeDatasource = resolveActiveDatasource(skillId, resources);
		if (activeDatasource == null || activeDatasource.getDatasourceId() == null) {
			return emptyResult(query, "当前没有可用于语义模型检索工具的活动数据源。");
		}
		TableSearchScope scope = resolveTableSearchScope(activeDatasource,
				request == null ? null : request.getTableNames());
		if (scope.isScoped() && CollectionUtils.isEmpty(scope.getTableNames())) {
			return emptyResult(query, "请求中指定的表超出了当前活动数据源对语义模型检索工具的可见范围。");
		}
		Set<Long> allowedSemanticIds = new LinkedHashSet<>(resources.semanticModelIds());
		List<SemanticModel> candidates = semanticModelService.getBySkillIdAndDatasourceIdAndIds(skillId,
				activeDatasource.getDatasourceId(), List.copyOf(allowedSemanticIds));
		if (scope.isScoped()) {
			candidates = candidates.stream()
				.filter(candidate -> scope.getTableNames().contains(normalizeTableName(candidate.getTableName())))
				.toList();
		}
		if (CollectionUtils.isEmpty(candidates)) {
			return emptyResult(query, "当前 Skill/表范围内没有匹配的已启用语义模型条目；物理表结构请改用数据源探索工具查看。");
		}

		List<ScoredHit> scoredHits = candidates.stream()
			.map(candidate -> score(query, candidate))
			.filter(Objects::nonNull)
			.sorted(Comparator.comparingInt(ScoredHit::getScore)
				.reversed()
				.thenComparing(scoredHit -> scoredHit.getModel().getCreateTime(),
						Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(scoredHit -> scoredHit.getModel().getTableName(),
						Comparator.nullsLast(String::compareToIgnoreCase))
				.thenComparing(scoredHit -> scoredHit.getModel().getColumnName(),
						Comparator.nullsLast(String::compareToIgnoreCase)))
			.limit(DEFAULT_MAX_HITS)
			.toList();

		if (scoredHits.isEmpty()) {
			return emptyResult(query, "没有匹配到补充语义提示；如果数据源探索工具已能回答物理表结构问题，就不要额外调用语义模型检索工具。");
		}

		List<SemanticModelSearchHit> hits = scoredHits.stream().map(this::toHit).toList();
		String summary = "共匹配到 %d 条补充语义提示。可直接按命中表和列编写 SQL；只有缺少类型或 JOIN 键时才调用 GET_TABLE_SCHEMA。"
			.formatted(hits.size());
		if (agentRequest != null) {
			answerTraceExplainStore.recordSemanticSearch(agentRequest, query, "共匹配到 %d 条补充语义提示".formatted(hits.size()),
					hits);
		}
		else {
			answerTraceExplainStore.recordSemanticSearch(query, "共匹配到 %d 条补充语义提示".formatted(hits.size()), hits);
		}
		return SemanticModelSearchResult.builder().summary(summary).hits(hits).resolution("matched").build();
	}

	private SemanticModelSearchResult emptyResult(String query, String summary) {
		return SemanticModelSearchResult.builder().summary(summary).resolution("no_match").build();
	}

	private SkillDatasource resolveActiveDatasource(Long skillId, SkillVersionResources resources) {
		if (resources == null || !resources.hasDatasourceAccess()) {
			return null;
		}
		SkillDatasource datasource = new SkillDatasource(skillId, resources.datasourceId());
		datasource.setSelectTables(resources.datasource().tables().stream()
			.map(SkillVersionResources.TableScope::table).toList());
		datasource.setSelectColumns(resources.datasource().tables().stream()
			.collect(java.util.stream.Collectors.toMap(SkillVersionResources.TableScope::table,
					SkillVersionResources.TableScope::columns, (left, right) -> left,
					java.util.LinkedHashMap::new)));
		return datasource;
	}

	private SkillVersionResources requireResources(SkillVersionResources resources) {
		if (resources == null || resources.skillId() == null || resources.skillVersionId() == null) {
			throw new IllegalArgumentException("Skill resource snapshot is required");
		}
		if (!resources.hasDatasourceAccess() || resources.semanticModelIds().isEmpty()) {
			throw new IllegalArgumentException("Skill semantic resource scope is empty");
		}
		return resources;
	}

	private void validateAgentRequest(@Nullable AgentRequest request, SkillVersionResources resources) {
		if (request == null) {
			return;
		}
		if (!resources.skillId().equals(request.getRoutedSkillId())
				|| !resources.skillVersionId().equals(request.getRoutedSkillVersionId())
				|| request.getRoutedSkillResources() == null
				|| !resources.skillId().equals(request.getRoutedSkillResources().skillId())
				|| !resources.skillVersionId().equals(request.getRoutedSkillResources().skillVersionId())) {
			throw new IllegalArgumentException("Routed Skill identity does not match its resource snapshot");
		}
	}

	private SemanticModelSearchHit toHit(ScoredHit scoredHit) {
		SemanticModel model = scoredHit.getModel();
		return SemanticModelSearchHit.builder()
			.tableName(model.getTableName())
			.columnName(model.getColumnName())
			.businessName(model.getBusinessName())
			.businessDescription(model.getBusinessDescription())
			.synonyms(model.getSynonyms())
			.columnComment(model.getColumnComment())
			.dataType(model.getDataType())
			.relationHint(extractRelationHint(model))
			.matchedBy(String.join(", ", scoredHit.getMatchedBy()))
			.score(scoredHit.getScore())
			.build();
	}

	private String extractRelationHint(SemanticModel model) {
		String[] candidates = { model.getBusinessDescription(), model.getColumnComment() };
		for (String candidate : candidates) {
			if (!StringUtils.hasText(candidate)) {
				continue;
			}
			String normalized = candidate.trim();
			if (normalized.contains("关联") || normalized.toLowerCase(Locale.ROOT).contains("join")
					|| normalized.contains("映射") || normalized.contains("外键")) {
				return normalized;
			}
		}
		return null;
	}

	private ScoredHit score(String query, SemanticModel model) {
		String normalizedQuery = normalize(query);
		List<String> tokens = tokenize(query);
		Set<String> matchedBy = new LinkedHashSet<>();
		int score = 0;
		score += scoreField(model.getBusinessName(), normalizedQuery, tokens, "businessName", 120, 80, 36, 12,
				matchedBy);
		score += scoreSynonyms(model.getSynonyms(), normalizedQuery, tokens, matchedBy);
		score += scoreField(model.getColumnName(), normalizedQuery, tokens, "columnName", 110, 74, 30, 10, matchedBy);
		score += scoreField(model.getTableName(), normalizedQuery, tokens, "tableName", 64, 42, 18, 6, matchedBy);
		score += scoreField(model.getBusinessDescription(), normalizedQuery, tokens, "businessDescription", 48, 30, 12,
				4, matchedBy);
		score += scoreField(model.getColumnComment(), normalizedQuery, tokens, "columnComment", 40, 24, 10, 3,
				matchedBy);
		score += scoreField(model.getDataType(), normalizedQuery, tokens, "dataType", 20, 14, 6, 2, matchedBy);
		if (score <= 0) {
			return null;
		}
		return new ScoredHit(model, score, List.copyOf(matchedBy));
	}

	private int scoreSynonyms(String synonyms, String normalizedQuery, List<String> tokens, Set<String> matchedBy) {
		if (!StringUtils.hasText(synonyms)) {
			return 0;
		}
		int score = 0;
		for (String synonym : splitSynonyms(synonyms)) {
			score += scoreField(synonym, normalizedQuery, tokens, "synonym", 108, 72, 28, 10, matchedBy);
		}
		return score;
	}

	private int scoreField(String fieldValue, String normalizedQuery, List<String> tokens, String matchedLabel,
			int exactScore, int containsScore, int tokenExactScore, int tokenContainsScore, Set<String> matchedBy) {
		if (!StringUtils.hasText(fieldValue) || !StringUtils.hasText(normalizedQuery)) {
			return 0;
		}
		String normalizedField = normalize(fieldValue);
		if (!StringUtils.hasText(normalizedField)) {
			return 0;
		}
		int score = 0;
		if (normalizedField.equals(normalizedQuery)) {
			score += exactScore;
		}
		else if (normalizedField.contains(normalizedQuery) || normalizedQuery.contains(normalizedField)) {
			score += containsScore;
		}
		for (String token : tokens) {
			if (!StringUtils.hasText(token)) {
				continue;
			}
			if (normalizedField.equals(token)) {
				score += tokenExactScore;
			}
			else if (normalizedField.contains(token)) {
				score += tokenContainsScore;
			}
		}
		if (score > 0) {
			matchedBy.add(matchedLabel);
		}
		return score;
	}

	private List<String> normalizeTableNames(List<String> tableNames) {
		if (CollectionUtils.isEmpty(tableNames)) {
			return List.of();
		}
		return tableNames.stream()
			.filter(StringUtils::hasText)
			.map(String::trim)
			.map(tableName -> tableName.toLowerCase(Locale.ROOT))
			.distinct()
			.toList();
	}

	private String normalizeTableName(String tableName) {
		return StringUtils.hasText(tableName) ? tableName.trim().toLowerCase(Locale.ROOT) : null;
	}

	private TableSearchScope resolveTableSearchScope(SkillDatasource activeDatasource, List<String> requestTableNames) {
		List<String> selectedTables = normalizeTableNames(activeDatasource.getSelectTables());
		List<String> requestedTables = normalizeTableNames(requestTableNames);
		if (CollectionUtils.isEmpty(selectedTables)) {
			return CollectionUtils.isEmpty(requestedTables) ? TableSearchScope.unbounded()
					: TableSearchScope.scoped(requestedTables);
		}
		if (CollectionUtils.isEmpty(requestedTables)) {
			return TableSearchScope.scoped(selectedTables);
		}
		return TableSearchScope.scoped(requestedTables.stream().filter(selectedTables::contains).distinct().toList());
	}

	private List<String> splitSynonyms(String synonyms) {
		String[] items = synonyms.split("[,，;；/|、\\s]+");
		List<String> result = new ArrayList<>(items.length);
		for (String item : items) {
			if (StringUtils.hasText(item)) {
				result.add(item.trim());
			}
		}
		return result;
	}

	private List<String> tokenize(String query) {
		return com.sn68.agent.dataagent.util.QueryTokenUtil.tokenize(query);
	}

	private String normalize(String value) {
		if (!StringUtils.hasText(value)) {
			return "";
		}
		return value.trim()
			.toLowerCase(Locale.ROOT)
			.replace('_', ' ')
			.replace('-', ' ')
			.replaceAll("[()\\[\\]{}]", " ")
			.replaceAll("[,，;；/|、]+", " ")
			.replaceAll("\\s+", " ");
	}

	private static final class ScoredHit {

		private final SemanticModel model;

		private final int score;

		private final List<String> matchedBy;

		private ScoredHit(SemanticModel model, int score, List<String> matchedBy) {
			this.model = model;
			this.score = score;
			this.matchedBy = matchedBy;
		}

		private SemanticModel getModel() {
			return model;
		}

		private int getScore() {
			return score;
		}

		private List<String> getMatchedBy() {
			return matchedBy;
		}

	}

	private static final class TableSearchScope {

		private final boolean unbounded;

		private final List<String> tableNames;

		private TableSearchScope(boolean unbounded, List<String> tableNames) {
			this.unbounded = unbounded;
			this.tableNames = tableNames;
		}

		private static TableSearchScope unbounded() {
			return new TableSearchScope(true, List.of());
		}

		private static TableSearchScope scoped(List<String> tableNames) {
			return new TableSearchScope(false, List.copyOf(tableNames));
		}

		private boolean isUnbounded() {
			return unbounded;
		}

		private boolean isScoped() {
			return !unbounded;
		}

		private List<String> getTableNames() {
			return tableNames;
		}

	}

}
