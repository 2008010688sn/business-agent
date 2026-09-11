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
package com.sn68.agent.dataagent.employee.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeRelease;
import com.sn68.agent.dataagent.employee.service.EmployeeReleaseSnapshotAssembler;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * PostgreSQL integration coverage for the JSONB parameter binding used by Seal.
 *
 * <p>The test uses a temporary table, so it never changes the configured business schema.</p>
 */
@EnabledIfEnvironmentVariable(named = "DATABASE_URL", matches = ".+")
class DigitalEmployeeReleaseMapperJsonbIntegrationTest {

	private static final long RELEASE_ID = 1L;

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	@BeforeAll
	static void initTableInfo() {
		if (TableInfoHelper.getTableInfo(DigitalEmployeeRelease.class) == null) {
			TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
					DigitalEmployeeRelease.class);
		}
	}

	@Test
	void casSealWritesJsonbAndKeepsCasGuards() throws Exception {
		try (Connection connection = openConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				statement.execute("CREATE TEMP TABLE digital_employee_release ("
						+ "id BIGINT PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, snapshot JSONB NOT NULL, "
						+ "spec_hash VARCHAR(64), sealed_at TIMESTAMPTZ, sealed_by VARCHAR(64), "
						+ "status VARCHAR(32) NOT NULL, deleted BOOLEAN NOT NULL)");
				statement.execute("INSERT INTO digital_employee_release "
						+ "(id, tenant_id, snapshot, status, deleted) VALUES "
						+ "(1, 'tenant-1', '{}'::jsonb, 'DRAFT', false), "
						+ "(2, 'tenant-1', '{}'::jsonb, 'SEALED', false)");
			}

			SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
			SqlSessionFactory factory = buildSqlSessionFactory(dataSource);
			String snapshot = "{\"schemaVersion\":\"1\",\"capabilities\":[{\"skillVersionId\":\"42\"}]}";
			try (SqlSession session = factory.openSession(false)) {
				DigitalEmployeeReleaseMapper mapper = session.getMapper(DigitalEmployeeReleaseMapper.class);

				assertEquals(1, mapper.casSeal(RELEASE_ID, "tenant-1", snapshot, "hash-1", "user-1"));
				assertEquals(0, mapper.casSeal(RELEASE_ID, "tenant-1", snapshot, "hash-2", "user-1"));
				assertEquals(0, mapper.casSeal(2L, "tenant-1", snapshot, "hash-3", "user-1"));
				assertEquals(0, mapper.casSeal(RELEASE_ID, "tenant-2", snapshot, "hash-4", "user-1"));
				session.commit();
			}

			try (Statement statement = connection.createStatement();
					ResultSet resultSet = statement.executeQuery("SELECT snapshot::text, pg_typeof(snapshot)::text, "
							+ "status, spec_hash FROM digital_employee_release WHERE id = 1")) {
				assertTrue(resultSet.next());
				assertNotNull(resultSet.getString(1));
				assertEquals(OBJECT_MAPPER.readTree(snapshot), OBJECT_MAPPER.readTree(resultSet.getString(1)));
				assertEquals("jsonb", resultSet.getString(2));
				assertEquals("SEALED", resultSet.getString(3));
				assertEquals("hash-1", resultSet.getString(4));
				EmployeeReleaseSnapshotAssembler assembler = new EmployeeReleaseSnapshotAssembler(OBJECT_MAPPER,
						null, null);
				assertEquals(assembler.computeSpecHash(snapshot), assembler.computeSpecHash(resultSet.getString(1)));
			}
		}
	}

	private static SqlSessionFactory buildSqlSessionFactory(SingleConnectionDataSource dataSource) {
		MybatisConfiguration configuration = new MybatisConfiguration();
		configuration.setEnvironment(new Environment("jsonb-integration", new JdbcTransactionFactory(), dataSource));
		configuration.addMapper(DigitalEmployeeReleaseMapper.class);
		return new MybatisSqlSessionFactoryBuilder().build(configuration);
	}

	private static Connection openConnection() throws Exception {
		String databaseUrl = System.getenv("DATABASE_URL");
		if (databaseUrl.startsWith("jdbc:")) {
			return DriverManager.getConnection(databaseUrl);
		}
		URI uri = URI.create(databaseUrl);
		Properties properties = new Properties();
		if (uri.getUserInfo() != null) {
			String[] userInfo = uri.getUserInfo().split(":", 2);
			properties.setProperty("user", userInfo[0]);
			if (userInfo.length == 2) {
				properties.setProperty("password", userInfo[1]);
			}
		}
		String host = uri.getHost();
		String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
		String query = uri.getQuery() == null ? "" : "?" + uri.getQuery();
		return DriverManager.getConnection("jdbc:postgresql://" + host + port + uri.getPath() + query, properties);
	}

}
