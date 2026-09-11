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

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;

/**
 * 本轮产品预算。挂在 {@code RuntimeContext} 上给信封读；token 来自
 * {@link AgentRequest#getMaxPromptTokens()}，轮次来自
 * {@link AgentRequest#getReactMaxIterations()}（平台 ∩ 智能体 ∩ 技能）。
 *
 * <p>产品 token 字段是 prompt-only 口径；信封按 input+output 使用同一数字，比旧 ReAct 闸门略紧。
 */
public record V2RequestBudget(long maxTokens, int maxIters) {

	public static V2RequestBudget from(AgentRequest request) {
		long tokens = 0L;
		int iters = 0;
		if (request != null && request.getMaxPromptTokens() != null && request.getMaxPromptTokens() > 0L) {
			tokens = request.getMaxPromptTokens();
		}
		if (request != null && request.getReactMaxIterations() != null && request.getReactMaxIterations() > 0) {
			iters = request.getReactMaxIterations();
		}
		return new V2RequestBudget(tokens, iters);
	}

	public boolean hasTokenLimit() {
		return maxTokens > 0L;
	}

	public boolean hasIterLimit() {
		return maxIters > 0;
	}

}
