/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sn68.agent.dataagent.agentscope.runtime;

import lombok.Getter;

/**
 * Raised when an Agent runtime budget prevents further execution.
 */
@Getter
public class AgentRuntimeBudgetExceededException extends RuntimeException {

	private final Reason reason;

	private final long limit;

	private final long current;

	private final long attempted;

	public AgentRuntimeBudgetExceededException(Reason reason, long limit, long current, long attempted) {
		super("Agent runtime budget exceeded: reason=%s, limit=%d, current=%d, attempted=%d"
			.formatted(reason, limit, current, attempted));
		this.reason = reason;
		this.limit = limit;
		this.current = current;
		this.attempted = attempted;
	}

	public enum Reason {

		MODEL_CALLS,

		PROMPT_TOKENS,

		TOOL_CALLS

	}

}
