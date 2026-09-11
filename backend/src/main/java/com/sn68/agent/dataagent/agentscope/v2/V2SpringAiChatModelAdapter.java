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
package com.sn68.agent.dataagent.agentscope.v2;

import com.sn68.agent.dataagent.agentscope.service.AgentScopeModelFactory;
import io.agentscope.core.model.Model;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 将项目 Spring AI ChatModel 适配为 AgentScope 2.0 {@link Model}，不绑定 DashScope 扩展包。
 */
@RequiredArgsConstructor
public class V2SpringAiChatModelAdapter {

	private final AgentScopeModelFactory agentScopeModelFactory;

	public Model adapt(ChatModel chatModel, String modelName) {
		return agentScopeModelFactory.create(chatModel, modelName, Map.of());
	}

}
