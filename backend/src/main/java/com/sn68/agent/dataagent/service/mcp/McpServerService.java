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
package com.sn68.agent.dataagent.service.mcp;

import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.repository.DataAgentMapper;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import lombok.AllArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * MCP Server 工具集：把智能体查询等能力以 {@code @Tool} 形式暴露给 MCP 客户端。
 */
@Service
@AllArgsConstructor
public class McpServerService {

	private final DataAgentMapper dataAgentMapper;

	private final AuthenticationContext authenticationContext;

	public record AgentListRequest(String status, String keyword) {
	}

	@Tool(description = "查询智能体列表，支持按状态和关键词过滤。可以根据智能体的状态（如已发布PUBLISHED、草稿DRAFT等）进行过滤，也可以通过关键词搜索智能体的名称、描述或标签。返回按创建时间降序排列的智能体列表。")
	public List<DataAgent> listAgentsToolCallback(AgentListRequest agentListRequest) {
		String tenantId;
		try {
			tenantId = authenticationContext.tenantId();
		}
		catch (Exception ex) {
			return List.of();
		}
		if (!StringUtils.hasText(tenantId)) {
			return List.of();
		}
		return dataAgentMapper.findByConditions(agentListRequest.status(), agentListRequest.keyword(), tenantId);
	}

}
