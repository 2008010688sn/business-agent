/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import com.sn68.agent.dataagent.entity.DataAgentFlowInstance;
import com.sn68.agent.dataagent.ui.AgentUiMessage;

/**
 * FLOW engine response for one user turn.
 */
public record FlowExecutionResult(DataAgentFlowInstance instance, AgentUiMessage message, String text, boolean terminal,
		FlowTurnOutcome turnOutcome) {

	public FlowExecutionResult(DataAgentFlowInstance instance, AgentUiMessage message, String text, boolean terminal) {
		this(instance, message, text, terminal, inferOutcome(instance, terminal));
	}

	private static FlowTurnOutcome inferOutcome(DataAgentFlowInstance instance, boolean terminal) {
		if (instance != null && FlowInstanceStatus.FAILED.name().equals(instance.getStatus())) {
			return FlowTurnOutcome.FAILED;
		}
		return terminal ? FlowTurnOutcome.SUCCEEDED : FlowTurnOutcome.WAITING;
	}

}
