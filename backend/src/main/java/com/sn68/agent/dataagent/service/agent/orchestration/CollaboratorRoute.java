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

import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.entity.AgentCollaborator;
import com.sn68.agent.dataagent.routing.model.DelegationMode;
import com.sn68.agent.dataagent.routing.model.RouteStepInputMapping;
import com.sn68.agent.dataagent.routing.model.RouteStepOutputBinding;
import java.util.List;

/**
 * 编排路由后的协作者任务。
 */
public record CollaboratorRoute(AgentCollaborator collaborator, DataAgent dataAgent, String task, String reason,
		String expectedOutput, String stepId, List<String> dependsOn, List<RouteStepOutputBinding> outputBindings,
		List<RouteStepInputMapping> inputMappings, String delegationMode) {

	public CollaboratorRoute {
		dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
		outputBindings = outputBindings == null ? List.of() : List.copyOf(outputBindings);
		inputMappings = inputMappings == null ? List.of() : List.copyOf(inputMappings);
		delegationMode = delegationMode == null ? null : DelegationMode.resolve(delegationMode).name();
	}

	public CollaboratorRoute(AgentCollaborator collaborator, DataAgent dataAgent, String task, String reason,
			String expectedOutput, String stepId, List<String> dependsOn, List<RouteStepOutputBinding> outputBindings,
			List<RouteStepInputMapping> inputMappings) {
		this(collaborator, dataAgent, task, reason, expectedOutput, stepId, dependsOn, outputBindings, inputMappings,
				null);
	}

	public CollaboratorRoute(AgentCollaborator collaborator, DataAgent dataAgent, String task, String reason,
			String expectedOutput, String stepId, List<String> dependsOn) {
		this(collaborator, dataAgent, task, reason, expectedOutput, stepId, dependsOn, List.of(), List.of());
	}

	public CollaboratorRoute(AgentCollaborator collaborator, DataAgent dataAgent, String task, String reason,
			String expectedOutput) {
		this(collaborator, dataAgent, task, reason, expectedOutput, null, List.of(), List.of(), List.of());
	}

	public Long collaboratorAgentId() {
		return collaborator == null ? null : collaborator.getCollaboratorAgentId();
	}

	public CollaboratorRoute withTask(String task, String reason, String expectedOutput) {
		return new CollaboratorRoute(collaborator, dataAgent, task, reason, expectedOutput, stepId, dependsOn,
				outputBindings, inputMappings, delegationMode);
	}

}
