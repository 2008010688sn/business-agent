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
package com.sn68.agent.dataagent.agentscope.tool;

import com.sn68.agent.dataagent.constant.AgentTypeConstant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

/**
 * 智能体工具可见性策略：按 agentType 过滤工具回调集合，只保留该类型允许使用的工具。
 */
@Service
public class AgentToolPolicyService {

	public Map<String, ToolCallback> filter(String agentType, Map<String, ToolCallback> callbacks) {
		if (callbacks == null || callbacks.isEmpty()) {
			return Map.of();
		}
		String normalizedType = AgentTypeConstant.normalize(agentType);
		Map<String, ToolCallback> filtered = new LinkedHashMap<>();
		callbacks.forEach((toolName, callback) -> {
			if (isAllowed(normalizedType, toolName)) {
				filtered.put(toolName, callback);
			}
		});
		return filtered;
	}

	private boolean isAllowed(String agentType, String toolName) {
		if (toolName == null) {
			return false;
		}
		// 生产链路上这条过滤是死代码：没有任何工具提供方会产出 "agent.collaborate"
		// （AgentModelToolName.normalize 会把点号换成下划线，isValid 也不接受点号），
		// 且编排智能体还会被下方 ORCHESTRATOR 分支无差别挡掉。只有单测自己 put 了这个名字。
		// AgentOrchestrationServiceImpl.collaborate 是内部编排 API，从来不是模型可见工具，勿据此当死代码删除。
		if ("agent.collaborate".equals(toolName)) {
			return false;
		}
		if (AgentModelToolName.isSkillActionTool(toolName)) {
			return false;
		}
		if (AgentTypeConstant.ORCHESTRATOR.equals(agentType)) {
			return false;
		}
		if (AgentTypeConstant.KNOWLEDGE_BASE.equals(agentType)) {
			return isKnowledgeTool(toolName) || isSkillTool(toolName) || isWebEvidenceTool(toolName);
		}
		if (AgentTypeConstant.CUSTOMER_SERVICE.equals(agentType)) {
			return isKnowledgeTool(toolName) || isSkillTool(toolName) || isCustomerServiceTool(toolName)
					|| isDataQueryTool(toolName) || isWebEvidenceTool(toolName);
		}
		return true;
	}

	private boolean isKnowledgeTool(String toolName) {
		return AgentModelToolName.isKnowledgeTool(toolName);
	}

	private boolean isSkillTool(String toolName) {
		return AgentModelToolName.isSkillTool(toolName);
	}

	private boolean isCustomerServiceTool(String toolName) {
		return toolName.startsWith("ticket.") || toolName.startsWith("work_order.") || toolName.startsWith("order.");
	}

	private boolean isDataQueryTool(String toolName) {
		return AgentModelToolName.isDataQueryTool(toolName);
	}

	private boolean isWebEvidenceTool(String toolName) {
		return AgentModelToolName.isWebEvidenceTool(toolName);
	}

}
