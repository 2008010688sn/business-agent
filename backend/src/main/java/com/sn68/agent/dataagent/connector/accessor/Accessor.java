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
package com.sn68.agent.dataagent.connector.accessor;

import com.sn68.agent.dataagent.bo.schema.ColumnInfoBO;
import com.sn68.agent.dataagent.bo.schema.DatabaseInfoBO;
import com.sn68.agent.dataagent.connector.DbQueryParameter;
import com.sn68.agent.dataagent.bo.schema.ForeignKeyInfoBO;
import com.sn68.agent.dataagent.bo.schema.ResultSetBO;
import com.sn68.agent.dataagent.bo.schema.SchemaInfoBO;
import com.sn68.agent.dataagent.bo.schema.TableInfoBO;
import com.sn68.agent.dataagent.bo.DbConfigBO;
import com.sn68.agent.dataagent.enums.BizDataSourceTypeEnum;

import java.util.List;

/**
 * Data access interface definition.
 *
 * @author yuluo
 * @author <a href="mailto:yuluo08290126@gmail.com">yuluo</a>
 */

public interface Accessor {

	/**
	 * 查询Accessor。
	 */
	String getAccessorType();

	/**
	 * 处理Accessor。
	 */
	boolean supportedDataSourceType(String type);

	/**
	 * 判断Accessor访问器是否满足条件。
	 */
	default boolean supportedDataSourceType(BizDataSourceTypeEnum typeEnum) {
		return supportedDataSourceType(typeEnum.getTypeName());
	}

	/**
	 * Access the database and execute the specified method with the given parameters.
	 * @param dbConfig database configuration
	 * @param method method name
	 * @param param query parameters
	 * @return result object, which can be a list of database information, schema
	 * information, table information, etc.
	 * @throws Exception if an error occurs during database access
	 */
	<T> T accessDb(DbConfigBO dbConfig, String method, DbQueryParameter param) throws Exception;

	/**
	 * 处理Accessor。
	 */
	List<DatabaseInfoBO> showDatabases(DbConfigBO dbConfig) throws Exception;

	/**
	 * 处理Accessor。
	 */
	List<SchemaInfoBO> showSchemas(DbConfigBO dbConfig) throws Exception;

	/**
	 * 处理Accessor。
	 */
	List<TableInfoBO> showTables(DbConfigBO dbConfig, DbQueryParameter param) throws Exception;

	/**
	 * 处理Accessor。
	 */
	List<TableInfoBO> fetchTables(DbConfigBO dbConfig, DbQueryParameter param) throws Exception;

	/**
	 * 处理Accessor。
	 */
	List<ColumnInfoBO> showColumns(DbConfigBO dbConfig, DbQueryParameter param) throws Exception;

	/**
	 * 处理Accessor。
	 */
	List<ForeignKeyInfoBO> showForeignKeys(DbConfigBO dbConfig, DbQueryParameter param) throws Exception;

	/**
	 * 处理Accessor。
	 */
	List<String> sampleColumn(DbConfigBO dbConfig, DbQueryParameter param) throws Exception;

	/**
	 * 处理Accessor。
	 */
	ResultSetBO scanTable(DbConfigBO dbConfig, DbQueryParameter param) throws Exception;

	/**
	 * 执行Accessor。
	 */
	ResultSetBO executeSqlAndReturnObject(DbConfigBO dbConfig, DbQueryParameter param) throws Exception;

}
