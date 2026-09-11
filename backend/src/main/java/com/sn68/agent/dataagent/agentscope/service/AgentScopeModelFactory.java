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
package com.sn68.agent.dataagent.agentscope.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.context.DataAgentAsyncContextBridge;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.enums.ModelEndpointDialect;
import com.sn68.agent.dataagent.service.tokenusage.AgentTokenUsageContext;
import com.sn68.agent.dataagent.service.tokenusage.AgentTokenUsageService;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeToolMetrics;
import io.agentscope.core.model.Model;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * AgentScope 模型工厂：将 Spring AI ChatModel 适配为 AgentScope Model，
 * 按模型方言决定是否启用严格工具名校验，并注入 token 用量统计上下文。
 */
@Component
@RequiredArgsConstructor
public class AgentScopeModelFactory {

	private final ObjectMapper objectMapper;

	private final AgentTokenUsageService tokenUsageService;

	private final DataAgentAsyncContextBridge asyncContextBridge;

	public Model create(org.springframework.ai.chat.model.ChatModel chatModel, String modelName,
			Map<String, ToolCallback> toolCallbacks) {
		return new SpringAiAgentScopeModel(chatModel, modelName, toolCallbacks, objectMapper);
	}

	public Model create(org.springframework.ai.chat.model.ChatModel chatModel, String modelName,
			Map<String, ToolCallback> toolCallbacks, AgentTokenUsageContext usageContext) {
		return create(chatModel, modelName, toolCallbacks, usageContext, null);
	}

	public Model create(org.springframework.ai.chat.model.ChatModel chatModel, String modelName,
			Map<String, ToolCallback> toolCallbacks, AgentTokenUsageContext usageContext,
			AgentRuntimeToolMetrics runtimeToolMetrics) {
		return new SpringAiAgentScopeModel(chatModel, modelName, toolCallbacks, objectMapper, tokenUsageService,
				usageContext, asyncContextBridge, runtimeToolMetrics);
	}

	public Model create(org.springframework.ai.chat.model.ChatModel chatModel, ModelConfigDTO modelConfig,
			Map<String, ToolCallback> toolCallbacks, AgentTokenUsageContext usageContext,
			AgentRuntimeToolMetrics runtimeToolMetrics) {
		String modelName = modelConfig == null ? null : modelConfig.getModelName();
		return new SpringAiAgentScopeModel(chatModel, modelName, toolCallbacks, objectMapper, tokenUsageService,
				usageContext, asyncContextBridge, runtimeToolMetrics, requiresStrictToolNameValidation(modelConfig));
	}

	private boolean requiresStrictToolNameValidation(ModelConfigDTO modelConfig) {
		if (modelConfig == null) {
			return false;
		}
		return "deepseek".equalsIgnoreCase(modelConfig.getProvider())
				|| ModelEndpointDialect.DEEPSEEK_NATIVE.name().equals(modelConfig.getEndpointDialect());
	}

}
