/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing.model;

import java.util.List;

public record RouteModelResult(RouteDecisionType decision, List<RouteTargetRef> targets, double confidence,
		RoutePlan plan, String clarificationQuestion) {

	public RouteModelResult(RouteDecisionType decision, List<RouteTargetRef> targets, double confidence) {
		this(decision, targets, confidence, RoutePlan.empty(), null);
	}

	public RouteModelResult {
		targets = targets == null ? List.of() : List.copyOf(targets);
	}

}
