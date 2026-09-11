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
package com.sn68.agent.dataagent.agentscope.tool.webfetch;

import com.sn68.agent.dataagent.agentscope.tool.AgentModelToolName;
import com.sn68.agent.dataagent.agentscope.tool.SkillResourceToolProvider;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.skill.execution.SkillVersionResources;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * 技能 ReAct 目录不走 Toolkit 工厂。按当前 {@code fetch-enabled} 决定是否挂上同一 web_fetch Bean。
 */
@Component
@RequiredArgsConstructor
public class WebFetchToolProvider implements SkillResourceToolProvider {

	private final WebFetchToolCallback webFetchToolCallback;

	private final DataAgentProperties dataAgentProperties;

	@Override
	public Map<String, ToolCallback> getSkillToolCallbacks(SkillVersionResources resources) {
		if (dataAgentProperties == null || dataAgentProperties.getWebEvidence() == null
				|| !dataAgentProperties.getWebEvidence().isFetchEnabled()) {
			return Map.of();
		}
		return Map.of(AgentModelToolName.WEB_FETCH, webFetchToolCallback);
	}

}
