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
package com.sn68.agent.dataagent.agentscope.template;

import com.sn68.agent.dataagent.constant.AgentTypeConstant;
import io.agentscope.core.message.Msg;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

class ManagedDataAgentRegistryTest {

	@Test
	void getRequired_selectsAgentByNormalizedType() {
		ManagedAgent dataAgent = stub(AgentTypeConstant.DATA_ANALYSIS);
		ManagedAgent orchestratorAgent = stub(AgentTypeConstant.ORCHESTRATOR);
		ManagedAgentRegistry registry = new ManagedAgentRegistry(List.of(dataAgent, orchestratorAgent));

		assertSame(dataAgent, registry.getRequired("commonagent"));
		assertSame(orchestratorAgent, registry.getRequired("ORCHESTRATOR"));
	}

	private ManagedAgent stub(String type) {
		return new ManagedAgent() {
			@Override
			public String getAgentType() {
				return type;
			}

			@Override
			public Msg run(AgentRunContext context) {
				return null;
			}
		};
	}

}
