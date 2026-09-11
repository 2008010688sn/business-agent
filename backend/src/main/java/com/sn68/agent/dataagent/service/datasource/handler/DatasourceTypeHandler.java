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
package com.sn68.agent.dataagent.service.datasource.handler;

import com.sn68.agent.dataagent.enums.DbAccessTypeEnum;
import com.sn68.agent.dataagent.bo.DbConfigBO;
import com.sn68.agent.dataagent.entity.Datasource;
import com.sn68.agent.dataagent.service.datasource.DatasourceConnectionGuard;
import org.springframework.util.StringUtils;

/**
 * 数据源TypeHandler服务契约。
 */
public interface DatasourceTypeHandler {

	/**
	 * 处理数据源TypeHandler。
	 */
	String typeName();

	/**
	 * 连接方式编码，默认 JDBC。
	 */
	default String connectionType() {
		return DbAccessTypeEnum.JDBC.getCode();
	}

	/**
	 * SQL 方言编码，默认与数据库类型名一致。
	 */
	default String dialectType() {
		return typeName();
	}

	/**
	 * 判断DatasourceTypeHandler是否满足条件。
	 */
	default boolean supports(String type) {
		return typeName().equalsIgnoreCase(type);
	}

	/**
	 * 判断DatasourceTypeHandler是否满足条件。
	 */
	default boolean hasRequiredConnectionFields(Datasource datasource) {
		return datasource.getHost() != null && datasource.getPort() != null && datasource.getDatabaseName() != null;
	}

	/**
	 * 构建DatasourceTypeHandler所需的数据。
	 */
	default String buildConnectionUrl(Datasource datasource) {
		return datasource.getConnectionUrl();
	}

	/**
	 * 取运行期实际使用的连接串：库内已有连接串优先，否则按 host/port/databaseName 现场组装。
	 *
	 * <p>库内连接串一律由服务端生成——写入口（创建 / 修改数据源）自 PRD S-2 起不再接受调用方传入的
	 * {@code connectionUrl}。这里仍对已有串做一次危险驱动参数检查，让历史上通过旧入口写进来的恶意串
	 * 在运行期失败关闭，而不是继续被用来连接。
	 */
	default String resolveConnectionUrl(Datasource datasource) {
		String existing = datasource.getConnectionUrl();
		if (StringUtils.hasText(existing)) {
			DatasourceConnectionGuard.requireNoDangerousJdbcParameters(existing);
			return existing;
		}
		return buildConnectionUrl(datasource);
	}

	/**
	 * 提取元数据查询用的 schema 名，默认取数据库名（Oracle/PG 等方言可覆写）。
	 */
	default String extractSchemaName(Datasource datasource) {
		return datasource.getDatabaseName();
	}

	/**
	 * 把数据源实体转换为访问器所需的连接配置对象。
	 */
	default DbConfigBO toDbConfig(Datasource datasource) {
		DbConfigBO config = new DbConfigBO();
		config.setDatasourceId(datasource.getId());
		config.setTenantId(datasource.getTenantId());
		config.setUrl(resolveConnectionUrl(datasource));
		config.setUsername(datasource.getUsername());
		config.setPassword(datasource.getPassword());
		config.setConnectionType(connectionType());
		config.setDialectType(dialectType());
		config.setSchema(extractSchemaName(datasource));
		return config;
	}

	/**
	 * 连接测试前归一化 JDBC URL，默认原样返回（方言可覆写补默认库等）。
	 */
	default String normalizeTestUrl(Datasource datasource, String url) {
		return url;
	}

}
