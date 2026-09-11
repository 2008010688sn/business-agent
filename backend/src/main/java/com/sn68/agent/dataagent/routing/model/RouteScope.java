/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.model;

import com.sn68.agent.dataagent.constant.AgentTypeConstant;

/** Runtime ownership boundary for route candidates. */
public enum RouteScope {

	SKILL_SCOPE,

	COLLABORATOR_SCOPE;

	public static RouteScope forOwnerType(String ownerAgentType) {
		return AgentTypeConstant.isOrchestrator(ownerAgentType) ? COLLABORATOR_SCOPE : SKILL_SCOPE;
	}

	public RouteTargetType targetType() {
		return this == COLLABORATOR_SCOPE ? RouteTargetType.COLLABORATOR : RouteTargetType.SKILL;
	}

}
