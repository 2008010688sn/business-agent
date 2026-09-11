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

import io.agentscope.core.model.Model;
import io.agentscope.core.message.ContentBlock;
import java.time.Duration;
import java.util.List;

/**
 * 单次智能体运行的完整输入：模型、系统/用户提示词、多模态内容块、超时与运行时扩展；
 * 构造时对集合与扩展做空值兜底，保证下游模板无需判空。
 */
public record AgentRunContext(String agentId, String threadId, Model model, String systemPrompt, String userPrompt,
		List<ContentBlock> userContentBlocks, Duration timeout, AgentRuntimeExtensions extensions) {

	public AgentRunContext {
		userContentBlocks = userContentBlocks == null ? List.of() : List.copyOf(userContentBlocks);
		extensions = extensions == null ? AgentRuntimeExtensions.empty() : extensions;
	}

}
