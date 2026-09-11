/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.skill.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.sn68.agent.dataagent.properties.DataAgentProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReactRuntimeBudgetPolicyTest {

	@Test
	void agentAndSkillCanOnlyTightenPlatformBudget() {
		DataAgentProperties.Runtime runtime = new DataAgentProperties.Runtime();
		ReactRuntimeBudgetPolicy.Budget agentBudget = ReactRuntimeBudgetPolicy.resolve(runtime, 9, 4, 8, 50000L);
		ReactRuntimeBudgetPolicy.Budget skillBudget = ReactRuntimeBudgetPolicy.tighten(agentBudget,
				Map.of("maxIterations", 3, "maxModelCalls", 9, "maxToolCalls", 4, "maxPromptTokens", 12000));

		assertEquals(new ReactRuntimeBudgetPolicy.Budget(9, 4, 8, 50000L), agentBudget);
		assertEquals(new ReactRuntimeBudgetPolicy.Budget(3, 4, 4, 12000L), skillBudget);
	}

	@Test
	void invalidSkillBudgetIsRejectedByValidation() {
		assertFalse(ReactRuntimeBudgetPolicy.validate(
				Map.of("maxIterations", 0, "maxModelCalls", 1.5, "maxPromptTokens", "30000")).isEmpty());
	}

}
