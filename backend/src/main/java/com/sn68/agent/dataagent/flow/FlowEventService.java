/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import com.sn68.agent.dataagent.entity.DataAgentFlowInstance;
import com.sn68.agent.dataagent.flow.definition.FlowNode;
import java.util.Map;

/**
 * Append-only FLOW audit event service.
 */
public interface FlowEventService {

	void record(DataAgentFlowInstance instance, String runtimeRequestId, FlowNode node, String eventType, String status,
			long durationMs, Map<String, Object> input, Map<String, Object> output, Throwable error);

	/**
	 * Publishes the existing generic resource-success automation event. The
	 * default is intentionally empty so non-database test implementations remain
	 * valid.
	 */
	default void resourceSuccess(DataAgentFlowInstance instance, FlowNode node, String runtimeRequestId,
			String idempotencyKey, Map<String, Object> input, Map<String, Object> output) {
	}

}
