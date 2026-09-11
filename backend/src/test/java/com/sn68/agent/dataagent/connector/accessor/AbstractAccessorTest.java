/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.connector.accessor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sn68.agent.dataagent.bo.DbConfigBO;
import com.sn68.agent.dataagent.connector.DbQueryParameter;
import com.sn68.agent.dataagent.connector.ddl.AbstractJdbcDdl;
import com.sn68.agent.dataagent.connector.ddl.DdlFactory;
import com.sn68.agent.dataagent.connector.pool.DBConnectionPool;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class AbstractAccessorTest {

	@Test
	void suppressesRawDatabaseMessageForSensitiveQuery() throws Exception {
		SQLException databaseFailure = new SQLException("relation bill_secret does not exist", "42P01");
		TestAccessor accessor = failingAccessor(databaseFailure);
		ListAppender<ILoggingEvent> appender = attachAppender();
		try {
			DbQueryParameter parameter = new DbQueryParameter().setSql("select * from bill_secret")
				.setSuppressErrorDetails(true);
			SQLException failure = assertThrows(SQLException.class,
					() -> accessor.executeSqlAndReturnObject(new DbConfigBO(), parameter));

			assertSame(databaseFailure, failure);
			assertFalse(parameter.toString().contains("bill_secret"));
			assertTrue(appender.list.stream().map(ILoggingEvent::getFormattedMessage)
				.anyMatch(message -> message.contains("errorCode=DATABASE_ERROR")));
			assertTrue(appender.list.stream().map(ILoggingEvent::getFormattedMessage)
				.noneMatch(message -> message.contains("bill_secret")));
		}
		finally {
			detachAppender(appender);
		}
	}

	private TestAccessor failingAccessor(SQLException databaseFailure) throws SQLException {
		DdlFactory ddlFactory = mock(DdlFactory.class);
		DBConnectionPool connectionPool = mock(DBConnectionPool.class);
		AbstractJdbcDdl ddl = mock(AbstractJdbcDdl.class);
		Connection connection = mock(Connection.class);
		Statement statement = mock(Statement.class);
		DatabaseMetaData metadata = mock(DatabaseMetaData.class);
		when(ddlFactory.getDdlExecutorByDbConfig(any(DbConfigBO.class))).thenReturn(ddl);
		when(connectionPool.getConnection(any(DbConfigBO.class))).thenReturn(connection);
		when(connection.createStatement()).thenReturn(statement);
		when(connection.getMetaData()).thenReturn(metadata);
		when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
		when(statement.executeQuery(anyString())).thenThrow(databaseFailure);
		return new TestAccessor(ddlFactory, connectionPool);
	}

	private ListAppender<ILoggingEvent> attachAppender() {
		Logger logger = (Logger) LoggerFactory.getLogger(AbstractAccessor.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private void detachAppender(ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger) LoggerFactory.getLogger(AbstractAccessor.class);
		logger.detachAppender(appender);
		appender.stop();
	}

	private static final class TestAccessor extends AbstractAccessor {

		private TestAccessor(DdlFactory ddlFactory, DBConnectionPool connectionPool) {
			super(ddlFactory, connectionPool);
		}

		@Override
		public String getAccessorType() {
			return "test";
		}

		@Override
		public boolean supportedDataSourceType(String type) {
			return true;
		}

	}

}
