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
package com.sn68.agent.dataagent.constant;

import com.sn68.agent.dataagent.enums.AgentType;

/**
 * 智能体类型编码常量（取值收敛自 {@link AgentType}，供只需字符串编码的场景使用）。
 */
public final class AgentTypeConstant {

	public static final String COMMON_AGENT = AgentType.LEGACY_COMMON_AGENT;

	public static final String DATA_ANALYSIS = AgentType.DATA_ANALYSIS.getCode();

	public static final String KNOWLEDGE_BASE = AgentType.KNOWLEDGE_BASE.getCode();

	public static final String CUSTOMER_SERVICE = AgentType.CUSTOMER_SERVICE.getCode();

	public static final String ORCHESTRATOR = AgentType.ORCHESTRATOR.getCode();

	private AgentTypeConstant() {
	}

	public static String normalize(String agentType) {
		return AgentType.normalizeCode(agentType);
	}

	public static String promptName(String agentType) {
		return AgentType.promptName(agentType);
	}

	public static boolean isOrchestrator(String agentType) {
		return AgentType.isOrchestrator(agentType);
	}

	public static boolean isDataAnalysis(String agentType) {
		return AgentType.isDataAnalysis(agentType);
	}

}
