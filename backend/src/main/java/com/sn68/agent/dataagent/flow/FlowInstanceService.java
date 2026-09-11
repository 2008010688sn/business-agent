/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.entity.DataAgentFlowInstance;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import java.time.Instant;
import java.util.Map;

/**
 * FLOW instance persistence and optimistic-lock boundary.
 */
public interface FlowInstanceService {

	DataAgentFlowInstance loadOrCreate(AgentRequest request, DataAgentSkill skill, DataAgentSkillVersion version,
			String startNode);

	DataAgentFlowInstance findActive(AgentRequest request);

	DataAgentFlowInstance advance(DataAgentFlowInstance instance, FlowInstanceStatus status, String nodeId,
			Map<String, Object> context, Map<String, Object> waitingPayload, String idempotencyKey,
			String runtimeRequestId, String errorCode, String errorMessage, Instant finishedAt);

	/**
	 * Persists one coordinated FLOW batch. The default keeps custom test/runtime
	 * implementations compatible while the database implementation uses the same
	 * single optimistic-lock update as the normal advance path.
	 */
	default DataAgentFlowInstance advanceBatch(DataAgentFlowInstance instance, FlowInstanceStatus status, String nodeId,
			Map<String, Object> context, Map<String, Object> waitingPayload, String idempotencyKey,
			String runtimeRequestId, String errorCode, String errorMessage, Instant finishedAt) {
		return advance(instance, status, nodeId, context, waitingPayload, idempotencyKey, runtimeRequestId, errorCode,
				errorMessage, finishedAt);
	}

}
