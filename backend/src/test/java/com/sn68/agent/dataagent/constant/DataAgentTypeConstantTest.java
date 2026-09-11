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
package com.sn68.agent.dataagent.constant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataAgentTypeConstantTest {

	@Test
	void dataAnalysis_usesCommonAgentCode() {
		assertEquals("commonagent", AgentTypeConstant.DATA_ANALYSIS);
	}

	@Test
	void normalize_mapsCurrentAndHistoricalDataAnalysisTypesToCommonAgent() {
		assertEquals(AgentTypeConstant.DATA_ANALYSIS, AgentTypeConstant.normalize("commonagent"));
		assertEquals(AgentTypeConstant.DATA_ANALYSIS, AgentTypeConstant.normalize("data_analysis"));
	}

	@Test
	void normalize_defaultsBlankTypeToCommonAgent() {
		assertEquals(AgentTypeConstant.DATA_ANALYSIS, AgentTypeConstant.normalize(" "));
	}

	@Test
	void promptName_resolvesByNormalizedType() {
		assertEquals("orchestratoragent", AgentTypeConstant.promptName(AgentTypeConstant.ORCHESTRATOR));
	}

}
