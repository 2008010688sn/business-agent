/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.agentscope.runtime;

/**
 * Raised when the model produces neither a native tool call nor a public answer.
 */
public class AgentRuntimeProtocolException extends RuntimeException {

	private final String errorCode;

	public AgentRuntimeProtocolException(String message) {
		this(message, AgentRuntimeErrorCode.MODEL_PROTOCOL_ERROR.getValue());
	}

	public AgentRuntimeProtocolException(String message, String errorCode) {
		super(message);
		this.errorCode = errorCode;
	}

	public String errorCode() {
		return errorCode;
	}

}
