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
package com.sn68.agent.dataagent.agentscope.tool.datasource;

import com.sn68.agent.dataagent.bo.DbConfigBO;
import com.sn68.agent.dataagent.bo.schema.ColumnInfoBO;
import com.sn68.agent.dataagent.bo.schema.ForeignKeyInfoBO;
import com.sn68.agent.dataagent.bo.schema.ResultSetBO;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.tool.datasource.permission.DataAgentSqlPermissionRewriteService;
import com.sn68.agent.dataagent.agentscope.tool.datasource.permission.DataAgentSqlPermissionRewriteService.PermissionRewriteResult;
import com.sn68.agent.dataagent.connector.DbQueryParameter;
import com.sn68.agent.dataagent.connector.accessor.Accessor;
import com.sn68.agent.dataagent.connector.accessor.AccessorFactory;
import com.sn68.agent.dataagent.entity.SkillDatasource;
import com.sn68.agent.dataagent.entity.Datasource;
import com.sn68.agent.dataagent.entity.LogicalRelation;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.service.datasource.DatasourceService;
import com.sn68.agent.dataagent.service.report.SearchResultColumnLexiconFactory;
import com.sn68.agent.dataagent.service.report.SearchResultColumnNamer;
import com.sn68.agent.dataagent.service.schema.SchemaService;
import com.sn68.agent.dataagent.skill.execution.SkillVersionResources;
import com.sn68.agent.dataagent.util.QueryTokenUtil;
import com.sn68.agent.dataagent.util.SqlUtil;
import com.sn68.agent.dataagent.util.TopNLimitResolver;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.LateralSubSelect;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.TableFunction;
import net.sf.jsqlparser.statement.select.WithItem;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * 数据源探索工具的核心执行服务：在技能绑定的数据源与可见表范围内，
 * 提供列表/查找表、取表结构、关联表、行预览、只读查询等动作；
 * 所有查询强制走权限改写与确定性 SQL 守护，结果按单元格与总量预算裁剪后返回给模型。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasourceExplorerService {

	private static final int DEFAULT_EXPLORER_LIMIT = 20;

	private static final int MAX_EXPLORER_LIMIT = 200;

	private static final int FIND_TABLES_MAX_RESULTS = 8;

	/**
	 * 单次数据源查询分段耗时汇总升为 WARN 的总耗时阈值（毫秒）。历史观测到 SEARCH 单次 17s 且运行时无分段日志、
	 * 静默窗无法归属（常规约 1s-1.5s）；总耗时超过该阈值视为慢查询，分段汇总由 INFO 升为 WARN 便于定位。
	 */
	private static final long SLOW_QUERY_WARN_MS = 5000L;

	/**
	 * 单个结果单元格发给模型的最大字符数，**含截断标记**。取值对齐
	 * {@code DeterministicSkillExecutor.MAX_PRESENTATION_CELL_CHARS}（同一批 SQL 结果在展示层已按 128
	 * 字符裁剪）：把标记算进预算后总长恰好不超过展示层上限，展示层不会再截一次而把标记本身切碎。
	 */
	private static final int MAX_RESULT_CELL_CHARS = 128;

	/**
	 * 超长单元格的显式截断标记。模型无法区分“字段本来就这么长”和“被平台裁剪过”，不加标记它会把残缺值
	 * 当成完整业务值继续推理（例如据此判断枚举取值或做字符串匹配）。
	 */
	private static final String CELL_TRUNCATION_SUFFIX = "…（已截断）";

	/**
	 * GET_TABLE_SCHEMA 响应中表/字段说明文本的最大保留字符数。控制单份 schema dump 体积，
	 * 配合 7 张表的探查预算让总占用不超出模型窗口；按字符截断而非按句切，避免丢掉括号内的口径说明，
	 * 截断发生时追加 {@link #SCHEMA_COMMENT_TRUNCATION_SUFFIX}，空说明原样返回。
	 */
	private static final int MAX_SCHEMA_COMMENT_CHARS = 60;

	private static final String SCHEMA_COMMENT_TRUNCATION_SUFFIX = "…";

	private static final Pattern WHERE_PATTERN = Pattern.compile("(?i)\\bwhere\\b");

	private static final Pattern GROUP_BY_PATTERN = Pattern.compile("(?i)\\bgroup\\s+by\\b");

	private static final Pattern ORDER_BY_PATTERN = Pattern.compile("(?i)\\border\\s+by\\b");

	private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
	};

	private static final String HIDDEN_FIELD_INFERENCE_WARNING = " 请严格基于返回字段作答，不要根据邮箱前缀、ID、编码、别名等可见值推断任何未返回的隐藏字段。";

	private static final String SETTLEMENT_DATE_COLUMN = "settlement_date";

	private static final String EMPTY_RESULT_DEFAULT_NEXT_ACTION = "核对过滤条件是否用错列语义或值格式；链接键未命中时改用材料，不要编造；不要把 DATA_PROFILE 当默认下一步。";

	private static final String EMPTY_RESULT_SETTLEMENT_DATE_HINT = "属期是 text，按月等值（IN/等值）或按期重叠，不要 DATA_PROFILE。";

	/**
	 * 高危函数名黑名单：文件读写、命令执行、外部网络访问、锁/延时，以及能绕开表白名单读取任意表的函数
	 * （如 {@code query_to_xml} 可以把任意 SQL 的结果当作 XML 返回）。
	 * <p>
	 * 这里刻意取所有受支持方言的并集，而不是按 {@code dialectType} 分方言查表：{@code dialectType}
	 * 的取值大小写与写法并不统一（handler 侧产出 {@code "postgresql"}，测试侧构造 {@code "PostgreSQL"}，
	 * {@link #wrapLimitSql} 也是靠 {@code toLowerCase().contains} 兜底的），按方言精确匹配会在取值对不上时
	 * 静默失败开放——而安全黑名单必须失败关闭。各方言的危险命名空间互不重叠，取并集不会误伤正常业务函数。
	 */
	private static final Set<String> DENIED_FUNCTION_NAMES = Set.of("query_to_xml", "query_to_xmlschema",
			"query_to_xml_and_xmlschema", "table_to_xml", "schema_to_xml", "database_to_xml", "current_setting",
			"set_config", "load_file", "benchmark", "sleep", "get_lock", "release_lock", "master_pos_wait",
			"updatexml", "extractvalue", "openrowset", "opendatasource", "openquery", "openxml", "httpuritype",
			"file_read", "file_write", "csvread", "csvwrite", "link_schema", "reflect", "reflect2", "java_method",
			// MySQL 侧的命令执行 UDF，历史上以 sys_exec/sys_eval 命名安装。
			"sys_exec", "sys_eval",
			// SQL Server 内置的文件/日志/权限读取函数。此处逐个列名而不用 fn_ 前缀：
			// SQL Server 用户自定义标量函数普遍以 fn_ 开头，整前缀会误杀客户的正常业务函数。
			"fn_get_audit_file", "fn_trace_gettable", "fn_trace_geteventinfo", "fn_dblog", "fn_virtualfilestats",
			"fn_my_permissions", "fn_builtin_permissions");

	/**
	 * 高危函数名前缀黑名单，用于覆盖同一命名空间下的整族函数（如 {@code pg_read_file}、{@code utl_http}、
	 * {@code DBMS_XMLGEN.getxml}、{@code xp_cmdshell}）。取并集的理由同 {@link #DENIED_FUNCTION_NAMES}。
	 * <p>
	 * 只收厂商保留命名空间的前缀。{@code fn_} 与 {@code sys_} 看似同类，但它们同时也是用户自定义函数的
	 * 常见命名习惯（尤其 SQL Server 的 {@code fn_*} 标量函数），用前缀会误杀正常业务查询，因此改为在
	 * {@link #DENIED_FUNCTION_NAMES} 中逐个列举其危险成员。
	 */
	private static final List<String> DENIED_FUNCTION_PREFIXES = List.of("pg_", "dblink", "lo_", "utl_", "dbms_",
			"sys.", "xp_", "sp_oa");

	private final DatasourceService datasourceService;

	private final SchemaService schemaService;

	private final AccessorFactory accessorFactory;

	private final ObjectMapper objectMapper;

	private final AnswerTraceExplainStore answerTraceExplainStore;

	private final DataAgentSqlPermissionRewriteService sqlPermissionRewriteService;

	private final DatasourceRuntimeContextCache datasourceRuntimeContextCache;

	private final DataAgentProperties dataAgentProperties;

	private final SearchResultColumnLexiconFactory searchResultColumnLexiconFactory;

	/**
	 * 单次数据源查询的分段耗时累计器，execute 生命周期内绑定到当前线程。工具调用在同一线程同步完成，
	 * 各真实步骤（上下文/关系加载、SQL 守卫、权限改写、JDBC 执行）直接写入累计器，避免为计时逐层加参改变签名；
	 * execute 收尾统一输出单行汇总并在 finally 中清理。
	 */
	private static final ThreadLocal<QuerySegments> QUERY_SEGMENTS = new ThreadLocal<>();

	public DatasourceExplorerResult execute(DatasourceExplorerRequest request, @Nullable AgentRequest graphRequest)
			throws Exception {
		if (request == null || request.getAction() == null) {
			throw new IllegalArgumentException("数据源探索请求必须提供 action");
		}
		requireSkillResources(graphRequest);
		long startNanos = System.nanoTime();
		QUERY_SEGMENTS.set(new QuerySegments());
		try {
			long relationFetchStartNanos = System.nanoTime();
			ExplorerContext context = resolveContext(graphRequest, request.isSkipPhysicalRelationMetadata());
			recordSegment(segments -> segments.relationFetchNanos += System.nanoTime() - relationFetchStartNanos);
			DatasourceExplorerResult result = switch (request.getAction()) {
				case LIST_TABLES -> listTables(context, request, graphRequest);
				case FIND_TABLES -> findTables(context, request, graphRequest);
				case GET_TABLE_SCHEMA -> getTableSchema(context, request, graphRequest);
				case GET_RELATED_TABLES -> getRelatedTables(context, request, graphRequest);
				case PREVIEW_ROWS -> previewRows(context, request, graphRequest);
				case SEARCH -> search(context, request, graphRequest);
			};
			logQuerySegments(request.getAction(), context, startNanos, result);
			return result;
		}
		finally {
			QUERY_SEGMENTS.remove();
		}
	}

	/**
	 * 记录一段真实步骤耗时到当前线程的分段累计器；不在 execute 生命周期内（如单测直调内部方法）时静默跳过。
	 */
	private static void recordSegment(Consumer<QuerySegments> recorder) {
		QuerySegments segments = QUERY_SEGMENTS.get();
		if (segments != null) {
			recorder.accept(segments);
		}
	}

	private static long toMillis(long nanos) {
		return nanos / 1_000_000L;
	}

	/**
	 * 输出单行分段耗时汇总。postProcessMs 为总耗时扣除已归属分段后的余量（表/字段文档加载、展示名解析、
	 * 结果裁剪与落库等收尾步骤）；总耗时超过 {@link #SLOW_QUERY_WARN_MS} 时整体升为 WARN，
	 * 便于把离群慢查询的静默窗归属到具体阶段。日志只做观测，不影响任何执行语义与异常路径。
	 */
	private static void logQuerySegments(DatasourceExplorerAction action, ExplorerContext context, long startNanos,
			DatasourceExplorerResult result) {
		QuerySegments segments = QUERY_SEGMENTS.get();
		if (segments == null) {
			return;
		}
		long totalMs = toMillis(System.nanoTime() - startNanos);
		long relationFetchMs = toMillis(segments.relationFetchNanos);
		long guardMs = toMillis(segments.guardNanos);
		long permissionMs = toMillis(segments.permissionNanos);
		long coerceMs = toMillis(segments.coerceNanos);
		long jdbcExecuteMs = toMillis(segments.jdbcExecuteNanos);
		long postProcessMs = Math.max(0L,
				totalMs - relationFetchMs - guardMs - permissionMs - coerceMs - jdbcExecuteMs);
		Object datasourceId = context == null || context.datasource() == null ? null : context.datasource().getId();
		long rows = result == null || result.getRows() == null ? 0 : result.getRows().size();
		// SLF4J 占位符必须配合参数化调用；此前误用 String.formatted（%s 语法）导致 {} 原样输出、参数被丢弃。
		String message = "Datasource query segments. action={}, datasourceId={}, totalMs={}, relationFetchMs={}, "
				+ "guardMs={}, permissionMs={}, coerceMs={}, jdbcExecuteMs={}, postProcessMs={}, rows={}";
		Object[] args = {action, datasourceId, totalMs, relationFetchMs, guardMs, permissionMs, coerceMs,
				jdbcExecuteMs, postProcessMs, rows};
		if (totalMs > SLOW_QUERY_WARN_MS) {
			log.warn(message, args);
			return;
		}
		log.info(message, args);
	}

	/**
	 * 分段耗时累计器（纳秒），仅单线程内读写，无需并发保护。段名与真实步骤边界一一对应：
	 * relationFetch=上下文与逻辑/物理关系加载，guard=静态 SQL 守卫解析校验，permission=数据权限改写，
	 * coerce=PostgreSQL 布尔字面量改写（可能按表补查列类型），jdbcExecute=JDBC 执行与取数。
	 */
	private static final class QuerySegments {

		private long relationFetchNanos;

		private long guardNanos;

		private long permissionNanos;

		private long coerceNanos;

		private long jdbcExecuteNanos;

	}

	private DatasourceExplorerResult listTables(ExplorerContext context, DatasourceExplorerRequest request,
			@Nullable AgentRequest graphRequest) {
		int limit = normalizeExplorerLimit(request.getLimit());
		Map<String, Document> tableDocumentMap = loadTableDocumentMap(context, context.visibleTables());
		List<Map<String, Object>> tables = context.visibleTables()
			.stream()
			.sorted(String.CASE_INSENSITIVE_ORDER)
			.map(tableName -> toTableEntry(context, tableName, tableDocumentMap.get(normalizeTableName(tableName)),
					filterRelations(context, tableName)))
			.limit(limit)
			.toList();
		return capture(baseResult(context, DatasourceExplorerAction.LIST_TABLES, "共发现 %d 张可见表".formatted(tables.size()))
			.tables(tables)
			.searchReady(!tables.isEmpty())
			.build(), graphRequest);
	}

	private DatasourceExplorerResult findTables(ExplorerContext context, DatasourceExplorerRequest request,
			@Nullable AgentRequest graphRequest) {
		int limit = Math.min(normalizeExplorerLimit(request.getLimit()), FIND_TABLES_MAX_RESULTS);
		String query = StringUtils.trimToEmpty(request.getQuery());
		Map<String, Document> tableDocumentMap = loadTableDocumentMap(context, context.visibleTables());
		List<Map<String, Object>> catalog = context.visibleTables()
			.stream()
			.map(tableName -> toTableEntry(context, tableName, tableDocumentMap.get(normalizeTableName(tableName)),
					filterRelations(context, tableName)))
			.toList();
		List<Map<String, Object>> matchedTables;
		if (StringUtils.isBlank(query)) {
			matchedTables = catalog.stream().limit(limit).toList();
		}
		else {
			List<String> tokens = QueryTokenUtil.tokenize(query);
			matchedTables = catalog.stream()
				.map(table -> Map.entry(table, scoreCatalogTable(table, query, tokens)))
				.filter(entry -> entry.getValue() > 0)
				.sorted(Map.Entry.<Map<String, Object>, Integer>comparingByValue().reversed())
				.limit(limit)
				.map(Map.Entry::getKey)
				.toList();
		}
		String summary;
		if (StringUtils.isBlank(query)) {
			summary = "未提供筛选词，返回当前技能可见表列表";
		}
		else if (matchedTables.isEmpty()) {
			summary = "未在当前技能可见表中匹配到与“%s”相关的表，请使用 Allowed datasource scope 编写 SQL，不要猜测未授权表。"
				.formatted(request.getQuery());
		}
		else {
			summary = "在当前技能可见表中，针对“%s”匹配到 %d 张表".formatted(request.getQuery(), matchedTables.size());
		}
		return capture(baseResult(context, DatasourceExplorerAction.FIND_TABLES, summary).tables(matchedTables)
			.searchReady(!matchedTables.isEmpty())
			.build(), graphRequest);
	}

	private DatasourceExplorerResult getTableSchema(ExplorerContext context, DatasourceExplorerRequest request,
			@Nullable AgentRequest graphRequest) throws Exception {
		String tableName = resolveVisibleTableName(context, requireSingleTableName(request));
		long showColumnsStartNanos = System.nanoTime();
		List<ColumnInfoBO> columns = context.accessor()
			.showColumns(context.dbConfig(),
					DbQueryParameter.from(context.dbConfig())
						.setSchema(context.dbConfig().getSchema())
						.setTable(tableName));
		recordSegment(segments -> segments.jdbcExecuteNanos += System.nanoTime() - showColumnsStartNanos);
		Document tableDocument = loadTableDocumentMap(context, List.of(tableName)).get(normalizeTableName(tableName));
		Map<String, Document> columnDocumentMap = loadColumnDocumentMap(context, tableName);
		List<Map<String, Object>> columnEntries = applyVisibleColumnFilter(context, tableName, columns).stream()
			.map(column -> trimSchemaComment(toColumnEntry(column,
					columnDocumentMap.get(normalizeColumnName(column.getName())))))
			.toList();
		List<UnifiedRelation> relations = filterRelations(context, tableName);
		List<Map<String, Object>> relationEntries = relations.stream().map(this::toRelationEntry).toList();
		Map<String, Object> tableEntry = trimSchemaComment(toTableEntry(context, tableName, tableDocument, relations));
		return capture(
				baseResult(context, DatasourceExplorerAction.GET_TABLE_SCHEMA, "已加载表“%s”的结构信息".formatted(tableName))
					.tables(List.of(tableEntry))
					.columns(columnEntries)
					.relations(relationEntries)
					.searchReady(true)
					.build(),
				graphRequest);
	}

	/**
	 * GET_TABLE_SCHEMA 响应瘦身的唯一约束点：对表/字段说明按字符截断，只截注释、不剔除任何列
	 * （审计列可能被业务查询引用）。仅作用于本方法产出的响应装配，LIST_TABLES/FIND_TABLES/
	 * GET_RELATED_TABLES 等其它通道共用 {@link #toTableEntry}/{@link #toColumnEntry}，不受影响。
	 */
	private Map<String, Object> trimSchemaComment(Map<String, Object> entry) {
		Object description = entry.get("description");
		if (description instanceof String text && text.length() > MAX_SCHEMA_COMMENT_CHARS) {
			entry.put("description", text.substring(0, MAX_SCHEMA_COMMENT_CHARS) + SCHEMA_COMMENT_TRUNCATION_SUFFIX);
		}
		return entry;
	}

	private DatasourceExplorerResult getRelatedTables(ExplorerContext context, DatasourceExplorerRequest request,
			@Nullable AgentRequest graphRequest) {
		String tableName = resolveVisibleTableName(context, requireSingleTableName(request));
		List<UnifiedRelation> relations = filterRelations(context, tableName);
		List<Map<String, Object>> relationEntries = relations.stream().map(this::toRelationEntry).toList();
		Set<String> relatedTables = relations.stream()
			.flatMap(relation -> Arrays.stream(new String[] { relation.sourceTable(), relation.targetTable() }))
			.filter(candidate -> !normalizeTableName(candidate).equals(normalizeTableName(tableName)))
			.filter(candidate -> context.visibleTableNameSet().contains(normalizeTableName(candidate)))
			.collect(Collectors.toCollection(LinkedHashSet::new));
		Map<String, Document> tableDocumentMap = loadTableDocumentMap(context, new ArrayList<>(relatedTables));
		List<Map<String, Object>> tableEntries = relatedTables.stream()
			.map(relatedTable -> toTableEntry(context, relatedTable,
					tableDocumentMap.get(normalizeTableName(relatedTable)), filterRelations(context, relatedTable)))
			.toList();
		return capture(baseResult(context, DatasourceExplorerAction.GET_RELATED_TABLES,
				"表“%s”共找到 %d 张关联表".formatted(tableName, tableEntries.size()))
			.tables(tableEntries)
			.relations(relationEntries)
			.searchReady(!relationEntries.isEmpty())
			.build(), graphRequest);
	}

	private DatasourceExplorerResult previewRows(ExplorerContext context, DatasourceExplorerRequest request,
			@Nullable AgentRequest graphRequest) throws Exception {
		String tableName = resolveVisibleTableName(context, requireSingleTableName(request));
		// PREVIEW_ROWS 同样把整行业务数据发给外部模型，必须和 SEARCH 走同一套行数上限：
		// 只用 normalizeExplorerLimit 会绕过平台 maxResultRows 与 Skill 快照 maxRows，
		// 让预览路径一次返回超出租户自身配置的行数。
		int limit = Math.min(normalizeExplorerLimit(request.getLimit()),
				Math.min(maxResultRows(), snapshotMaxRows(graphRequest)));
		String sql = SqlUtil.buildSelectSql(context.dbConfig().getDialectType(),
				SqlUtil.quoteIdentifier(context.dbConfig().getDialectType(), tableName),
				resolvePreviewColumnSelection(context, tableName), limit);
		long permissionStartNanos = System.nanoTime();
		PermissionRewriteResult permissionRewrite = sqlPermissionRewriteService.rewrite(context.datasource().getId(),
				sql, graphRequest);
		recordSegment(segments -> segments.permissionNanos += System.nanoTime() - permissionStartNanos);
		if (permissionRewrite.denied()) {
			return permissionDeniedResult(context, DatasourceExplorerAction.PREVIEW_ROWS, permissionRewrite,
					List.of(tableName), graphRequest);
		}
		ResultSetBO resultSet = executeSql(context, permissionRewrite.sql());
		Map<String, String> displayNames = resolveDisplayNames(context, graphRequest, List.of(tableName),
				permissionRewrite.sql(), toExplainColumns(resultSet));
		return capture(baseResult(context, DatasourceExplorerAction.PREVIEW_ROWS,
				("已预览表“%s”的 %d 行数据".formatted(tableName, resultSet.getData().size())) + HIDDEN_FIELD_INFERENCE_WARNING)
			.tables(List.of(Map.of("name", tableName)))
			.columns(toColumnHeaders(resultSet, displayNames))
			.rows(toRows(resultSet))
			.sql(permissionRewrite.sql())
			.usedTables(List.of(tableName))
			.usedColumns(toExplainColumns(resultSet))
			.resultScope("预览结果仅包含当前可见字段，并受 limit 限制。")
			.resultScopeDetails(appendPermissionScopeDetails(buildPreviewResultScopeDetails(tableName, resultSet, limit),
					permissionRewrite))
			.decisionReason("当前通过 PREVIEW_ROWS 直接预览单表数据，用于快速确认表内容。")
			.toolDecisionReasons(appendPermissionDecisionReasons(buildPreviewDecisionReasons(tableName, limit),
					permissionRewrite))
			.searchReady(true)
			.build(), graphRequest);
	}

	private DatasourceExplorerResult search(ExplorerContext context, DatasourceExplorerRequest request,
			@Nullable AgentRequest graphRequest) throws Exception {
		String rawSql = StringUtils.trimToNull(request.getSql());
		if (rawSql == null) {
			throw new IllegalArgumentException("search action 必须提供 sql");
		}
		String query = StringUtils.firstNonBlank(graphRequest == null ? null : graphRequest.getQuery(), request.getQuery());
		String rankingQuery = StringUtils.firstNonBlank(
				graphRequest == null ? null : graphRequest.getRankingIntentQuery(), query);
		Optional<Integer> topN = TopNLimitResolver.extractRequestedRows(rankingQuery);
		int requestedRows = resolveRequestedRows(topN, request.getLimit());
		int snapshotLimit = snapshotMaxRows(graphRequest);
		int appliedLimit = Math.min(requestedRows, Math.min(maxResultRows(), snapshotLimit));
		SqlGuardedQuery guardedQuery;
		try {
			long guardStartNanos = System.nanoTime();
			guardedQuery = guardReadonlySql(context, rawSql, appliedLimit + 1);
			validateTopNLimit(topN, guardedQuery.outerLimit(), appliedLimit);
			recordSegment(segments -> segments.guardNanos += System.nanoTime() - guardStartNanos);
		}
		catch (IllegalArgumentException ex) {
			if (!request.isRequireAuthorizedBaseTable()) {
				throw ex;
			}
			throw new DeterministicSqlGuardRejectedException("确定性查询未通过静态 SQL Guard", ex);
		}
		if (request.isRequireAuthorizedBaseTable()) {
			Runnable staticGuardPassedCallback = request.getStaticGuardPassedCallback();
			if (staticGuardPassedCallback != null) {
				staticGuardPassedCallback.run();
			}
		}
		long permissionStartNanos = System.nanoTime();
		PermissionRewriteResult permissionRewrite = sqlPermissionRewriteService.rewrite(context.datasource().getId(),
				guardedQuery.sql(), graphRequest);
		recordSegment(segments -> segments.permissionNanos += System.nanoTime() - permissionStartNanos);
		List<String> usedTables = toExplainTables(guardedQuery.referencedTables(), context.visibleTablesByName());
		if (permissionRewrite.denied()) {
			return permissionDeniedResult(context, DatasourceExplorerAction.SEARCH, permissionRewrite, usedTables,
					graphRequest);
		}
		long coerceStartNanos = System.nanoTime();
		String sqlToExecute = coercePostgresBooleanLiterals(context, permissionRewrite.sql(),
				guardedQuery.referencedTables());
		recordSegment(segments -> segments.coerceNanos += System.nanoTime() - coerceStartNanos);
		ResultSetBO resultSet = executeSql(context, sqlToExecute, request.getStatementTimeoutSeconds(),
				request.isRequireAuthorizedBaseTable());
		int fetchedRows = resultSet.getData().size();
		boolean hasMore = fetchedRows > appliedLimit;
		if (hasMore) {
			resultSet.setData(new ArrayList<>(resultSet.getData().subList(0, appliedLimit)));
		}
		resultSet = filterResultSet(resultSet, guardedQuery);
		List<Map<String, Object>> relationEvidence = collectRelationEvidence(context, guardedQuery.referencedTables());
		List<String> usedColumns = toExplainColumns(resultSet);
		Map<String, String> displayNames = resolveDisplayNames(context, graphRequest, usedTables, sqlToExecute,
				usedColumns);
		boolean emptyResult = resultSet.getData() == null || resultSet.getData().isEmpty();
		int rowCount = resultSet.getData() == null ? 0 : resultSet.getData().size();
		ResultCoverageStatus coverageStatus = resolveCoverageStatus(requestedRows, appliedLimit, rowCount, hasMore,
				guardedQuery.outerLimit());
		Boolean hasMoreValue = hasMore ? Boolean.TRUE
				: coverageStatus == ResultCoverageStatus.LIMIT_REACHED_UNKNOWN ? null : Boolean.FALSE;
		return capture(baseResult(context, DatasourceExplorerAction.SEARCH,
				buildSearchSummary(rowCount, appliedLimit, coverageStatus) + HIDDEN_FIELD_INFERENCE_WARNING
						+ (rowCount > 0 ? " 已返回查询结果，若已覆盖当前问题请直接作答，不要继续探表。" : ""))
			.emptyResult(emptyResult ? Boolean.TRUE : null)
			.suggestedNextAction(emptyResult ? resolveEmptyResultSuggestedNextAction(sqlToExecute) : null)
			.columns(toColumnHeaders(resultSet, displayNames))
			.rows(toRows(resultSet))
			.requestedRows(requestedRows)
			.returnedRows(rowCount)
			.appliedLimit(appliedLimit)
			.hasMore(hasMoreValue)
			.coverageStatus(coverageStatus)
			.sql(sqlToExecute)
			.usedTables(usedTables)
			.usedColumns(usedColumns)
			.relationEvidence(relationEvidence)
			.resultScope(buildResultScopeSummary(resultSet, appliedLimit, coverageStatus))
			.resultScopeDetails(appendPermissionScopeDetails(
					buildSearchResultScopeDetails(resultSet, requestedRows, appliedLimit, coverageStatus,
							guardedQuery.allowedResultHeaders()),
					permissionRewrite))
			.decisionReason(buildDecisionReason(guardedQuery.referencedTables(), relationEvidence))
			.toolDecisionReasons(appendPermissionDecisionReasons(
					buildSearchDecisionReasons(usedTables, relationEvidence, appliedLimit), permissionRewrite))
			.searchReady(true)
			.build(), graphRequest);
	}

	private String resolveEmptyResultSuggestedNextAction(String sqlToExecute) {
		if (referencesSettlementDate(sqlToExecute)) {
			log.info("empty SEARCH suggestedNextAction=settlement_date_period");
			return EMPTY_RESULT_DEFAULT_NEXT_ACTION + EMPTY_RESULT_SETTLEMENT_DATE_HINT;
		}
		return EMPTY_RESULT_DEFAULT_NEXT_ACTION;
	}

	private boolean referencesSettlementDate(String sqlToExecute) {
		return StringUtils.containsIgnoreCase(sqlToExecute, SETTLEMENT_DATE_COLUMN);
	}

	private ExplorerContext resolveContext(@Nullable AgentRequest graphRequest, boolean skipPhysicalRelationMetadata)
			throws Exception {
		Long skillId = requireSkillId(graphRequest);
		if (skipPhysicalRelationMetadata) {
			return loadContext(graphRequest, true);
		}
		return datasourceRuntimeContextCache.getOrLoadExplorerContext(String.valueOf(skillId), graphRequest,
				() -> loadContext(graphRequest, false));
	}

	private SkillVersionResources requireSkillResources(@Nullable AgentRequest request) {
		if (request == null || request.getRoutedSkillResources() == null) {
			throw new IllegalArgumentException("Skill resource snapshot is required");
		}
		SkillVersionResources resources = request.getRoutedSkillResources();
		if (!resources.hasDatasourceAccess()) {
			throw new IllegalArgumentException("Skill resource snapshot has no usable datasource scope");
		}
		return resources;
	}

	private Long requireSkillId(@Nullable AgentRequest request) {
		SkillVersionResources resources = requireSkillResources(request);
		if (request.getRoutedSkillId() == null || !request.getRoutedSkillId().equals(resources.skillId())
				|| request.getRoutedSkillVersionId() == null
				|| !request.getRoutedSkillVersionId().equals(resources.skillVersionId())) {
			throw new IllegalArgumentException("Routed Skill identity does not match its resource snapshot");
		}
		return resources.skillId();
	}

	private String requireTenantId(@Nullable AgentRequest request) {
		if (request == null || !StringUtils.isNotBlank(request.getTenantIdSnapshot())) {
			throw new IllegalArgumentException("Tenant permission snapshot is required");
		}
		return request.getTenantIdSnapshot().trim();
	}

	private void applySnapshotScope(SkillDatasource target, SkillVersionResources.DatasourceResource datasource) {
		if (datasource == null || !datasource.usable()) {
			throw new IllegalArgumentException("Skill datasource snapshot has an empty table or column whitelist");
		}
		List<String> tables = datasource.tables().stream().map(SkillVersionResources.TableScope::table).toList();
		Map<String, List<String>> columns = datasource.tables().stream()
			.collect(Collectors.toMap(SkillVersionResources.TableScope::table,
					SkillVersionResources.TableScope::columns, (left, right) -> left, LinkedHashMap::new));
		target.setSelectTables(tables);
		target.setSelectColumns(columns);
	}

	private ExplorerContext loadContext(@Nullable AgentRequest graphRequest, boolean skipPhysicalRelationMetadata)
			throws Exception {
		Long skillId = requireSkillId(graphRequest);
		SkillDatasource skillDatasource;
		if (graphRequest != null) {
			if (graphRequest.getRoutedSkillResources().datasourceId() == null) {
				throw new IllegalArgumentException("当前Skill安装版本未配置可用数据源");
			}
			skillDatasource = new SkillDatasource();
			skillDatasource.setSkillId(skillId);
			skillDatasource.setDatasourceId(graphRequest.getRoutedSkillResources().datasourceId());
			applySnapshotScope(skillDatasource, graphRequest.getRoutedSkillResources().datasource());
		}
		else {
			throw new IllegalArgumentException("Skill runtime context is required");
		}
		Datasource datasource = skillDatasource.getDatasource() != null ? skillDatasource.getDatasource()
				: datasourceService.requireDatasourceForTenant(skillDatasource.getDatasourceId(), requireTenantId(graphRequest));
		if (datasource == null) {
			throw new IllegalStateException("当前 Skill 未找到活动数据源：" + skillId);
		}
		DbConfigBO dbConfig = datasourceService.getDbConfig(datasource);
		Accessor accessor = accessorFactory.getAccessorByDbConfig(dbConfig);
		List<String> explicitSelectedTables = skillDatasource.getSelectTables() == null ? List.of()
				: skillDatasource.getSelectTables();
		if (explicitSelectedTables.isEmpty()) {
			throw new IllegalArgumentException("Skill datasource snapshot has an empty table whitelist");
		}
		List<String> visibleTables = explicitSelectedTables;
		Map<String, List<String>> visibleTablesByName = indexTablesByIdentity(visibleTables);
		Map<String, List<String>> visibleTablesByLeafName = indexTablesByLeafName(visibleTables);
		Set<String> visibleTableNameSet = visibleTables.stream()
			.map(this::normalizeTableName)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		Map<String, List<String>> visibleColumnsByTable = buildVisibleColumnsByTable(skillDatasource,
				visibleTablesByName, visibleTablesByLeafName);
		Map<String, Set<String>> visibleColumnNameSetByTable = visibleColumnsByTable.entrySet()
			.stream()
			.collect(Collectors.toMap(Map.Entry::getKey,
					entry -> entry.getValue()
						.stream()
						.map(this::normalizeColumnName)
						.collect(Collectors.toCollection(LinkedHashSet::new)),
					(left, right) -> left, LinkedHashMap::new));
		// 工具跑在 boundedElastic，不能走 getLogicalRelations()：它依赖当前登录态
		// tenantId，异步线程上会打成「登录过期」。租户以请求快照为准。
		List<LogicalRelation> logicalRelations = datasourceService
			.getLogicalRelationsForTenant(datasource.getId(), requireTenantId(graphRequest));
		List<ForeignKeyInfoBO> physicalRelations = skipPhysicalRelationMetadata ? List.of()
				: loadPhysicalRelations(accessor, dbConfig, visibleTables);
		List<UnifiedRelation> unifiedRelations = buildUnifiedRelations(visibleTablesByName, visibleTablesByLeafName,
				physicalRelations, logicalRelations == null ? List.of() : logicalRelations);
		return new ExplorerContext(skillDatasource, datasource, dbConfig, accessor, List.copyOf(visibleTables),
				Set.copyOf(visibleTableNameSet), toImmutableListIndex(visibleTablesByName),
				toImmutableListIndex(visibleTablesByLeafName), List.copyOf(explicitSelectedTables),
				Map.copyOf(visibleColumnsByTable), toImmutableSetIndex(visibleColumnNameSetByTable),
				Set.copyOf(visibleColumnsByTable.keySet()), List.copyOf(unifiedRelations),
				indexRelationsByTable(unifiedRelations));
	}

	private List<ForeignKeyInfoBO> loadPhysicalRelations(Accessor accessor, DbConfigBO dbConfig, List<String> tables) {
		try {
			List<ForeignKeyInfoBO> foreignKeys = accessor.showForeignKeys(dbConfig,
					DbQueryParameter.from(dbConfig).setSchema(dbConfig.getSchema()).setTables(tables));
			return foreignKeys == null ? List.of() : foreignKeys;
		}
		catch (Exception ex) {
			// Losing foreign keys silently makes the agent generate joins it cannot justify.
			log.warn("Failed to load physical relations, continuing without them. schema={}, tableCount={}",
					dbConfig == null ? null : dbConfig.getSchema(), tables == null ? 0 : tables.size(), ex);
			return List.of();
		}
	}

	private ResultSetBO executeSql(ExplorerContext context, String sql) throws Exception {
		return executeSql(context, sql, null, false);
	}

	private ResultSetBO executeSql(ExplorerContext context, String sql, @Nullable Integer statementTimeoutSeconds)
			throws Exception {
		return executeSql(context, sql, statementTimeoutSeconds, false);
	}

	private ResultSetBO executeSql(ExplorerContext context, String sql, @Nullable Integer statementTimeoutSeconds,
			boolean suppressErrorDetails) throws Exception {
		try {
			long jdbcStartNanos = System.nanoTime();
			ResultSetBO resultSet = context.accessor()
				.executeSqlAndReturnObject(context.dbConfig(),
						DbQueryParameter.from(context.dbConfig())
							.setSchema(context.dbConfig().getSchema())
							.setSql(sql)
							.setStatementTimeoutSeconds(statementTimeoutSeconds)
							.setSuppressErrorDetails(suppressErrorDetails));
			recordSegment(segments -> segments.jdbcExecuteNanos += System.nanoTime() - jdbcStartNanos);
			if (resultSet == null) {
				return ResultSetBO.builder().column(List.of()).data(List.of()).errorMsg(null).build();
			}
			if (resultSet.getErrorMsg() != null) {
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
			throw translateSqlFailure(context, sql, ex);
		}
	}

	private Exception translateSqlFailure(ExplorerContext context, String sql, Exception error) {
		if (error instanceof IllegalStateException
				&& PostgresBooleanLiteralNormalizer.MODEL_HINT.equals(error.getMessage())) {
			return error;
		}
		if (PostgresBooleanLiteralNormalizer.isBooleanIntegerMismatch(error)) {
			log.warn("PostgreSQL rejected boolean compared with integer. dialect={}, sql={}",
					context == null || context.dbConfig() == null ? null : context.dbConfig().getDialectType(),
					PostgresBooleanLiteralNormalizer.truncateSql(sql));
			return new IllegalStateException(PostgresBooleanLiteralNormalizer.MODEL_HINT, error);
		}
		return error;
	}

	private String coercePostgresBooleanLiterals(ExplorerContext context, String sql, Set<String> referencedTables) {
		if (context == null || context.dbConfig() == null
				|| !PostgresBooleanLiteralNormalizer.isPostgresFamily(context.dbConfig().getDialectType())
				|| !PostgresBooleanLiteralNormalizer.mightContainIntegerBooleanLiteral(sql)) {
			return sql;
		}
		return PostgresBooleanLiteralNormalizer.rewrite(sql, loadColumnTypes(context, referencedTables));
	}

	private Map<String, String> loadColumnTypes(ExplorerContext context, Set<String> referencedTables) {
		Map<String, String> types = new LinkedHashMap<>();
		Map<String, String> unqualified = new LinkedHashMap<>();
		Set<String> collisions = new LinkedHashSet<>();
		if (referencedTables == null || referencedTables.isEmpty()) {
			return types;
		}
		for (String tableName : referencedTables) {
			List<ColumnInfoBO> columns;
			try {
				columns = context.accessor()
					.showColumns(context.dbConfig(),
							DbQueryParameter.from(context.dbConfig())
								.setSchema(context.dbConfig().getSchema())
								.setTable(tableName));
			}
			catch (Exception ex) {
				log.warn("Failed to load column types for boolean literal rewrite. table={}", tableName, ex);
				continue;
			}
			if (columns == null) {
				continue;
			}
			String normalizedTable = normalizeTableName(tableName);
			for (ColumnInfoBO column : columns) {
				if (column == null || !StringUtils.isNotBlank(column.getName())) {
					continue;
				}
				String normalizedColumn = normalizeColumnName(column.getName());
				String type = StringUtils.trimToEmpty(column.getType());
				types.put(normalizedTable + "." + normalizedColumn, type);
				String previous = unqualified.putIfAbsent(normalizedColumn, type);
				if (previous != null && !previous.equalsIgnoreCase(type)) {
					collisions.add(normalizedColumn);
				}
			}
		}
		for (String collision : collisions) {
			unqualified.remove(collision);
		}
		types.putAll(unqualified);
		return types;
	}

	private SqlGuardedQuery guardReadonlySql(ExplorerContext context, String rawSql, int probeLimit) {
		String compactSql = stripTrailingSemicolons(rawSql);
		if (compactSql.isEmpty()) {
			throw new IllegalArgumentException("SQL 不能为空");
		}
		Statement statement = parseSingleSelectStatement(compactSql);
		expandSingleRestrictedTableSelectStar(context, (Select) statement);
		// 静态守卫跑在解析后的语法树上，因此执行的必须是同一棵树重新序列化出来的 SQL，而不是调用方原串。
		// 否则任何 jsqlparser 与数据库之间的解析差异（例如 MySQL 可执行注释 /*! */）都会变成
		// 「校验看到的是一条语句、数据库执行的是另一条」的绕过面。
		String sqlToExecute = statement.toString();
		// 失败关闭自检：重新序列化的文本必须仍能解析成单条 SELECT。
		// deparser 一旦吐出解析不回来的文本就说明它不可信，此时直接拒绝，绝不回退执行原串。
		try {
			parseSingleSelectStatement(sqlToExecute);
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("SQL 规范化后无法再次解析，出于安全考虑已拒绝执行，请简化查询后重试", ex);
		}
		SqlValidationResult validationResult = new SqlColumnAccessValidator(context).validate((Select) statement);
		if (validationResult.referencedTables().isEmpty()) {
			throw new IllegalArgumentException("只读查询必须引用至少一张当前 Skill 已授权的基础表");
		}
		Integer outerLimit = TopNLimitResolver.extractOuterLimit((Select) statement).orElse(null);
		String guardedSql = wrapLimitSql(context.dbConfig().getDialectType(), sqlToExecute, probeLimit);
		return new SqlGuardedQuery(guardedSql, validationResult.referencedTables(),
				validationResult.allowedResultHeaders(), outerLimit);
	}

	private boolean expandSingleRestrictedTableSelectStar(ExplorerContext context, Select select) {
		if (!(select instanceof PlainSelect plainSelect)) {
			return false;
		}
		if (select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) {
			return false;
		}
		List<SelectItem<?>> selectItems = Optional.ofNullable(plainSelect.getSelectItems()).orElse(List.of());
		if (selectItems.size() != 1 || !(selectItems.get(0).getExpression() instanceof AllColumns)
				|| selectItems.get(0).getExpression() instanceof AllTableColumns) {
			return false;
		}
		if (!(plainSelect.getFromItem() instanceof Table table)
				|| (plainSelect.getJoins() != null && !plainSelect.getJoins().isEmpty())) {
			return false;
		}
		String tableName = resolveVisibleTableName(context, extractTableReference(table));
		String normalizedTableName = normalizeTableName(tableName);
		if (!context.columnRestrictedTables().contains(normalizedTableName)) {
			return false;
		}
		List<String> visibleColumns = context.visibleColumnsByTable().get(normalizedTableName);
		if (visibleColumns == null || visibleColumns.isEmpty()) {
			return false;
		}
		List<SelectItem<?>> expandedItems = new ArrayList<>();
		for (String columnName : visibleColumns) {
			expandedItems
				.add(SelectItem.from(new Column(SqlUtil.quoteIdentifier(context.dbConfig().getDialectType(), columnName))));
		}
		plainSelect.setSelectItems(expandedItems);
		return true;
	}

	private String stripTrailingSemicolons(String sql) {
		String trimmed = StringUtils.trimToEmpty(sql);
		while (trimmed.endsWith(";")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
		}
		return trimmed;
	}

	private Statement parseSingleSelectStatement(String sql) {
		List<Statement> statements;
		try {
			statements = CCJSqlParserUtil.parseStatements(sql).getStatements();
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("SQL 解析失败，请检查语法后重试", ex);
		}
		if (statements == null || statements.isEmpty()) {
			throw new IllegalArgumentException("SQL 不能为空");
		}
		if (statements.size() > 1) {
			throw new IllegalArgumentException("仅允许执行单条 SELECT / WITH 查询，请勿拼接多条语句");
		}
		Statement statement = statements.get(0);
		if (!(statement instanceof Select)) {
			throw new IllegalArgumentException("只允许执行 SELECT / WITH 查询");
		}
		return statement;
	}

	private String wrapLimitSql(String dialectType, String sql, int limit) {
		String normalizedDialect = StringUtils.defaultString(dialectType).toLowerCase(Locale.ROOT);
		if (normalizedDialect.contains("sqlserver") || normalizedDialect.contains("sql_server")) {
			return "SELECT TOP %d * FROM (%s) dataagent_safe_limit".formatted(limit, sql);
		}
		if (normalizedDialect.contains("oracle")) {
			return "SELECT * FROM (%s) dataagent_safe_limit FETCH FIRST %d ROWS ONLY".formatted(sql, limit);
		}
		return "SELECT * FROM (%s) dataagent_safe_limit LIMIT %d".formatted(sql, limit);
	}

	private String requireSingleTableName(DatasourceExplorerRequest request) {
		if (StringUtils.isNotBlank(request.getTableName())) {
			return request.getTableName().trim();
		}
		throw new IllegalArgumentException("当前 action 必须提供 tableName");
	}

	private int normalizeExplorerLimit(Integer limit) {
		if (limit == null || limit <= 0) {
			return DEFAULT_EXPLORER_LIMIT;
		}
		return Math.min(limit, MAX_EXPLORER_LIMIT);
	}

	private int resolveRequestedRows(Optional<Integer> topN, Integer toolLimit) {
		if (topN.isPresent()) {
			return topN.get();
		}
		return toolLimit == null || toolLimit <= 0 ? defaultResultRows() : toolLimit;
	}

	private int defaultResultRows() {
		return Math.max(1, Math.min(dataAgentProperties.getRuntime().getDefaultResultRows(), maxResultRows()));
	}

	private int snapshotMaxRows(@Nullable AgentRequest request) {
		Integer maxRows = requireSkillResources(request).datasource().maxRows();
		if (maxRows == null || maxRows <= 0) {
			throw new IllegalArgumentException("Skill datasource snapshot has no row limit");
		}
		return maxRows;
	}

	private int maxResultRows() {
		return Math.max(1, dataAgentProperties.getRuntime().getMaxResultRows());
	}

	private void validateTopNLimit(Optional<Integer> topN, Integer outerLimit, int appliedLimit) {
		if (topN.isEmpty() || outerLimit == null || outerLimit >= appliedLimit) {
			return;
		}
		throw new IllegalArgumentException(
				"用户要求 Top%d，但 SQL 最外层仅限制 %d 行；请将 LIMIT/TOP/FETCH FIRST 至少调整为 %d"
					.formatted(topN.get(), outerLimit, appliedLimit));
	}

	private ResultCoverageStatus resolveCoverageStatus(int requestedRows, int appliedLimit, int returnedRows,
			boolean hasMore, Integer outerLimit) {
		if (requestedRows > appliedLimit) {
			if (hasMore) {
				return ResultCoverageStatus.PLATFORM_LIMITED;
			}
			if (returnedRows >= appliedLimit || (outerLimit != null && outerLimit <= returnedRows)) {
				return ResultCoverageStatus.LIMIT_REACHED_UNKNOWN;
			}
			return ResultCoverageStatus.SOURCE_SHORT;
		}
		if (returnedRows >= requestedRows) {
			return ResultCoverageStatus.FULL;
		}
		if (outerLimit != null && outerLimit <= returnedRows) {
			return ResultCoverageStatus.LIMIT_REACHED_UNKNOWN;
		}
		return ResultCoverageStatus.SOURCE_SHORT;
	}

	private String buildSearchSummary(int rowCount, int appliedLimit, ResultCoverageStatus coverageStatus) {
		return switch (coverageStatus) {
			case PLATFORM_LIMITED -> "已执行只读查询，受平台单次查询上限限制，返回前 %d 行结果".formatted(rowCount);
			case LIMIT_REACHED_UNKNOWN -> "已执行只读查询，返回 %d 行结果并达到当前限制，无法确认是否仍有更多数据".formatted(rowCount);
			case SOURCE_SHORT -> "已执行只读查询，实际仅返回 %d 行结果".formatted(rowCount);
			case FULL -> "已执行只读查询，完整返回 %d 行结果".formatted(Math.min(rowCount, appliedLimit));
		};
	}

	private Map<String, Document> loadTableDocumentMap(ExplorerContext context, List<String> tableNames) {
		if (tableNames == null || tableNames.isEmpty()) {
			return Collections.emptyMap();
		}
		try {
			return schemaService
				.getTableDocuments(context.skillDatasource().getSkillId().toString(), context.datasource().getId(),
						tableNames)
				.stream()
				.collect(Collectors.toMap(doc -> normalizeTableName(String.valueOf(doc.getMetadata().get("name"))),
						doc -> doc, (left, right) -> left, LinkedHashMap::new));
		}
		catch (Exception ex) {
			// Without table descriptions the agent picks tables blind, so the loss must be traceable.
			log.warn("Failed to load table documents, continuing without descriptions. datasourceId={}, tableCount={}",
					context.datasource().getId(), tableNames.size(), ex);
			return Collections.emptyMap();
		}
	}

	private Map<String, Document> loadColumnDocumentMap(ExplorerContext context, String tableName) {
		try {
			return schemaService
				.getColumnDocumentsByTableName(context.skillDatasource().getSkillId().toString(),
						context.datasource().getId(), List.of(tableName))
				.stream()
				.collect(Collectors.toMap(doc -> normalizeColumnName(String.valueOf(doc.getMetadata().get("name"))),
						doc -> doc, (left, right) -> left, LinkedHashMap::new));
		}
		catch (Exception ex) {
			// Without column descriptions the agent picks columns blind, so the loss must be traceable.
			log.warn("Failed to load column documents, continuing without descriptions. datasourceId={}, table={}",
					context.datasource().getId(), tableName, ex);
			return Collections.emptyMap();
		}
	}

	private Map<String, Object> toTableEntry(ExplorerContext context, String tableName, Document tableDocument,
			List<UnifiedRelation> relations) {
		Map<String, Object> tableEntry = new LinkedHashMap<>();
		tableEntry.put("name", tableName);
		tableEntry.put("selected", isSelectedTable(context, tableName));
		String unifiedForeignKeys = summarizeRelations(relations);
		if (tableDocument != null) {
			tableEntry.put("schema", tableDocument.getMetadata().getOrDefault("schema", ""));
			tableEntry.put("description", tableDocument.getMetadata().getOrDefault("description", ""));
			tableEntry.put("primaryKeys", tableDocument.getMetadata().getOrDefault("primaryKey", List.of()));
			tableEntry.put("foreignKeys", StringUtils.defaultIfBlank(unifiedForeignKeys,
					String.valueOf(tableDocument.getMetadata().getOrDefault("foreignKey", ""))));
		}
		else if (StringUtils.isNotBlank(unifiedForeignKeys)) {
			tableEntry.put("foreignKeys", unifiedForeignKeys);
		}
		return tableEntry;
	}

	private Map<String, Object> toColumnEntry(ColumnInfoBO columnInfo, Document columnDocument) {
		Map<String, Object> columnEntry = new LinkedHashMap<>();
		columnEntry.put("name", columnInfo.getName());
		columnEntry.put("type", columnInfo.getType());
		columnEntry.put("description", resolveColumnDescription(columnInfo, columnDocument));
		columnEntry.put("primary", columnInfo.isPrimary());
		columnEntry.put("notnull", columnInfo.isNotnull());
		columnEntry.put("samples", resolveColumnSamples(columnInfo, columnDocument));
		return columnEntry;
	}

	private String resolveColumnDescription(ColumnInfoBO columnInfo, Document columnDocument) {
		if (StringUtils.isNotBlank(columnInfo.getDescription())) {
			return columnInfo.getDescription();
		}
		if (columnDocument == null) {
			return StringUtils.EMPTY;
		}
		return String.valueOf(columnDocument.getMetadata().getOrDefault("description", ""));
	}

	private List<String> resolveColumnSamples(ColumnInfoBO columnInfo, Document columnDocument) {
		if (StringUtils.isNotBlank(columnInfo.getSamples())) {
			return parseSamples(columnInfo.getSamples());
		}
		if (columnDocument == null) {
			return List.of();
		}
		return parseSamples(String.valueOf(columnDocument.getMetadata().getOrDefault("samples", "")));
	}

	private List<String> parseSamples(String samples) {
		if (StringUtils.isBlank(samples)) {
			return List.of();
		}
		try {
			return objectMapper.readValue(samples, STRING_LIST_TYPE);
		}
		catch (Exception ex) {
			return List.of(samples);
		}
	}

	private List<UnifiedRelation> filterRelations(ExplorerContext context, String tableName) {
		return context.relationsByTable()
			.getOrDefault(normalizeTableName(tableName), List.of())
			.stream()
			.filter(relation -> isRelationVisible(context, relation))
			.toList();
	}

	private Map<String, Object> toRelationEntry(UnifiedRelation relation) {
		Map<String, Object> relationEntry = new LinkedHashMap<>();
		relationEntry.put("sourceTable", relation.sourceTable());
		relationEntry.put("sourceColumn", relation.sourceColumn());
		relationEntry.put("targetTable", relation.targetTable());
		relationEntry.put("targetColumn", relation.targetColumn());
		relationEntry.put("relationType", relation.relationType());
		relationEntry.put("description", relation.description());
		relationEntry.put("sourceType", relation.sourceType());
		relationEntry.put("virtual", relation.virtual());
		relationEntry.put("declaredInDatabase", relation.declaredInDatabase());
		return relationEntry;
	}

	private List<UnifiedRelation> buildUnifiedRelations(Map<String, List<String>> visibleTablesByName,
			Map<String, List<String>> visibleTablesByLeafName, List<ForeignKeyInfoBO> physicalRelations,
			List<LogicalRelation> logicalRelations) {
		Map<String, UnifiedRelation> relationMap = new LinkedHashMap<>();
		for (ForeignKeyInfoBO physicalRelation : physicalRelations) {
			UnifiedRelation relation = canonicalizeRelation(visibleTablesByName, visibleTablesByLeafName,
					toUnifiedRelation(physicalRelation));
			if (relation != null) {
				mergeRelation(relationMap, relation);
			}
		}
		for (LogicalRelation logicalRelation : logicalRelations) {
			UnifiedRelation relation = canonicalizeRelation(visibleTablesByName, visibleTablesByLeafName,
					toUnifiedRelation(logicalRelation));
			if (relation != null) {
				mergeRelation(relationMap, relation);
			}
		}
		return relationMap.values()
			.stream()
			.sorted(Comparator.comparing((UnifiedRelation relation) -> normalizeTableName(relation.sourceTable()))
				.thenComparing(relation -> StringUtils.defaultString(relation.sourceColumn()))
				.thenComparing(relation -> normalizeTableName(relation.targetTable()))
				.thenComparing(relation -> StringUtils.defaultString(relation.targetColumn())))
			.toList();
	}

	private UnifiedRelation toUnifiedRelation(ForeignKeyInfoBO relation) {
		return new UnifiedRelation(relation.getTable(), relation.getColumn(), relation.getReferencedTable(),
				relation.getReferencedColumn(), StringUtils.EMPTY, StringUtils.EMPTY, "physical", false, true);
	}

	private UnifiedRelation toUnifiedRelation(LogicalRelation relation) {
		return new UnifiedRelation(relation.getSourceTableName(), relation.getSourceColumnName(),
				relation.getTargetTableName(), relation.getTargetColumnName(),
				StringUtils.defaultString(relation.getRelationType()),
				StringUtils.defaultString(relation.getDescription()), "logical", true, false);
	}

	private UnifiedRelation canonicalizeRelation(Map<String, List<String>> visibleTablesByName,
			Map<String, List<String>> visibleTablesByLeafName, UnifiedRelation relation) {
		Optional<String> sourceTable = findVisibleTableName(visibleTablesByName, visibleTablesByLeafName,
				relation.sourceTable(), true);
		Optional<String> targetTable = findVisibleTableName(visibleTablesByName, visibleTablesByLeafName,
				relation.targetTable(), true);
		if (sourceTable.isEmpty() || targetTable.isEmpty()) {
			return null;
		}
		return new UnifiedRelation(sourceTable.get(), relation.sourceColumn(), targetTable.get(),
				relation.targetColumn(), relation.relationType(), relation.description(), relation.sourceType(),
				relation.virtual(), relation.declaredInDatabase());
	}

	private void mergeRelation(Map<String, UnifiedRelation> relationMap, UnifiedRelation incoming) {
		String relationKey = buildRelationKey(incoming);
		UnifiedRelation existing = relationMap.get(relationKey);
		if (existing == null) {
			relationMap.put(relationKey, incoming);
			return;
		}
		if (existing.declaredInDatabase() && !incoming.declaredInDatabase()) {
			relationMap.put(relationKey, mergeRelation(existing, incoming));
			return;
		}
		if (!existing.declaredInDatabase() && incoming.declaredInDatabase()) {
			relationMap.put(relationKey, mergeRelation(incoming, existing));
			return;
		}
		relationMap.put(relationKey, mergeRelation(existing, incoming));
	}

	private UnifiedRelation mergeRelation(UnifiedRelation preferred, UnifiedRelation supplement) {
		return new UnifiedRelation(preferred.sourceTable(), preferred.sourceColumn(), preferred.targetTable(),
				preferred.targetColumn(),
				StringUtils.firstNonBlank(preferred.relationType(), supplement.relationType()),
				StringUtils.firstNonBlank(preferred.description(), supplement.description()), preferred.sourceType(),
				preferred.virtual(), preferred.declaredInDatabase());
	}

	private String buildRelationKey(UnifiedRelation relation) {
		return normalizeTableName(relation.sourceTable()) + "|" + StringUtils.defaultString(relation.sourceColumn())
				+ "|" + normalizeTableName(relation.targetTable()) + "|"
				+ StringUtils.defaultString(relation.targetColumn());
	}

	private Map<String, List<UnifiedRelation>> indexRelationsByTable(List<UnifiedRelation> relations) {
		Map<String, List<UnifiedRelation>> relationIndex = new LinkedHashMap<>();
		for (UnifiedRelation relation : relations) {
			relationIndex.computeIfAbsent(normalizeTableName(relation.sourceTable()), key -> new ArrayList<>())
				.add(relation);
			String targetKey = normalizeTableName(relation.targetTable());
			if (!targetKey.equals(normalizeTableName(relation.sourceTable()))) {
				relationIndex.computeIfAbsent(targetKey, key -> new ArrayList<>()).add(relation);
			}
		}
		Map<String, List<UnifiedRelation>> immutableIndex = new LinkedHashMap<>();
		relationIndex
			.forEach((tableName, tableRelations) -> immutableIndex.put(tableName, List.copyOf(tableRelations)));
		return Map.copyOf(immutableIndex);
	}

	private String summarizeRelations(List<UnifiedRelation> relations) {
		return relations.stream()
			.map(relation -> relation.sourceTable() + "." + relation.sourceColumn() + "=" + relation.targetTable() + "."
					+ relation.targetColumn())
			.distinct()
			.collect(Collectors.joining("、"));
	}

	private List<Map<String, Object>> toColumnHeaders(ResultSetBO resultSet) {
		return toColumnHeaders(resultSet, Map.of());
	}

	private List<Map<String, Object>> toColumnHeaders(ResultSetBO resultSet, Map<String, String> displayNames) {
		return resultSet.getColumn().stream().map(column -> {
			Map<String, Object> header = new LinkedHashMap<>();
			header.put("name", column);
			String displayName = displayNames == null ? null : displayNames.get(column);
			if (StringUtils.isNotBlank(displayName)) {
				header.put("displayName", displayName);
			}
			return header;
		}).toList();
	}

	private Map<String, String> resolveDisplayNames(ExplorerContext context, @Nullable AgentRequest graphRequest,
			List<String> usedTables, String sql, List<String> jdbcColumns) {
		if (jdbcColumns == null || jdbcColumns.isEmpty() || searchResultColumnLexiconFactory == null) {
			return SearchResultColumnNamer.resolve(jdbcColumns, sql, SearchResultColumnNamer.lexicon());
		}
		Long skillId = graphRequest == null ? null : graphRequest.getRoutedSkillId();
		SearchResultColumnNamer.Lexicon lexicon = searchResultColumnLexiconFactory.load(
				context.datasource() == null ? null : context.datasource().getId(), skillId, usedTables);
		return SearchResultColumnNamer.resolve(jdbcColumns, sql, lexicon);
	}

	private List<String> toExplainColumns(ResultSetBO resultSet) {
		return Optional.ofNullable(resultSet.getColumn())
			.orElse(List.of())
			.stream()
			.filter(StringUtils::isNotBlank)
			.map(String::trim)
			.distinct()
			.toList();
	}

	private List<String> toExplainTables(Set<String> referencedTables, Map<String, List<String>> visibleTablesByName) {
		if (referencedTables == null || referencedTables.isEmpty()) {
			return List.of();
		}
		return referencedTables.stream().map(tableName -> {
			List<String> candidates = visibleTablesByName.getOrDefault(normalizeTableName(tableName), List.of());
			return candidates.isEmpty() ? tableName : candidates.get(0);
		}).distinct().toList();
	}

	private List<Map<String, Object>> collectRelationEvidence(ExplorerContext context, Set<String> referencedTables) {
		if (referencedTables == null || referencedTables.size() < 2) {
			return List.of();
		}
		Set<String> normalizedReferencedTables = referencedTables.stream()
			.map(this::normalizeTableName)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		return context.unifiedRelations()
			.stream()
			.filter(relation -> normalizedReferencedTables.contains(normalizeTableName(relation.sourceTable()))
					&& normalizedReferencedTables.contains(normalizeTableName(relation.targetTable())))
			.map(this::toRelationEntry)
			.toList();
	}

	private String buildResultScopeSummary(ResultSetBO resultSet, int limit, ResultCoverageStatus coverageStatus) {
		int rowCount = resultSet.getData() == null ? 0 : resultSet.getData().size();
		int columnCount = resultSet.getColumn() == null ? 0 : resultSet.getColumn().size();
		if (coverageStatus == ResultCoverageStatus.PLATFORM_LIMITED) {
			return "受平台单次查询上限限制，当前覆盖前 %d 行、%d 个返回字段；字段范围已经过可见性裁剪。".formatted(limit, columnCount);
		}
		if (coverageStatus == ResultCoverageStatus.LIMIT_REACHED_UNKNOWN) {
			return "当前结果达到 %d 行限制，无法确认是否仍有更多数据；共包含 %d 个返回字段。".formatted(limit, columnCount);
		}
		if (rowCount >= limit) {
			return "当前结果仅展示前 %d 行、%d 个返回字段；字段范围已经过可见性裁剪。".formatted(limit, columnCount);
		}
		return "当前结果展示 %d 行、%d 个返回字段；字段范围已经过可见性裁剪。".formatted(rowCount, columnCount);
	}

	private String buildDecisionReason(Set<String> referencedTables, List<Map<String, Object>> relationEvidence) {
		List<String> usedTables = toExplainTables(referencedTables, Map.of());
		if (!relationEvidence.isEmpty()) {
			return "本轮选择执行 SQL，是因为问题需要跨表查数；表间关联优先依据已配置的物理外键或逻辑关系。";
		}
		if (usedTables.size() > 1) {
			return "本轮选择执行 SQL，是因为问题需要联合多张表获取结构化结果。";
		}
		if (usedTables.size() == 1) {
			return "本轮选择执行 SQL，是因为问题需要直接从目标表提取结构化结果。";
		}
		return "本轮选择执行 SQL，是因为问题需要结构化数据结果来支撑回答。";
	}

	private List<String> buildPreviewDecisionReasons(String tableName, int limit) {
		return List.of("本轮直接选择 PREVIEW_ROWS，是为了快速确认单表“%s”的样例数据。".formatted(tableName),
				"预览查询会自动附带 limit=%d，避免一次返回过多行。".formatted(limit));
	}

	private List<String> buildSearchDecisionReasons(List<String> usedTables, List<Map<String, Object>> relationEvidence,
			int limit) {
		List<String> reasons = new ArrayList<>();
		reasons.add("本轮选择执行 SQL，是因为回答需要结构化结果来支撑结论。");
		if (!usedTables.isEmpty()) {
			reasons.add("实际命中的表有：%s。".formatted(String.join("、", usedTables)));
		}
		if (usedTables.size() > 1) {
			reasons.add("由于问题涉及多张表，系统需要联合查询后再生成答案。");
		}
		if (!relationEvidence.isEmpty()) {
			reasons.add("多表关联优先依据已配置的物理外键或逻辑关系，而不是临时猜测关联条件。");
		}
		reasons.add("查询结果默认限制为最多 %d 行，避免一次返回过多数据。".formatted(limit));
		return List.copyOf(reasons);
	}

	private List<String> appendPermissionDecisionReasons(List<String> reasons,
			PermissionRewriteResult permissionRewrite) {
		List<String> merged = new ArrayList<>(reasons);
		if (permissionRewrite.denied()) {
			merged.add("SQL 执行前已按 xx-cloud 数据权限口子判定当前无数据权限，未执行查询。");
		}
		else if (permissionRewrite.rewritten()) {
			merged.add("SQL 执行前已按当前登录用户的数据权限追加过滤条件。");
		}
		else {
			merged.add("SQL 执行前已检查数据权限配置，本次未产生额外过滤条件。");
		}
		if (!permissionRewrite.skippedTables().isEmpty()) {
			merged.add("未配置权限映射的表按 ALLOW_ON_MISSING 放行：%s。"
				.formatted(String.join("、", permissionRewrite.skippedTables())));
		}
		return List.copyOf(merged);
	}

	private List<String> buildPreviewResultScopeDetails(String tableName, ResultSetBO resultSet, int limit) {
		List<String> details = new ArrayList<>();
		details.add("当前展示的是表“%s”的预览结果，不代表完整数据集。".formatted(tableName));
		details.add("本次共返回 %d 行、%d 个字段。".formatted(resultSet.getData() == null ? 0 : resultSet.getData().size(),
				resultSet.getColumn() == null ? 0 : resultSet.getColumn().size()));
		details.add("预览结果仅包含当前 Agent 可见字段。");
		details.add("预览查询使用了 limit=%d。".formatted(limit));
		details.add("禁止根据未返回字段推断隐藏信息。");
		return List.copyOf(details);
	}

	private List<String> buildSearchResultScopeDetails(ResultSetBO resultSet, int requestedRows, int limit,
			ResultCoverageStatus coverageStatus, Set<String> allowedHeaders) {
		List<String> details = new ArrayList<>();
		int rowCount = resultSet.getData() == null ? 0 : resultSet.getData().size();
		int columnCount = resultSet.getColumn() == null ? 0 : resultSet.getColumn().size();
		details.add("当前结果共返回 %d 行、%d 个字段。".formatted(rowCount, columnCount));
		details.add("查询结果最多展示 %d 行。".formatted(limit));
		if (requestedRows > limit) {
			details.add("用户请求 %d 行，受平台单次查询上限限制，本次最多覆盖前 %d 行。".formatted(requestedRows, limit));
		}
		if (coverageStatus == ResultCoverageStatus.SOURCE_SHORT) {
			details.add("实际可返回结果少于请求数量，本次按真实数量展示。");
		}
		else if (coverageStatus == ResultCoverageStatus.LIMIT_REACHED_UNKNOWN) {
			details.add("结果已达到当前限制，但无法可靠探测限制之后是否仍有数据。");
		}
		if (allowedHeaders != null && !allowedHeaders.isEmpty()) {
			details.add("最终仅保留 SQL select 列表中显式声明的返回字段。");
			details.add("允许返回的字段有：%s。".formatted(String.join("、", allowedHeaders)));
		}
		else {
			details.add("当前 SQL 未触发额外的结果列过滤。");
		}
		details.add("所有结果仍受字段可见性约束限制。");
		details.add("禁止根据未返回字段推断隐藏信息。");
		return List.copyOf(details);
	}

	private List<String> appendPermissionScopeDetails(List<String> details, PermissionRewriteResult permissionRewrite) {
		List<String> merged = new ArrayList<>(details);
		if (permissionRewrite.denied() && !permissionRewrite.appliedTables().isEmpty()) {
			merged.add("已拒绝访问的数据权限表：%s。".formatted(String.join("、", permissionRewrite.appliedTables())));
		}
		else if (!permissionRewrite.appliedTables().isEmpty()) {
			merged.add("已应用数据权限过滤的表：%s。".formatted(String.join("、", permissionRewrite.appliedTables())));
		}
		if (!permissionRewrite.messages().isEmpty()) {
			merged.addAll(permissionRewrite.messages());
		}
		return List.copyOf(merged);
	}

	/**
	 * 只有 PREVIEW_ROWS 与 SEARCH 两条“返回业务数据行”的路径会走到这里，表/列/关系等元数据出口走的是
	 * {@code toTableEntry} / {@code toColumnEntry} / {@code toRelationEntry}，因此单元格上限直接落在这里，
	 * 既覆盖两条数据路径，也不会误伤元数据返回。
	 */
	private List<Map<String, Object>> toRows(ResultSetBO resultSet) {
		return resultSet.getData().stream().map(row -> {
			Map<String, Object> mappedRow = new LinkedHashMap<>();
			row.forEach((column, value) -> mappedRow.put(column, truncateCellValue(value)));
			return mappedRow;
		}).toList();
	}

	private String truncateCellValue(String value) {
		if (value == null || value.length() <= MAX_RESULT_CELL_CHARS) {
			return value;
		}
		return value.substring(0, MAX_RESULT_CELL_CHARS - CELL_TRUNCATION_SUFFIX.length()) + CELL_TRUNCATION_SUFFIX;
	}

	private ResultSetBO filterResultSet(ResultSetBO resultSet, SqlGuardedQuery guardedQuery) {
		Set<String> allowedHeaders = guardedQuery.allowedResultHeaders();
		if (allowedHeaders == null || allowedHeaders.isEmpty()) {
			return resultSet;
		}
		List<String> originalColumns = Optional.ofNullable(resultSet.getColumn()).orElse(List.of());
		List<Integer> keptIndexes = new ArrayList<>();
		List<String> keptColumns = new ArrayList<>();
		for (int index = 0; index < originalColumns.size(); index++) {
			String columnName = originalColumns.get(index);
			if (allowedHeaders.contains(normalizeColumnName(columnName))) {
				keptIndexes.add(index);
				keptColumns.add(columnName);
			}
		}
		if (keptIndexes.size() == originalColumns.size()) {
			return resultSet;
		}
		List<Map<String, String>> filteredRows = Optional.ofNullable(resultSet.getData())
			.orElse(List.of())
			.stream()
			.map(row -> {
				Map<String, String> filteredRow = new LinkedHashMap<>();
				for (Integer keptIndex : keptIndexes) {
					String columnName = originalColumns.get(keptIndex);
					filteredRow.put(columnName, row.get(columnName));
				}
				return filteredRow;
			})
			.toList();
		resultSet.setColumn(keptColumns);
		resultSet.setData(filteredRows);
		return resultSet;
	}

	private boolean containsQuery(Map<String, Object> table, String query) {
		return table.values()
			.stream()
			.filter(String.class::isInstance)
			.map(String.class::cast)
			.map(value -> value.toLowerCase(Locale.ROOT))
			.anyMatch(value -> value.contains(query));
	}

	private int scoreCatalogTable(Map<String, Object> table, String query, List<String> tokens) {
		if (table == null) {
			return 0;
		}
		String normalizedQuery = QueryTokenUtil.normalize(query);
		int score = 0;
		if (StringUtils.isNotBlank(normalizedQuery) && containsQuery(table, normalizedQuery)) {
			score += 50;
		}
		Object name = table.get("name");
		String tableName = name == null ? "" : String.valueOf(name).toLowerCase(Locale.ROOT);
		for (String token : tokens) {
			if (!StringUtils.isNotBlank(token) || token.length() < 2) {
				continue;
			}
			if (tableName.contains(token)) {
				score += token.length() >= 3 ? 12 : 8;
			}
			for (Object value : table.values()) {
				if (!(value instanceof String text) || !StringUtils.isNotBlank(text)) {
					continue;
				}
				if (text.toLowerCase(Locale.ROOT).contains(token)) {
					score += 3;
					break;
				}
			}
		}
		return score;
	}

	private List<ColumnInfoBO> applyVisibleColumnFilter(ExplorerContext context, String tableName,
			List<ColumnInfoBO> columns) {
		return Optional.ofNullable(columns)
			.orElse(List.of())
			.stream()
			.filter(column -> isColumnVisible(context, tableName, column.getName()))
			.toList();
	}

	private String resolvePreviewColumnSelection(ExplorerContext context, String tableName) {
		List<String> visibleColumns = context.visibleColumnsByTable().get(normalizeTableName(tableName));
		if (visibleColumns == null) {
			return "*";
		}
		if (visibleColumns.isEmpty()) {
			throw new IllegalArgumentException("表 '%s' 当前没有可预览字段，请先调整字段级可见性配置".formatted(tableName));
		}
		return visibleColumns.stream()
			.map(columnName -> SqlUtil.quoteIdentifier(context.dbConfig().getDialectType(), columnName))
			.collect(Collectors.joining(", "));
	}

	private boolean isRelationVisible(ExplorerContext context, UnifiedRelation relation) {
		return isColumnVisible(context, relation.sourceTable(), relation.sourceColumn())
				&& isColumnVisible(context, relation.targetTable(), relation.targetColumn());
	}

	private boolean isColumnVisible(ExplorerContext context, String tableName, String columnName) {
		String normalizedTableName = normalizeTableName(tableName);
		if (!context.columnRestrictedTables().contains(normalizedTableName)) {
			return true;
		}
		Set<String> visibleColumns = context.visibleColumnNameSetByTable().get(normalizedTableName);
		return visibleColumns != null && visibleColumns.contains(normalizeColumnName(columnName));
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
				.collect(Collectors.toCollection(LinkedHashSet::new))
				.stream()
				.toList();
			if (!sanitizedColumns.isEmpty()) {
				visibleColumnsByTable.put(normalizeTableName(resolvedTableName.get()), List.copyOf(sanitizedColumns));
			}
		});
		return visibleColumnsByTable;
	}

	private Map<String, List<String>> toImmutableListIndex(Map<String, List<String>> source) {
		Map<String, List<String>> immutableIndex = new LinkedHashMap<>();
		source.forEach((key, value) -> immutableIndex.put(key, List.copyOf(value)));
		return Map.copyOf(immutableIndex);
	}

	private Map<String, Set<String>> toImmutableSetIndex(Map<String, Set<String>> source) {
		Map<String, Set<String>> immutableIndex = new LinkedHashMap<>();
		source.forEach((key, value) -> immutableIndex.put(key, Set.copyOf(value)));
		return Map.copyOf(immutableIndex);
	}

	private Map<String, List<String>> indexTablesByIdentity(List<String> tableNames) {
		return indexTables(tableNames, false);
	}

	private Map<String, List<String>> indexTablesByLeafName(List<String> tableNames) {
		return indexTables(tableNames, true);
	}

	private Map<String, List<String>> indexTables(List<String> tableNames, boolean leafOnly) {
		Map<String, LinkedHashSet<String>> index = new LinkedHashMap<>();
		for (String tableName : Optional.ofNullable(tableNames).orElse(List.of())) {
			if (StringUtils.isBlank(tableName)) {
				continue;
			}
			String normalizedKey = leafOnly ? normalizeTableLeafName(tableName) : normalizeTableName(tableName);
			index.computeIfAbsent(normalizedKey, key -> new LinkedHashSet<>()).add(tableName);
		}
		Map<String, List<String>> immutableIndex = new LinkedHashMap<>();
		index.forEach((key, value) -> immutableIndex.put(key, List.copyOf(value)));
		return Map.copyOf(immutableIndex);
	}

	private String resolveVisibleTableName(ExplorerContext context, String tableName) {
		return findVisibleTableName(context.visibleTablesByName(), context.visibleTablesByLeafName(), tableName, false)
			.orElseThrow(() -> buildInvisibleTableException(context, tableName));
	}

	private Optional<String> findVisibleTableName(Map<String, List<String>> visibleTablesByName,
			Map<String, List<String>> visibleTablesByLeafName, String tableName, boolean allowQualifiedFallback) {
		String normalizedTableName = normalizeTableName(tableName);
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

	private String extractTableReference(Table table) {
		String fullyQualifiedName = table == null ? StringUtils.EMPTY : table.getFullyQualifiedName();
		if (StringUtils.isNotBlank(fullyQualifiedName)) {
			return fullyQualifiedName;
		}
		return table == null ? StringUtils.EMPTY : table.getName();
	}

	private IllegalArgumentException buildInvisibleTableException(ExplorerContext context, String tableName) {
		return new IllegalArgumentException(
				"表 '%s' 对当前 Agent 不可见。当前可见表：%s".formatted(tableName, String.join(", ", context.visibleTables())));
	}

	private boolean isSelectedTable(ExplorerContext context, String tableName) {
		if (context.explicitSelectedTables().isEmpty()) {
			return true;
		}
		String normalizedTableName = normalizeTableName(tableName);
		return context.explicitSelectedTables()
			.stream()
			.map(this::normalizeTableName)
			.anyMatch(normalizedTableName::equals);
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
		if (normalized.contains(".")) {
			return normalized.substring(normalized.lastIndexOf('.') + 1);
		}
		return normalized;
	}

	private String normalizeColumnName(String columnName) {
		return normalizeTableLeafName(columnName);
	}

	private DatasourceExplorerResult.DatasourceExplorerResultBuilder baseResult(ExplorerContext context,
			DatasourceExplorerAction action, String summary) {
		return DatasourceExplorerResult.builder()
			.datasource(context.datasource().getName())
			.action(action.name())
			.summary(summary);
	}

	private DatasourceExplorerResult permissionDeniedResult(ExplorerContext context, DatasourceExplorerAction action,
			PermissionRewriteResult permissionRewrite, List<String> usedTables, @Nullable AgentRequest graphRequest) {
		String message = StringUtils.defaultIfBlank(permissionRewrite.denialMessage(), "当前无数据权限");
		return capture(baseResult(context, action, message)
			.columns(List.of())
			.rows(List.of())
			.sql(permissionRewrite.sql())
			.usedTables(usedTables)
			.usedColumns(List.of())
			.resultScope(message)
			.resultScopeDetails(appendPermissionScopeDetails(List.of("当前用户无权查看本次查询命中的数据范围。"), permissionRewrite))
			.decisionReason("SQL 执行前已按 xx-cloud 数据权限口子判定当前无数据权限，未执行查询。")
			.toolDecisionReasons(appendPermissionDecisionReasons(List.of("本轮 SQL 已生成，但数据权限判定未通过。"), permissionRewrite))
			.searchReady(false)
			.build(), graphRequest);
	}

	private DatasourceExplorerResult capture(DatasourceExplorerResult result, @Nullable AgentRequest graphRequest) {
		if (graphRequest != null) {
			answerTraceExplainStore.recordDatasourceResult(graphRequest, result);
		}
		else {
			answerTraceExplainStore.recordDatasourceResult(result);
		}
		return result;
	}

	record ExplorerContext(SkillDatasource skillDatasource, Datasource datasource, DbConfigBO dbConfig,
			Accessor accessor, List<String> visibleTables, Set<String> visibleTableNameSet,
			Map<String, List<String>> visibleTablesByName, Map<String, List<String>> visibleTablesByLeafName,
			List<String> explicitSelectedTables, Map<String, List<String>> visibleColumnsByTable,
			Map<String, Set<String>> visibleColumnNameSetByTable, Set<String> columnRestrictedTables,
			List<UnifiedRelation> unifiedRelations, Map<String, List<UnifiedRelation>> relationsByTable) {
	}

	private record UnifiedRelation(String sourceTable, String sourceColumn, String targetTable, String targetColumn,
			String relationType, String description, String sourceType, boolean virtual, boolean declaredInDatabase) {
	}

	private record SqlGuardedQuery(String sql, Set<String> referencedTables, Set<String> allowedResultHeaders,
			Integer outerLimit) {
	}

	private record SqlValidationResult(Set<String> referencedTables, Set<String> allowedResultHeaders) {
	}

	private record SourceBinding(String referenceName, String tableName) {

		boolean isBaseTable() {
			return StringUtils.isNotBlank(tableName);
		}
	}

	private record SelectScope(Map<String, SourceBinding> sourcesByReference, List<SourceBinding> baseSources) {

		SourceBinding resolve(String reference) {
			return sourcesByReference.get(reference);
		}
	}

	private final class SqlColumnAccessValidator {

		private final ExplorerContext context;

		private SqlColumnAccessValidator(ExplorerContext context) {
			this.context = context;
		}

		private SqlValidationResult validate(Select select) {
			Set<String> referencedTables = new LinkedHashSet<>();
			validateSelect(select, new LinkedHashSet<>(), referencedTables);
			return new SqlValidationResult(Set.copyOf(referencedTables), extractAllowedResultHeaders(select));
		}

		private void validateSelect(Select select, Set<String> cteNames, Set<String> referencedTables) {
			Set<String> nextCteNames = new LinkedHashSet<>(cteNames);
			if (select.getWithItemsList() != null) {
				for (WithItem withItem : select.getWithItemsList()) {
					if (withItem.getAlias() != null && StringUtils.isNotBlank(withItem.getAlias().getName())) {
						nextCteNames.add(normalizeTableName(withItem.getAlias().getName()));
					}
				}
				for (WithItem withItem : select.getWithItemsList()) {
					validateSelect(withItem.getSelect(), nextCteNames, referencedTables);
				}
			}
			if (select instanceof PlainSelect plainSelect) {
				validatePlainSelect(plainSelect, nextCteNames, referencedTables);
				return;
			}
			if (select instanceof SetOperationList setOperationList) {
				for (Select childSelect : Optional.ofNullable(setOperationList.getSelects()).orElse(List.of())) {
					validateSelect(childSelect, nextCteNames, referencedTables);
				}
				return;
			}
			if (select instanceof ParenthesedSelect parenthesedSelect) {
				validateSelect(parenthesedSelect.getSelect(), nextCteNames, referencedTables);
				return;
			}
			throw new IllegalArgumentException("当前 SQL 包含暂不支持的查询结构: " + select.getClass().getSimpleName());
		}

		private void validatePlainSelect(PlainSelect plainSelect, Set<String> cteNames, Set<String> referencedTables) {
			SelectScope scope = buildScope(plainSelect, cteNames, referencedTables);
			Set<String> selectAliases = extractSelectAliases(plainSelect.getSelectItems());
			for (SelectItem<?> selectItem : Optional.ofNullable(plainSelect.getSelectItems()).orElse(List.of())) {
				validateExpression(selectItem.getExpression(), scope, cteNames, "SELECT", Set.of());
			}
			validateExpression(plainSelect.getWhere(), scope, cteNames, "WHERE", Set.of());
			validateExpression(plainSelect.getHaving(), scope, cteNames, "HAVING", selectAliases);
			validateExpression(plainSelect.getQualify(), scope, cteNames, "QUALIFY", selectAliases);
			if (plainSelect.getGroupBy() != null && plainSelect.getGroupBy().getGroupByExpressions() != null) {
				for (Object groupByExpression : Optional
					.ofNullable(plainSelect.getGroupBy().getGroupByExpressions().getExpressions())
					.orElse(List.of())) {
					if (groupByExpression instanceof Expression expression) {
						validateExpression(expression, scope, cteNames, "GROUP BY", selectAliases);
					}
				}
			}
			for (OrderByElement orderByElement : Optional.ofNullable(plainSelect.getOrderByElements())
				.orElse(List.of())) {
				validateExpression(orderByElement.getExpression(), scope, cteNames, "ORDER BY", selectAliases);
			}
		}

		private SelectScope buildScope(PlainSelect plainSelect, Set<String> cteNames, Set<String> referencedTables) {
			Map<String, SourceBinding> sourcesByReference = new LinkedHashMap<>();
			List<SourceBinding> baseSources = new ArrayList<>();
			addFromItemSources(plainSelect.getFromItem(), cteNames, referencedTables, sourcesByReference, baseSources);
			for (Join join : Optional.ofNullable(plainSelect.getJoins()).orElse(List.of())) {
				addFromItemSources(join.getRightItem(), cteNames, referencedTables, sourcesByReference, baseSources);
				if (join.getUsingColumns() != null && !join.getUsingColumns().isEmpty()) {
					throw new IllegalArgumentException(
							"当前 SQL 使用了 JOIN ... USING 语法。字段级可见性校验要求改写成显式 ON alias.column = alias.column");
				}
				for (Expression onExpression : Optional.ofNullable(join.getOnExpressions()).orElse(List.of())) {
					validateExpression(onExpression,
							new SelectScope(Map.copyOf(sourcesByReference), List.copyOf(baseSources)), cteNames,
							"JOIN ON", Set.of());
				}
			}
			return new SelectScope(Map.copyOf(sourcesByReference), List.copyOf(baseSources));
		}

		private void addFromItemSources(FromItem fromItem, Set<String> cteNames, Set<String> referencedTables,
				Map<String, SourceBinding> sourcesByReference, List<SourceBinding> baseSources) {
			if (fromItem == null) {
				return;
			}
			if (fromItem instanceof Table table) {
				String normalizedTableReference = normalizeTableName(extractTableReference(table));
				String aliasName = table.getAlias() == null ? null : normalizeTableName(table.getAlias().getName());
				if (cteNames.contains(normalizedTableReference)) {
					registerSource(sourcesByReference,
							new SourceBinding(StringUtils.defaultIfBlank(aliasName, normalizedTableReference), null));
					return;
				}
				String resolvedTableName = resolveVisibleTableName(context, extractTableReference(table));
				referencedTables.add(normalizeTableName(resolvedTableName));
				SourceBinding sourceBinding = new SourceBinding(
						StringUtils.defaultIfBlank(aliasName, normalizeTableName(resolvedTableName)),
						resolvedTableName);
				registerSource(sourcesByReference, sourceBinding);
				registerSource(sourcesByReference,
						new SourceBinding(normalizeTableName(resolvedTableName), resolvedTableName));
				String tableLeafName = normalizeTableLeafName(resolvedTableName);
				if (!tableLeafName.equals(normalizeTableName(resolvedTableName))) {
					registerSource(sourcesByReference, new SourceBinding(tableLeafName, resolvedTableName));
				}
				baseSources.add(sourceBinding);
				return;
			}
			if (fromItem instanceof LateralSubSelect lateralSubSelect) {
				validateSelect(lateralSubSelect.getSelect(), cteNames, referencedTables);
				registerDerivedSource(lateralSubSelect, sourcesByReference);
				return;
			}
			if (fromItem instanceof ParenthesedSelect parenthesedSelect) {
				validateSelect(parenthesedSelect.getSelect(), cteNames, referencedTables);
				registerDerivedSource(parenthesedSelect, sourcesByReference);
				return;
			}
			if (fromItem instanceof ParenthesedFromItem parenthesedFromItem) {
				Map<String, SourceBinding> nestedSources = new LinkedHashMap<>();
				List<SourceBinding> nestedBaseSources = new ArrayList<>();
				addFromItemSources(parenthesedFromItem.getFromItem(), cteNames, referencedTables, nestedSources,
						nestedBaseSources);
				for (Join join : Optional.ofNullable(parenthesedFromItem.getJoins()).orElse(List.of())) {
					addFromItemSources(join.getRightItem(), cteNames, referencedTables, nestedSources,
							nestedBaseSources);
					if (join.getUsingColumns() != null && !join.getUsingColumns().isEmpty()) {
						throw new IllegalArgumentException(
								"当前 SQL 使用了 JOIN ... USING 语法。字段级可见性校验要求改写成显式 ON alias.column = alias.column");
					}
					for (Expression onExpression : Optional.ofNullable(join.getOnExpressions()).orElse(List.of())) {
						validateExpression(onExpression,
								new SelectScope(Map.copyOf(nestedSources), List.copyOf(nestedBaseSources)), cteNames,
								"JOIN ON", Set.of());
					}
				}
				if (parenthesedFromItem.getAlias() != null
						&& StringUtils.isNotBlank(parenthesedFromItem.getAlias().getName())) {
					registerDerivedSource(parenthesedFromItem, sourcesByReference);
				}
				else {
					nestedSources.values().forEach(binding -> registerSource(sourcesByReference, binding));
					baseSources.addAll(nestedBaseSources);
				}
				return;
			}
			if (fromItem instanceof TableFunction) {
				// 无条件拒绝，不能只在确定性路径拒绝：表函数的实参不会走到表达式访问器，
				// 函数名黑名单根本看不到 FROM pg_ls_dir('/')、FROM dblink(...) 这类载荷。
				throw new IllegalArgumentException("只读查询不允许使用表函数");
			}
			throw new IllegalArgumentException("当前 SQL 包含暂不支持的 FROM 结构: " + fromItem.getClass().getSimpleName());
		}

		private void registerDerivedSource(FromItem fromItem, Map<String, SourceBinding> sourcesByReference) {
			if (fromItem.getAlias() == null || StringUtils.isBlank(fromItem.getAlias().getName())) {
				return;
			}
			registerSource(sourcesByReference,
					new SourceBinding(normalizeTableName(fromItem.getAlias().getName()), null));
		}

		private void registerSource(Map<String, SourceBinding> sourcesByReference, SourceBinding sourceBinding) {
			SourceBinding existingBinding = sourcesByReference.get(sourceBinding.referenceName());
			if (existingBinding != null && !Objects.equals(existingBinding.tableName(), sourceBinding.tableName())) {
				throw new IllegalArgumentException(
						"Table reference '%s' is ambiguous in current SQL scope; please use aliases"
							.formatted(sourceBinding.referenceName()));
			}
			sourcesByReference.put(sourceBinding.referenceName(), sourceBinding);
		}

		private void validateExpression(Expression expression, SelectScope scope, Set<String> cteNames, String clause,
				Set<String> allowedAliases) {
			if (expression == null) {
				return;
			}
			expression.accept(new ExpressionVisitorAdapter() {
				@Override
				public void visit(Function function) {
					requireAllowedFunction(function, clause);
					if (isCountStar(function)) {
						return;
					}
					if (hasStarArgument(function)) {
						throw new IllegalArgumentException("子句 %s 中检测到 %s(*)。字段级可见性校验禁止使用除 COUNT(*) 外的星号聚合，请显式列出字段"
							.formatted(clause, StringUtils.defaultIfBlank(function.getName(), "function")));
					}
					super.visit(function);
				}

				@Override
				public void visit(AllColumns allColumns) {
					throw new IllegalArgumentException("子句 %s 中检测到 SELECT *。请改成显式列名，避免越权读取隐藏字段".formatted(clause));
				}

				@Override
				public void visit(AllTableColumns allTableColumns) {
					throw new IllegalArgumentException(
							"子句 %s 中检测到 %s.*。请改成显式列名，避免越权读取隐藏字段".formatted(clause, allTableColumns.getTable()));
				}

				@Override
				public void visit(Column column) {
					validateColumnReference(scope, clause, column, allowedAliases);
				}

				@Override
				public void visit(ParenthesedSelect parenthesedSelect) {
					validateSelect(parenthesedSelect.getSelect(), cteNames, new LinkedHashSet<>());
				}

				@Override
				public void visit(Select select) {
					validateSelect(select, cteNames, new LinkedHashSet<>());
				}
			});
		}

		private boolean isCountStar(Function function) {
			return "count".equalsIgnoreCase(function.getName())
					&& (hasOnlyStarArgument(function) || (function.isAllColumns()
							&& (function.getParameters() == null || function.getParameters().isEmpty())));
		}

		private boolean hasOnlyStarArgument(Function function) {
			return function.getParameters() != null && function.getParameters().size() == 1
					&& function.getParameters().get(0) instanceof AllColumns;
		}

		private boolean hasStarArgument(Function function) {
			return function.isAllColumns() || (function.getParameters() != null
					&& function.getParameters().stream().anyMatch(AllColumns.class::isInstance));
		}

		private void requireAllowedFunction(Function function, String clause) {
			String functionName = StringUtils.trimToEmpty(function.getName());
			if (functionName.isEmpty()) {
				return;
			}
			// Oracle / 达梦会写成 package.function（如 DBMS_XMLGEN.getxml），因此全名和末段都要过一遍黑名单：
			// 全名用于命中 dbms_ / sys. 这类命名空间前缀，末段用于命中被 schema 限定后的裸函数名。
			if (isDeniedFunctionName(functionName.toLowerCase(Locale.ROOT))
					|| isDeniedFunctionName(normalizeColumnName(functionName))) {
				throw new IllegalArgumentException(
						"子句 %s 中检测到高危函数 '%s'。只读查询不允许调用文件、命令、网络、锁等待或可绕过表白名单的函数"
							.formatted(clause, functionName));
			}
		}

		private boolean isDeniedFunctionName(String normalizedFunctionName) {
			if (StringUtils.isBlank(normalizedFunctionName)) {
				return false;
			}
			return DENIED_FUNCTION_NAMES.contains(normalizedFunctionName)
					|| DENIED_FUNCTION_PREFIXES.stream().anyMatch(normalizedFunctionName::startsWith);
		}

		private void validateColumnReference(SelectScope scope, String clause, Column column,
				Set<String> allowedAliases) {
			String normalizedColumnName = normalizeColumnName(column.getColumnName());
			if (StringUtils.isBlank(normalizedColumnName)) {
				throw new IllegalArgumentException("子句 %s 中存在无法识别的字段引用: %s".formatted(clause, column));
			}
			Table table = column.getTable();
			String tableReference = table == null ? StringUtils.EMPTY
					: normalizeTableName(extractTableReference(table));
			if (StringUtils.isBlank(tableReference)) {
				if (isBooleanLiteralColumn(normalizedColumnName)) {
					return;
				}
				if (allowedAliases.contains(normalizedColumnName)) {
					return;
				}
				if (scope.baseSources().isEmpty()) {
					return;
				}
				if (scope.baseSources().size() > 1) {
					throw new IllegalArgumentException("子句 %s 中的字段 '%s' 没有带表前缀。当前 SQL 涉及多张基础表，无法安全判断字段归属，请改成 alias.%s"
						.formatted(clause, column.getColumnName(), column.getColumnName()));
				}
				SourceBinding sourceBinding = scope.baseSources().get(0);
				assertVisibleColumn(sourceBinding.tableName(), column.getColumnName(), clause, column.toString());
				return;
			}
			SourceBinding sourceBinding = scope.resolve(tableReference);
			if (sourceBinding == null) {
				throw new IllegalArgumentException(
						"子句 %s 中引用了未知表/别名 '%s'。请检查 SQL 中的表别名是否和 FROM/JOIN 定义一致".formatted(clause, table.getName()));
			}
			if (!sourceBinding.isBaseTable()) {
				return;
			}
			assertVisibleColumn(sourceBinding.tableName(), column.getColumnName(), clause, column.toString());
		}

		private boolean isBooleanLiteralColumn(String normalizedColumnName) {
			return "true".equals(normalizedColumnName) || "false".equals(normalizedColumnName);
		}

		private void assertVisibleColumn(String tableName, String columnName, String clause, String expression) {
			String normalizedTableName = normalizeTableName(tableName);
			if (!context.columnRestrictedTables().contains(normalizedTableName)) {
				return;
			}
			Set<String> visibleColumns = context.visibleColumnNameSetByTable().get(normalizedTableName);
			if (visibleColumns == null || !visibleColumns.contains(normalizeColumnName(columnName))) {
				String visibleColumnSummary = Optional
					.ofNullable(context.visibleColumnsByTable().get(normalizedTableName))
					.orElse(List.of())
					.stream()
					.collect(Collectors.joining(", "));
				throw new IllegalArgumentException("子句 %s 中的字段引用 '%s' 不被允许。表 '%s' 已启用字段级可见性控制，仅允许字段: [%s]"
					.formatted(clause, expression, tableName, visibleColumnSummary));
			}
		}

		private Set<String> extractSelectAliases(List<SelectItem<?>> selectItems) {
			return Optional.ofNullable(selectItems)
				.orElse(List.of())
				.stream()
				.map(SelectItem::getAlias)
				.filter(alias -> alias != null && StringUtils.isNotBlank(alias.getName()))
				.map(alias -> normalizeColumnName(alias.getName()))
				.collect(Collectors.toCollection(LinkedHashSet::new));
		}

		private Set<String> extractAllowedResultHeaders(Select select) {
			if (!(select instanceof PlainSelect plainSelect)) {
				return null;
			}
			Set<String> headers = new LinkedHashSet<>();
			for (SelectItem<?> selectItem : Optional.ofNullable(plainSelect.getSelectItems()).orElse(List.of())) {
				Expression expression = selectItem.getExpression();
				if (expression instanceof AllColumns || expression instanceof AllTableColumns) {
					return null;
				}
				if (selectItem.getAlias() != null && StringUtils.isNotBlank(selectItem.getAlias().getName())) {
					headers.add(normalizeColumnName(selectItem.getAlias().getName()));
					continue;
				}
				if (expression instanceof Column column) {
					headers.add(normalizeColumnName(column.getColumnName()));
					continue;
				}
				return null;
			}
			return headers;
		}

	}

}
