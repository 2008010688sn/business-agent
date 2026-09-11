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

import com.sn68.agent.dataagent.prompt.AgentPromptTemplateService;
import com.sn68.agent.dataagent.service.security.UntrustedContentBoundary;
import io.agentscope.core.message.Msg;
import org.springframework.util.StringUtils;

/**
 * 基于提示词模板的 ReAct 智能体基类：按 agentType 加载系统提示词。
 * {@link #run} 已退役，ReAct 主循环必须走 HarnessAgentFactory。
 */
public abstract class PromptBackedReActAgent implements ManagedAgent {

	private final AgentPromptTemplateService promptTemplateService;

	protected PromptBackedReActAgent(AgentPromptTemplateService promptTemplateService) {
		this.promptTemplateService = promptTemplateService;
	}

	@Override
	public Msg run(AgentRunContext context) {
		throw new IllegalStateException("PromptBackedReActAgent.run is retired; ReAct must use HarnessAgentFactory");
	}

	public String resolveSystemPrompt(AgentRunContext context) {
		return defaultSystemPrompt(context.systemPrompt(), context.extensions().skillInstructions());
	}

	String defaultSystemPrompt(String systemPrompt, String skillInstructions) {
		// 指令层级声明放在最后追加，而不是写进各智能体的 prompts/*.md：
		// 声明与包裹标记同源于 UntrustedContentBoundary，两者不会随提示词各自演进而漂移，
		// 也保证 Agent 自定义提示词与 Skill 指令无法把这条边界规则改掉。
		return UntrustedContentBoundary
			.appendInstructionHierarchyRule(mergeSystemPrompt(systemPrompt, skillInstructions));
	}

	private String mergeSystemPrompt(String systemPrompt, String skillInstructions) {
		String basePrompt = mergePrompt(promptTemplateService.loadTypePrompt(getAgentType()), systemPrompt);
		String runtimeSkillPrompt = StringUtils.hasText(skillInstructions) ? skillInstructions.trim() : "";
		if (!StringUtils.hasText(basePrompt)) {
			return runtimeSkillPrompt;
		}
		if (!StringUtils.hasText(runtimeSkillPrompt)) {
			return basePrompt;
		}
		return basePrompt + System.lineSeparator() + System.lineSeparator() + runtimeSkillPrompt;
	}

	private String mergePrompt(String fixedPrompt, String customPrompt) {
		String left = StringUtils.hasText(fixedPrompt) ? fixedPrompt.trim() : "";
		String right = StringUtils.hasText(customPrompt) ? customPrompt.trim() : "";
		if (!StringUtils.hasText(left)) {
			return right;
		}
		if (!StringUtils.hasText(right)) {
			return left;
		}
		return left + System.lineSeparator() + System.lineSeparator() + right;
	}

}
