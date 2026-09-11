/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.agentscope.runtime;

/**
 * The outbound model request violates the selected provider contract.
 */
public class AgentRuntimeModelRequestException extends RuntimeException {

	public AgentRuntimeModelRequestException(String message) {
		super(message);
	}

}
