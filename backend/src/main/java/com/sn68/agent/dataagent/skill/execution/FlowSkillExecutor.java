/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.skill.execution;

import com.sn68.agent.dataagent.flow.FlowEngine;
import com.sn68.agent.dataagent.flow.FlowExecutionResult;
import com.sn68.agent.dataagent.flow.FlowInstanceStatus;
import com.sn68.agent.dataagent.flow.FlowTurnOutcome;
import com.sn68.agent.dataagent.skill.SkillExecutionMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Deterministic FLOW Skill executor.
 */
@Component
@RequiredArgsConstructor
public class FlowSkillExecutor implements SkillExecutor {

	private final FlowEngine flowEngine;

	@Override
	public SkillExecutionMode supports() {
		return SkillExecutionMode.FLOW;
	}

	@Override
	public SkillExecutionResult execute(SkillExecutionContext context) {
		FlowExecutionResult result = flowEngine.execute(context.request(), context.skill(), context.version(),
				context.modelConfig());
		boolean waitingInstance = result.instance() != null
				&& FlowInstanceStatus.WAITING.name().equals(result.instance().getStatus());
		SkillExecutionOutcome outcome = waitingInstance ? SkillExecutionOutcome.WAITING
				: result.turnOutcome() == FlowTurnOutcome.FAILED ? SkillExecutionOutcome.FAILED
				: result.turnOutcome() == FlowTurnOutcome.SUCCEEDED ? SkillExecutionOutcome.SUCCEEDED
				: SkillExecutionOutcome.WAITING;
		String instanceStatus = result.instance() == null ? null : result.instance().getStatus();
		String stageCode = FlowInstanceStatus.PROCESSING.name().equals(instanceStatus) ? "FLOW_PROCESSING"
				: waitingInstance && result.turnOutcome() == FlowTurnOutcome.FAILED ? "FLOW_INPUT_FAILED"
				: outcome == SkillExecutionOutcome.FAILED ? "FLOW_FAILED"
				: outcome == SkillExecutionOutcome.WAITING ? "FLOW_WAITING" : "FLOW_DONE";
		return new SkillExecutionResult(true, result.text(), result.message(), stageCode, outcome);
	}

}
