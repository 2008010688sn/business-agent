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
package com.sn68.agent.dataagent.connector.impls.oracle;

import com.sn68.agent.dataagent.connector.pool.AbstractDBConnectionPool;
import com.sn68.agent.dataagent.enums.BizDataSourceTypeEnum;
import com.sn68.agent.dataagent.enums.ErrorCodeEnum;
import org.springframework.stereotype.Service;

import static com.sn68.agent.dataagent.enums.ErrorCodeEnum.DATASOURCE_CONNECTION_FAILURE_08S01;
import static com.sn68.agent.dataagent.enums.ErrorCodeEnum.PASSWORD_ERROR_28000;
import static com.sn68.agent.dataagent.enums.ErrorCodeEnum.DATABASE_NOT_EXIST_42000;
import static com.sn68.agent.dataagent.enums.ErrorCodeEnum.OTHERS;

/**
 * Oracle JDBC 连接池实现，驱动 oracle.jdbc.OracleDriver，池化行为复用 {@link AbstractDBConnectionPool}。
 */
@Service("oracleJdbcConnectionPool")
public class OracleJdbcConnectionPool extends AbstractDBConnectionPool {

	private final static String DRIVER = "oracle.jdbc.OracleDriver";

	@Override
	public String getDriver() {
		return DRIVER;
	}

	@Override
	public ErrorCodeEnum errorMapping(String sqlState) {

		ErrorCodeEnum ret = ErrorCodeEnum.fromCode(sqlState);
		if (ret != null) {
			return ret;
		}

		return switch (sqlState) {
			case "08S01" -> DATASOURCE_CONNECTION_FAILURE_08S01;
			case "28000" -> PASSWORD_ERROR_28000;
			case "42000" -> DATABASE_NOT_EXIST_42000;
			default -> OTHERS;
		};
	}

	@Override
	public boolean supportedDataSourceType(String type) {
		return BizDataSourceTypeEnum.ORACLE.getTypeName().equals(type);
	}

	@Override
	public String getConnectionPoolType() {
		return BizDataSourceTypeEnum.ORACLE.getTypeName();
	}

}
