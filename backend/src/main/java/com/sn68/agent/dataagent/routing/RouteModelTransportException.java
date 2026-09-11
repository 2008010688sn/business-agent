/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing;

/**
 * Classifies a failed upstream route-model call without retaining provider error
 * bodies, prompts, or reasoning text.
 */
public class RouteModelTransportException extends RuntimeException {

	private final FailureKind failureKind;

	private final Integer httpStatus;

	private final String failureCode;

	public RouteModelTransportException(FailureKind failureKind, String message, Throwable cause) {
		this(failureKind, null, null, message, cause);
	}

	public RouteModelTransportException(FailureKind failureKind, Integer httpStatus, String message, Throwable cause) {
		this(failureKind, httpStatus, null, message, cause);
	}

	public RouteModelTransportException(FailureKind failureKind, Integer httpStatus, String failureCode, String message,
			Throwable cause) {
		super(message, cause);
		this.failureKind = failureKind;
		this.httpStatus = httpStatus;
		this.failureCode = failureCode;
	}

	public FailureKind failureKind() {
		return failureKind;
	}

	public Integer httpStatus() {
		return httpStatus;
	}

	public String failureCode() {
		return failureCode;
	}

	public enum FailureKind {

		PROTOCOL_REJECTED,

		UNAVAILABLE,

		FAILED

	}

}
