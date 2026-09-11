/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing;

public class RouteStageException extends RuntimeException {

	private final String reasonCode;

	public RouteStageException(String reasonCode, String message) {
		super(message);
		this.reasonCode = reasonCode;
	}

	public RouteStageException(String reasonCode, String message, Throwable cause) {
		super(message, cause);
		this.reasonCode = reasonCode;
	}

	public String reasonCode() {
		return reasonCode;
	}

}
