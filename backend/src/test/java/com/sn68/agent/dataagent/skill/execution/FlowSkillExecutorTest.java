/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.skill.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.entity.DataAgentFlowInstance;
import com.sn68.agent.dataagent.flow.FlowEngine;
import com.sn68.agent.dataagent.flow.FlowExecutionResult;
import com.sn68.agent.dataagent.flow.FlowInstanceStatus;
import com.sn68.agent.dataagent.flow.FlowTurnOutcome;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import com.sn68.agent.dataagent.routing.model.RouteSelection;
import com.sn68.agent.dataagent.routing.model.RouteTargetRef;
import com.sn68.agent.dataagent.routing.model.RouteTargetType;
import org.junit.jupiter.api.Test;

class FlowSkillExecutorTest {

	@Test
	void mapsFailedFlowInstanceToFailedSkillOutcome() {
		FlowEngine flowEngine = mock(FlowEngine.class);
		when(flowEngine.execute(any(), any(), any(), any())).thenReturn(new FlowExecutionResult(
				DataAgentFlowInstance.builder().status(FlowInstanceStatus.FAILED.name()).build(), null,
				"创建需求单失败：缺少需求号", true));
		FlowSkillExecutor executor = new FlowSkillExecutor(flowEngine);

		SkillExecutionResult result = executor.execute(context());

		assertEquals(SkillExecutionOutcome.FAILED, result.outcome());
		assertEquals("FLOW_FAILED", result.stageCode());
	}

	@Test
	void mapsRecoverableInputFailureToWaitingSkillOutcome() {
		FlowEngine flowEngine = mock(FlowEngine.class);
		when(flowEngine.execute(any(), any(), any(), any())).thenReturn(new FlowExecutionResult(
				DataAgentFlowInstance.builder().status(FlowInstanceStatus.WAITING.name()).build(), null,
				"刚才未能完整识别本次信息，请重新说明需要办理的内容。", false, FlowTurnOutcome.WAITING));
		FlowSkillExecutor executor = new FlowSkillExecutor(flowEngine);

		SkillExecutionResult result = executor.execute(context());

		assertEquals(SkillExecutionOutcome.WAITING, result.outcome());
		assertEquals("FLOW_WAITING", result.stageCode());
	}

	@Test
	void mapsTechnicalFailureOnWaitingInstanceToWaitingSkillOutcome() {
		FlowEngine flowEngine = mock(FlowEngine.class);
		when(flowEngine.execute(any(), any(), any(), any())).thenReturn(new FlowExecutionResult(
				DataAgentFlowInstance.builder().status(FlowInstanceStatus.WAITING.name()).build(), null,
				"刚才未能完整识别本次信息，请重新说明需要办理的内容。", false, FlowTurnOutcome.FAILED));
		FlowSkillExecutor executor = new FlowSkillExecutor(flowEngine);

		SkillExecutionResult result = executor.execute(context());

		assertEquals(SkillExecutionOutcome.WAITING, result.outcome());
		assertEquals("FLOW_INPUT_FAILED", result.stageCode());
	}

	private SkillExecutionContext context() {
		DataAgentSkill skill = DataAgentSkill.builder().id(7L).skillCode("demand-create").build();
		DataAgentSkillVersion version = DataAgentSkillVersion.builder().id(8L).skillId(7L).build();
		RouteSelection selection = new RouteSelection(
				new RouteTargetRef(RouteTargetType.SKILL, 7L, 8L, null), 11L, 12L, RouteRisk.FLOW, "checksum");
		return new SkillExecutionContext(new AgentRequest(), null, null, selection, skill, version, null);
	}

}
