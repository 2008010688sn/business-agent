/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import org.junit.jupiter.api.Test;

class FlowExtractionBudgetPlannerTest {

	private final FlowExtractionBudgetPlanner planner = new FlowExtractionBudgetPlanner();

	@Test
	void zeroExtractMaxTokensFollowsTheModelOutputCap() {
		DataAgentProperties.Flow flow = new DataAgentProperties.Flow();
		flow.setExtractMaxTokens(0);
		ModelConfigDTO model = ModelConfigDTO.builder().maxTokens(20000L).contextWindowTokens(200000L).build();

		FlowExtractionBudgetPlanner.Budget budget = planner.plan(request(), model, "short prompt", flow);

		assertEquals(20000L, budget.maxOutputTokens());
	}

	@Test
	void explicitExtractMaxTokensTightensWhenConfigured() {
		DataAgentProperties.Flow flow = new DataAgentProperties.Flow();
		flow.setExtractMaxTokens(1024);
		ModelConfigDTO model = ModelConfigDTO.builder().maxTokens(20000L).contextWindowTokens(200000L).build();

		FlowExtractionBudgetPlanner.Budget budget = planner.plan(request(), model, "short prompt", flow);

		assertEquals(1024L, budget.maxOutputTokens());
	}

	@Test
	void defaultFlowPropertiesDoNotImposeADedicatedExtractCap() {
		DataAgentProperties.Flow flow = new DataAgentProperties().getFlow();
		ModelConfigDTO model = ModelConfigDTO.builder().maxTokens(8000L).contextWindowTokens(32000L).build();

		FlowExtractionBudgetPlanner.Budget budget = planner.plan(request(), model, "short prompt", flow);

		assertTrue(flow.getExtractMaxTokens() <= 0);
		assertEquals(8000L, budget.maxOutputTokens());
	}

	private static AgentRequest request() {
		return AgentRequest.builder().query("hello").build();
	}

}
