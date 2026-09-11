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
package com.sn68.agent.dataagent.agentscope.v2;

import com.sn68.agent.dataagent.agentscope.tool.AgentModelToolName;
import com.sn68.agent.dataagent.service.analysis.AnalysisConfig;
import com.sn68.agent.dataagent.service.analysis.AnalysisConfig.AnalysisSource;
import com.sn68.agent.dataagent.service.analysis.AnalysisTurnDecision;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class V2ToolkitFilterTest {

	@Test
	void defaultChannelKeepsWriteAndJdbcTools() {
		Map<String, ToolCallback> callbacks = callbacks(AgentModelToolName.DATASOURCE_SKILL_SEARCH, "crm__create_order",
				AgentModelToolName.DOMAIN_BUSINESS_KNOWLEDGE_SEARCH);

		Map<String, ToolCallback> filtered = V2ToolkitFilter.filter(callbacks, null);

		assertEquals(callbacks.keySet(), filtered.keySet());
	}

	@Test
	void analysisDropsExecuteMcpAndWriteKeepsJdbc() {
		Map<String, ToolCallback> callbacks = callbacks(AgentModelToolName.DATASOURCE_SKILL_SEARCH, "crm__create_order",
				"skill_order_execute", AgentModelToolName.DOMAIN_BUSINESS_KNOWLEDGE_SEARCH);

		Map<String, ToolCallback> filtered = V2ToolkitFilter.filter(callbacks, V2ToolkitFilter.INTENT_ANALYSIS);

		assertTrue(filtered.containsKey(AgentModelToolName.DATASOURCE_SKILL_SEARCH));
		assertTrue(filtered.containsKey(AgentModelToolName.DOMAIN_BUSINESS_KNOWLEDGE_SEARCH));
		assertFalse(filtered.containsKey("crm__create_order"));
		assertFalse(filtered.containsKey("skill_order_execute"));
	}

	@Test
	void fileOnlyDropsJdbcExecuteMcpAndWrite() {
		Map<String, ToolCallback> callbacks = callbacks(AgentModelToolName.DATASOURCE_SKILL_SEARCH,
				AgentModelToolName.SQL_GUARD_CHECK, "crm__create_order",
				AgentModelToolName.DOMAIN_BUSINESS_KNOWLEDGE_SEARCH);

		Map<String, ToolCallback> filtered = V2ToolkitFilter.filter(callbacks, V2ToolkitFilter.INTENT_FILE_ONLY);

		assertEquals(1, filtered.size());
		assertTrue(filtered.containsKey(AgentModelToolName.DOMAIN_BUSINESS_KNOWLEDGE_SEARCH));
	}

	@Test
	void resolveReturnsNullWhenNoAnalysisConfigAndNotReport() {
		assertNull(V2ToolkitFilter.resolve(AnalysisConfig.empty(), false, AnalysisTurnDecision.tableQuery()));
		assertNull(V2ToolkitFilter.resolve(null, false, null));
	}

	@Test
	void resolveMapsFileOnlyJoinAndAnalysis() {
		AnalysisConfig tables = new AnalysisConfig(
				List.of(new AnalysisSource("t1", AnalysisConfig.TYPE_TABLE, 1L, "orders", List.of(), null, false)),
				List.of(), null, List.of(), List.of());
		AnalysisConfig files = new AnalysisConfig(
				List.of(new AnalysisSource("f1", AnalysisConfig.TYPE_FILE_TABLE, null, null, List.of(), null, true)),
				List.of(), null, List.of(), List.of());

		assertEquals(V2ToolkitFilter.INTENT_FILE_ONLY, V2ToolkitFilter.resolve(files, false, AnalysisTurnDecision.tableQuery()));
		assertEquals(V2ToolkitFilter.INTENT_FILE_ONLY,
				V2ToolkitFilter.resolve(tables, false, AnalysisTurnDecision.fileOnly(null, false)));
		assertEquals(V2ToolkitFilter.INTENT_FILE_JOIN,
				V2ToolkitFilter.resolve(tables, false, AnalysisTurnDecision.fileJoin(null, null)));
		assertEquals(V2ToolkitFilter.INTENT_ANALYSIS,
				V2ToolkitFilter.resolve(tables, false, AnalysisTurnDecision.tableQuery()));
		assertEquals(V2ToolkitFilter.INTENT_ANALYSIS, V2ToolkitFilter.resolve(AnalysisConfig.empty(), true, null));
	}

	@Test
	void readOnlyFlagsMatchKnownQueryTools() {
		assertTrue(V2ToolkitFilter.isReadOnly(AgentModelToolName.DATASOURCE_SKILL_SEARCH));
		assertTrue(V2ToolkitFilter.isReadOnly(AgentModelToolName.SQL_GUARD_CHECK));
		assertTrue(V2ToolkitFilter.isReadOnly(AgentModelToolName.WEB_FETCH));
		assertFalse(V2ToolkitFilter.isWriteToolName(AgentModelToolName.WEB_FETCH));
		assertTrue(V2ToolkitFilter.allowedInToolkit(AgentModelToolName.WEB_FETCH, V2ToolkitFilter.INTENT_ANALYSIS));
		assertFalse(V2ToolkitFilter.isReadOnly("crm__create_order"));
		assertTrue(V2ToolkitFilter.isExecuteMcp("crm__create_order"));
		assertTrue(V2ToolkitFilter.isWriteToolName("skill_order_execute"));
	}

	private static Map<String, ToolCallback> callbacks(String... names) {
		Map<String, ToolCallback> callbacks = new LinkedHashMap<>();
		for (String name : names) {
			ToolCallback callback = mock(ToolCallback.class);
			when(callback.getToolDefinition())
				.thenReturn(ToolDefinition.builder().name(name).description(name).inputSchema("{}").build());
			callbacks.put(name, callback);
		}
		return callbacks;
	}

}
