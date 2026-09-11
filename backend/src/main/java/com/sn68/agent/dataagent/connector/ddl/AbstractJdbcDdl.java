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
package com.sn68.agent.dataagent.connector.ddl;

import com.sn68.agent.dataagent.bo.schema.ColumnInfoBO;
import com.sn68.agent.dataagent.bo.schema.DatabaseInfoBO;
import com.sn68.agent.dataagent.bo.schema.ForeignKeyInfoBO;
import com.sn68.agent.dataagent.bo.schema.ResultSetBO;
import com.sn68.agent.dataagent.bo.schema.SchemaInfoBO;
import com.sn68.agent.dataagent.bo.schema.TableInfoBO;
import com.sn68.agent.dataagent.util.SqlUtil;

import java.sql.Connection;
import java.util.List;

/**
 * JDBC 方言 DDL 读取抽象基类：定义库/模式/表/列/外键/采样等元数据读取契约，具体 SQL 由各数据库方言子类实现。
 */
public abstract class AbstractJdbcDdl implements Ddl {

	@Deprecated
	public abstract List<DatabaseInfoBO> showDatabases(Connection connection);

	public abstract List<SchemaInfoBO> showSchemas(Connection connection);

	public abstract List<TableInfoBO> showTables(Connection connection, String schema, String tablePattern);

	public abstract List<TableInfoBO> fetchTables(Connection connection, String schema, List<String> tables);

	public abstract List<ColumnInfoBO> showColumns(Connection connection, String schema, String table);

	public abstract List<ForeignKeyInfoBO> showForeignKeys(Connection connection, String schema, List<String> tables);

	public abstract List<String> sampleColumn(Connection connection, String schema, String table, String column);

	public abstract ResultSetBO scanTable(Connection connection, String schema, String table);

	public String getSelectSql(String typeName, String tableName, String columnNames, int limit) {
		return SqlUtil.buildSelectSql(typeName, tableName, columnNames, limit);
	}

}
