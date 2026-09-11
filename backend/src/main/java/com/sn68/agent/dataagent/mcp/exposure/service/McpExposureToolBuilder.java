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
package com.sn68.agent.dataagent.mcp.exposure.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.tool.skilltool.ExecutionResourceToolCallbackFactory;
import com.sn68.agent.dataagent.entity.AgentExecutionResource;
import com.sn68.agent.dataagent.mcp.exposure.entity.AgentMcpExposure;
import com.sn68.agent.dataagent.mcp.exposure.repository.AgentMcpExposureMapper;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 构建由 MCP 暴露配置管理的 ToolCallback。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpExposureToolBuilder {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final AgentMcpExposureMapper exposureMapper;

	private final AgentExecutionResourceMapper resourceMapper;

	private final ExecutionResourceToolCallbackFactory callbackFactory;

	private final ObjectMapper objectMapper;

	/**
	 * 读取当前有效配置并构建对外 Tool。
	 */
	public Map<String, ExposureTool> buildEnabledTools() {
		Map<String, ExposureTool> tools = new LinkedHashMap<>();
		for (AgentMcpExposure exposure : exposureMapper.findAllOrdered(null, "enabled")) {
			ExposureTool tool = build(exposure);
			if (tool == null) {
				continue;
			}
			ExposureTool duplicate = tools.putIfAbsent(tool.snapshot().toolName(), tool);
			if (duplicate != null) {
				log.error("跳过重复的 MCP 暴露 Tool Name，exposureCode={}, toolName={}",
						exposure.getExposureCode(), tool.snapshot().toolName());
			}
		}
		return tools;
	}

	private ExposureTool build(AgentMcpExposure exposure) {
		if (exposure == null || !"TOOL".equalsIgnoreCase(firstText(exposure.getExposureType(), "TOOL"))
				|| !StringUtils.hasText(exposure.getExposedToolName())) {
			return null;
		}
		AgentExecutionResource resource = resourceMapper.findEnabledByResourceKey(exposure.getToolKey());
		if (resource == null) {
			log.warn("跳过资源不存在或未启用的 MCP 暴露，exposureCode={}, toolKey={}", exposure.getExposureCode(),
					exposure.getToolKey());
			return null;
		}
		String toolName = exposure.getExposedToolName().trim();
		String description = description(exposure, resource);
		String inputSchema = inputSchema(exposure, resource);
		ExposureToolSnapshot snapshot = new ExposureToolSnapshot(exposure.getId(), exposure.getExposureCode(), toolName,
				description, inputSchema, resource.getResourceKey());
		ToolCallback delegate = callbackFactory.create(toolName, description, inputSchema, resource.getResourceKey());
		return new ExposureTool(snapshot, failClosed(snapshot, delegate));
	}

	private ToolCallback failClosed(ExposureToolSnapshot snapshot, ToolCallback delegate) {
		return new ToolCallback() {
			@Override
			public ToolDefinition getToolDefinition() {
				return delegate.getToolDefinition();
			}

			@Override
			public ToolMetadata getToolMetadata() {
				return delegate.getToolMetadata();
			}

			@Override
			public String call(String toolInput) {
				validateCurrent(snapshot);
				return delegate.call(toolInput);
			}

			@Override
			public String call(String toolInput, ToolContext toolContext) {
				validateCurrent(snapshot);
				return delegate.call(toolInput, toolContext);
			}
		};
	}

	private void validateCurrent(ExposureToolSnapshot snapshot) {
		AgentMcpExposure current = exposureMapper.selectById(snapshot.exposureId());
		boolean exposureEnabled = current != null && !Boolean.TRUE.equals(current.getDeleted())
				&& "enabled".equalsIgnoreCase(current.getStatus())
				&& "TOOL".equalsIgnoreCase(firstText(current.getExposureType(), "TOOL"))
				&& Objects.equals(snapshot.toolName(), trim(current.getExposedToolName()))
				&& Objects.equals(snapshot.resourceKey(), trim(current.getToolKey()));
		if (!exposureEnabled || resourceMapper.findEnabledByResourceKey(snapshot.resourceKey()) == null) {
			throw new IllegalStateException("MCP 暴露已停用、删除或资源不可用: " + snapshot.exposureCode());
		}
	}

	private String description(AgentMcpExposure exposure, AgentExecutionResource resource) {
		Map<String, Object> exposureConfig = readJsonObject(exposure.getExtConfig());
		Map<String, Object> resourceConfig = readJsonObject(resource.getExtConfig());
		return firstText(stringValue(exposureConfig.get("description")), stringValue(resourceConfig.get("description")),
				exposure.getExposureName(), resource.getResourceName(), "Call " + resource.getResourceKey());
	}

	private String inputSchema(AgentMcpExposure exposure, AgentExecutionResource resource) {
		Map<String, Object> exposureConfig = readJsonObject(exposure.getExtConfig());
		Map<String, Object> resourceConfig = readJsonObject(resource.getExtConfig());
		Object schema = exposureConfig.containsKey("inputSchema") ? exposureConfig.get("inputSchema")
				: resourceConfig.get("inputSchema");
		if (schema == null) {
			return null;
		}
		if (schema instanceof String text) {
			return text;
		}
		try {
			return objectMapper.writeValueAsString(schema);
		}
		catch (Exception ex) {
			log.debug("序列化 MCP 暴露输入 Schema 失败，exposureCode={}", exposure.getExposureCode(), ex);
			return null;
		}
	}

	private Map<String, Object> readJsonObject(String value) {
		if (!StringUtils.hasText(value)) {
			return Map.of();
		}
		try {
			Map<String, Object> map = objectMapper.readValue(value, MAP_TYPE);
			return map == null ? Map.of() : new LinkedHashMap<>(map);
		}
		catch (Exception ex) {
			log.debug("解析 MCP 暴露 JSON 失败", ex);
			return Map.of();
		}
	}

	private String stringValue(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private String trim(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
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

	public record ExposureToolSnapshot(Long exposureId, String exposureCode, String toolName, String description,
			String inputSchema, String resourceKey) {
	}

	public record ExposureTool(ExposureToolSnapshot snapshot, ToolCallback callback) {
	}

}
