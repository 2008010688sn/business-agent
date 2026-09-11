/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing;

public class RouteUnavailableException extends RuntimeException {

	private final String reasonCode;

	public RouteUnavailableException(String reasonCode) {
		super(reasonCode == null ? "ROUTE_UNAVAILABLE" : reasonCode);
		this.reasonCode = reasonCode == null ? "ROUTE_UNAVAILABLE" : reasonCode;
	}

	public String reasonCode() {
		return reasonCode;
	}
}
