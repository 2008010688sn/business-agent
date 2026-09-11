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
import com.sn68.agent.dataagent.prompt.AgentPromptTemplateService;
import org.springframework.stereotype.Component;

/**
 * 数据分析（通用）智能体模板：使用 DATA_ANALYSIS 提示词模板的 ReAct 运行模板。
 */
@Component
public class CommonAgent extends PromptBackedReActAgent {

	public static final String AGENT_TYPE = AgentTypeConstant.DATA_ANALYSIS;

	public CommonAgent(AgentPromptTemplateService promptTemplateService) {
		super(promptTemplateService);
	}

	@Override
	public String getAgentType() {
		return AGENT_TYPE;
	}

}
