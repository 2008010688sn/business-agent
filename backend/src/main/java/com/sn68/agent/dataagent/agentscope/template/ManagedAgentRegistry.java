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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 智能体运行模板注册表：按 agentType 归一化索引全部 ManagedAgent 模板，缺失类型直接抛错暴露配置问题。
 */
@Component
public class ManagedAgentRegistry {

	private final Map<String, ManagedAgent> agentsByType;

	public ManagedAgentRegistry(List<ManagedAgent> managedAgents) {
		this.agentsByType = managedAgents.stream()
			.collect(Collectors.toUnmodifiableMap(agent -> normalize(agent.getAgentType()), Function.identity()));
	}

	public ManagedAgent getRequired() {
		return getRequired(AgentTypeConstant.DATA_ANALYSIS);
	}

	public ManagedAgent getRequired(String agentType) {
		String normalizedType = AgentTypeConstant.normalize(agentType);
		ManagedAgent managedAgent = this.agentsByType.get(normalize(normalizedType));
		if (managedAgent == null) {
			throw new IllegalStateException("智能体运行模板注册表缺少类型：" + normalizedType);
		}
		return managedAgent;
	}

	private String normalize(String agentType) {
		return agentType == null ? "" : agentType.toLowerCase(Locale.ROOT);
	}

}
