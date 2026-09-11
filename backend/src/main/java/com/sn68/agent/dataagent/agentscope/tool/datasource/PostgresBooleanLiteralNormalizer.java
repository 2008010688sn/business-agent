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

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.LateralSubSelect;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import org.apache.commons.lang3.StringUtils;

/**
 * PostgreSQL 不允许 {@code boolean = integer}。模型常把逻辑删除写成 {@code deleted = 0}，
 * 静态 SQL 守卫不查列类型，会原样放到 JDBC。这里只改写布尔列与 0/1 的等值比较，不放宽表白名单。
 */
public final class PostgresBooleanLiteralNormalizer {

	public static final String MODEL_HINT = "PostgreSQL 布尔列不能与 0/1 比较，请改用 true/false 后重试，不要重复同一条 SQL。";

	private static final Pattern INTEGER_EQUALITY = Pattern.compile("(?:=|<>|!=)\\s*[01]\\b");

	private static final int SQL_LOG_LIMIT = 400;

	private PostgresBooleanLiteralNormalizer() {
	}

	public static boolean isPostgresFamily(String dialectType) {
		if (!StringUtils.isNotBlank(dialectType)) {
			return false;
		}
		String normalized = dialectType.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
		return normalized.contains("postgres") || normalized.contains("hologres") || normalized.contains("gauss")
				|| normalized.contains("adbpg") || normalized.contains("adgpg");
	}

	public static boolean mightContainIntegerBooleanLiteral(String sql) {
		return StringUtils.isNotBlank(sql) && INTEGER_EQUALITY.matcher(sql).find();
	}

	public static boolean isBooleanIntegerMismatch(Throwable error) {
		return isBooleanIntegerMismatch(flatten(error));
	}

	public static boolean isBooleanIntegerMismatch(String text) {
		if (!StringUtils.isNotBlank(text)) {
			return false;
		}
		String normalized = text.toLowerCase(Locale.ROOT);
		return normalized.contains("boolean = integer")
				|| (normalized.contains("operator does not exist") && normalized.contains("boolean")
						&& normalized.contains("integer"))
				|| (normalized.contains("操作符不存在") && normalized.contains("boolean")
						&& normalized.contains("integer"));
	}

	public static String truncateSql(String sql) {
		if (sql == null) {
			return null;
		}
		String compact = sql.replaceAll("\\s+", " ").trim();
		return compact.length() <= SQL_LOG_LIMIT ? compact : compact.substring(0, SQL_LOG_LIMIT) + "...";
	}

	/**
	 * @param typesByTableColumn keys are {@code table.column} and, when unambiguous,
	 * {@code column}; values are connector type names such as {@code boolean}
	 */
	public static String rewrite(String sql, Map<String, String> typesByTableColumn) {
		if (!mightContainIntegerBooleanLiteral(sql)) {
			return sql;
		}
		Statement statement;
		try {
			statement = CCJSqlParserUtil.parse(sql);
		}
		catch (Exception ex) {
			return sql;
		}
		if (!(statement instanceof Select select)) {
			return sql;
		}
		AtomicBoolean changed = new AtomicBoolean(false);
		rewriteSelect(select, typesByTableColumn == null ? Map.of() : typesByTableColumn, new Scope(), changed);
		return changed.get() ? statement.toString() : sql;
	}

	private static void rewriteSelect(Select select, Map<String, String> types, Scope scope, AtomicBoolean changed) {
		if (select == null) {
			return;
		}
		if (select.getWithItemsList() != null) {
			for (WithItem withItem : select.getWithItemsList()) {
				rewriteSelect(withItem.getSelect(), types, new Scope(), changed);
			}
		}
		if (select instanceof PlainSelect plainSelect) {
			rewritePlainSelect(plainSelect, types, scope, changed);
			return;
		}
		if (select instanceof SetOperationList setOperationList) {
			for (Select child : Optional.ofNullable(setOperationList.getSelects()).orElse(List.of())) {
				rewriteSelect(child, types, new Scope(), changed);
			}
			return;
		}
		if (select instanceof ParenthesedSelect parenthesedSelect) {
			rewriteSelect(parenthesedSelect.getSelect(), types, scope, changed);
		}
	}

	private static void rewritePlainSelect(PlainSelect plainSelect, Map<String, String> types, Scope scope,
			AtomicBoolean changed) {
		rewriteFromItem(plainSelect.getFromItem(), types, scope, changed);
		for (Join join : Optional.ofNullable(plainSelect.getJoins()).orElse(List.of())) {
			rewriteFromItem(join.getRightItem(), types, scope, changed);
			Collection<Expression> onExpressions = join.getOnExpressions();
			if (onExpressions != null) {
				for (Expression onExpression : onExpressions) {
					rewriteExpression(onExpression, types, scope, changed);
				}
			}
		}
		rewriteExpression(plainSelect.getWhere(), types, scope, changed);
		rewriteExpression(plainSelect.getHaving(), types, scope, changed);
	}

	private static void rewriteFromItem(FromItem fromItem, Map<String, String> types, Scope scope,
			AtomicBoolean changed) {
		if (fromItem == null) {
			return;
		}
		if (fromItem instanceof Table table) {
			String tableName = extractTableName(table);
			String alias = table.getAlias() == null ? tableName : table.getAlias().getName();
			scope.register(alias, tableName);
			scope.register(tableName, tableName);
			return;
		}
		if (fromItem instanceof ParenthesedSelect parenthesedSelect) {
			rewriteSelect(parenthesedSelect.getSelect(), types, new Scope(), changed);
			return;
		}
		if (fromItem instanceof LateralSubSelect lateralSubSelect) {
			rewriteSelect(lateralSubSelect.getSelect(), types, new Scope(), changed);
			return;
		}
		if (fromItem instanceof ParenthesedFromItem parenthesedFromItem) {
			rewriteFromItem(parenthesedFromItem.getFromItem(), types, scope, changed);
		}
	}

	private static void rewriteExpression(Expression expression, Map<String, String> types, Scope scope,
			AtomicBoolean changed) {
		if (expression == null) {
			return;
		}
		if (expression instanceof EqualsTo || expression instanceof NotEqualsTo) {
			coerceComparison((BinaryExpression) expression, types, scope, changed);
		}
		if (expression instanceof Parenthesis parenthesis) {
			rewriteExpression(parenthesis.getExpression(), types, scope, changed);
			return;
		}
		if (expression instanceof NotExpression notExpression) {
			rewriteExpression(notExpression.getExpression(), types, scope, changed);
			return;
		}
		if (expression instanceof BinaryExpression binaryExpression) {
			rewriteExpression(binaryExpression.getLeftExpression(), types, scope, changed);
			rewriteExpression(binaryExpression.getRightExpression(), types, scope, changed);
		}
	}

	private static void coerceComparison(BinaryExpression comparison, Map<String, String> types, Scope scope,
			AtomicBoolean changed) {
		Expression left = comparison.getLeftExpression();
		Expression right = comparison.getRightExpression();
		if (isZeroOrOne(right) && isBooleanColumn(left, types, scope)) {
			comparison.setRightExpression(booleanLiteral(isOne(right)));
			changed.set(true);
			return;
		}
		if (isZeroOrOne(left) && isBooleanColumn(right, types, scope)) {
			comparison.setLeftExpression(booleanLiteral(isOne(left)));
			changed.set(true);
		}
	}

	private static boolean isBooleanColumn(Expression expression, Map<String, String> types, Scope scope) {
		if (!(expression instanceof Column column)) {
			return false;
		}
		String columnName = normalize(column.getColumnName());
		String tableRef = column.getTable() == null ? null : extractTableName(column.getTable());
		String resolvedTable = tableRef == null ? null : scope.resolve(tableRef);
		String type = null;
		if (StringUtils.isNotBlank(resolvedTable)) {
			type = types.get(normalize(resolvedTable) + "." + columnName);
		}
		if (type == null && StringUtils.isNotBlank(tableRef)) {
			type = types.get(normalize(tableRef) + "." + columnName);
		}
		if (type == null) {
			type = lookupUnqualifiedType(types, columnName);
		}
		if (isBooleanType(type)) {
			return true;
		}
		if (StringUtils.isNotBlank(type)) {
			return false;
		}
		return "deleted".equals(columnName);
	}

	private static String lookupUnqualifiedType(Map<String, String> types, String columnName) {
		String direct = types.get(columnName);
		String suffix = "." + columnName;
		String found = null;
		for (Map.Entry<String, String> entry : types.entrySet()) {
			if (entry.getKey() == null || !entry.getKey().endsWith(suffix)) {
				continue;
			}
			if (found != null && !found.equalsIgnoreCase(entry.getValue())) {
				return null;
			}
			found = entry.getValue();
		}
		if (found != null) {
			return found;
		}
		return direct;
	}

	private static boolean isBooleanType(String type) {
		if (!StringUtils.isNotBlank(type)) {
			return false;
		}
		String normalized = type.toLowerCase(Locale.ROOT);
		int parenthesis = normalized.indexOf('(');
		if (parenthesis > 0) {
			normalized = normalized.substring(0, parenthesis);
		}
		return "bool".equals(normalized) || "boolean".equals(normalized);
	}

	private static boolean isZeroOrOne(Expression expression) {
		return expression instanceof LongValue longValue && (longValue.getValue() == 0 || longValue.getValue() == 1);
	}

	private static boolean isOne(Expression expression) {
		return expression instanceof LongValue longValue && longValue.getValue() == 1;
	}

	private static Expression booleanLiteral(boolean value) {
		try {
			return CCJSqlParserUtil.parseExpression(value ? "true" : "false");
		}
		catch (Exception ex) {
			throw new IllegalStateException("无法构造 PostgreSQL 布尔字面量", ex);
		}
	}

	private static String extractTableName(Table table) {
		if (table == null) {
			return null;
		}
		if (StringUtils.isNotBlank(table.getName())) {
			return table.getName();
		}
		return table.getFullyQualifiedName();
	}

	private static String normalize(String value) {
		if (!StringUtils.isNotBlank(value)) {
			return "";
		}
		String normalized = value.trim();
		normalized = StringUtils.removeStart(normalized, "`");
		normalized = StringUtils.removeEnd(normalized, "`");
		normalized = StringUtils.removeStart(normalized, "\"");
		normalized = StringUtils.removeEnd(normalized, "\"");
		normalized = StringUtils.removeStart(normalized, "[");
		normalized = StringUtils.removeEnd(normalized, "]");
		if (normalized.contains(".")) {
			normalized = normalized.substring(normalized.lastIndexOf('.') + 1);
		}
		return normalized.toLowerCase(Locale.ROOT);
	}

	private static String flatten(Throwable error) {
		if (error == null) {
			return null;
		}
		StringBuilder builder = new StringBuilder();
		Throwable current = error;
		int depth = 0;
		while (current != null && depth < 8) {
			if (StringUtils.isNotBlank(current.getMessage())) {
				if (builder.length() > 0) {
					builder.append(' ');
				}
				builder.append(current.getMessage());
			}
			current = current.getCause();
			depth++;
		}
		return builder.toString();
	}

	private static final class Scope {

		private final Map<String, String> aliasToTable = new LinkedHashMap<>();

		private void register(String aliasOrName, String tableName) {
			String normalizedTable = normalize(tableName);
			if (!StringUtils.isNotBlank(normalizedTable)) {
				return;
			}
			String normalizedAlias = normalize(aliasOrName);
			if (StringUtils.isNotBlank(normalizedAlias)) {
				aliasToTable.put(normalizedAlias, normalizedTable);
			}
			aliasToTable.put(normalizedTable, normalizedTable);
		}

		private String resolve(String tableRef) {
			String normalized = normalize(tableRef);
			return aliasToTable.getOrDefault(normalized, normalized);
		}

	}

}
