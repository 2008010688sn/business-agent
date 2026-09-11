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
package com.sn68.agent.dataagent.prompt;

import com.sn68.agent.dataagent.constant.AgentTypeConstant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataAgentPromptTemplateServiceTest {

	@Test
	void loadTypePrompt_loadsDedicatedTypePrompt() {
		AgentPromptTemplateService service = new AgentPromptTemplateService();

		String prompt = service.loadTypePrompt(AgentTypeConstant.ORCHESTRATOR);

		assertFalse(prompt.isBlank());
	}

	@Test
	void loadTypePrompt_usesCommonAgentPromptForCurrentAndHistoricalDataAnalysisTypes() {
		AgentPromptTemplateService service = new AgentPromptTemplateService();

		String commonPrompt = service.loadTypePrompt("commonagent");

		assertEquals(commonPrompt, service.loadTypePrompt("data_analysis"));
		assertEquals(commonPrompt, service.loadTypePrompt(AgentTypeConstant.DATA_ANALYSIS));
	}

	@Test
	void loadTypePrompt_reportsMissingPromptOnlyWhenRequested() {
		AgentPromptTemplateService service = new AgentPromptTemplateService();

		assertThrows(IllegalStateException.class, () -> service.loadTypePrompt("not_exist_type"));
	}

	@Test
	void loadTypePrompt_routesKnowledgeDocumentsThroughQaSkills() {
		AgentPromptTemplateService service = new AgentPromptTemplateService();

		String knowledgePrompt = service.loadTypePrompt(AgentTypeConstant.KNOWLEDGE_BASE);
		String customerServicePrompt = service.loadTypePrompt(AgentTypeConstant.CUSTOMER_SERVICE);

		assertQaSkillKnowledgeRule(knowledgePrompt);
		assertQaSkillKnowledgeRule(customerServicePrompt);
		assertTrue(customerServicePrompt.contains("动态业务查询"));
		assertTrue(customerServicePrompt.contains("SQL_VERIFY"));
		assertTrue(customerServicePrompt.contains("datasource"));
		assertTrue(customerServicePrompt.contains("数据源不是强制绑定"));
		assertTrue(customerServicePrompt.contains("我的订单到哪了"));
		assertTrue(customerServicePrompt.contains("啥时能到"));
		assertTrue(customerServicePrompt.contains("费用怎么样"));
	}

	private void assertQaSkillKnowledgeRule(String prompt) {
		assertFalse(prompt.isBlank());
		assertTrue(prompt.contains("QA + KNOWLEDGE"));
		assertFalse(prompt.contains("agentKnowledge"));
	}

}
