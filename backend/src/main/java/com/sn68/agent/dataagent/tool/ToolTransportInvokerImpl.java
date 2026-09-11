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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRequestSnapshotSupport;
import com.sn68.agent.dataagent.dto.tool.McpToolCallResult;
import com.sn68.agent.dataagent.entity.AgentExecutionResource;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceMapper;
import com.sn68.agent.dataagent.entity.AgentMcpServer;
import com.sn68.agent.dataagent.repository.AgentMcpServerMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
/**
 * 工具传输层调用器：按资源类型（MCP 工具/HTTP/内部资源等）路由到对应传输通道并执行实际调用。
 */
@Service
@RequiredArgsConstructor
public class ToolTransportInvokerImpl implements ToolTransportInvoker {

	private static final String RESOURCE_MCP_TOOL = "MCP_TOOL";

	private static final String RESOURCE_INTERNAL_API = "INTERNAL_API";

	private static final String RESOURCE_HTTP = "HTTP";

	private static final String RESOURCE_WEBHOOK = "WEBHOOK";

	private static final String RESOURCE_DATA_QUERY = "DATA_QUERY";

	private static final ParameterizedTypeReference<Map<String, Object>> HTTP_MAP_TYPE = new ParameterizedTypeReference<>() {
	};

	private static final TypeReference<Map<String, Object>> JSON_MAP_TYPE = new TypeReference<>() {
	};

	private final AgentExecutionResourceMapper resourceMapper;

	private final AgentMcpServerMapper mcpServerMapper;

	private final McpClientService mcpClientService;

	private final McpToolArgumentNormalizer mcpToolArgumentNormalizer;

	private final DataQueryToolService dataQueryToolService;

	private final WebClient.Builder webClientBuilder;

	private final DiscoveryClient discoveryClient;

	private final ObjectMapper objectMapper;

	private final AuthenticationContext authenticationContext;

	@Override
	public Map<String, Object> invoke(String resourceKey, Map<String, Object> arguments) {
		AgentExecutionResource resource = resourceMapper.findEnabledByResourceKey(resourceKey);
		if (resource == null) {
			throw CheckedException.notFound("Execution resource is disabled or does not exist: " + resourceKey);
		}
		return invokeResource(resource, arguments);
	}

	@Override
	public Map<String, Object> invoke(AgentExecutionResource resourceSnapshot, Map<String, Object> arguments) {
		if (resourceSnapshot == null || !StringUtils.hasText(resourceSnapshot.getResourceKey())) {
			throw CheckedException.badRequest("Published execution resource snapshot is invalid");
		}
		AgentExecutionResource current = resourceMapper.findEnabledByResourceKey(resourceSnapshot.getResourceKey());
		if (current == null) {
			throw CheckedException.notFound(
					"Execution resource is disabled or does not exist: " + resourceSnapshot.getResourceKey());
		}
		return invokeResource(resourceSnapshot, arguments);
	}

	private Map<String, Object> invokeResource(AgentExecutionResource resource, Map<String, Object> arguments) {
		Map<String, Object> safeArguments = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
		if (RESOURCE_MCP_TOOL.equalsIgnoreCase(resource.getResourceType())) {
			return invokeMcp(resource, safeArguments);
		}
		if (isHttpResource(resource)) {
			return invokeHttp(resource, safeArguments);
		}
		if (RESOURCE_DATA_QUERY.equalsIgnoreCase(resource.getResourceType())) {
			return dataQueryToolService.query(resource, safeArguments);
		}
		throw CheckedException.badRequest("Unsupported execution resource type: " + resource.getResourceType());
	}

	private Map<String, Object> invokeMcp(AgentExecutionResource resource, Map<String, Object> arguments) {
		AgentMcpServer server = mcpServerMapper.findByServerCode(resource.getServerCode());
		if (server == null || !"enabled".equalsIgnoreCase(server.getStatus())) {
			throw CheckedException.badRequest("MCP Server is unavailable.");
		}
		Map<String, Object> normalizedArguments = mcpToolArgumentNormalizer.normalize(resource, arguments);
		McpToolCallResult result = mcpClientService.callTool(server, resource.getToolName(), normalizedArguments);
		Map<String, Object> data = new LinkedHashMap<>(result.data() == null ? Map.of() : result.data());
		data.putIfAbsent("success", result.success());
		data.put("mcpSuccess", result.success());
		data.put("toolError", result.toolError());
		data.put("message", result.message());
		data.put("resourceType", resource.getResourceType());
		data.put("resourceKey", resource.getResourceKey());
		data.put("serverCode", resource.getServerCode());
		data.put("toolName", resource.getToolName());
		return data;
	}

	private Map<String, Object> invokeHttp(AgentExecutionResource resource, Map<String, Object> arguments) {
		String endpoint = resolveHttpEndpoint(resource);
		HttpMethod method = HttpMethod.valueOf(firstText(resource.getHttpMethod(), "POST").toUpperCase());
		Map<String, Object> requestBody = buildRequestBody(resource, arguments);
		WebClient.RequestBodySpec request = webClientBuilder.clone()
			.build()
			.method(method)
			.uri(endpoint)
			.accept(MediaType.APPLICATION_JSON)
			.contentType(MediaType.APPLICATION_JSON)
			.headers(headers -> {
				readJsonObject(resource.getHeaderTemplate()).forEach((key, value) -> {
					if (StringUtils.hasText(key) && value != null) {
						headers.set(key, String.valueOf(value));
					}
				});
				appendUserHeaders(headers::set, arguments);
				if (StringUtils.hasText(resource.getCredentialRef())) {
					headers.set("X-Credential-Ref", resource.getCredentialRef());
				}
			});
		Map<String, Object> response = method == HttpMethod.GET || method == HttpMethod.DELETE
				? request.retrieve().bodyToMono(HTTP_MAP_TYPE).block()
				: request.bodyValue(requestBody).retrieve().bodyToMono(HTTP_MAP_TYPE).block();
		Map<String, Object> data = new LinkedHashMap<>(response == null ? Map.of() : response);
		data.putIfAbsent("resourceType", resource.getResourceType());
		data.putIfAbsent("resourceKey", resource.getResourceKey());
		return data;
	}

	private Map<String, Object> buildRequestBody(AgentExecutionResource resource, Map<String, Object> arguments) {
		Map<String, Object> template = readJsonObject(resource.getRequestTemplate());
		if (template.isEmpty()) {
			return arguments;
		}
		Map<String, Object> body = new LinkedHashMap<>(template);
		body.putAll(arguments);
		return body;
	}

	private String resolveHttpEndpoint(AgentExecutionResource resource) {
		String endpointUrl = firstText(resource.getEndpointUrl());
		if (!StringUtils.hasText(endpointUrl)) {
			throw CheckedException.badRequest("Execution resource URL/path is required.");
		}
		if (isAbsoluteUrl(endpointUrl)) {
			return endpointUrl;
		}
		String baseUrl = firstText(resource.getBaseUrl());
		if (!StringUtils.hasText(baseUrl) && StringUtils.hasText(resource.getServerCode())) {
			AgentMcpServer server = mcpServerMapper.findByServerCode(resource.getServerCode());
			baseUrl = server == null ? null : firstText(server.getBaseUrl());
			if (!StringUtils.hasText(baseUrl) && server != null) {
				baseUrl = resolveServiceBaseUrl(server.getServiceName());
			}
		}
		if (!StringUtils.hasText(baseUrl) && StringUtils.hasText(resource.getServiceName())) {
			baseUrl = resolveServiceBaseUrl(resource.getServiceName());
		}
		if (!StringUtils.hasText(baseUrl)) {
			throw CheckedException.badRequest("Execution resource baseUrl or serviceName is required.");
		}
		return joinUrl(baseUrl, endpointUrl);
	}

	private String resolveServiceBaseUrl(String serviceName) {
		if (!StringUtils.hasText(serviceName)) {
			return null;
		}
		List<ServiceInstance> instances = discoveryClient.getInstances(serviceName);
		if (instances == null || instances.isEmpty()) {
			throw CheckedException.badRequest("Service is not discovered: " + serviceName);
		}
		URI uri = instances.get(0).getUri();
		return uri == null ? null : uri.toString();
	}

	private void appendUserHeaders(HeaderWriter writer, Map<String, Object> arguments) {
		try {
			String snapshotUserId = AgentRequestSnapshotSupport.userId(arguments);
			String snapshotTenantId = AgentRequestSnapshotSupport.tenantId(arguments);
			String snapshotTenantCode = AgentRequestSnapshotSupport.tenantCode(arguments);
			String snapshotClientId = AgentRequestSnapshotSupport.clientId(arguments);
			List<String> snapshotTeamIds = AgentRequestSnapshotSupport.teamIds(arguments, objectMapper);
			if (StringUtils.hasText(snapshotUserId)) {
				putHeader(writer, "X-AI-User-Id", snapshotUserId);
				putHeader(writer, "X-AI-Tenant-Id", snapshotTenantId);
				putHeader(writer, "X-AI-Tenant-Code", snapshotTenantCode);
				putHeader(writer, "X-AI-Client-Id", snapshotClientId);
				putHeader(writer, "X-AI-Team-Ids", snapshotTeamIds == null ? null : String.join(",", snapshotTeamIds));
				return;
			}
			if (authenticationContext == null || authenticationContext.anonymous()) {
				return;
			}
			putHeader(writer, "X-AI-User-Id", authenticationContext.userId());
			putHeader(writer, "X-AI-Tenant-Id", authenticationContext.tenantId());
			putHeader(writer, "X-AI-Tenant-Code", authenticationContext.tenantCode());
			putHeader(writer, "X-AI-Client-Id", authenticationContext.clientId());
		}
		catch (Exception ex) {
			log.debug("Failed to append execution resource user headers", ex);
		}
	}

	private void putHeader(HeaderWriter writer, String key, String value) {
		if (StringUtils.hasText(value)) {
			writer.set(key, value);
		}
	}

	private boolean isHttpResource(AgentExecutionResource resource) {
		String type = resource == null ? null : resource.getResourceType();
		return RESOURCE_INTERNAL_API.equalsIgnoreCase(type) || RESOURCE_HTTP.equalsIgnoreCase(type)
				|| RESOURCE_WEBHOOK.equalsIgnoreCase(type);
	}

	private boolean isAbsoluteUrl(String value) {
		return StringUtils.hasText(value) && (value.startsWith("http://") || value.startsWith("https://"));
	}

	private String joinUrl(String baseUrl, String path) {
		String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		String suffix = path.startsWith("/") ? path : "/" + path;
		return base + suffix;
	}

	private Map<String, Object> readJsonObject(String value) {
		if (!StringUtils.hasText(value)) {
			return Map.of();
		}
		try {
			Map<String, Object> map = objectMapper.readValue(value, JSON_MAP_TYPE);
			return map == null ? Map.of() : map;
		}
		catch (Exception ex) {
			log.debug("Failed to parse execution resource JSON config", ex);
			return Map.of();
		}
	}

	private String firstText(String... values) {
		if (values == null) {
			return null;
		}
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value.trim();
			}
		}
		return null;
	}

	private interface HeaderWriter {

		void set(String key, String value);

	}

}
