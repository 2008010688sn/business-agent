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
package com.sn68.agent.dataagent.connector;

import com.sn68.agent.dataagent.bo.schema.ResultSetBO;
import com.sn68.agent.dataagent.enums.BizDataSourceTypeEnum;
import com.sn68.agent.dataagent.enums.DatabaseDialectEnum;
import com.sn68.agent.dataagent.util.ResultSetConvertUtil;
import com.sn68.agent.dataagent.util.SqlUtil;
import com.sn68.agent.framework.commons.exception.CheckedException;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Responsible for executing SQL and returning structured results.
 */
public class SqlExecutor {

	public static final Integer RESULT_SET_LIMIT = 1000;

	public static final Integer STATEMENT_TIMEOUT = 30;

	/**
	 * schema / database 名会被拼进 SQL，只放行纯标识符字符，杜绝借 schema 注入语句。
	 */
	private static final Pattern SCHEMA_NAME_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

	/**
	 * Execute SQL query and return structured results (with column information)
	 * @param connection database connection
	 * @param sql SQL statement
	 * @return ResultSetBO structured result
	 * @throws SQLException SQL execution exception
	 */
	public static ResultSetBO executeSqlAndReturnObject(Connection connection, String schema, String sql)
			throws SQLException {
		return executeSqlAndReturnObject(connection, schema, sql, null);
	}

	/**
	 * Execute SQL query with a request-specific JDBC timeout.
	 * @param connection database connection
	 * @param schema database schema
	 * @param sql SQL statement
	 * @param statementTimeoutSeconds timeout in seconds; {@code null} keeps the
	 * shared default
	 * @return structured query result
	 * @throws SQLException SQL execution exception
	 */
	public static ResultSetBO executeSqlAndReturnObject(Connection connection, String schema, String sql,
			Integer statementTimeoutSeconds) throws SQLException {
		int effectiveTimeout = resolveStatementTimeout(statementTimeoutSeconds);
		try (Statement statement = connection.createStatement()) {
			statement.setMaxRows(RESULT_SET_LIMIT);
			statement.setQueryTimeout(effectiveTimeout);

			DatabaseMetaData metaData = connection.getMetaData();
			String dialect = metaData.getDatabaseProductName();

			if (dialect.equals(DatabaseDialectEnum.POSTGRESQL.code)) {
				if (StringUtils.isNotEmpty(schema)) {
					statement.execute("set search_path = " + quotePgSchema(schema) + ";");
				}
			}
			else if (dialect.equals(DatabaseDialectEnum.H2.code)) {
				if (StringUtils.isNotEmpty(schema)) {
					// H2/Oracle 不加引号：加引号会关闭标识符大小写折叠，已有数据源的
					// 小写 schema 配置会解析失败；注入面已由 requireValidSchema 关闭。
					statement.execute("use " + requireValidSchema(schema) + ";");
				}
			}
			else if (dialect.equals(DatabaseDialectEnum.ORACLE.code)) {
				if (StringUtils.isNotEmpty(schema)) {
					statement.execute("ALTER SESSION SET CURRENT_SCHEMA = " + requireValidSchema(schema));
				}
			}

			try (ResultSet rs = statement.executeQuery(sql)) {
				return ResultSetBuilder.buildFrom(rs, schema);
			}
		}
	}

	private static int resolveStatementTimeout(Integer statementTimeoutSeconds) {
		if (statementTimeoutSeconds == null) {
			return STATEMENT_TIMEOUT;
		}
		if (statementTimeoutSeconds <= 0) {
			throw new IllegalArgumentException("statementTimeoutSeconds must be greater than 0");
		}
		return statementTimeoutSeconds;
	}

	/**
	 * Execute SQL query and return string two-dimensional array format result
	 * @param connection database connection
	 * @param sql SQL statement
	 * @return two-dimensional array result
	 * @throws SQLException SQL execution exception
	 */
	public static String[][] executeSqlAndReturnArr(Connection connection, String sql) throws SQLException {
		List<String[]> list = executeQuery(connection, sql);
		return list.toArray(new String[0][]);
	}

	public static String[][] executeSqlAndReturnArr(Connection connection, String databaseOrSchema, String sql)
			throws SQLException {
		List<String[]> list = executeQuery(connection, databaseOrSchema, sql);
		return list.toArray(new String[0][]);
	}

	private static List<String[]> executeQuery(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {

			return ResultSetConvertUtil.convert(rs);
		}
	}

	private static List<String[]> executeQuery(Connection connection, String databaseOrSchema, String sql)
			throws SQLException {
		String originalDb = connection.getCatalog();
		DatabaseMetaData metaData = connection.getMetaData();
		String dialect = metaData.getDatabaseProductName();

		try (Statement statement = connection.createStatement()) {

			if (dialect.equals(DatabaseDialectEnum.MYSQL.code)) {
				if (StringUtils.isNotEmpty(databaseOrSchema)) {
					statement.execute("use " + quoteMysqlSchema(databaseOrSchema) + ";");
				}
			}
			else if (dialect.equals(DatabaseDialectEnum.POSTGRESQL.code)) {
				if (StringUtils.isNotEmpty(databaseOrSchema)) {
					statement.execute("set search_path = " + quotePgSchema(databaseOrSchema) + ";");
				}
			}
			else if (dialect.equals(DatabaseDialectEnum.ORACLE.code)) {
				if (StringUtils.isNotEmpty(databaseOrSchema)) {
					statement.execute("ALTER SESSION SET CURRENT_SCHEMA = " + requireValidSchema(databaseOrSchema));
				}
			}

			ResultSet rs = statement.executeQuery(sql);

			List<String[]> result = ResultSetConvertUtil.convert(rs);

			if (StringUtils.isNotEmpty(databaseOrSchema) && StringUtils.isNotEmpty(originalDb)
					&& dialect.equals(DatabaseDialectEnum.MYSQL.code)) {
				statement.execute(
						"use " + SqlUtil.quoteIdentifier(BizDataSourceTypeEnum.MYSQL.getTypeName(), originalDb) + ";");
			}

			return result;
		}
	}

	private static String quoteMysqlSchema(String schema) {
		return SqlUtil.quoteIdentifier(BizDataSourceTypeEnum.MYSQL.getTypeName(), requireValidSchema(schema));
	}

	private static String quotePgSchema(String schema) {
		return SqlUtil.quoteIdentifier(BizDataSourceTypeEnum.POSTGRESQL.getTypeName(), requireValidSchema(schema));
	}

	private static String requireValidSchema(String schema) {
		if (schema == null || !SCHEMA_NAME_PATTERN.matcher(schema).matches()) {
			throw CheckedException.badRequest("数据源 schema 名非法, schema=" + schema);
		}
		return schema;
	}

}
