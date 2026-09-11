/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing.model;

public record RouteSelection(RouteTargetRef target, Long routeArtifactId, Long routeProfileId, RouteRisk risk,
		String sourceChecksum, DelegationMode delegationMode) {

	public RouteSelection(RouteTargetRef target, Long routeArtifactId, Long routeProfileId, RouteRisk risk,
			String sourceChecksum) {
		this(target, routeArtifactId, routeProfileId, risk, sourceChecksum, null);
	}

	public static RouteSelection from(RouteCandidate candidate) {
		return new RouteSelection(candidate.target(), candidate.routeArtifactId(), candidate.routeProfileId(),
				candidate.risk(), candidate.sourceChecksum(), candidate.delegationMode());
	}

}
