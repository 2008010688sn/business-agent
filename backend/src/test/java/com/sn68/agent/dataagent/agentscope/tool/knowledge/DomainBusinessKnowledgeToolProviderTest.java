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
package com.sn68.agent.dataagent.agentscope.tool.knowledge;

import com.sn68.agent.dataagent.skill.execution.SkillVersionResources;
import com.sn68.agent.dataagent.agentscope.tool.AgentModelToolName;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DomainBusinessKnowledgeToolProviderTest {

	@Test
	void getSkillToolCallbacks_describesCompanyAndProductKnowledgeScenarios() {
		DomainBusinessKnowledgeToolSupport toolSupport = mock(DomainBusinessKnowledgeToolSupport.class);
		when(toolSupport.createSearchToolCallback(org.mockito.ArgumentMatchers.any(SkillVersionResources.class),
				org.mockito.ArgumentMatchers.eq(AgentModelToolName.DOMAIN_BUSINESS_KNOWLEDGE_SEARCH),
				org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
			String description = invocation.getArgument(2, String.class);
			return mockToolCallback(description);
		});
		DomainBusinessKnowledgeToolProvider provider = new DomainBusinessKnowledgeToolProvider(toolSupport);

		Map<String, ToolCallback> callbacks = provider.getSkillToolCallbacks(
				new SkillVersionResources(10L, 11L, null, List.of(), List.of(12L), Map.of()));
		String description = callbacks.get(AgentModelToolName.DOMAIN_BUSINESS_KNOWLEDGE_SEARCH).getToolDefinition()
			.description();

		assertTrue(description.contains("公司介绍"));
		assertTrue(description.contains("产品"));
		assertTrue(description.contains("解决方案"));
		assertTrue(description.contains("联系方式"));
	}

	@Test
	void getSkillToolCallbacks_doesNotExposeBusinessKnowledgeOutsideASkillSnapshot() {
		DomainBusinessKnowledgeToolProvider provider = new DomainBusinessKnowledgeToolProvider(mock(
				DomainBusinessKnowledgeToolSupport.class));

		assertTrue(provider.getSkillToolCallbacks(SkillVersionResources.empty()).isEmpty());
	}

	private ToolCallback mockToolCallback(String description) {
		ToolCallback callback = mock(ToolCallback.class);
		when(callback.getToolDefinition()).thenReturn(org.springframework.ai.tool.definition.ToolDefinition.builder()
			.name(AgentModelToolName.DOMAIN_BUSINESS_KNOWLEDGE_SEARCH)
			.description(description)
			.inputSchema("{}")
			.build());
		return callback;
	}

}
