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
package com.sn68.agent.dataagent.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataAgentTypeTest {

	@Test
	void normalizeCode_defaultsBlankAndLegacyTypeToCommonAgent() {
		assertEquals("commonagent", AgentType.normalizeCode(" "));
		assertEquals("commonagent", AgentType.normalizeCode("commonagent"));
		assertEquals("commonagent", AgentType.normalizeCode("data_analysis"));
	}

	@Test
	void normalizeCode_supportsHyphenatedTypeCode() {
		assertEquals(AgentType.KNOWLEDGE_BASE.getCode(), AgentType.normalizeCode("knowledge-base"));
	}

	@Test
	void fromCode_resolvesByNormalizedCode() {
		assertEquals(AgentType.CUSTOMER_SERVICE, AgentType.fromCode("customer-service"));
	}

	@Test
	void promptName_resolvesByAgentType() {
		assertEquals("orchestratoragent", AgentType.promptName(AgentType.ORCHESTRATOR.getCode()));
		assertEquals("commonagent", AgentType.promptName("commonagent"));
		assertEquals("commonagent", AgentType.promptName("data_analysis"));
	}

	@Test
	void isOrchestrator_checksNormalizedCode() {
		assertTrue(AgentType.isOrchestrator("ORCHESTRATOR"));
	}

	@Test
	void isDataAnalysis_acceptsCurrentAndHistoricalCodes() {
		assertTrue(AgentType.isDataAnalysis("commonagent"));
		assertTrue(AgentType.isDataAnalysis("data_analysis"));
	}

}
