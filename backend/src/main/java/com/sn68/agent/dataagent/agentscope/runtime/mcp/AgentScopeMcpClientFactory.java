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

import com.sn68.agent.dataagent.entity.AgentMcpServer;
import com.sn68.agent.framework.commons.exception.CheckedException;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * AgentScopeMcpClient组件，封装 DataAgent 对应业务入口。
 */
@Component
@RequiredArgsConstructor
public class AgentScopeMcpClientFactory {

	private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(120);

	private static final Duration DEFAULT_INIT_TIMEOUT = Duration.ofSeconds(30);

	private final DiscoveryClient discoveryClient;

	/**
	 * 创建AgentScopeMcpClient。
	 */
	public McpClientWrapper create(AgentMcpServer server, Map<String, String> headers) {
		String endpoint = resolveEndpoint(server);
		String clientName = firstText(server.getServerCode(), server.getServiceName(), endpoint);
		McpClientBuilder builder = McpClientBuilder.create(clientName)
			.timeout(DEFAULT_REQUEST_TIMEOUT)
			.initializationTimeout(DEFAULT_INIT_TIMEOUT);
		String transportType = firstText(server.getTransportType(), "streamable-http").toLowerCase();
		if ("sse".equals(transportType)) {
			builder.sseTransport(endpoint);
		}
		else if ("stdio".equals(transportType)) {
			throw CheckedException.badRequest("MCP stdio transport is not supported in server runtime.");
		}
		else {
			builder.streamableHttpTransport(endpoint);
		}
		if (headers != null && !headers.isEmpty()) {
			builder.headers(headers);
		}
		return builder.buildSync();
	}

	/**
	 * 查询AgentScopeMcpClient。
	 */
	public String resolveEndpoint(AgentMcpServer server) {
		if (server == null) {
			throw CheckedException.badRequest("MCP server is not configured.");
		}
		String path = firstText(server.getEndpointPath(), "/mcp");
		if (StringUtils.hasText(server.getBaseUrl())) {
			return joinUrl(server.getBaseUrl(), path);
		}
		if (!StringUtils.hasText(server.getServiceName())) {
			throw CheckedException.badRequest("MCP server serviceName is required.");
		}
		List<ServiceInstance> instances = discoveryClient.getInstances(server.getServiceName());
		if (instances == null || instances.isEmpty()) {
			throw CheckedException.badRequest("MCP server service is not discovered: " + server.getServiceName());
		}
		URI uri = instances.get(0).getUri();
		if (uri == null) {
			throw CheckedException.badRequest("MCP server service URI is empty: " + server.getServiceName());
		}
		return joinUrl(uri.toString(), path);
	}

	private String joinUrl(String baseUrl, String path) {
		String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		String suffix = path.startsWith("/") ? path : "/" + path;
		return base + suffix;
	}

	private String firstText(String... values) {
		if (values == null) {
			return "";
		}
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value.trim();
			}
		}
		return "";
	}

}
