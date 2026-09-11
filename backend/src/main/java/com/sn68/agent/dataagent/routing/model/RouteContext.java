/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing.model;

import java.time.Instant;
import java.util.List;

public record RouteContext(String tenantId, Long ownerAgentId, String ownerAgentType, Long sessionId, String userId,
		String runtimeRequestId, String query, String previousQuery, RouteTargetRef previousTarget,
		ExplicitRouteTarget explicitTarget, Instant deadline, int maxSelections, RouteScope scope,
		List<Long> pinnedSkillVersionIds) {

	public RouteContext(String tenantId, Long ownerAgentId, String ownerAgentType, Long sessionId, String userId,
			String runtimeRequestId, String query, String previousQuery, RouteTargetRef previousTarget,
			ExplicitRouteTarget explicitTarget, Instant deadline, int maxSelections) {
		this(tenantId, ownerAgentId, ownerAgentType, sessionId, userId, runtimeRequestId, query, previousQuery,
				previousTarget, explicitTarget, deadline, maxSelections, RouteScope.forOwnerType(ownerAgentType),
				null);
	}

	public RouteContext(String tenantId, Long ownerAgentId, String ownerAgentType, Long sessionId, String userId,
			String runtimeRequestId, String query, String previousQuery, RouteTargetRef previousTarget,
			ExplicitRouteTarget explicitTarget, Instant deadline, int maxSelections, RouteScope scope) {
		this(tenantId, ownerAgentId, ownerAgentType, sessionId, userId, runtimeRequestId, query, previousQuery,
				previousTarget, explicitTarget, deadline, maxSelections, scope, null);
	}

	public RouteScope effectiveScope() {
		return scope == null ? RouteScope.forOwnerType(ownerAgentType) : scope;
	}
}
