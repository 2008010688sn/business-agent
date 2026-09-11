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
package com.sn68.agent.dataagent.prompt;

import com.sn68.agent.dataagent.constant.AgentTypeConstant;
import org.springframework.stereotype.Service;

/**
 * 智能体类型提示词加载服务：按 agentType 映射的模板名加载系统提示词，缺失时直接抛错暴露配置问题。
 */
@Service
public class AgentPromptTemplateService {

	public String loadTypePrompt(String agentType) {
		String promptName = AgentTypeConstant.promptName(agentType);
		try {
			return PromptLoader.loadPrompt(promptName, "md");
		}
		catch (RuntimeException ex) {
			throw new IllegalStateException("智能体类型提示词未配置：" + promptName, ex);
		}
	}

}
