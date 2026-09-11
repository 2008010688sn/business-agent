/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.routing;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.routing.model.DelegationMode;
import com.sn68.agent.dataagent.routing.model.RouteClarification;
import com.sn68.agent.dataagent.routing.model.RouteDecision;
import java.time.Instant;
import java.util.Map;

/** Owns opaque continuation tokens and their server-side route snapshots. */
public interface RoutePendingService {

	String TYPE_BUSINESS_CLARIFICATION = "BUSINESS_CLARIFICATION";

	String TYPE_ROUTE_CLARIFICATION = "ROUTE_CLARIFICATION";

	String TYPE_CONFIRMATION = "CONFIRMATION";

	String EXECUTION_PENDING = "PENDING";

	String EXECUTION_CLAIMED = "CLAIMED";

	String EXECUTION_SUCCEEDED = "SUCCEEDED";

	String EXECUTION_FAILED = "FAILED";

	String EXECUTION_CANCELLED = "CANCELLED";

	String EXECUTION_FORWARDED = "FORWARDED";

	String EXECUTION_EXPIRED = "EXPIRED";

	/**
	 * 创建一条待确认交互并落库路由快照，返回明文令牌与展示元数据（令牌仅此一次可见）。
	 */
	PendingInteraction create(AgentRequest request, String interactionType, String originalQuery, int round,
			RouteClarification clarification, RouteDecision routeSnapshot);

	/**
	 * 一次性消费请求中携带的澄清/确认响应。未携带 token 时，若本会话有 PENDING 且 query
	 * 对齐某选项文案/序号则合成响应再消费；否则取消该 PENDING 并返回 null。
	 * 令牌过期、会话不匹配或重复消费时抛出业务异常。
	 */
	PendingResolution consume(AgentRequest request);

	/** Marks a previously claimed interaction without allowing a second execution to overwrite it. */
	void finishExecution(AgentRequest request, String executionState, String resultReference);

	record PendingInteraction(String clarificationId, Map<String, Object> metadata) {
	}

	record PendingResolution(String interactionType, String originalQuery, String effectiveQuery, int round,
			RouteDecision confirmedRoute, boolean cancelled, OrchestrationContinuation orchestrationContinuation) {

		public PendingResolution(String interactionType, String originalQuery, String effectiveQuery, int round,
				RouteDecision confirmedRoute, boolean cancelled) {
			this(interactionType, originalQuery, effectiveQuery, round, confirmedRoute, cancelled, null);
		}

		public boolean hasOrchestrationContinuation() {
			return orchestrationContinuation != null;
		}
	}

	record OrchestrationExecutionPolicy(String failureStrategy, boolean exposeTrace) {
	}

	record OrchestrationContinuation(Long runId, Long stepId, String childAgentId, String childThreadId,
			String childRuntimeRequestId, String childQuery, Map<String, Object> childDependencyInputs,
			RouteDecision confirmedRoute,
			RouteDecision orchestrationRouteSnapshot, String childDelegationMode,
			OrchestrationExecutionPolicy executionPolicy) {

		public OrchestrationContinuation {
			childDependencyInputs = childDependencyInputs == null ? Map.of() : Map.copyOf(childDependencyInputs);
			childDelegationMode = DelegationMode.resolve(childDelegationMode).name();
		}

		public OrchestrationContinuation(Long runId, Long stepId, String childAgentId, String childThreadId,
				String childRuntimeRequestId, String childQuery, Map<String, Object> childDependencyInputs,
				RouteDecision confirmedRoute, RouteDecision orchestrationRouteSnapshot, String childDelegationMode) {
			this(runId, stepId, childAgentId, childThreadId, childRuntimeRequestId, childQuery, childDependencyInputs,
					confirmedRoute, orchestrationRouteSnapshot, childDelegationMode, null);
		}

		public OrchestrationContinuation(Long runId, Long stepId, String childAgentId, String childThreadId,
				String childRuntimeRequestId, String childQuery, RouteDecision confirmedRoute,
				RouteDecision orchestrationRouteSnapshot) {
			this(runId, stepId, childAgentId, childThreadId, childRuntimeRequestId, childQuery, Map.of(),
					confirmedRoute, orchestrationRouteSnapshot, DelegationMode.INTERACTIVE.name(), null);
		}

		public OrchestrationContinuation(Long runId, Long stepId, String childAgentId, String childThreadId,
				String childRuntimeRequestId, String childQuery, RouteDecision confirmedRoute) {
			this(runId, stepId, childAgentId, childThreadId, childRuntimeRequestId, childQuery, Map.of(),
					confirmedRoute, null, DelegationMode.INTERACTIVE.name(), null);
		}

		public OrchestrationContinuation withResolution(String effectiveQuery, RouteDecision nextConfirmedRoute) {
			return new OrchestrationContinuation(runId, stepId, childAgentId, childThreadId, childRuntimeRequestId,
					effectiveQuery, childDependencyInputs, nextConfirmedRoute, orchestrationRouteSnapshot,
					childDelegationMode, executionPolicy);
		}
	}

}
