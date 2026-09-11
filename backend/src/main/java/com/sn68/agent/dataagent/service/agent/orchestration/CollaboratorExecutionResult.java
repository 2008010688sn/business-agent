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
package com.sn68.agent.dataagent.service.agent.orchestration;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.service.routing.RoutePendingService.PendingInteraction;
import java.util.List;
import java.util.Map;

/**
 * 协作者执行结果。
 */
public record CollaboratorExecutionResult(CollaboratorRoute route, String answer, Throwable error, long durationMs,
		AgentRequest childRequest, PendingInteraction pendingInteraction, Map<String, Object> structuredOutput,
		List<String> alignmentNames) {

	public CollaboratorExecutionResult {
		structuredOutput = structuredOutput == null ? Map.of() : Map.copyOf(structuredOutput);
		alignmentNames = alignmentNames == null ? List.of() : List.copyOf(alignmentNames);
	}

	public CollaboratorExecutionResult(CollaboratorRoute route, String answer, Throwable error, long durationMs,
			AgentRequest childRequest, PendingInteraction pendingInteraction, Map<String, Object> structuredOutput) {
		this(route, answer, error, durationMs, childRequest, pendingInteraction, structuredOutput, List.of());
	}

	public CollaboratorExecutionResult(CollaboratorRoute route, String answer, Throwable error, long durationMs,
			AgentRequest childRequest, PendingInteraction pendingInteraction) {
		this(route, answer, error, durationMs, childRequest, pendingInteraction, Map.of(), List.of());
	}

	public CollaboratorExecutionResult(CollaboratorRoute route, String answer, Throwable error, long durationMs) {
		this(route, answer, error, durationMs, null, null, Map.of(), List.of());
	}

	public boolean success() {
		return error == null && pendingInteraction == null;
	}

	public boolean waiting() {
		return pendingInteraction != null;
	}

	public String errorMessage() {
		return error == null ? null : error.getMessage();
	}

}
