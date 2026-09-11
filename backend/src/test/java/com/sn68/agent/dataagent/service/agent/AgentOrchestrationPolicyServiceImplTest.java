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
package com.sn68.agent.dataagent.service.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.constant.AgentTypeConstant;
import com.sn68.agent.dataagent.entity.AgentOrchestrationPolicy;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.repository.AgentOrchestrationPolicyMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentOrchestrationPolicyServiceImplTest {

	private final AgentOrchestrationPolicyMapper policyMapper = mock(AgentOrchestrationPolicyMapper.class);

	private final DataAgentService agentService = mock(DataAgentService.class);

	private final AgentOrchestrationPolicyServiceImpl service = new AgentOrchestrationPolicyServiceImpl(policyMapper,
			agentService, new DataAgentProperties());

	@BeforeEach
	void setUp() {
		when(agentService.requireAgent(1L))
			.thenReturn(DataAgent.builder().id(1L).agentType(AgentTypeConstant.ORCHESTRATOR).build());
		AgentOrchestrationPolicy existing = policy();
		existing.setId(10L);
		when(policyMapper.findByAgentId(1L)).thenReturn(existing);
		when(policyMapper.selectById(10L)).thenAnswer(invocation -> existing);
	}

	@Test
	void invalidFailureStrategyIsRejected() {
		AgentOrchestrationPolicy input = policy();
		input.setFailureStrategy("ignore");

		assertThrows(CheckedException.class, () -> service.update(1L, input));
	}

	@Test
	void temporalAliasesAreNormalizedAsVisibleSemanticMappings() {
		AgentOrchestrationPolicy input = policy();
		input.setClarificationConfig(Map.of("temporalAliases",
				Map.of(" current month ", "current_month", "this month", "CURRENT_MONTH"),
				"explicitMetricAliases", List.of("order count", "order count"),
				"ambiguousMetricAliases", List.of("GMV"),
				"orderingMetricAliases", List.of("order count")));

		AgentOrchestrationPolicy updated = service.update(1L, input);

		assertEquals(Map.of("current month", "CURRENT_MONTH", "this month", "CURRENT_MONTH"),
				updated.getClarificationConfig().get("temporalAliases"));
		assertEquals(List.of("order count"), updated.getClarificationConfig().get("explicitMetricAliases"));
	}

	@Test
	void clarificationConfigRejectsExplicitAndAmbiguousOverlap() {
		AgentOrchestrationPolicy input = policy();
		input.setClarificationConfig(Map.of("explicitMetricAliases", List.of("GMV"),
				"ambiguousMetricAliases", List.of("gmv")));

		assertThrows(CheckedException.class, () -> service.update(1L, input));
	}

	@Test
	void clarificationConfigRejectsTooManyLegacyTemporalAliases() {
		AgentOrchestrationPolicy input = policy();
		input.setClarificationConfig(Map.of("timeAliases", Collections.nCopies(101, "current month")));

		assertThrows(CheckedException.class, () -> service.update(1L, input));
	}

	private AgentOrchestrationPolicy policy() {
		return AgentOrchestrationPolicy.builder()
			.maxCollaboratorsPerRun(2)
			.failureStrategy("continue")
			.exposeTrace(true)
			.enabled(true)
			.build();
	}

}
