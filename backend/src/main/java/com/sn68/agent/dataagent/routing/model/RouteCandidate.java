/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing.model;

public record RouteCandidate(RouteTargetRef target, String tenantId, Long ownerAgentId, String name,
		String description, String skillKind, String executionMode, RouteRules rules, RouteRisk risk, int priority,
		Long routeProfileId, Long routeArtifactId, String sourceChecksum, String embeddingFingerprint,
		DelegationMode delegationMode) {

	public RouteCandidate(RouteTargetRef target, String tenantId, Long ownerAgentId, String name,
			String description, String skillKind, String executionMode, RouteRules rules, RouteRisk risk, int priority,
			Long routeProfileId, Long routeArtifactId, String sourceChecksum, String embeddingFingerprint) {
		this(target, tenantId, ownerAgentId, name, description, skillKind, executionMode, rules, risk, priority,
				routeProfileId, routeArtifactId, sourceChecksum, embeddingFingerprint, null);
	}

	public RouteCandidate {
		rules = rules == null ? RouteRules.empty() : rules;
		risk = risk == null ? RouteRisk.UNKNOWN : risk;
		if (target != null && target.targetType() == RouteTargetType.COLLABORATOR && delegationMode == null) {
			delegationMode = DelegationMode.INTERACTIVE;
		}
	}

}
