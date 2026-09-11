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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SqlExecutorTest {

	private final Connection connection = mock(Connection.class);

	private final Statement statement = mock(Statement.class);

	private final DatabaseMetaData metadata = mock(DatabaseMetaData.class);

	private final SQLException datasourceFailure = new SQLException("column does not exist", "42703");

	@BeforeEach
	void setUp() throws Exception {
		when(connection.createStatement()).thenReturn(statement);
		when(connection.getMetaData()).thenReturn(metadata);
		when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
		when(statement.executeQuery("select missing_column")).thenThrow(datasourceFailure);
	}

	@Test
	void usesRequestSpecificStatementTimeoutAndPreservesSqlState() throws Exception {
		SQLException failure = assertThrows(SQLException.class,
				() -> SqlExecutor.executeSqlAndReturnObject(connection, null, "select missing_column", 6));

		verify(statement).setQueryTimeout(6);
		assertSame(datasourceFailure, failure);
		assertEquals("42703", failure.getSQLState());
	}

	@Test
	void keepsSharedStatementTimeoutWhenRequestDoesNotOverrideIt() throws Exception {
		assertThrows(SQLException.class,
				() -> SqlExecutor.executeSqlAndReturnObject(connection, null, "select missing_column", null));

		verify(statement).setQueryTimeout(SqlExecutor.STATEMENT_TIMEOUT);
	}

}
