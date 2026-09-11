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

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

/**
 * FilteringMcpClientWrapper组件，封装 DataAgent 对应业务入口。
 */
public class FilteringMcpClientWrapper extends McpClientWrapper {

	private final McpClientWrapper delegate;

	private final Map<String, AgentScopeMcpToolGrant> grantsByAlias;

	public FilteringMcpClientWrapper(String name, McpClientWrapper delegate, List<AgentScopeMcpToolGrant> grants) {
		super(name);
		this.delegate = delegate;
		this.grantsByAlias = indexGrants(grants);
	}

	/**
	 * 创建FilteringMcpClientWrapper。
	 */
	@Override
	public Mono<Void> initialize() {
		return delegate.initialize()
			.then(Mono.defer(this::listTools))
			.doOnNext(tools -> {
				cachedTools.clear();
				for (McpSchema.Tool tool : tools) {
					cachedTools.put(tool.name(), tool);
				}
				initialized = true;
			})
			.then();
	}

	/**
	 * 查询FilteringMcpClientWrapper。
	 */
	@Override
	public Mono<List<McpSchema.Tool>> listTools() {
		Set<String> allowed = grantsByAlias.values()
			.stream()
			.map(AgentScopeMcpToolGrant::toolName)
			.collect(Collectors.toSet());
		return delegate.listTools()
			.map(tools -> tools.stream()
				.filter(tool -> tool != null && allowed.contains(tool.name()))
				.map(this::aliasTool)
				.toList());
	}

	/**
	 * 处理FilteringMcpClientWrapper。
	 */
	@Override
	public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments) {
		AgentScopeMcpToolGrant grant = grantsByAlias.get(toolName);
		if (grant == null) {
			return Mono.error(new IllegalArgumentException("MCP tool is not authorized: " + toolName));
		}
		return delegate.callTool(grant.toolName(), arguments);
	}

	@Override
	public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments,
			Map<String, Object> metadata) {
		AgentScopeMcpToolGrant grant = grantsByAlias.get(toolName);
		if (grant == null) {
			return Mono.error(new IllegalArgumentException("MCP tool is not authorized: " + toolName));
		}
		return delegate.callTool(grant.toolName(), arguments, metadata);
	}

	/**
	 * 处理FilteringMcpClientWrapper。
	 */
	@Override
	public void close() {
		delegate.close();
	}

	private McpSchema.Tool aliasTool(McpSchema.Tool tool) {
		AgentScopeMcpToolGrant grant = grantsByAlias.values()
			.stream()
			.filter(candidate -> candidate.toolName().equals(tool.name()))
			.findFirst()
			.orElse(null);
		if (grant == null) {
			return tool;
		}
		return new McpSchema.Tool(grant.alias(), tool.title(), tool.description(), tool.inputSchema(),
				tool.outputSchema(), tool.annotations(), tool.meta());
	}

	private Map<String, AgentScopeMcpToolGrant> indexGrants(List<AgentScopeMcpToolGrant> grants) {
		if (grants == null || grants.isEmpty()) {
			return Map.of();
		}
		Map<String, AgentScopeMcpToolGrant> indexed = new LinkedHashMap<>();
		for (AgentScopeMcpToolGrant grant : grants) {
			if (grant == null || !StringUtils.hasText(grant.serverCode()) || !StringUtils.hasText(grant.toolName())) {
				continue;
			}
			indexed.putIfAbsent(grant.alias(), grant);
		}
		return indexed;
	}

}
