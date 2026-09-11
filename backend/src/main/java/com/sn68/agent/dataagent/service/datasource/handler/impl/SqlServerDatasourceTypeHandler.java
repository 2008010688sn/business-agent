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
package com.sn68.agent.dataagent.service.datasource.handler.impl;

import com.sn68.agent.dataagent.enums.BizDataSourceTypeEnum;
import com.sn68.agent.dataagent.entity.Datasource;
import com.sn68.agent.dataagent.service.datasource.handler.DatasourceTypeHandler;
import org.springframework.stereotype.Component;

/**
 * SQL Server 数据库类型处理器：提供驱动、JDBC URL 与连接参数的方言实现。
 */
@Component
public class SqlServerDatasourceTypeHandler implements DatasourceTypeHandler {

	@Override
	public String typeName() {
		return BizDataSourceTypeEnum.SQL_SERVER.getTypeName();
	}

	/**
	 * 补齐 SQL Server 的连接串模板。此前本类没有实现，连接串只能由调用方直接传入——而写入口自 PRD S-2
	 * 起不再接受调用方连接串，缺了模板这个类型就无法登记。
	 *
	 * <p>不追加 {@code encrypt=false}：驱动 12.x 默认要求 TLS，这里保持驱动的安全默认值，不新增一个明文开关
	 * （MySQL / PostgreSQL 模板里的 {@code useSSL=false} 属存量，PRD S-1 记为待随 {@code sslMode} 字段一并整改）。
	 * 目标库未启用 TLS 时连接会失败，属需要按数据源配置 TLS 的场景，不在本类兜底。
	 */
	@Override
	public String buildConnectionUrl(Datasource datasource) {
		if (!hasRequiredConnectionFields(datasource)) {
			return datasource.getConnectionUrl();
		}
		return String.format("jdbc:sqlserver://%s:%d;databaseName=%s", datasource.getHost(), datasource.getPort(),
				datasource.getDatabaseName());
	}

}
