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
package com.sn68.agent.dataagent.util;

import com.sn68.agent.dataagent.enums.BizDataSourceTypeEnum;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;

/**
 * SQL utilities.
 */
@UtilityClass
public class SqlUtil {

	/**
	 * 规范化解析的时间上限。入参由模型生成，不能让一条畸形 SQL 把解析器拖住。
	 */
	private static final long CANONICALIZE_PARSE_TIMEOUT_MS = 2000L;

	/**
	 * 生成用于「是否同一次调用」比较的 SQL 文本。
	 *
	 * <p>先解析成语法树再重新序列化，抹掉空白、换行、关键字大小写这类表面差异；字面量原样保留，
	 * 因此 {@code LIMIT 10} 与 {@code LIMIT 11} 仍然是两次不同的调用——那是语义差异，
	 * 判成相同会误伤正常的翻页与下钻。解析失败时只折叠空白，不做任何改写。
	 */
	public static String canonicalizeForComparison(String sql) {
		if (sql == null || sql.isBlank()) {
			return "";
		}
		String compact = sql.trim().replaceAll("\\s+", " ");
		while (compact.endsWith(";")) {
			compact = compact.substring(0, compact.length() - 1).trim();
		}
		try {
			return CCJSqlParserUtil.parse(compact, parser -> parser.withTimeOut(CANONICALIZE_PARSE_TIMEOUT_MS))
				.toString();
		}
		catch (Exception ignored) {
			// 解析不了的入参未必是 SQL，退回折叠空白后的原文，行为与规范化之前一致。
			return compact;
		}
	}

	public static String buildSelectSql(String typeName, String tableName, String columnNames, int limit) {
		if (tableName == null || tableName.isEmpty()) {
			throw new IllegalArgumentException("Table name cannot be empty");
		}
		if (columnNames == null || columnNames.isEmpty()) {
			columnNames = "*";
		}

		if (BizDataSourceTypeEnum.isSqlServerDialect(typeName)) {
			return String.format("SELECT TOP %d %s FROM %s", limit, columnNames, tableName);
		}
		if (BizDataSourceTypeEnum.isOracleDialect(typeName)) {
			return String.format("SELECT %s FROM %s FETCH FIRST %d ROWS ONLY", columnNames, tableName, limit);
		}
		return String.format("SELECT %s FROM %s LIMIT %d", columnNames, tableName, limit);
	}

	public static String quoteIdentifier(String typeName, String identifier) {
		if (identifier == null || identifier.isBlank()) {
			throw new IllegalArgumentException("Identifier cannot be empty");
		}
		String trimmed = identifier.trim();
		if ("*".equals(trimmed)) {
			return trimmed;
		}
		String normalizedType = typeName == null ? "" : typeName.toLowerCase(Locale.ROOT);
		boolean mysqlLikeDialect = BizDataSourceTypeEnum.isMysqlDialect(normalizedType);
		String quoteStart = mysqlLikeDialect ? "`" : "\"";
		String quoteEnd = mysqlLikeDialect ? "`" : "\"";
		return Arrays.stream(trimmed.split("\\."))
			.map(String::trim)
			.filter(part -> !part.isEmpty())
			.map(part -> wrapIdentifierPart(part, quoteStart, quoteEnd))
			.collect(Collectors.joining("."));
	}

	private static String wrapIdentifierPart(String identifierPart, String quoteStart, String quoteEnd) {
		String normalizedPart = identifierPart;
		if ((normalizedPart.startsWith("`") && normalizedPart.endsWith("`"))
				|| (normalizedPart.startsWith("\"") && normalizedPart.endsWith("\""))
				|| (normalizedPart.startsWith("[") && normalizedPart.endsWith("]"))) {
			normalizedPart = normalizedPart.substring(1, normalizedPart.length() - 1);
		}
		String escapedPart = normalizedPart.replace(quoteEnd, quoteEnd + quoteEnd);
		return quoteStart + escapedPart + quoteEnd;
	}

}
