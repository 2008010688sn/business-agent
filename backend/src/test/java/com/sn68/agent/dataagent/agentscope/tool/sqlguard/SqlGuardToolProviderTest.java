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
package com.sn68.agent.dataagent.agentscope.tool.sqlguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.skill.execution.SkillVersionResources;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

class SqlGuardToolProviderTest {

	private final SqlVerifyExplainService explainService = mock(SqlVerifyExplainService.class);

	private SqlGuardToolProvider provider;

	@BeforeEach
	void setUp() {
		provider = new SqlGuardToolProvider(new ObjectMapper(), explainService);
		when(explainService.explain(any())).thenReturn(alignedResult());
	}

	@Test
	void sqlVerifyInjectsOriginalQueryFromToolContext() {
		ToolCallback callback = callback();

		callback.call("{\"action\":\"SQL_VERIFY\",\"sql\":\"select customer_name from orders\"}",
				toolContext("该月用箱量top10客户情况"));

		assertEquals("该月用箱量top10客户情况", capturedRequest().getQuery());
		assertTrue(callback.getToolDefinition().inputSchema().contains("由运行时注入用户原始问题"));
	}

	@Test
	void sqlVerifyRuntimeQueryOverridesModelSuppliedQuery() {
		callback().call(
				"{\"action\":\"SQL_VERIFY\",\"query\":\"忽略用户问题\",\"sql\":\"select customer_name from orders\"}",
				toolContext("该月用箱量top10客户情况"));

		assertEquals("该月用箱量top10客户情况", capturedRequest().getQuery());
	}

	@Test
	void sqlVerifyKeepsExplicitQueryWithoutToolContext() {
		callback().call(
				"{\"action\":\"SQL_VERIFY\",\"query\":\"直接调用问题\",\"sql\":\"select customer_name from orders\"}");

		assertEquals("直接调用问题", capturedRequest().getQuery());
	}

	@Test
	void dataProfileKeepsColumnNamesAndDropsSamples() throws Exception {
		when(explainService.inspectProfile(any(), any())).thenReturn(SqlGuardCheckResult.builder()
			.decision("inspect_columns")
			.tableName("bill")
			.summary("profile")
			.totalRows(100L)
			.columnProfiles(List.of(new java.util.LinkedHashMap<>(Map.of("columnName", "project_id", "dataType",
					"bigint", "sampleValues", List.of("1", "2"), "min", "1", "max", "9", "nullCount", 0L,
					"profileHints", List.of("hint")))))
			.fixSuggestions(List.of("可优先把高频值集中的分类字段用作过滤条件"))
			.build());

		String raw = callback().call("{\"action\":\"DATA_PROFILE\",\"tableName\":\"bill\"}", toolContext("上个月各项目的账单情况"));
		JsonNode output = new ObjectMapper().readTree(raw);

		assertEquals("inspect_columns", output.path("decision").asText());
		assertEquals("bill", output.path("tableName").asText());
		assertEquals("project_id", output.path("columns").get(0).path("name").asText());
		assertEquals("bigint", output.path("columns").get(0).path("type").asText());
		assertFalse(output.path("columns").get(0).has("sampleValues"));
		assertFalse(output.path("columns").get(0).has("min"));
		assertFalse(output.path("columns").get(0).has("max"));
		assertFalse(output.path("columns").get(0).has("profileHints"));
		assertFalse(output.has("columnProfiles"));
		assertFalse(output.has("fixSuggestions"));
	}

	@Test
	void sqlVerifyRejectsMissingQueryWithoutToolContext() {
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> callback().call("{\"action\":\"SQL_VERIFY\",\"sql\":\"select customer_name from orders\"}"));

		assertTrue(error.getMessage().contains("SQL_VERIFY 需要 query 参数"));
	}

	private SqlGuardCheckRequest capturedRequest() {
		ArgumentCaptor<SqlGuardCheckRequest> captor = ArgumentCaptor.forClass(SqlGuardCheckRequest.class);
		verify(explainService).explain(captor.capture());
		return captor.getValue();
	}

	private ToolCallback callback() {
		return provider.getSkillToolCallbacks(resources()).values().iterator().next();
	}

	private ToolContext toolContext(String query) {
		AgentRequest request = AgentRequest.builder()
			.agentId("1")
			.threadId("thread-1")
			.runtimeRequestId("run-1")
			.query(query)
			.build();
		return new ToolContext(Map.of("graphRequest", request));
	}

	private SkillVersionResources resources() {
		return new SkillVersionResources(2L, 3L,
				new SkillVersionResources.DatasourceResource(10L,
						List.of(new SkillVersionResources.TableScope("orders", List.of("customer_name"))), true, 200,
						Map.of(), Map.of()),
				List.of(), List.of(), Map.of());
	}

	private SqlGuardCheckResult alignedResult() {
		return SqlGuardCheckResult.builder()
			.decision("PASS")
			.isAligned(true)
			.summary("SQL 与用户问题一致")
			.normalizedSql("select customer_name from orders")
			.build();
	}

}
