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
import com.sn68.agent.dataagent.agentscope.runtime.mcp.AgentScopeMcpClientFactory;
import com.sn68.agent.dataagent.agentscope.runtime.mcp.DataAgentMcpHeaderProvider;
import com.sn68.agent.dataagent.dto.tool.McpToolCallResult;
import com.sn68.agent.dataagent.entity.AgentMcpServer;
import com.sn68.agent.framework.commons.exception.CheckedException;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
/**
 * MCP 客户端服务：按 MCP Server 配置建立连接、列出/调用远端工具，并做调用结果的统一转换。
 */
@Service
@RequiredArgsConstructor
public class McpClientServiceImpl implements McpClientService {

	private static final TypeReference<Map<String, Object>> JSON_MAP_TYPE = new TypeReference<>() {
	};

	private static final TypeReference<Object> JSON_VALUE_TYPE = new TypeReference<>() {
	};

	private final AgentScopeMcpClientFactory clientFactory;

	private final DataAgentMcpHeaderProvider headerProvider;

	private final ObjectMapper objectMapper;

	@Override
	public List<Map<String, Object>> listTools(AgentMcpServer server) {
		try (McpClientWrapper client = clientFactory.create(server, headerProvider.effectiveHeaders())) {
			client.initialize().block();
			List<McpSchema.Tool> tools = client.listTools().block();
			return tools == null ? List.of() : tools.stream().map(this::toolToMap).toList();
		}
		catch (Exception ex) {
			log.warn("MCP list tools failed. serverCode={}", server == null ? null : server.getServerCode(), ex);
			throw CheckedException.badRequest("MCP server is unavailable.");
		}
	}

	@Override
	public McpToolCallResult callTool(AgentMcpServer server, String toolName, Map<String, Object> arguments) {
		if (!StringUtils.hasText(toolName)) {
			throw CheckedException.badRequest("MCP tool name is required.");
		}
		try (McpClientWrapper client = clientFactory.create(server, headerProvider.effectiveHeaders())) {
			client.initialize().block();
			McpSchema.CallToolResult result = client.callTool(toolName.trim(), arguments == null ? Map.of() : arguments)
				.block();
			return callResult(result);
		}
		catch (Exception ex) {
			log.warn("MCP call tool failed. serverCode={}, toolName={}",
					server == null ? null : server.getServerCode(), toolName, ex);
			throw new CheckedException("MCP tool execution failed.", ex);
		}
	}

	private Map<String, Object> toolToMap(McpSchema.Tool tool) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("name", tool.name());
		data.put("title", tool.title());
		data.put("description", tool.description());
		data.put("inputSchema", jsonValue(tool.inputSchema()));
		data.put("outputSchema", tool.outputSchema());
		if (tool.annotations() != null) {
			Map<String, Object> annotations = annotationsToMap(tool.annotations());
			data.put("annotations", annotations);
			data.put("readOnlyHint", annotations.get("readOnlyHint"));
			data.put("destructiveHint", annotations.get("destructiveHint"));
		}
		if (tool.meta() != null) {
			data.put("_meta", tool.meta());
		}
		return data;
	}

	private Map<String, Object> annotationsToMap(McpSchema.ToolAnnotations annotations) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("title", annotations.title());
		data.put("readOnlyHint", annotations.readOnlyHint());
		data.put("destructiveHint", annotations.destructiveHint());
		data.put("idempotentHint", annotations.idempotentHint());
		data.put("openWorldHint", annotations.openWorldHint());
		data.put("returnDirect", annotations.returnDirect());
		return data;
	}

	private McpToolCallResult callResult(McpSchema.CallToolResult result) {
		if (result == null) {
			return new McpToolCallResult(false, true, "MCP tool returned empty result.", Map.of());
		}
		boolean toolError = Boolean.TRUE.equals(result.isError());
		Map<String, Object> data = normalizeCallResult(result);
		String message = firstText(stringValue(data.get("message")), stringValue(data.get("text")),
				toolError ? "MCP tool execution failed." : "MCP tool executed.");
		return new McpToolCallResult(!toolError, toolError, message, data);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> normalizeCallResult(McpSchema.CallToolResult result) {
		Map<String, Object> data = new LinkedHashMap<>();
		Object structuredContent = result.structuredContent();
		if (structuredContent instanceof Map<?, ?> map) {
			data.putAll((Map<String, Object>) map);
		}
		else if (structuredContent instanceof List<?> list) {
			data.put("items", list);
		}
		if (result.content() != null && !result.content().isEmpty()) {
			data.put("content", objectMapper.convertValue(result.content(), List.class));
			result.content()
				.stream()
				.filter(McpSchema.TextContent.class::isInstance)
				.map(McpSchema.TextContent.class::cast)
				.map(McpSchema.TextContent::text)
				.filter(StringUtils::hasText)
				.findFirst()
				.ifPresent(text -> mergeTextContent(data, text));
		}
		data.put("isError", result.isError());
		if (result.meta() != null) {
			data.put("_meta", result.meta());
		}
		return data;
	}

	private void mergeTextContent(Map<String, Object> data, String text) {
		data.putIfAbsent("text", text);
		Object textData = parseJsonValue(text);
		if (textData instanceof Map<?, ?> map) {
			map.forEach((key, value) -> data.putIfAbsent(String.valueOf(key), value));
		}
		else if (textData instanceof List<?> list) {
			data.putIfAbsent("items", list);
		}
	}

	private Object parseJsonValue(String text) {
		if (!StringUtils.hasText(text)) {
			return null;
		}
		String trimmed = text.trim();
		if (!(trimmed.startsWith("{") && trimmed.endsWith("}"))
				&& !(trimmed.startsWith("[") && trimmed.endsWith("]"))) {
			return null;
		}
		try {
			return objectMapper.readValue(trimmed, JSON_VALUE_TYPE);
		}
		catch (Exception ex) {
			log.debug("Failed to parse MCP text content as JSON", ex);
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> jsonValue(Object value) {
		if (value == null) {
			return Map.of();
		}
		if (value instanceof Map<?, ?> map) {
			return (Map<String, Object>) map;
		}
		return objectMapper.convertValue(value, Map.class);
	}

	private String stringValue(Object value) {
		return value == null ? null : String.valueOf(value);
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
