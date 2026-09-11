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
package com.sn68.agent.dataagent.agentscope.tool.skilltool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRequestSnapshotSupport;
import com.sn68.agent.dataagent.agentscope.runtime.ToolContextRequestResolver;
import com.sn68.agent.dataagent.tool.ToolTransportInvoker;
import com.sn68.agent.dataagent.service.interaction.SuggestedReplyProjectionService;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 把已发布的执行资源版本（HTTP/Feign 等工具定义）装配为 Spring AI ToolCallback：
 * 按资源声明生成输入 Schema 与描述，调用经 ToolTransportInvoker 统一透传与审计。
 */
@Component
@RequiredArgsConstructor
public class ExecutionResourceToolCallbackFactory {

	private static final String DEFAULT_INPUT_SCHEMA = """
			{"type":"object","additionalProperties":true}
			""";

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final ToolTransportInvoker toolTransportInvoker;

	private final SuggestedReplyProjectionService suggestedReplyProjectionService;

	private final ObjectMapper objectMapper;

	public ToolCallback create(String toolName, String description, String inputSchema, String resourceKey) {
		return create(toolName, description, inputSchema, resourceKey, null, null);
	}

	public ToolCallback create(String toolName, String description, String inputSchema, String resourceKey,
			String agentId, String skillId) {
		return create(toolName, description, inputSchema, resourceKey, agentId, skillId, false, null);
	}

	public ToolCallback create(String toolName, String description, String inputSchema, String resourceKey,
			String agentId, String skillId, boolean candidateQuery, String candidateTitle) {
		ToolDefinition definition = ToolDefinition.builder()
			.name(toolName)
			.description(firstText(description, "Call execution resource " + resourceKey))
			.inputSchema(firstText(inputSchema, DEFAULT_INPUT_SCHEMA))
			.build();
		return new ExecutionResourceToolCallback(definition, resourceKey, agentId, skillId, toolTransportInvoker,
				suggestedReplyProjectionService, objectMapper, candidateQuery, candidateTitle);
	}

	public String safeToolName(String prefix, String value) {
		String normalized = firstText(value, "resource").toLowerCase().replaceAll("[^a-z0-9]+", "_");
		normalized = normalized.replaceAll("(^_+|_+$)", "");
		return prefix + "." + (StringUtils.hasText(normalized) ? normalized : "resource");
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

	private static final class ExecutionResourceToolCallback implements ToolCallback {

		private final ToolDefinition definition;

		private final String resourceKey;

		private final String agentId;

		private final String skillId;

		private final ToolTransportInvoker toolTransportInvoker;

		private final SuggestedReplyProjectionService suggestedReplyProjectionService;

		private final ObjectMapper objectMapper;

		private final boolean candidateQuery;

		private final String candidateTitle;

		private ExecutionResourceToolCallback(ToolDefinition definition, String resourceKey, String agentId,
				String skillId,
				ToolTransportInvoker toolTransportInvoker,
				SuggestedReplyProjectionService suggestedReplyProjectionService, ObjectMapper objectMapper,
				boolean candidateQuery, String candidateTitle) {
			this.definition = definition;
			this.resourceKey = resourceKey;
			this.agentId = agentId;
			this.skillId = skillId;
			this.toolTransportInvoker = toolTransportInvoker;
			this.suggestedReplyProjectionService = suggestedReplyProjectionService;
			this.objectMapper = objectMapper;
			this.candidateQuery = candidateQuery;
			this.candidateTitle = candidateTitle;
		}

		@Override
		public ToolDefinition getToolDefinition() {
			return definition;
		}

		@Override
		public String call(String toolInput) {
			return invoke(toolInput, null);
		}

		@Override
		public String call(String toolInput, ToolContext toolContext) {
			return invoke(toolInput, toolContext);
		}

		private String invoke(String toolInput, ToolContext toolContext) {
			try {
				Map<String, Object> arguments = StringUtils.hasText(toolInput)
						? objectMapper.readValue(toolInput, MAP_TYPE) : Map.of();
				Map<String, Object> enrichedArguments = enrichArguments(arguments, toolContext);
				Map<String, Object> result = new LinkedHashMap<>(
						toolTransportInvoker.invoke(resourceKey, enrichedArguments));
				if (candidateQuery) {
					result = suggestedReplyProjectionService.buildCandidateQueryResult(
							SuggestedReplyProjectionService.SOURCE_AGENT_RUNTIME,
							"skill-candidates-" + firstText(skillId, resourceKey), candidateTitle, resourceKey, result,
							arguments);
				}
				return objectMapper.writeValueAsString(result);
			}
			catch (Exception ex) {
				throw new IllegalStateException("Execution resource call failed: " + ex.getMessage(), ex);
			}
		}

		private Map<String, Object> enrichArguments(Map<String, Object> arguments, ToolContext toolContext) {
			AgentRequest graphRequest = ToolContextRequestResolver.resolveGraphRequest(toolContext);
			Map<String, Object> enriched = AgentRequestSnapshotSupport.enrichArguments(arguments, graphRequest);
			putIfAbsent(enriched, "agentId",
					parseLong(firstText(graphRequest == null ? null : graphRequest.getAgentId(), agentId)));
			putIfAbsent(enriched, "skillId", skillId);
			putIfAbsent(enriched, "sessionId", graphRequest == null ? null : graphRequest.getThreadId());
			putIfAbsent(enriched, "runtimeRequestId", graphRequest == null ? null : graphRequest.getRuntimeRequestId());
			putIfAbsent(enriched, "idempotencyKey", defaultIdempotencyKey(enriched));
			return enriched;
		}

		private void putIfAbsent(Map<String, Object> target, String key, Object value) {
			if (value == null) {
				return;
			}
			Object current = target.get(key);
			if (current == null || (current instanceof String text && !StringUtils.hasText(text))) {
				target.put(key, value);
			}
		}

		private String defaultIdempotencyKey(Map<String, Object> arguments) {
			String runtimeRequestId = stringValue(arguments.get("runtimeRequestId"));
			if (!StringUtils.hasText(runtimeRequestId)) {
				return null;
			}
			return runtimeRequestId + ":" + resourceKey + ":" + stringValue(arguments.get("targetAlias")) + ":"
					+ stringValue(arguments.get("templateCode"));
		}

		private Long parseLong(String value) {
			if (!StringUtils.hasText(value)) {
				return null;
			}
			try {
				return Long.valueOf(value.trim());
			}
			catch (NumberFormatException ex) {
				return null;
			}
		}

		private String stringValue(Object value) {
			return value == null ? null : String.valueOf(value);
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

	}

}
