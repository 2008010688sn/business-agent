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
package com.sn68.agent.dataagent.agentscope.tool;

import com.sn68.agent.dataagent.constant.AgentTypeConstant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataAgentToolPolicyServiceTest {

	private static final String SKILL_ACTION = AgentModelToolName.skill("demand-create", "execute");

	private static final String SKILL_QUERY = AgentModelToolName.skill("waybill-query", "query");

	private final AgentToolPolicyService service = new AgentToolPolicyService();

	@Test
	void filter_removesModelCallableToolsForOrchestrator() {
		Map<String, ToolCallback> filtered = service.filter(AgentTypeConstant.ORCHESTRATOR, callbacks());

		assertFalse(filtered.containsKey("agent.collaborate"));
		assertFalse(filtered.containsKey(AgentModelToolName.SQL_GUARD_CHECK));
		assertFalse(filtered.containsKey(AgentModelToolName.DOMAIN_BUSINESS_KNOWLEDGE_SEARCH));
		assertFalse(filtered.containsKey(AgentModelToolName.WEB_FETCH));
	}

	@Test
	void filter_removesSqlAndDatasourceToolsForKnowledgeBase() {
		Map<String, ToolCallback> filtered = service.filter(AgentTypeConstant.KNOWLEDGE_BASE, callbacks());

		assertFalse(filtered.containsKey(AgentModelToolName.SQL_GUARD_CHECK));
		assertFalse(filtered.containsKey(AgentModelToolName.DATASOURCE_SKILL_SEARCH));
		assertFalse(filtered.containsKey(AgentModelToolName.SEMANTIC_MODEL_SEARCH));
		assertTrue(filtered.containsKey(AgentModelToolName.DOMAIN_BUSINESS_KNOWLEDGE_SEARCH));
		assertTrue(filtered.containsKey(AgentModelToolName.WEB_FETCH));
		assertFalse(filtered.containsKey("agent_knowledge.search"));
	}

	@Test
	void filter_keepsKnowledgeAndDataQueryToolsForCustomerService() {
		Map<String, ToolCallback> filtered = service.filter(AgentTypeConstant.CUSTOMER_SERVICE, callbacks());

		assertFalse(filtered.containsKey("agent.collaborate"));
		assertTrue(filtered.containsKey(AgentModelToolName.DOMAIN_BUSINESS_KNOWLEDGE_SEARCH));
		assertTrue(filtered.containsKey(AgentModelToolName.DATASOURCE_SKILL_SEARCH));
		assertTrue(filtered.containsKey(AgentModelToolName.SEMANTIC_MODEL_SEARCH));
		assertTrue(filtered.containsKey(AgentModelToolName.SQL_GUARD_CHECK));
		assertTrue(filtered.containsKey(SKILL_QUERY));
		assertTrue(filtered.containsKey(AgentModelToolName.WEB_FETCH));
		assertFalse(filtered.containsKey(SKILL_ACTION));
	}

	@Test
	void filter_removesCollaborateToolForDataAnalysis() {
		Map<String, ToolCallback> filtered = service.filter(AgentTypeConstant.DATA_ANALYSIS, callbacks());

		assertFalse(filtered.containsKey("agent.collaborate"));
		assertTrue(filtered.containsKey(AgentModelToolName.SQL_GUARD_CHECK));
		assertTrue(filtered.containsKey(AgentModelToolName.WEB_FETCH));
	}

	private Map<String, ToolCallback> callbacks() {
		Map<String, ToolCallback> callbacks = new LinkedHashMap<>();
		callbacks.put("agent.collaborate", callback("agent.collaborate"));
		callbacks.put(AgentModelToolName.SQL_GUARD_CHECK, callback(AgentModelToolName.SQL_GUARD_CHECK));
		callbacks.put(AgentModelToolName.DATASOURCE_SKILL_SEARCH, callback(AgentModelToolName.DATASOURCE_SKILL_SEARCH));
		callbacks.put(AgentModelToolName.DOMAIN_BUSINESS_KNOWLEDGE_SEARCH,
				callback(AgentModelToolName.DOMAIN_BUSINESS_KNOWLEDGE_SEARCH));
		callbacks.put("agent_knowledge.search", callback("agent_knowledge.search"));
		callbacks.put(AgentModelToolName.SEMANTIC_MODEL_SEARCH, callback(AgentModelToolName.SEMANTIC_MODEL_SEARCH));
		callbacks.put(AgentModelToolName.WEB_FETCH, callback(AgentModelToolName.WEB_FETCH));
		callbacks.put(SKILL_ACTION, callback(SKILL_ACTION));
		callbacks.put(SKILL_QUERY, callback(SKILL_QUERY));
		return callbacks;
	}

	private ToolCallback callback(String name) {
		return new ToolCallback() {
			@Override
			public ToolDefinition getToolDefinition() {
				return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
			}

			@Override
			public String call(String toolInput) {
				return "{}";
			}
		};
	}

}
