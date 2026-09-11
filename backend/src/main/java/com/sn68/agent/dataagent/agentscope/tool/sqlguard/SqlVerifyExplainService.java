/*
 * Copyright 2026 the original author or authors.
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
package com.sn68.agent.dataagent.agentscope.tool.sqlguard;

import lombok.extern.slf4j.Slf4j;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.bo.DbConfigBO;
import com.sn68.agent.dataagent.bo.schema.ColumnInfoBO;
import com.sn68.agent.dataagent.bo.schema.ResultSetBO;
import com.sn68.agent.dataagent.connector.DbQueryParameter;
import com.sn68.agent.dataagent.connector.accessor.Accessor;
import com.sn68.agent.dataagent.connector.accessor.AccessorFactory;
import com.sn68.agent.dataagent.entity.SkillDatasource;
import com.sn68.agent.dataagent.entity.Datasource;
import com.sn68.agent.dataagent.service.datasource.DatasourceService;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.service.report.ReportColumnSemantics;
import com.sn68.agent.dataagent.skill.execution.SkillVersionResources;
import com.sn68.agent.dataagent.util.SqlUtil;
import com.sn68.agent.dataagent.util.TopNLimitResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * SQL 意图一致性校验与数据画像服务：把候选 SQL 解析为语法树后，
 * 按聚合/分组/时间窗口/排序/去重等规则集逐项核对 query 意图与 SQL 结构是否一致，
 * 并可对目标表列生成小样本画像辅助模型自查。只做只读校验，不改写、不执行业务 SQL。
 */
@Slf4j
@Service
public class SqlVerifyExplainService {

	private static final String ACTION_SQL_VERIFY = "SQL_VERIFY";

	private static final String ACTION_DATA_PROFILE = "DATA_PROFILE";

	private static final int DEFAULT_PROFILE_LIMIT = 5;

	private static final int MAX_PROFILE_LIMIT = 20;

	private static final int DEFAULT_PROFILE_COLUMN_COUNT = 3;

	private static final Pattern AGGREGATE_PATTERN = Pattern
		.compile("(?i)\\b(count|sum|avg|average|min|max)\\s*\\(([^)]*)\\)\\s*(?:as\\s+([a-zA-Z0-9_]+))?");

	private static final Pattern GROUP_BY_PATTERN = Pattern.compile("(?is)\\bgroup\\s+by\\b");

	private static final Pattern ORDER_BY_PATTERN = Pattern.compile("(?is)\\border\\s+by\\b");

	private static final Pattern DISTINCT_PATTERN = Pattern
		.compile("(?is)\\bselect\\s+distinct\\b|count\\s*\\(\\s*distinct\\b");

	private static final Pattern DATE_LITERAL_PATTERN = Pattern
		.compile("\\b\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}\\b|\\b\\d{6,8}\\b");

	private static final Pattern EXPLICIT_DATE_QUERY_PATTERN = Pattern.compile(
			"(?i)(?<![a-z0-9_])20\\d{2}(?:[-/]\\d{1,2}(?:[-/]\\d{1,2})?|年(?:\\d{1,2}月(?:\\d{1,2}日?)?)?|年度)?(?![a-z0-9_])");

	private static final Pattern QUARTER_QUERY_PATTERN = Pattern.compile("(?i)(?<![a-z0-9_])q[1-4](?![a-z0-9_])|第?[一二三四1234]季度");

	private static final Pattern TIME_FUNCTION_PATTERN = Pattern.compile(
			"(?is)\\b(current_date|current_timestamp|now\\s*\\(|curdate\\s*\\(|date\\s*\\(|date_trunc\\s*\\(|strftime\\s*\\(|to_date\\s*\\(|to_timestamp\\s*\\(|datediff\\s*\\(|dateadd\\s*\\(|timestampdiff\\s*\\(|interval\\b)");

	private static final Pattern WHERE_PATTERN = Pattern.compile("(?is)\\bwhere\\b");

	private static final Pattern WHERE_CLAUSE_END_PATTERN = Pattern
		.compile("(?is)\\b(group\\s+by|having|order\\s+by|limit|fetch\\s+first|union)\\b");

	private static final Pattern SQL_STRING_LITERAL_PATTERN = Pattern.compile("'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"");

	private static final Pattern SQL_IDENTIFIER_PATTERN = Pattern.compile("(?i)\\b[a-z_][a-z0-9_]*\\b");

	private static final Pattern DESC_PATTERN = Pattern.compile("(?is)\\border\\s+by\\b.+?\\bdesc\\b");

	private static final Pattern ASC_PATTERN = Pattern.compile("(?is)\\border\\s+by\\b.+?\\basc\\b");

	private static final Pattern STATUS_COLUMN_PATTERN = Pattern
		.compile("(?is)\\b(status|order_status|payment_status|trade_status|state)\\b");

	private static final Pattern NEGATIVE_STATUS_OPERATOR_PATTERN = Pattern
		.compile("(?is)(<>|!=|not\\s+in\\s*\\(|not\\s+like\\b)");

	private final DatasourceService datasourceService;

	private final AccessorFactory accessorFactory;

	private final DataAgentProperties dataAgentProperties;

	public SqlVerifyExplainService(DatasourceService datasourceService, AccessorFactory accessorFactory,
			DataAgentProperties dataAgentProperties) {
		this.datasourceService = datasourceService;
		this.accessorFactory = accessorFactory;
		this.dataAgentProperties = dataAgentProperties;
	}

	public SqlGuardCheckResult explain(SqlGuardCheckRequest request) {
		String query = StringUtils.trimToEmpty(request == null ? null : request.getQuery());
		String sql = StringUtils.trimToEmpty(request == null ? null : request.getSql());
		String humanFeedbackContent = StringUtils
			.trimToEmpty(request == null ? null : request.getHumanFeedbackContent());
		if (StringUtils.isBlank(query)) {
			throw new IllegalArgumentException("SQL 校验工具需要 query");
		}
		if (StringUtils.isBlank(sql)) {
			throw new IllegalArgumentException("SQL 校验工具需要 sql");
		}

		String effectiveIntentSource = mergeIntentSource(query, humanFeedbackContent);
		QueryIntent intent = analyzeQueryIntent(effectiveIntentSource);
		HumanFeedbackConstraint feedbackConstraint = analyzeHumanFeedbackConstraint(humanFeedbackContent);

		Statement statement;
		try {
			statement = parseSingleSelectStatement(sql);
		}
		catch (IllegalArgumentException ex) {
			return sqlParseErrorResult(ex);
		}

		SqlShape shape = analyzeSqlShape(statement, sql, request);
		List<SqlGuardProblem> problems = new ArrayList<>();
		Set<String> fixSuggestions = new LinkedHashSet<>();
		List<SqlGuardRuleCheck> ruleChecks = new ArrayList<>();

		evaluateAggregationRule(query, sql, intent, shape, problems, fixSuggestions, ruleChecks);
		evaluateGroupingRule(query, sql, intent, shape, problems, fixSuggestions, ruleChecks);
		evaluateTimeFilterRule(query, sql, intent, shape, problems, fixSuggestions, ruleChecks);
		evaluateTimeBucketRule(sql, intent, shape, problems, fixSuggestions, ruleChecks);
		evaluateTimeOrderRule(query, sql, intent, shape, problems, fixSuggestions, ruleChecks);
		evaluateOrderingRule(query, sql, intent, shape, problems, fixSuggestions, ruleChecks);
		evaluateLimitRule(query, sql, intent, shape, problems, fixSuggestions, ruleChecks);
		evaluateDistinctRule(query, sql, intent, shape, problems, fixSuggestions, ruleChecks);
		evaluateOrderDirectionRule(sql, intent, shape, problems, fixSuggestions, ruleChecks);
		evaluateSelectStarRule(statement, sql, problems, fixSuggestions, ruleChecks);
		evaluateDeletedRule(statement, sql, problems, fixSuggestions, ruleChecks);
		evaluateHumanFeedbackRule(query, sql, humanFeedbackContent, feedbackConstraint, problems, fixSuggestions,
				ruleChecks);

		boolean aligned = problems.stream().noneMatch(problem -> isBlockingSeverity(problem.getSeverity()));
		String summary = aligned ? "SQL 通过了当前规则版意图一致性校验。" : "检测到 %d 个可能影响答案正确性的意图一致性问题。".formatted(problems.size());
		if (aligned) {
			fixSuggestions.add("当前规则校验通过；如要进一步提高置信度，可继续核对执行结果与最终答案解释。");
		}
		return SqlGuardCheckResult.builder()
			.decision(aligned ? "safe_to_execute" : "revise_sql")
			.isAligned(aligned)
			.errorCode(aligned ? null : primaryErrorCode(problems))
			.summary(summary)
			.normalizedSql(aligned ? statement.toString() : null)
			.problems(problems)
			.fixSuggestions(List.copyOf(fixSuggestions))
			.ruleChecks(ruleChecks)
			.build();
	}

	/** SQL 语法解析失败时的固定校验结论：标记为需修订，并给出修复引导。 */
	private SqlGuardCheckResult sqlParseErrorResult(IllegalArgumentException ex) {
		return SqlGuardCheckResult.builder()
			.decision("revise_sql")
			.isAligned(false)
			.errorCode("SQL_PARSE_ERROR")
			.summary("SQL 无法通过语法解析，无法继续做结构和意图一致性校验。")
			.problems(List.of(SqlGuardProblem.builder()
				.code("SQL_PARSE_ERROR")
				.title("SQL 语法解析失败")
				.severity("high")
				.message("SQL 无法解析，当前结果不能视为已校验通过。")
				.why("校验器必须先把 SQL 解析成合法的 SELECT / WITH 语法树，才能继续检查聚合、分组、排序和时间窗口。")
				.expected("输入应为单条可解析的只读 SELECT / WITH 查询。")
				.actual("当前 SQL 在语法层面未通过解析。")
				.evidence(ex.getMessage())
				.repairHint("先修复括号、关键字顺序、逗号、别名或多语句拼接问题，再重新调用 SQL 校验工具。")
				.build()))
			.fixSuggestions(List.of("先修复 SQL 语法错误，再重新调用 SQL 校验工具。"))
			.ruleChecks(List.of(SqlGuardRuleCheck.builder()
				.code("SQL_PARSE")
				.title("SQL 语法解析")
				.status("FAILED")
				.detail("当前 SQL 未通过语法解析，后续结构规则无法继续执行。")
				.evidence(ex.getMessage())
				.build()))
			.build();
	}

	/**
	 * Verifies a candidate SQL statement without exposing the internal tool DTO.
	 */
	public SqlGuardVerification verifySql(String query, String sql, String humanFeedbackContent) {
		SqlGuardCheckRequest request = new SqlGuardCheckRequest();
		request.setQuery(query);
		request.setSql(sql);
		request.setHumanFeedbackContent(humanFeedbackContent);
		SqlGuardCheckResult result = explain(request);
		return new SqlGuardVerification(Boolean.TRUE.equals(result.getIsAligned()), result.getNormalizedSql(),
				result.getErrorCode(), result.getSummary(), result.getFixSuggestions());
	}

	public record SqlGuardVerification(boolean aligned, String normalizedSql, String errorCode, String summary,
			List<String> fixSuggestions) {

		public SqlGuardVerification {
			fixSuggestions = fixSuggestions == null ? List.of() : List.copyOf(fixSuggestions);
		}
	}

	private String primaryErrorCode(List<SqlGuardProblem> problems) {
		if (problems == null) {
			return "SQL_POLICY_REJECTED";
		}
		return problems.stream()
			.filter(problem -> problem != null && isBlockingSeverity(problem.getSeverity()))
			.map(SqlGuardProblem::getCode)
			.filter(StringUtils::isNotBlank)
			.findFirst()
			.orElse("SQL_POLICY_REJECTED");
	}

	public SqlGuardCheckResult inspectProfile(AgentRequest runtimeRequest, SqlGuardCheckRequest request) {
		String tableName = StringUtils.trimToEmpty(request == null ? null : request.getTableName());
		if (StringUtils.isBlank(tableName)) {
			throw new IllegalArgumentException("SQL 校验工具在 action=DATA_PROFILE 时必须提供 tableName");
		}
		ProfileContext context = resolveProfileContext(runtimeRequest);
		String actualTableName = resolveVisibleTableName(context, tableName);
		List<ColumnInfoBO> availableColumns = loadTableColumns(context, actualTableName);
		List<ColumnInfoBO> visibleColumns = applyVisibleColumnRestrictions(context, actualTableName, availableColumns);
		if (visibleColumns.isEmpty()) {
			throw new IllegalArgumentException("表 '%s' 在当前 Agent 下没有可见字段".formatted(actualTableName));
		}
		List<ColumnInfoBO> columnsToInspect = resolveColumnsToInspect(request, actualTableName, visibleColumns);
		int sampleLimit = normalizeProfileLimit(request == null ? null : request.getLimit());
		long totalRows = querySingleLong(context,
				"SELECT COUNT(*) AS total_rows FROM " + quoteTable(context, actualTableName), "total_rows");
		List<Map<String, Object>> columnProfiles = columnsToInspect.stream()
			.map(column -> buildColumnProfile(context, actualTableName, column, totalRows, sampleLimit))
			.toList();
		String summary = "仅基于可见字段对表 '%s' 的 %d 个字段完成 profile 分析。".formatted(actualTableName, columnProfiles.size());
		return SqlGuardCheckResult.builder()
			.decision("inspect_columns")
			.tableName(actualTableName)
			.summary(summary)
			.totalRows(totalRows)
			.columnProfiles(columnProfiles)
			.fixSuggestions(
					List.of("可优先把高频值集中的分类字段用作过滤条件或 GROUP BY 候选字段。", "可优先把具备 min/max 范围的数值或时间字段用作指标、趋势或时间窗口候选字段。"))
			.build();
	}

	private ProfileContext resolveProfileContext(AgentRequest request) {
		SkillVersionResources resources = requireDatasourceResources(request);
		SkillDatasource skillDatasource = snapshotDatasource(resources);
		Datasource datasource = datasourceService.requireDatasourceForTenant(skillDatasource.getDatasourceId(),
				requireTenantId(request));
		if (datasource == null) {
			throw new IllegalStateException("Routed Skill datasource does not exist: " + skillDatasource.getDatasourceId());
		}
		DbConfigBO dbConfig = datasourceService.getDbConfig(datasource);
		Accessor accessor = accessorFactory.getAccessorByDbConfig(dbConfig);
		List<String> visibleTables = skillDatasource.getSelectTables();
		if (visibleTables == null || visibleTables.isEmpty()) {
			throw new IllegalArgumentException("Skill datasource snapshot has an empty table whitelist");
		}
		Map<String, List<String>> visibleTablesByName = indexTables(visibleTables, false);
		Map<String, List<String>> visibleTablesByLeafName = indexTables(visibleTables, true);
		Map<String, List<String>> visibleColumnsByTable = buildVisibleColumnsByTable(skillDatasource,
				visibleTablesByName, visibleTablesByLeafName);
		Map<String, Set<String>> visibleColumnNameSetByTable = new LinkedHashMap<>();
		visibleColumnsByTable.forEach((key, value) -> visibleColumnNameSetByTable.put(key,
				value.stream().map(this::normalizeColumnName)
					.collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll)));
		return new ProfileContext(skillDatasource, datasource, dbConfig, accessor, List.copyOf(visibleTables),
				Map.copyOf(visibleTablesByName), Map.copyOf(visibleTablesByLeafName), Map.copyOf(visibleColumnsByTable),
				Map.copyOf(visibleColumnNameSetByTable), Set.copyOf(visibleColumnsByTable.keySet()));
	}

	private SkillVersionResources requireDatasourceResources(AgentRequest request) {
		if (request == null || request.getRoutedSkillResources() == null || request.getRoutedSkillId() == null
				|| request.getRoutedSkillVersionId() == null) {
			throw new IllegalArgumentException("Skill resource snapshot is required");
		}
		SkillVersionResources resources = request.getRoutedSkillResources();
		if (!resources.hasDatasourceAccess() || !request.getRoutedSkillId().equals(resources.skillId())
				|| !request.getRoutedSkillVersionId().equals(resources.skillVersionId())) {
			throw new IllegalArgumentException("Routed Skill identity does not match its datasource snapshot");
		}
		return resources;
	}

	private String requireTenantId(AgentRequest request) {
		if (request == null || StringUtils.isBlank(request.getTenantIdSnapshot())) {
			throw new IllegalArgumentException("Tenant permission snapshot is required");
		}
		return request.getTenantIdSnapshot().trim();
	}

	private SkillDatasource snapshotDatasource(SkillVersionResources resources) {
		SkillDatasource datasource = new SkillDatasource(resources.skillId(), resources.datasourceId());
		datasource.setSelectTables(resources.datasource().tables().stream()
			.map(SkillVersionResources.TableScope::table).toList());
		Map<String, List<String>> columns = new LinkedHashMap<>();
		resources.datasource().tables().forEach(table -> columns.put(table.table(), table.columns()));
		datasource.setSelectColumns(Map.copyOf(columns));
		return datasource;
	}

	private List<ColumnInfoBO> loadTableColumns(ProfileContext context, String tableName) {
		try {
			return Optional
				.ofNullable(context.accessor()
					.showColumns(context.dbConfig(),
							DbQueryParameter.from(context.dbConfig())
								.setSchema(context.dbConfig().getSchema())
								.setTable(tableName)))
				.orElse(List.of());
		}
		catch (Exception ex) {
			throw new IllegalStateException("加载表 '%s' 的字段失败：%s".formatted(tableName, ex.getMessage()), ex);
		}
	}

	private List<ColumnInfoBO> applyVisibleColumnRestrictions(ProfileContext context, String tableName,
			List<ColumnInfoBO> columns) {
		return Optional.ofNullable(columns)
			.orElse(List.of())
			.stream()
			.filter(column -> isColumnVisible(context, tableName, column.getName()))
			.toList();
	}

	private List<ColumnInfoBO> resolveColumnsToInspect(SqlGuardCheckRequest request, String tableName,
			List<ColumnInfoBO> visibleColumns) {
		Map<String, ColumnInfoBO> columnsByName = new LinkedHashMap<>();
		for (ColumnInfoBO column : visibleColumns) {
			columnsByName.put(normalizeColumnName(column.getName()), column);
		}
		List<String> requestedColumns = Optional.ofNullable(request == null ? null : request.getColumnNames())
			.orElse(List.of())
			.stream()
			.filter(StringUtils::isNotBlank)
			.map(String::trim)
			.toList();
		if (requestedColumns.isEmpty()) {
			return visibleColumns.stream()
				.filter(column -> !ReportColumnSemantics.isInternalColumn(column.getName()))
				.limit(DEFAULT_PROFILE_COLUMN_COUNT)
				.toList();
		}
		List<ColumnInfoBO> resolvedColumns = new ArrayList<>();
		for (String requestedColumn : requestedColumns) {
			if (ReportColumnSemantics.isInternalColumn(requestedColumn)) {
				continue;
			}
			ColumnInfoBO column = columnsByName.get(normalizeColumnName(requestedColumn));
			if (column == null) {
				throw new IllegalArgumentException(
						"字段 '%s' 在表 '%s' 中对当前 Agent 不可见".formatted(requestedColumn, tableName));
			}
			resolvedColumns.add(column);
		}
		return resolvedColumns;
	}

	private Map<String, Object> buildColumnProfile(ProfileContext context, String tableName, ColumnInfoBO column,
			long totalRows, int sampleLimit) {
		String quotedTable = quoteTable(context, tableName);
		String quotedColumn = SqlUtil.quoteIdentifier(context.dbConfig().getDialectType(), column.getName());
		long nullCount = querySingleLong(context,
				"SELECT COUNT(*) AS null_rows FROM %s WHERE %s IS NULL".formatted(quotedTable, quotedColumn),
				"null_rows");
		Double nullRatio = totalRows <= 0 ? 0D : roundRatio((double) nullCount / (double) totalRows);
		Long distinctCount = null;
		if (supportsDistinctCount(column)) {
			distinctCount = querySingleLong(context,
					"SELECT COUNT(DISTINCT %s) AS distinct_count FROM %s".formatted(quotedColumn, quotedTable),
					"distinct_count");
		}
		List<Map<String, Object>> topValues = supportsGroupedTopValues(column)
				? queryTopValues(context, quotedTable, quotedColumn, sampleLimit) : List.of();
		List<String> sampleValues = querySampleValues(context, quotedTable, quotedColumn, sampleLimit,
				supportsDistinctCount(column));
		String minValue = supportsMinMax(column) ? querySingleValue(context,
				"SELECT MIN(%s) AS min_value FROM %s".formatted(quotedColumn, quotedTable), "min_value") : null;
		String maxValue = supportsMinMax(column) ? querySingleValue(context,
				"SELECT MAX(%s) AS max_value FROM %s".formatted(quotedColumn, quotedTable), "max_value") : null;
		Map<String, Object> profile = new LinkedHashMap<>();
		profile.put("columnName", column.getName());
		profile.put("dataType", column.getType());
		profile.put("notNull", column.isNotnull());
		profile.put("nullCount", nullCount);
		profile.put("nullRatio", nullRatio);
		profile.put("distinctCount", distinctCount);
		profile.put("sampleValues", sampleValues);
		profile.put("topValues", topValues);
		profile.put("min", minValue);
		profile.put("max", maxValue);
		profile.put("profileHints", buildProfileHints(column, nullRatio, distinctCount, totalRows, topValues));
		return profile;
	}

	private List<Map<String, Object>> queryTopValues(ProfileContext context, String quotedTable, String quotedColumn,
			int sampleLimit) {
		String sql = applyLimit("""
				SELECT %s AS profile_value, COUNT(*) AS profile_count
				FROM %s
				WHERE %s IS NOT NULL
				GROUP BY %s
				ORDER BY profile_count DESC
				""".formatted(quotedColumn, quotedTable, quotedColumn, quotedColumn),
				context.dbConfig().getDialectType(), sampleLimit);
		ResultSetBO resultSet = executeSql(context, sql);
		List<Map<String, Object>> values = new ArrayList<>();
		for (Map<String, String> row : Optional.ofNullable(resultSet.getData()).orElse(List.of())) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("value", row.get("profile_value"));
			entry.put("count", parseLong(row.get("profile_count")));
			values.add(entry);
		}
		return values;
	}

	private List<String> querySampleValues(ProfileContext context, String quotedTable, String quotedColumn,
			int sampleLimit, boolean distinctPreferred) {
		String selectClause = distinctPreferred ? "SELECT DISTINCT %s AS sample_value".formatted(quotedColumn)
				: "SELECT %s AS sample_value".formatted(quotedColumn);
		String sql = applyLimit("""
				%s
				FROM %s
				WHERE %s IS NOT NULL
				ORDER BY %s
				""".formatted(selectClause, quotedTable, quotedColumn, quotedColumn),
				context.dbConfig().getDialectType(), sampleLimit);
		ResultSetBO resultSet = executeSql(context, sql);
		return Optional.ofNullable(resultSet.getData())
			.orElse(List.of())
			.stream()
			.map(row -> row.get("sample_value"))
			.filter(StringUtils::isNotBlank)
			.toList();
	}

	private List<String> buildProfileHints(ColumnInfoBO column, Double nullRatio, Long distinctCount, long totalRows,
			List<Map<String, Object>> topValues) {
		List<String> hints = new ArrayList<>();
		if (Boolean.TRUE.equals(isLikelyCategorical(column, distinctCount, totalRows, topValues))) {
			hints.add("该字段很可能是枚举或分类字段，适合用于过滤条件或 GROUP BY。");
		}
		if (supportsMinMax(column)) {
			hints.add("该字段很可能具备顺序语义，适合用于范围过滤、指标计算或趋势轴。");
		}
		if (nullRatio != null && nullRatio >= 0.5D) {
			hints.add("该字段空值比例较高，作为强过滤条件时需要谨慎。");
		}
		if (hints.isEmpty()) {
			hints.add("请先结合样例值和高频值判断，再决定是否将该字段写入 SQL。");
		}
		return hints;
	}

	private Boolean isLikelyCategorical(ColumnInfoBO column, Long distinctCount, long totalRows,
			List<Map<String, Object>> topValues) {
		if (!supportsGroupedTopValues(column)) {
			return false;
		}
		if (distinctCount != null && distinctCount > 0 && distinctCount <= 20) {
			return true;
		}
		if (totalRows > 0 && distinctCount != null && distinctCount <= Math.max(10, totalRows / 10)) {
			return true;
		}
		return !topValues.isEmpty() && topValues.size() <= 10;
	}

	private long querySingleLong(ProfileContext context, String sql, String columnName) {
		return parseLong(querySingleValue(context, sql, columnName));
	}

	private String querySingleValue(ProfileContext context, String sql, String columnName) {
		ResultSetBO resultSet = executeSql(context, sql);
		List<Map<String, String>> rows = Optional.ofNullable(resultSet.getData()).orElse(List.of());
		if (rows.isEmpty()) {
			return null;
		}
		Map<String, String> row = rows.get(0);
		if (row.containsKey(columnName)) {
			return row.get(columnName);
		}
		return row.values().stream().findFirst().orElse(null);
	}

	private ResultSetBO executeSql(ProfileContext context, String sql) {
		try {
			ResultSetBO resultSet = context.accessor()
				.executeSqlAndReturnObject(context.dbConfig(),
						DbQueryParameter.from(context.dbConfig())
							.setSchema(context.dbConfig().getSchema())
							.setSql(sql));
			if (resultSet == null) {
				return ResultSetBO.builder().column(List.of()).data(List.of()).build();
			}
			if (StringUtils.isNotBlank(resultSet.getErrorMsg())) {
				throw new IllegalStateException(resultSet.getErrorMsg());
			}
			if (resultSet.getColumn() == null) {
				resultSet.setColumn(List.of());
			}
			if (resultSet.getData() == null) {
				resultSet.setData(List.of());
			}
			return resultSet;
		}
		catch (Exception ex) {
			throw new IllegalStateException("执行 profile SQL 失败：" + ex.getMessage(), ex);
		}
	}

	private int normalizeProfileLimit(Integer requestedLimit) {
		if (requestedLimit == null || requestedLimit <= 0) {
			return DEFAULT_PROFILE_LIMIT;
		}
		return Math.min(requestedLimit, MAX_PROFILE_LIMIT);
	}

	private boolean supportsDistinctCount(ColumnInfoBO column) {
		String normalizedType = normalizeType(column);
		return !containsAny(normalizedType, "blob", "clob", "text", "ntext", "image", "json", "xml", "bytea");
	}

	private boolean supportsGroupedTopValues(ColumnInfoBO column) {
		String normalizedType = normalizeType(column);
		return !containsAny(normalizedType, "blob", "clob", "ntext", "image", "bytea");
	}

	private boolean supportsMinMax(ColumnInfoBO column) {
		String normalizedType = normalizeType(column);
		return containsAny(normalizedType, "int", "number", "numeric", "decimal", "double", "float", "real", "date",
				"time", "year", "timestamp");
	}

	private String normalizeType(ColumnInfoBO column) {
		return StringUtils.defaultString(column == null ? null : column.getType()).toLowerCase(Locale.ROOT);
	}

	private String applyLimit(String sql, String dialectType, int limit) {
		String trimmed = StringUtils.trimToEmpty(sql);
		if (trimmed.isEmpty()) {
			return trimmed;
		}
		String normalizedDialect = StringUtils.defaultString(dialectType).toLowerCase(Locale.ROOT);
		if (normalizedDialect.contains("sqlserver") || normalizedDialect.contains("sql_server")) {
			if (trimmed.matches("(?is)^select\\s+distinct\\b.*")) {
				return trimmed.replaceFirst("(?is)^select\\s+distinct\\b", "SELECT DISTINCT TOP %d".formatted(limit));
			}
			return trimmed.replaceFirst("(?is)^select\\b", "SELECT TOP %d".formatted(limit));
		}
		if (normalizedDialect.contains("oracle")) {
			return trimmed + " FETCH FIRST " + limit + " ROWS ONLY";
		}
		return trimmed + " LIMIT " + limit;
	}

	private String quoteTable(ProfileContext context, String tableName) {
		return SqlUtil.quoteIdentifier(context.dbConfig().getDialectType(), tableName);
	}

	private double roundRatio(double value) {
		return Math.round(value * 10000D) / 10000D;
	}

	private long parseLong(String value) {
		if (StringUtils.isBlank(value)) {
			return 0L;
		}
		try {
			return Long.parseLong(value.trim());
		}
		catch (NumberFormatException ex) {
			try {
				return Math.round(Double.parseDouble(value.trim()));
			}
			catch (NumberFormatException ignored) {
				return 0L;
			}
		}
	}

	private String resolveVisibleTableName(ProfileContext context, String tableName) {
		return findVisibleTableName(context.visibleTablesByName(), context.visibleTablesByLeafName(), tableName, false)
			.orElseThrow(() -> new IllegalArgumentException(
					"表 '%s' 对当前 Agent 不可见。当前可见表：%s".formatted(tableName, String.join(", ", context.visibleTables()))));
	}

	private Optional<String> findVisibleTableName(Map<String, List<String>> visibleTablesByName,
			Map<String, List<String>> visibleTablesByLeafName, String tableName, boolean allowQualifiedFallback) {
		String normalizedTableName = normalizeIdentifier(tableName);
		List<String> exactMatches = visibleTablesByName.getOrDefault(normalizedTableName, List.of());
		if (exactMatches.size() == 1) {
			return Optional.of(exactMatches.get(0));
		}
		if (exactMatches.size() > 1) {
			throw new IllegalArgumentException(
					"表 '%s' 映射到了多张当前可见表：%s".formatted(tableName, String.join(", ", exactMatches)));
		}
		if (isQualifiedIdentifier(tableName) && !allowQualifiedFallback) {
			return Optional.empty();
		}
		List<String> leafMatches = visibleTablesByLeafName.getOrDefault(normalizeTableLeafName(tableName), List.of());
		if (leafMatches.size() == 1) {
			return Optional.of(leafMatches.get(0));
		}
		if (leafMatches.size() > 1) {
			throw new IllegalArgumentException(
					"表 '%s' 在当前可见表范围内存在歧义：%s".formatted(tableName, String.join(", ", leafMatches)));
		}
		return Optional.empty();
	}

	private Map<String, List<String>> buildVisibleColumnsByTable(SkillDatasource skillDatasource,
			Map<String, List<String>> visibleTablesByName, Map<String, List<String>> visibleTablesByLeafName) {
		Map<String, List<String>> selectedColumns = Optional.ofNullable(skillDatasource.getSelectColumns())
			.orElse(Map.of());
		Map<String, List<String>> visibleColumnsByTable = new LinkedHashMap<>();
		selectedColumns.forEach((tableName, columns) -> {
			Optional<String> resolvedTableName = findVisibleTableName(visibleTablesByName, visibleTablesByLeafName,
					tableName, true);
			if (resolvedTableName.isEmpty()) {
				return;
			}
			List<String> sanitizedColumns = Optional.ofNullable(columns)
				.orElse(List.of())
				.stream()
				.filter(StringUtils::isNotBlank)
				.map(String::trim)
				.distinct()
				.toList();
			if (!sanitizedColumns.isEmpty()) {
				visibleColumnsByTable.put(normalizeTableName(resolvedTableName.get()), sanitizedColumns);
			}
		});
		return visibleColumnsByTable;
	}

	private Map<String, List<String>> indexTables(List<String> tableNames, boolean leafOnly) {
		Map<String, List<String>> index = new LinkedHashMap<>();
		for (String tableName : Optional.ofNullable(tableNames).orElse(List.of())) {
			if (StringUtils.isBlank(tableName)) {
				continue;
			}
			String key = leafOnly ? normalizeTableLeafName(tableName) : normalizeTableName(tableName);
			index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(tableName);
		}
		return index;
	}

	private boolean isColumnVisible(ProfileContext context, String tableName, String columnName) {
		String normalizedTableName = normalizeTableName(tableName);
		if (!context.columnRestrictedTables().contains(normalizedTableName)) {
			return true;
		}
		Set<String> visibleColumns = context.visibleColumnNameSetByTable().get(normalizedTableName);
		return visibleColumns != null && visibleColumns.contains(normalizeColumnName(columnName));
	}

	private boolean isQualifiedIdentifier(String value) {
		return normalizeIdentifier(value).contains(".");
	}

	private String normalizeIdentifier(String value) {
		String normalized = StringUtils.trimToEmpty(value);
		normalized = StringUtils.removeStart(normalized, "`");
		normalized = StringUtils.removeEnd(normalized, "`");
		normalized = StringUtils.removeStart(normalized, "\"");
		normalized = StringUtils.removeEnd(normalized, "\"");
		normalized = StringUtils.removeStart(normalized, "[");
		normalized = StringUtils.removeEnd(normalized, "]");
		return normalized.toLowerCase(Locale.ROOT);
	}

	private String normalizeTableName(String tableName) {
		return normalizeIdentifier(tableName);
	}

	private String normalizeTableLeafName(String tableName) {
		String normalized = normalizeIdentifier(tableName);
		int lastDot = normalized.lastIndexOf('.');
		return lastDot >= 0 ? normalized.substring(lastDot + 1) : normalized;
	}

	private String normalizeColumnName(String columnName) {
		return normalizeTableLeafName(columnName);
	}

	private void evaluateAggregationRule(String query, String sql, QueryIntent intent, SqlShape shape,
			List<SqlGuardProblem> problems, Set<String> fixSuggestions, List<SqlGuardRuleCheck> ruleChecks) {
		if (!intent.requiresAggregation()) {
			return;
		}
		if (!shape.hasAggregation()) {
			addProblem(problems, fixSuggestions, "MISSING_AGGREGATION", "缺少聚合指标", "high", "问题看起来要求聚合指标，但 SQL 更像明细查询。",
					"用户问题带有数量、金额、总数、平均值等聚合口径，但 SQL 没有检测到 count/sum/avg/min/max 等聚合函数。", "SELECT 中应包含与题目口径匹配的聚合表达式。",
					"当前 SQL 未检测到聚合函数。", "query=" + query + "; sql=" + sql,
					"把 count/sum/avg/min/max 等聚合逻辑补齐到 SELECT 中。");
			recordRuleCheck(ruleChecks, "AGGREGATION_REQUIRED", "聚合指标校验", "FAILED", "问题要求聚合指标，但 SQL 未检测到聚合函数。",
					"query=" + query + "; sql=" + sql);
			return;
		}
		recordRuleCheck(ruleChecks, "AGGREGATION_REQUIRED", "聚合指标校验", "PASSED", "问题要求聚合指标，SQL 已检测到聚合函数。",
				"usedMetrics=" + shape.usedMetrics());
	}

	private void evaluateGroupingRule(String query, String sql, QueryIntent intent, SqlShape shape,
			List<SqlGuardProblem> problems, Set<String> fixSuggestions, List<SqlGuardRuleCheck> ruleChecks) {
		if (!intent.requiresGrouping()) {
			return;
		}
		if (!shape.hasGroupBy()) {
			addProblem(problems, fixSuggestions, "MISSING_GROUP_BY", "缺少 GROUP BY", "high",
					"问题要求按维度拆分，但 SQL 缺少 GROUP BY。", "用户问题包含按地区、按用户、各品类、每月等拆分意图；没有 GROUP BY 时，要么结果被压成总计，要么数据库直接报错。",
					"SQL 应按题目中的维度列做 GROUP BY。", "当前 SQL 未检测到 GROUP BY。", "query=" + query + "; sql=" + sql,
					"把用户要求的维度列加入 GROUP BY，并检查 SELECT 中的非聚合列。");
			recordRuleCheck(ruleChecks, "GROUP_BY_REQUIRED", "分组维度校验", "FAILED", "问题要求分维度拆分，但 SQL 未检测到 GROUP BY。",
					"query=" + query + "; sql=" + sql);
			return;
		}
		recordRuleCheck(ruleChecks, "GROUP_BY_REQUIRED", "分组维度校验", "PASSED", "问题要求分维度拆分，SQL 已检测到 GROUP BY。",
				"sql=" + sql);
	}

	private void evaluateTimeFilterRule(String query, String sql, QueryIntent intent, SqlShape shape,
			List<SqlGuardProblem> problems, Set<String> fixSuggestions, List<SqlGuardRuleCheck> ruleChecks) {
		if (!intent.requiresTimeFilter()) {
			return;
		}
		if (!shape.hasTimePredicate()) {
			addProblem(problems, fixSuggestions, "MISSING_TIME_FILTER", "缺少时间过滤", "high",
					"问题包含明确时间窗口，但 SQL 没有可靠的时间过滤信号。", "题目提到了今天、本月、最近30天、某年某月等时间范围，但 SQL 没有看到明确时间过滤，结果很可能回成全量数据。",
					"WHERE 中应包含与题目对应的时间范围约束。", "当前 SQL 未检测到可靠的时间过滤表达式。", "query=" + query + "; sql=" + sql,
					"在 WHERE 中补齐精确时间范围，不要按默认全量数据查询。");
			recordRuleCheck(ruleChecks, "TIME_FILTER_REQUIRED", "时间窗口校验", "FAILED", "问题包含明确时间窗口，但 SQL 未检测到可靠时间过滤。",
					"query=" + query + "; sql=" + sql);
			return;
		}
		recordRuleCheck(ruleChecks, "TIME_FILTER_REQUIRED", "时间窗口校验", "PASSED", "问题包含明确时间窗口，SQL 已检测到时间过滤。",
				"sql=" + sql);
	}

	private void evaluateTimeBucketRule(String sql, QueryIntent intent, SqlShape shape, List<SqlGuardProblem> problems,
			Set<String> fixSuggestions, List<SqlGuardRuleCheck> ruleChecks) {
		if (!intent.requiresTrend()) {
			return;
		}
		if (!shape.hasTimeBucket()) {
			addProblem(problems, fixSuggestions, "MISSING_TIME_BUCKET", "缺少时间分桶", "high",
					"趋势类问题通常需要时间分桶，但 SQL 没看到明确的时间粒度表达。", "趋势分析需要先按天、周、月、年等粒度汇总；没有时间分桶，返回的往往只是总数，不是趋势。",
					"SQL 应包含 DATE/DATE_TRUNC/DATE_FORMAT 等时间分桶表达式，并按同粒度分组。", "当前 SQL 未检测到明确的时间分桶表达式。", "sql=" + sql,
					"用 DATE/DATE_TRUNC/DATE_FORMAT 等时间分桶表达式，并对同一时间粒度做 GROUP BY。");
			recordRuleCheck(ruleChecks, "TIME_BUCKET_REQUIRED", "趋势时间粒度校验", "FAILED", "趋势问题需要时间分桶，但 SQL 未检测到时间粒度表达。",
					"sql=" + sql);
			return;
		}
		recordRuleCheck(ruleChecks, "TIME_BUCKET_REQUIRED", "趋势时间粒度校验", "PASSED", "趋势问题所需的时间分桶表达已检测到。", "sql=" + sql);
	}

	private void evaluateTimeOrderRule(String query, String sql, QueryIntent intent, SqlShape shape,
			List<SqlGuardProblem> problems, Set<String> fixSuggestions, List<SqlGuardRuleCheck> ruleChecks) {
		if (!intent.requiresTrend()) {
			return;
		}
		if (!shape.hasOrderBy()) {
			addProblem(problems, fixSuggestions, "MISSING_TIME_ORDER", "缺少时间排序", "medium",
					"趋势类问题通常需要按时间排序，但 SQL 缺少 ORDER BY。", "趋势结果如果不按时间排序，输出顺序可能是乱的，后续回答和可视化都容易误导。",
					"趋势 SQL 应按时间字段或时间分桶字段排序。", "当前 SQL 未检测到 ORDER BY。", "query=" + query + "; sql=" + sql,
					"按时间字段或时间分桶字段补齐 ORDER BY。");
			recordRuleCheck(ruleChecks, "TIME_ORDER_REQUIRED", "趋势时间排序校验", "FAILED", "趋势问题需要时间排序，但 SQL 未检测到 ORDER BY。",
					"sql=" + sql);
			return;
		}
		recordRuleCheck(ruleChecks, "TIME_ORDER_REQUIRED", "趋势时间排序校验", "PASSED", "趋势问题所需的时间排序已检测到。", "sql=" + sql);
	}

	private void evaluateOrderingRule(String query, String sql, QueryIntent intent, SqlShape shape,
			List<SqlGuardProblem> problems, Set<String> fixSuggestions, List<SqlGuardRuleCheck> ruleChecks) {
		if (!intent.requiresOrdering()) {
			return;
		}
		if (!shape.hasOrderBy()) {
			addProblem(problems, fixSuggestions, "MISSING_ORDER_BY", "缺少排序", "medium", "问题要求排序或排名，但 SQL 缺少 ORDER BY。",
					"题目要求最高、最低、TopN、排名等比较关系；没有 ORDER BY 时，即使有限制行数，返回的也不一定是目标对象。", "SQL 应明确按目标指标排序。",
					"当前 SQL 未检测到 ORDER BY。", "query=" + query + "; sql=" + sql, "根据问题要求补齐 ORDER BY，并明确升序还是降序。");
			recordRuleCheck(ruleChecks, "ORDER_REQUIRED", "排序要求校验", "FAILED", "问题包含排序或排名诉求，但 SQL 未检测到 ORDER BY。",
					"query=" + query + "; sql=" + sql);
			return;
		}
		recordRuleCheck(ruleChecks, "ORDER_REQUIRED", "排序要求校验", "PASSED", "问题包含排序或排名诉求，SQL 已检测到 ORDER BY。",
				"sql=" + sql);
	}

	private void evaluateLimitRule(String query, String sql, QueryIntent intent, SqlShape shape,
			List<SqlGuardProblem> problems, Set<String> fixSuggestions, List<SqlGuardRuleCheck> ruleChecks) {
		if (!intent.requiresLimit()) {
			return;
		}
		if (!shape.hasLimit()) {
			addProblem(problems, fixSuggestions, "MISSING_LIMIT", "缺少返回行数限制", "medium",
					"问题要求 TopN / 前N / 单个最值对象，但 SQL 没有限制返回行数。", "题目明确只需要前几名或唯一最值对象；如果不限制行数，结果会混入多余记录。",
					"SQL 应通过 LIMIT / TOP / FETCH FIRST 控制返回行数。", "当前 SQL 未检测到返回行数限制。",
					"query=" + query + "; sql=" + sql, "补齐 LIMIT / TOP / FETCH FIRST，避免把全量结果当成 TopN。");
			recordRuleCheck(ruleChecks, "LIMIT_REQUIRED", "TopN 行数限制校验", "FAILED",
					"问题要求限制返回行数，但 SQL 未检测到 LIMIT/TOP/FETCH FIRST。", "query=" + query + "; sql=" + sql);
			return;
		}
		int requiredLimit = Math.min(intent.expectedLimit(), maxResultRows());
		if (shape.limitValueKnown() && shape.limitValue() < requiredLimit) {
			addProblem(problems, fixSuggestions, "LIMIT_TOO_SMALL", "TopN 数量不足", "medium", "SQL 实际限制条数小于本次可提供的 TopN 数量。",
					"限制条数过小会导致已经可以查询到的排名对象缺失。", "SQL 最外层限制至少应为 " + requiredLimit + " 条。",
					"题目期望 " + intent.expectedLimit() + " 条，平台本次可提供 " + requiredLimit + " 条，但 SQL 当前限制为 "
							+ shape.limitValue() + " 条。",
					"query=" + query + "; sql=" + sql, "把 LIMIT/TOP/FETCH FIRST 至少调整为 " + requiredLimit + " 条。");
			recordRuleCheck(ruleChecks, "LIMIT_MATCH", "TopN 数量匹配校验", "FAILED",
					"题目期望 " + intent.expectedLimit() + " 条，当前 SQL 仅限制 " + shape.limitValue() + " 条。",
					"query=" + query + "; sql=" + sql);
			return;
		}
		recordRuleCheck(
				ruleChecks, "LIMIT_MATCH", "TopN 数量匹配校验", "PASSED", intent.expectedLimit() > maxResultRows()
						? "题目期望 %d 条，SQL 已覆盖平台单次最多可提供的 %d 条。".formatted(intent.expectedLimit(), maxResultRows())
						: "题目期望 " + intent.expectedLimit() + " 条，SQL 限制能够完整覆盖。",
				"sql=" + sql);
	}

	private void evaluateDistinctRule(String query, String sql, QueryIntent intent, SqlShape shape,
			List<SqlGuardProblem> problems, Set<String> fixSuggestions, List<SqlGuardRuleCheck> ruleChecks) {
		if (!intent.requiresDistinct()) {
			return;
		}
		if (!shape.hasDistinct()) {
			addProblem(problems, fixSuggestions, "MISSING_DISTINCT", "缺少 DISTINCT 去重", "high",
					"问题要求去重口径，但 SQL 没看到 DISTINCT。", "题目要求独立用户、去重人数、唯一值等口径；不去重会重复计算。",
					"SQL 应使用 SELECT DISTINCT 或 COUNT(DISTINCT ...)。", "当前 SQL 未检测到 DISTINCT。",
					"query=" + query + "; sql=" + sql, "把口径改成 SELECT DISTINCT 或 COUNT(DISTINCT ...)。");
			recordRuleCheck(ruleChecks, "DISTINCT_REQUIRED", "去重口径校验", "FAILED", "问题要求去重口径，但 SQL 未检测到 DISTINCT。",
					"query=" + query + "; sql=" + sql);
			return;
		}
		recordRuleCheck(ruleChecks, "DISTINCT_REQUIRED", "去重口径校验", "PASSED", "问题要求去重口径，SQL 已检测到 DISTINCT。",
				"sql=" + sql);
	}

	private void evaluateOrderDirectionRule(String sql, QueryIntent intent, SqlShape shape,
			List<SqlGuardProblem> problems, Set<String> fixSuggestions, List<SqlGuardRuleCheck> ruleChecks) {
		if (!shape.hasOrderBy()) {
			return;
		}
		if (intent.prefersDescending() && shape.orderDirectionKnown() && !shape.orderDescending()) {
			addProblem(problems, fixSuggestions, "ORDER_DIRECTION_MISMATCH", "排序方向不匹配", "high",
					"问题要求最高 / Top / 最多，但 SQL 排序方向不像降序。", "题目要的是最大值或靠前排名，若排序方向写成 ASC，返回的会是最小值或反向结果。",
					"这类问题通常应按目标指标 DESC 排序。", "当前 SQL 的排序方向与题目诉求不一致。", "sql=" + sql, "把排序方向改成 DESC，并确认排序指标是否正确。");
			recordRuleCheck(ruleChecks, "ORDER_DIRECTION", "排序方向校验", "FAILED", "题目要求高到低/最多/Top，但 SQL 当前排序方向不是 DESC。",
					"sql=" + sql);
			return;
		}
		if (intent.prefersAscending() && shape.orderDirectionKnown() && shape.orderDescending()) {
			addProblem(problems, fixSuggestions, "ORDER_DIRECTION_MISMATCH", "排序方向不匹配", "high",
					"问题要求最低 / 最少 / 最小，但 SQL 排序方向不像升序。", "题目要的是最小值或最低排名，若排序方向写成 DESC，返回的会是最大值或反向结果。",
					"这类问题通常应按目标指标 ASC 排序。", "当前 SQL 的排序方向与题目诉求不一致。", "sql=" + sql, "把排序方向改成 ASC，并确认排序指标是否正确。");
			recordRuleCheck(ruleChecks, "ORDER_DIRECTION", "排序方向校验", "FAILED", "题目要求低到高/最少/最小，但 SQL 当前排序方向不是 ASC。",
					"sql=" + sql);
			return;
		}
		if ((intent.prefersDescending() || intent.prefersAscending()) && !shape.orderDirectionKnown()) {
			addProblem(problems, fixSuggestions, "ORDER_DIRECTION_AMBIGUOUS", "排序方向不明确", "medium",
					"问题对排序方向有明确诉求，但 SQL 的 ORDER BY 没有写明 ASC / DESC。", "有些数据库默认升序，但不应该依赖默认行为承载业务口径，否则最值问题很容易答反。",
					"ORDER BY 应显式写明 ASC 或 DESC。", "当前 SQL 虽然有 ORDER BY，但没有检测到明确排序方向。", "sql=" + sql,
					"显式补上 ASC 或 DESC，不要依赖数据库默认排序方向。");
			recordRuleCheck(ruleChecks, "ORDER_DIRECTION_EXPLICIT", "显式排序方向校验", "FAILED",
					"题目存在明确最值方向，但 SQL 的 ORDER BY 未显式写出 ASC/DESC。", "sql=" + sql);
			return;
		}
		if (intent.prefersDescending() || intent.prefersAscending()) {
			recordRuleCheck(ruleChecks, "ORDER_DIRECTION_EXPLICIT", "显式排序方向校验", "PASSED", "ORDER BY 已显式声明排序方向。",
					"sql=" + sql);
		}
	}

	private void evaluateSelectStarRule(Statement statement, String sql, List<SqlGuardProblem> problems,
			Set<String> fixSuggestions, List<SqlGuardRuleCheck> ruleChecks) {
		if (!(statement instanceof Select select) || !containsSelectStar(select)) {
			recordRuleCheck(ruleChecks, "SELECT_STAR_FORBIDDEN", "SELECT 星号字段校验", "PASSED",
					"候选 SQL 未在查询结果列中使用 SELECT * 或 alias.*。", "sql=" + sql);
			return;
		}
		addProblem(problems, fixSuggestions, "SELECT_STAR_FORBIDDEN", "禁止使用 SELECT *", "high",
				"候选 SQL 在结果列中使用了 SELECT * 或 alias.*，可能读取当前回答不需要的隐藏字段。",
				"数据源执行工具会执行字段级可见性校验，星号列无法表达最小必要字段范围。",
				"SELECT 列表应改成回答问题所需的显式字段名，聚合统计场景可以继续使用 COUNT(*)。",
				"当前 SQL 检测到星号结果列。", "sql=" + sql, "把 SELECT * 或 alias.* 改成显式列名，只选择回答需要的字段。");
		recordRuleCheck(ruleChecks, "SELECT_STAR_FORBIDDEN", "SELECT 星号字段校验", "FAILED",
				"检测到 SELECT * 或 alias.*，需要改写为显式字段后再执行。", "sql=" + sql);
	}

	private void evaluateDeletedRule(Statement statement, String sql, List<SqlGuardProblem> problems,
			Set<String> fixSuggestions, List<SqlGuardRuleCheck> ruleChecks) {
		boolean selectsDeleted = containsSelectColumn(statement, "deleted");
		boolean filtersDeleted = sql != null && sql.matches("(?is).*\\bdeleted\\s*=\\s*false\\b.*");
		if (selectsDeleted) {
			addProblem(problems, fixSuggestions, "SELECT_DELETED_FORBIDDEN", "禁止查询删除标识", "high",
					"候选 SQL 把 deleted 放进了 SELECT 列表。逻辑删除字段不能展示给用户，也不能当作业务分类。",
					"deleted 是逻辑删除标记，不是业务维度。", "SELECT 列表不应包含 deleted。",
					"当前 SQL 的结果列包含 deleted。", "sql=" + sql,
					"从 SELECT 中去掉 deleted，并在 WHERE 中使用 deleted = false 过滤已删除行。");
			recordRuleCheck(ruleChecks, "SELECT_DELETED_FORBIDDEN", "删除标识结果列校验", "FAILED",
					"SELECT 列表包含 deleted，不能作为用户结果字段。", "sql=" + sql);
		}
		else {
			recordRuleCheck(ruleChecks, "SELECT_DELETED_FORBIDDEN", "删除标识结果列校验", "PASSED",
					"候选 SQL 未把 deleted 放入 SELECT 列表。", "sql=" + sql);
		}
		if (selectsDeleted && !filtersDeleted) {
			addProblem(problems, fixSuggestions, "DELETED_FILTER_REQUIRED", "必须过滤已删除数据", "high",
					"查询包含 deleted 字段但没有 deleted = false 条件，可能返回已删除数据。",
					"有逻辑删除字段的表只能查询未删除行。", "WHERE 应包含 deleted = false。",
					"当前 SQL 未检测到 deleted = false。", "sql=" + sql, "补上 WHERE deleted = false，不要查询已删除数据。");
			recordRuleCheck(ruleChecks, "DELETED_FILTER_REQUIRED", "逻辑删除过滤校验", "FAILED",
					"查询涉及 deleted 但未使用 deleted = false。", "sql=" + sql);
		}
		else if (filtersDeleted || !selectsDeleted) {
			recordRuleCheck(ruleChecks, "DELETED_FILTER_REQUIRED", "逻辑删除过滤校验", "PASSED",
					"未把 deleted 当作结果列，或已使用 deleted = false 过滤。", "sql=" + sql);
		}
	}

	private boolean containsSelectColumn(Statement statement, String columnName) {
		if (!(statement instanceof Select select)) {
			return false;
		}
		return containsSelectColumn(select, columnName);
	}

	private boolean containsSelectColumn(Select select, String columnName) {
		if (select.getWithItemsList() != null) {
			for (WithItem withItem : select.getWithItemsList()) {
				if (containsSelectColumn(withItem.getSelect(), columnName)) {
					return true;
				}
			}
		}
		if (select instanceof PlainSelect plainSelect) {
			return Optional.ofNullable(plainSelect.getSelectItems())
				.orElse(List.of())
				.stream()
				.map(SelectItem::getExpression)
				.anyMatch(expression -> isNamedColumn(expression, columnName));
		}
		if (select instanceof SetOperationList setOperationList) {
			return Optional.ofNullable(setOperationList.getSelects())
				.orElse(List.of())
				.stream()
				.anyMatch(item -> containsSelectColumn(item, columnName));
		}
		if (select instanceof ParenthesedSelect parenthesedSelect) {
			return containsSelectColumn(parenthesedSelect.getSelect(), columnName);
		}
		return false;
	}

	private boolean isNamedColumn(net.sf.jsqlparser.expression.Expression expression, String columnName) {
		if (expression instanceof net.sf.jsqlparser.schema.Column column) {
			return columnName.equalsIgnoreCase(normalizeColumnName(column.getColumnName()));
		}
		return false;
	}

	private boolean containsSelectStar(Select select) {
		if (select.getWithItemsList() != null) {
			for (WithItem withItem : select.getWithItemsList()) {
				if (containsSelectStar(withItem.getSelect())) {
					return true;
				}
			}
		}
		if (select instanceof PlainSelect plainSelect) {
			return Optional.ofNullable(plainSelect.getSelectItems())
				.orElse(List.of())
				.stream()
				.map(SelectItem::getExpression)
				.anyMatch(expression -> expression instanceof AllColumns || expression instanceof AllTableColumns);
		}
		if (select instanceof SetOperationList setOperationList) {
			return Optional.ofNullable(setOperationList.getSelects())
				.orElse(List.of())
				.stream()
				.anyMatch(this::containsSelectStar);
		}
		if (select instanceof ParenthesedSelect parenthesedSelect) {
			return containsSelectStar(parenthesedSelect.getSelect());
		}
		return false;
	}

	private void evaluateHumanFeedbackRule(String query, String sql, String humanFeedbackContent,
			HumanFeedbackConstraint feedbackConstraint, List<SqlGuardProblem> problems, Set<String> fixSuggestions,
			List<SqlGuardRuleCheck> ruleChecks) {
		if (!feedbackConstraint.hasConstraints()) {
			return;
		}
		String normalizedSql = StringUtils.trimToEmpty(sql).toLowerCase(Locale.ROOT);
		List<String> feedbackProblems = new ArrayList<>();

		if (!feedbackConstraint.requiredStatusTokens().isEmpty()) {
			boolean matchedRequiredStatus = feedbackConstraint.requiredStatusTokens()
				.stream()
				.anyMatch(token -> sqlContainsStatusToken(normalizedSql, token));
			if (!matchedRequiredStatus) {
				feedbackProblems.add("未看到与人工反馈一致的状态过滤条件");
				addProblem(problems, fixSuggestions, "MISSING_CONFIRMED_STATUS_FILTER", "缺少人工反馈确认的状态过滤", "high",
						"用户已经通过人工反馈明确了状态口径，但 SQL 里没有体现该约束。", "人工反馈属于已确认条件；如果 SQL 没落实这些条件，最终结果会与用户确认的口径不一致。",
						"SQL 应显式体现用户确认过的状态范围或订单口径。", "当前 SQL 未检测到与人工反馈一致的状态条件。",
						"query=" + query + "; feedback=" + humanFeedbackContent + "; sql=" + sql,
						"把人工反馈里确认过的状态过滤条件补进 WHERE，例如只统计 completed / paid 等已确认状态。");
			}
		}

		if (!feedbackConstraint.excludedStatusTokens().isEmpty()) {
			boolean mentionsExcludedStatus = feedbackConstraint.excludedStatusTokens()
				.stream()
				.anyMatch(token -> sqlContainsStatusToken(normalizedSql, token));
			boolean hasStatusPredicate = STATUS_COLUMN_PATTERN.matcher(normalizedSql).find()
					|| feedbackConstraint.requiredStatusTokens()
						.stream()
						.anyMatch(token -> sqlContainsStatusToken(normalizedSql, token));
			boolean hasNegativeStatusPredicate = NEGATIVE_STATUS_OPERATOR_PATTERN.matcher(normalizedSql).find();
			if (!hasStatusPredicate && !mentionsExcludedStatus) {
				feedbackProblems.add("未看到用于落实人工反馈排除条件的状态过滤");
				addProblem(problems, fixSuggestions, "MISSING_CONFIRMED_STATUS_EXCLUSION", "缺少人工反馈确认的排除条件", "high",
						"用户已经通过人工反馈确认要排除某些状态，但 SQL 里没有看到对应的过滤条件。", "像“不含退款”“排除取消单”这类反馈会直接改变统计口径；如果 SQL 不落实，结果会偏大或口径错误。",
						"SQL 应显式体现这些排除条件，或通过更窄的已确认状态集合覆盖它们。", "当前 SQL 未检测到相关状态过滤。",
						"query=" + query + "; feedback=" + humanFeedbackContent + "; sql=" + sql,
						"把人工反馈里确认的排除条件补进 WHERE，例如排除 refund / cancelled 等状态。");
			}
			else if (mentionsExcludedStatus && !hasNegativeStatusPredicate
					&& feedbackConstraint.requiredStatusTokens().isEmpty()) {
				feedbackProblems.add("SQL 提到了应排除的状态，但没有看到明确排除写法");
				addProblem(problems, fixSuggestions, "CONFIRMED_STATUS_EXCLUSION_MISMATCH", "人工反馈排除条件未落实", "high",
						"人工反馈要求排除某些状态，但 SQL 里虽然出现了这些状态词，却没有看到明确的排除写法。",
						"如果只是把 refund / cancelled 放进正向条件里，结果会和用户确认的口径相反。",
						"这些状态应通过 <> / != / NOT IN 等方式排除，或通过更窄的正向状态集间接排除。", "当前 SQL 提到了应排除的状态，但没有检测到明确排除条件。",
						"query=" + query + "; feedback=" + humanFeedbackContent + "; sql=" + sql,
						"把这些状态改成显式排除条件，或改成更精确的正向状态集合。");
			}
		}

		if (feedbackProblems.isEmpty()) {
			recordRuleCheck(ruleChecks, "CONFIRMED_FEEDBACK_CONSTRAINTS", "人工反馈一致性校验", "PASSED",
					"SQL 已体现当前人工反馈中的显式状态口径约束。", "feedback=" + humanFeedbackContent);
			return;
		}
		recordRuleCheck(ruleChecks, "CONFIRMED_FEEDBACK_CONSTRAINTS", "人工反馈一致性校验", "FAILED",
				String.join("；", feedbackProblems), "feedback=" + humanFeedbackContent + "; sql=" + sql);
	}

	private void addProblem(List<SqlGuardProblem> problems, Set<String> fixSuggestions, String code, String title,
			String severity, String message, String why, String expected, String actual, String evidence,
			String fixSuggestion) {
		problems.add(SqlGuardProblem.builder()
			.code(code)
			.title(title)
			.severity(severity)
			.message(message)
			.why(why)
			.expected(expected)
			.actual(actual)
			.evidence(evidence)
			.repairHint(fixSuggestion)
			.build());
		fixSuggestions.add(fixSuggestion);
	}

	private void recordRuleCheck(List<SqlGuardRuleCheck> ruleChecks, String code, String title, String status,
			String detail, String evidence) {
		ruleChecks.add(SqlGuardRuleCheck.builder()
			.code(code)
			.title(title)
			.status(status)
			.detail(detail)
			.evidence(evidence)
			.build());
	}

	private boolean isBlockingSeverity(String severity) {
		return "high".equalsIgnoreCase(severity) || "medium".equalsIgnoreCase(severity);
	}

	private String mergeIntentSource(String query, String humanFeedbackContent) {
		if (StringUtils.isBlank(humanFeedbackContent)) {
			return query;
		}
		return query + "\n" + humanFeedbackContent;
	}

	private HumanFeedbackConstraint analyzeHumanFeedbackConstraint(String humanFeedbackContent) {
		if (StringUtils.isBlank(humanFeedbackContent)) {
			return HumanFeedbackConstraint.empty();
		}
		String feedback = humanFeedbackContent.trim();
		String normalizedFeedback = feedback.toLowerCase(Locale.ROOT);
		Set<String> requiredStatusTokens = new LinkedHashSet<>();
		Set<String> excludedStatusTokens = new LinkedHashSet<>();

		collectRequiredStatusTokens(feedback, normalizedFeedback, requiredStatusTokens);
		collectExcludedStatusTokens(feedback, normalizedFeedback, excludedStatusTokens);
		return new HumanFeedbackConstraint(feedback, Set.copyOf(requiredStatusTokens),
				Set.copyOf(excludedStatusTokens));
	}

	private void collectRequiredStatusTokens(String feedback, String normalizedFeedback, Set<String> target) {
		if (containsAny(feedback, "已完成", "完成订单") || containsAny(normalizedFeedback, "completed", "complete")) {
			target.add("completed");
		}
		if (containsAny(feedback, "已支付", "支付成功") || containsAny(normalizedFeedback, "paid", "payment_success")) {
			target.add("paid");
		}
		if (containsAny(feedback, "待支付", "未支付", "待处理") || containsAny(normalizedFeedback, "pending", "unpaid")) {
			target.add("pending");
		}
		if (containsAny(feedback, "已取消", "取消单") || containsAny(normalizedFeedback, "cancelled", "canceled")) {
			target.add("cancelled");
		}
		if (containsAny(feedback, "退款", "已退款") || containsAny(normalizedFeedback, "refund", "refunded")) {
			if (!containsAny(feedback, "不含退款", "不包含退款", "排除退款", "剔除退款")
					&& !containsAny(normalizedFeedback, "exclude refund", "without refund", "not refunded")) {
				target.add("refund");
			}
		}
	}

	private void collectExcludedStatusTokens(String feedback, String normalizedFeedback, Set<String> target) {
		if (containsAny(feedback, "不含退款", "不包含退款", "排除退款", "剔除退款", "不看退款")
				|| containsAny(normalizedFeedback, "exclude refund", "without refund", "not refunded")) {
			target.add("refund");
		}
		if (containsAny(feedback, "不含取消", "不包含取消", "排除取消", "剔除取消", "不看取消") || containsAny(normalizedFeedback,
				"exclude cancel", "without cancel", "not cancelled", "not canceled")) {
			target.add("cancelled");
		}
	}

	private boolean sqlContainsStatusToken(String normalizedSql, String token) {
		return switch (token) {
			case "completed" -> containsAny(normalizedSql, "'completed'", "\"completed\"", " completed ", "'complete'",
					"已完成", "'success'", "\"success\"");
			case "paid" -> containsAny(normalizedSql, "'paid'", "\"paid\"", " paid ", "已支付", "payment_success");
			case "pending" ->
				containsAny(normalizedSql, "'pending'", "\"pending\"", " pending ", "待支付", "未支付", "'unpaid'");
			case "cancelled" -> containsAny(normalizedSql, "'cancelled'", "\"cancelled\"", "'canceled'", "\"canceled\"",
					" cancelled ", " canceled ", "已取消", "取消");
			case "refund" -> containsAny(normalizedSql, "'refund'", "\"refund\"", "'refunded'", "\"refunded\"",
					" refund ", " refunded ", "退款");
			default -> containsAny(normalizedSql, token);
		};
	}

	private QueryIntent analyzeQueryIntent(String query) {
		String normalized = query.toLowerCase(Locale.ROOT);
		boolean requiresTrend = containsAny(query, "趋势", "走势图", "按天", "按周", "按月", "按年", "daily", "weekly", "monthly",
				"trend", "over time", "环比", "同比");
		boolean requiresGrouping = requiresTrend
				|| containsAny(query, "按", "按照", "每个", "各", "分组", "group by", "维度", "分类", "分城市", "分地区", "分品类", "分渠道");
		boolean requiresTimeFilter = hasExplicitTimeFilterIntent(query, normalized);
		boolean requiresOrdering = requiresTrend || containsAny(normalized, "top ", "rank", "ranking", "highest",
				"lowest", "best", "worst", "most", "least")
				|| containsAny(query, "排名", "排行", "最高", "最低", "最多", "最少", "前", "后");
		boolean prefersDescending = containsAny(normalized, "top ", "highest", "best", "most", "largest")
				|| containsAny(query, "最高", "最多", "最大", "前");
		boolean prefersAscending = containsAny(normalized, "lowest", "least", "smallest", "worst")
				|| containsAny(query, "最低", "最少", "最小");
		boolean explicitDescendingDirection = containsAny(normalized, " desc", "descending")
				|| containsAny(query, "降序", "从高到低");
		boolean explicitAscendingDirection = containsAny(normalized, " asc", "ascending")
				|| containsAny(query, "升序", "从低到高");
		requiresOrdering = requiresOrdering || explicitDescendingDirection || explicitAscendingDirection;
		prefersDescending = prefersDescending || explicitDescendingDirection;
		prefersAscending = prefersAscending || explicitAscendingDirection;
		Integer expectedLimit = extractExpectedLimit(query, prefersDescending, prefersAscending);
		boolean requiresLimit = expectedLimit != null;
		boolean requiresDistinct = containsAny(normalized, "distinct", "deduplicate", "unique", "uv")
				|| containsAny(query, "去重", "独立用户", "唯一");
		boolean requiresAggregation = requiresTrend || requiresDistinct
				|| containsAny(normalized, "count", "sum", "avg", "average", "total", "amount", "sales", "revenue")
				|| containsAny(query, "数量", "总数", "总额", "金额", "销量", "销售额", "订单数", "人数", "平均", "占比", "比例", "贡献", "多少");
		return new QueryIntent(requiresAggregation, requiresGrouping, requiresTimeFilter, requiresOrdering,
				requiresLimit, requiresDistinct, requiresTrend, prefersDescending, prefersAscending, expectedLimit);
	}

	private SqlShape analyzeSqlShape(Statement statement, String sql, SqlGuardCheckRequest request) {
		String normalizedSql = StringUtils.trimToEmpty(sql).toLowerCase(Locale.ROOT);
		Set<String> knownTimeColumns = extractKnownTimeColumns(request);
		List<String> usedTables = extractReferencedTables(statement);
		List<String> usedMetrics = extractUsedMetrics(sql);
		boolean hasAggregation = AGGREGATE_PATTERN.matcher(sql).find();
		boolean hasGroupBy = GROUP_BY_PATTERN.matcher(normalizedSql).find();
		boolean hasOrderBy = ORDER_BY_PATTERN.matcher(normalizedSql).find();
		Select select = (Select) statement;
		boolean hasLimit = TopNLimitResolver.hasOuterLimit(select);
		boolean hasDistinct = DISTINCT_PATTERN.matcher(normalizedSql).find();
		boolean hasTimePredicate = detectTimePredicate(normalizedSql, knownTimeColumns);
		boolean hasTimeBucket = detectTimeBucket(normalizedSql, knownTimeColumns);
		boolean orderDescending = DESC_PATTERN.matcher(normalizedSql).find();
		boolean orderDirectionKnown = DESC_PATTERN.matcher(normalizedSql).find()
				|| ASC_PATTERN.matcher(normalizedSql).find();
		Integer limitValue = TopNLimitResolver.extractOuterLimit(select).orElse(null);
		boolean limitValueKnown = limitValue != null;
		return new SqlShape(List.copyOf(usedTables), List.copyOf(usedMetrics), hasAggregation, hasGroupBy, hasOrderBy,
				hasLimit, hasDistinct, hasTimePredicate, hasTimeBucket, orderDescending, orderDirectionKnown,
				limitValueKnown, limitValue);
	}

	private boolean detectTimePredicate(String normalizedSql, Set<String> knownTimeColumns) {
		String whereClause = extractWhereClause(normalizedSql);
		if (StringUtils.isBlank(whereClause)) {
			return false;
		}
		if (TIME_FUNCTION_PATTERN.matcher(whereClause).find()) {
			return true;
		}
		return containsTimeColumnReference(whereClause, knownTimeColumns);
	}

	private boolean hasExplicitTimeFilterIntent(String query, String normalized) {
		if (containsAny(query, "今天", "昨日", "昨天", "本周", "上周", "本月", "上月", "本季度", "上季度", "今年", "去年", "近", "最近", "最近的")
				|| containsAny(normalized, "latest", "recent", "last ", "past ", "today", "yesterday", "this month",
						"this year")) {
			return true;
		}
		return EXPLICIT_DATE_QUERY_PATTERN.matcher(normalized).find() || QUARTER_QUERY_PATTERN.matcher(query).find();
	}

	private String extractWhereClause(String normalizedSql) {
		Matcher whereMatcher = WHERE_PATTERN.matcher(normalizedSql);
		if (!whereMatcher.find()) {
			return "";
		}
		int start = whereMatcher.end();
		Matcher endMatcher = WHERE_CLAUSE_END_PATTERN.matcher(normalizedSql);
		int end = normalizedSql.length();
		if (endMatcher.find(start)) {
			end = endMatcher.start();
		}
		return normalizedSql.substring(start, end);
	}

	private boolean containsTimeColumnReference(String clause, Set<String> knownTimeColumns) {
		String withoutLiterals = SQL_STRING_LITERAL_PATTERN.matcher(StringUtils.defaultString(clause)).replaceAll(" ");
		Matcher matcher = SQL_IDENTIFIER_PATTERN.matcher(withoutLiterals);
		while (matcher.find()) {
			String identifier = matcher.group();
			if (isLikelyTimeColumn(identifier)) {
				return true;
			}
			if (knownTimeColumns.stream().anyMatch(column -> normalizeColumnName(column).equals(normalizeColumnName(identifier)))) {
				return true;
			}
		}
		return false;
	}

	private boolean detectTimeBucket(String normalizedSql, Set<String> knownTimeColumns) {
		if (!GROUP_BY_PATTERN.matcher(normalizedSql).find()) {
			return false;
		}
		if (containsAny(normalizedSql, "date_trunc(", "date(", "strftime(", "to_date(", "extract(", "year(", "month(",
				"day(")) {
			return true;
		}
		if (containsAny(normalizedSql, " by day", " by month", " by week", " by year")) {
			return true;
		}
		return knownTimeColumns.stream().anyMatch(column -> normalizedSql.contains(column.toLowerCase(Locale.ROOT)));
	}

	private Set<String> extractKnownTimeColumns(SqlGuardCheckRequest request) {
		return new LinkedHashSet<>();
	}

	private boolean isLikelyTimeColumn(String value) {
		String normalized = StringUtils.trimToEmpty(value).toLowerCase(Locale.ROOT);
		return containsAny(normalized, "date", "time", "day", "week", "month", "year", "created", "updated", "dt",
				"biz_date", "stat_date", "order_date");
	}

	private List<String> extractReferencedTables(Statement statement) {
		try {
			return new TablesNamesFinder().getTableList(statement)
				.stream()
				.filter(StringUtils::isNotBlank)
				.map(String::trim)
				.distinct()
				.toList();
		}
		catch (Exception ex) {
			// An empty table list silently skews the SQL shape analysis toward "no tables referenced".
			log.warn("Failed to extract referenced tables from the parsed statement, shape analysis will be degraded",
					ex);
			return List.of();
		}
	}

	private List<String> extractUsedMetrics(String sql) {
		Set<String> metrics = new LinkedHashSet<>();
		Matcher matcher = AGGREGATE_PATTERN.matcher(StringUtils.defaultString(sql));
		while (matcher.find()) {
			String alias = matcher.group(3);
			if (StringUtils.isNotBlank(alias)) {
				metrics.add(alias.trim());
				continue;
			}
			String functionName = Objects.toString(matcher.group(1), "").toUpperCase(Locale.ROOT);
			String argument = Objects.toString(matcher.group(2), "").trim();
			metrics.add(functionName + "(" + argument + ")");
		}
		return List.copyOf(metrics);
	}

	private Statement parseSingleSelectStatement(String sql) {
		String normalizedSql = stripTrailingSemicolons(sql);
		if (normalizedSql.isEmpty()) {
			throw new IllegalArgumentException("SQL 不能为空");
		}
		try {
			List<Statement> statements = CCJSqlParserUtil.parseStatements(normalizedSql).getStatements();
			if (statements == null || statements.isEmpty()) {
				throw new IllegalArgumentException("SQL 不能为空");
			}
			if (statements.size() > 1) {
				throw new IllegalArgumentException("仅支持单条 SELECT / WITH 查询");
			}
			Statement statement = statements.get(0);
			if (!(statement instanceof Select)) {
				throw new IllegalArgumentException("SQL 校验工具仅校验 SELECT / WITH 查询");
			}
			return statement;
		}
		catch (IllegalArgumentException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("SQL 解析失败，请检查语法后重试", ex);
		}
	}

	private String stripTrailingSemicolons(String sql) {
		String trimmed = StringUtils.trimToEmpty(sql);
		while (trimmed.endsWith(";")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
		}
		return trimmed;
	}

	private String buildIntentExplanation(QueryIntent intent) {
		List<String> fragments = new ArrayList<>();
		if (intent.requiresAggregation()) {
			fragments.add("问题包含聚合指标诉求");
		}
		if (intent.requiresGrouping()) {
			fragments.add("问题包含按维度拆分诉求");
		}
		if (intent.requiresTimeFilter()) {
			fragments.add("问题包含明确时间窗口");
		}
		if (intent.requiresTrend()) {
			fragments.add("问题包含趋势或时间序列分析");
		}
		if (intent.requiresOrdering()) {
			fragments.add("问题包含排序或排名要求");
		}
		if (intent.requiresLimit()) {
			fragments.add("问题包含 TopN / Top1 行数限制");
		}
		if (intent.requiresDistinct()) {
			fragments.add("问题包含去重口径");
		}
		if (fragments.isEmpty()) {
			return "当前规则没有识别到强约束口径，主要执行基础 SQL 结构检查。";
		}
		return String.join("；", fragments) + "。";
	}

	private boolean containsAny(String value, String... needles) {
		if (value == null) {
			return false;
		}
		for (String needle : needles) {
			if (needle != null && value.contains(needle)) {
				return true;
			}
		}
		return false;
	}

	private Integer extractExpectedLimit(String query, boolean prefersDescending, boolean prefersAscending) {
		String safeQuery = StringUtils.defaultString(query);
		Optional<Integer> topN = TopNLimitResolver.extractRequestedRows(safeQuery);
		if (topN.isPresent()) {
			return topN.get();
		}
		boolean asksSingleExtreme = containsAny(safeQuery, "最多", "最高", "最低", "最少", "第一", "首位", "top1", "top 1",
				"highest", "lowest", "most", "least", "first");
		if (asksSingleExtreme && (prefersDescending || prefersAscending)) {
			return 1;
		}
		boolean asksSingleTarget = containsAny(safeQuery, "哪个", "哪位", "哪一个", "谁");
		if (asksSingleTarget && (prefersDescending || prefersAscending)) {
			return 1;
		}
		return null;
	}

	private int maxResultRows() {
		return Math.max(1, dataAgentProperties.getRuntime().getMaxResultRows());
	}

	private record ProfileContext(SkillDatasource skillDatasource, Datasource datasource, DbConfigBO dbConfig,
			Accessor accessor, List<String> visibleTables, Map<String, List<String>> visibleTablesByName,
			Map<String, List<String>> visibleTablesByLeafName, Map<String, List<String>> visibleColumnsByTable,
			Map<String, Set<String>> visibleColumnNameSetByTable, Set<String> columnRestrictedTables) {
	}

	private record QueryIntent(boolean requiresAggregation, boolean requiresGrouping, boolean requiresTimeFilter,
			boolean requiresOrdering, boolean requiresLimit, boolean requiresDistinct, boolean requiresTrend,
			boolean prefersDescending, boolean prefersAscending, Integer expectedLimit) {
	}

	private record HumanFeedbackConstraint(String feedbackContent, Set<String> requiredStatusTokens,
			Set<String> excludedStatusTokens) {

		private static HumanFeedbackConstraint empty() {
			return new HumanFeedbackConstraint("", Set.of(), Set.of());
		}

		private boolean hasConstraints() {
			return StringUtils.isNotBlank(feedbackContent)
					&& (!requiredStatusTokens.isEmpty() || !excludedStatusTokens.isEmpty());
		}
	}

	private record SqlShape(List<String> usedTables, List<String> usedMetrics, boolean hasAggregation,
			boolean hasGroupBy, boolean hasOrderBy, boolean hasLimit, boolean hasDistinct, boolean hasTimePredicate,
			boolean hasTimeBucket, boolean orderDescending, boolean orderDirectionKnown, boolean limitValueKnown,
			Integer limitValue) {
	}

}
