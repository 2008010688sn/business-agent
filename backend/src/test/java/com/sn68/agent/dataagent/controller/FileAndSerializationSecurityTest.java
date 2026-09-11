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
package com.sn68.agent.dataagent.controller;

import com.sn68.agent.dataagent.bo.DbConfigBO;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.entity.Datasource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileAndSerializationSecurityTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void agentSerializationShouldHideApiKey() throws Exception {
		DataAgent data_Data_agent = DataAgent.builder().id(1L).name("demo").apiKey("sk-test-secret").apiKeyEnabled(true).build();

		String json = objectMapper.writeValueAsString(data_Data_agent);

		assertFalse(json.contains("\"apiKey\":"));
		assertFalse(json.contains("sk-test-secret"));
		assertTrue(json.contains("apiKeyEnabled"));
	}

	@Test
	void datasourceSerializationShouldHideCredentials() throws Exception {
		Datasource data_datasource = Datasource.builder()
			.id(1L)
			.name("mysql")
			.username("root")
			.password("secret")
			.connectionUrl("jdbc:mysql://127.0.0.1:3306/test")
			.build();

		String json = objectMapper.writeValueAsString(data_datasource);

		// 账号同属凭据：DatasourceResp 的掩码只覆盖以 Resp 为返回类型的端点，
		// 直接返回实体（SkillDatasourceController.candidates）与嵌套返回（SkillDatasource.datasource）会绕过掩码，
		// 故实体层不序列化 username。断言值而不只断言键名，避免 "usernameConfigured" 之类字段让断言假过。
		assertFalse(json.contains("\"username\":"));
		assertFalse(json.contains("root"));
		assertFalse(json.contains("\"password\":"));
		assertFalse(json.contains("secret"));
		assertFalse(json.contains("\"connectionUrl\":"));
		assertFalse(json.contains("jdbc:mysql://127.0.0.1:3306/test"));
	}

	@Test
	void datasourceDeserializationShouldAcceptCredentials() throws Exception {
		String json = """
				{
				  "name": "postgres",
				  "username": "admin",
				  "password": "secret",
				  "connectionUrl": "jdbc:postgresql://127.0.0.1:5432/test"
				}
				""";

		Datasource data_datasource = objectMapper.readValue(json, Datasource.class);

		assertEquals("secret", data_datasource.getPassword());
		assertEquals("jdbc:postgresql://127.0.0.1:5432/test", data_datasource.getConnectionUrl());
	}

	@Test
	void dbConfigToStringShouldHidePassword() {
		DbConfigBO dbConfig = DbConfigBO.builder()
			.url("jdbc:postgresql://127.0.0.1:5432/test")
			.username("admin")
			.password("secret")
			.build();

		String text = dbConfig.toString();

		assertFalse(text.contains("secret"));
		assertFalse(text.contains("password"));
		assertTrue(text.contains("username=admin"));
	}

}
