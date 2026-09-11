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

import com.sn68.agent.dataagent.bo.DbConfigBO;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.beans.BeanUtils;

import java.util.List;
import java.util.Objects;

/**
 * 数据源查询参数：承载库/模式/表/列定位信息与待执行 SQL，供各方言 Accessor/Ddl 使用。
 * setter 为链式调用（返回自身）；suppressErrorDetails 为 true 时 toString 隐藏连接与 SQL 明细，避免日志泄漏。
 */
@Getter
@Setter
@Accessors(chain = true)
public class DbQueryParameter {

	private String aliuid;

	private String workspaceId;

	private String region;

	private String secretArn;

	private String dbInstanceId;

	private String database;

	private String schema;

	private String table;

	private String tablePattern;

	private List<String> tables;

	private String column;

	private String sql;

	private Integer statementTimeoutSeconds;

	private boolean suppressErrorDetails;

	public DbQueryParameter() {
	}

	public DbQueryParameter(String aliuid, String workspaceId, String region, String secretArn, String dbInstanceId,
			String database, String schema, String table, String tablePattern, List<String> tables, String column,
			String sql) {
		this.aliuid = aliuid;
		this.workspaceId = workspaceId;
		this.region = region;
		this.secretArn = secretArn;
		this.dbInstanceId = dbInstanceId;
		this.database = database;
		this.schema = schema;
		this.table = table;
		this.tablePattern = tablePattern;
		this.tables = tables;
		this.column = column;
		this.sql = sql;
	}

	public static DbQueryParameter from(DbConfigBO config) {
		DbQueryParameter param = new DbQueryParameter();
		BeanUtils.copyProperties(config, param);
		return param;
	}

	@Override
	public String toString() {
		if (suppressErrorDetails) {
			return "DbQueryParameter{statementTimeoutSeconds=" + statementTimeoutSeconds
					+ ", suppressErrorDetails=true}";
		}
		return "DbQueryParameter{" + "aliuid='" + aliuid + '\'' + ", workspaceId='" + workspaceId + '\'' + ", region='"
				+ region + '\'' + ", secretArn='" + secretArn + '\'' + ", dbInstanceId='" + dbInstanceId + '\''
				+ ", database='" + database + '\'' + ", schema='" + schema + '\'' + ", table='" + table + '\''
				+ ", tablePattern='" + tablePattern + '\'' + ", tables=" + tables + ", column='" + column + '\''
				+ ", sql='" + sql + '\'' + ", statementTimeoutSeconds=" + statementTimeoutSeconds
				+ ", suppressErrorDetails=" + suppressErrorDetails + '}';
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		DbQueryParameter that = (DbQueryParameter) o;
		return Objects.equals(aliuid, that.aliuid) && Objects.equals(workspaceId, that.workspaceId)
				&& Objects.equals(region, that.region) && Objects.equals(secretArn, that.secretArn)
				&& Objects.equals(dbInstanceId, that.dbInstanceId) && Objects.equals(database, that.database)
				&& Objects.equals(schema, that.schema) && Objects.equals(table, that.table)
				&& Objects.equals(tablePattern, that.tablePattern) && Objects.equals(tables, that.tables)
				&& Objects.equals(column, that.column) && Objects.equals(sql, that.sql)
				&& Objects.equals(statementTimeoutSeconds, that.statementTimeoutSeconds)
				&& suppressErrorDetails == that.suppressErrorDetails;
	}

	@Override
	public int hashCode() {
		return Objects.hash(aliuid, workspaceId, region, secretArn, dbInstanceId, database, schema, table, tablePattern,
				tables, column, sql, statementTimeoutSeconds, suppressErrorDetails);
	}

}
