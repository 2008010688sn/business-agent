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

/**
 * MCP 工具授权项：记录某个 MCP 服务器工具被授予当前智能体使用的来源与资源键，
 * alias() 生成运行时注册用的统一工具别名。
 */
public record AgentScopeMcpToolGrant(String serverCode, String toolName, String resourceKey, String source) {

	public String alias() {
		return AgentScopeMcpToolName.alias(serverCode, toolName);
	}

}
