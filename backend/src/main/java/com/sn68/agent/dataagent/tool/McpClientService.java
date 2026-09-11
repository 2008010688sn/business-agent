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
package com.sn68.agent.dataagent.tool;

import com.sn68.agent.dataagent.dto.tool.McpToolCallResult;
import com.sn68.agent.dataagent.entity.AgentMcpServer;
import java.util.List;
import java.util.Map;

/**
 * MCPClient服务契约。
 */
public interface McpClientService {

	/**
	 * 查询MCPClient。
	 */
	List<Map<String, Object>> listTools(AgentMcpServer server);

	/**
	 * 处理MCPClient。
	 */
	McpToolCallResult callTool(AgentMcpServer server, String toolName, Map<String, Object> arguments);

}
