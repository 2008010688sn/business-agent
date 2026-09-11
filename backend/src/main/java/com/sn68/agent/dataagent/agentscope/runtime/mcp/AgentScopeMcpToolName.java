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
package com.sn68.agent.dataagent.agentscope.runtime.mcp;

import com.sn68.agent.dataagent.agentscope.tool.AgentModelToolName;

/**
 * MCP 工具别名工具类：统一生成"服务器编码 + 工具名"的运行时注册别名，避免多服务器同名工具冲突。
 */
public final class AgentScopeMcpToolName {

	private AgentScopeMcpToolName() {
	}

	public static String alias(String serverCode, String toolName) {
		return AgentModelToolName.mcp(serverCode, toolName);
	}

}
