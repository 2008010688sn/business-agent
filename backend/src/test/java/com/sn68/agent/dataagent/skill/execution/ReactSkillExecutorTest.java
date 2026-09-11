/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.skill.execution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import com.sn68.agent.dataagent.routing.model.RouteSelection;
import com.sn68.agent.dataagent.routing.model.RouteTargetRef;
import com.sn68.agent.dataagent.routing.model.RouteTargetType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReactSkillExecutorTest {

	@Test
	void preparesBusinessContextBeforeContinuingRuntime() {
		SkillBusinessContextService businessContextService = mock(SkillBusinessContextService.class);
		ReactSkillExecutor executor = new ReactSkillExecutor(businessContextService);
		SkillVersionResources resources = new SkillVersionResources(7L, 8L,
				new SkillVersionResources.DatasourceResource(10L,
						List.of(new SkillVersionResources.TableScope("orders", List.of("id"))), true, 200,
						Map.of(), Map.of()), List.of(), List.of(), Map.of());
		AgentRequest request = AgentRequest.builder().routedSkillId(7L).routedSkillVersionId(8L).build();
		DataAgentSkill skill = DataAgentSkill.builder().id(7L).build();
		DataAgentSkillVersion version = DataAgentSkillVersion.builder().id(8L).skillId(7L).build();
		RouteSelection selection = new RouteSelection(new RouteTargetRef(RouteTargetType.SKILL, 7L, 8L, null),
				1L, 2L, RouteRisk.READ_ONLY, "checksum");

		SkillExecutionResult result = executor.execute(
				new SkillExecutionContext(request, null, null, selection, skill, version, resources));

		assertFalse(result.handled());
		verify(businessContextService).prepare(request, resources);
	}

}
