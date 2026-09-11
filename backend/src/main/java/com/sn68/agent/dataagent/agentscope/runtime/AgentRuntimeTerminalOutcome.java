/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package com.sn68.agent.dataagent.agentscope.runtime;

/**
 * Final outcome of one agent runtime request.
 */
public enum AgentRuntimeTerminalOutcome {

	IN_PROGRESS,

	WAITING_CLARIFICATION,

	SUCCESS,

	BUSINESS_FAILED,

	TOOL_FAILED,

	MODEL_PROTOCOL_ERROR,

	RUNTIME_FAILED

}
