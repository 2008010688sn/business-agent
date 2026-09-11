/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing;

/**
 * Safe result of a route-model capability probe. No upstream response or prompt
 * content is retained here.
 */
public record RouteModelProbeResult(RouteCapabilityState state, RouteModelOutputProtocol protocol, long latencyMs,
		String failureCode) {

	public boolean supported() {
		return state == RouteCapabilityState.SUPPORTED && protocol != null
				&& protocol != RouteModelOutputProtocol.NONE;
	}

}
