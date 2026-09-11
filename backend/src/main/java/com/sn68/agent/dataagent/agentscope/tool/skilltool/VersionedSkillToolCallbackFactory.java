/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.agentscope.tool.skilltool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRequestSnapshotSupport;
import com.sn68.agent.dataagent.agentscope.runtime.ToolContextRequestResolver;
import com.sn68.agent.dataagent.agentscope.tool.AgentModelToolName;
import com.sn68.agent.dataagent.entity.AgentExecutionResource;
import com.sn68.agent.dataagent.entity.AgentExecutionResourceVersion;
import com.sn68.agent.dataagent.entity.DataAgentSkillToolRef;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceVersionMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillToolRefMapper;
import com.sn68.agent.dataagent.tool.ToolInvocationContext;
import com.sn68.agent.dataagent.tool.ToolInvoker;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Builds model tools exclusively from one immutable published Skill version.
 */
@Component
@RequiredArgsConstructor
public class VersionedSkillToolCallbackFactory {

	private static final String DEFAULT_INPUT_SCHEMA = "{\"type\":\"object\",\"additionalProperties\":true}";

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final DataAgentSkillToolRefMapper toolRefMapper;

	private final AgentExecutionResourceVersionMapper resourceVersionMapper;

	private final ToolInvoker toolInvoker;

	private final ObjectMapper objectMapper;

	public Map<String, ToolCallback> create(String agentId, String skillCode, Long skillVersionId) {
		Long numericAgentId = parseLong(agentId);
		if (numericAgentId == null || !StringUtils.hasText(skillCode) || skillVersionId == null) {
			return Map.of();
		}
		Map<String, ToolCallback> callbacks = new LinkedHashMap<>();
		for (DataAgentSkillToolRef ref : toolRefMapper.findBySkillVersionId(skillVersionId)) {
			if (ref == null || "disabled".equalsIgnoreCase(ref.getStatus())) {
				continue;
			}
			AgentExecutionResourceVersion resource = resourceVersionMapper.findPublished(ref.getResourceVersionId());
			if (!modelReadable(resource)) {
				continue;
			}
			AgentExecutionResource snapshot = snapshot(resource);
			String toolName = toolName(skillCode, resource.getResourceKey());
			ToolDefinition definition = ToolDefinition.builder()
				.name(toolName)
				.description(firstText(ref.getUsage(), snapshot.getResourceName(), snapshot.getToolName(),
						resource.getResourceKey()))
				.inputSchema(firstText(resource.getInputSchema(), DEFAULT_INPUT_SCHEMA))
				.build();
			callbacks.put(toolName, new VersionedToolCallback(definition, numericAgentId, skillVersionId,
					resource.getId(), toolInvoker, objectMapper));
		}
		return Map.copyOf(callbacks);
	}

	private boolean modelReadable(AgentExecutionResourceVersion resource) {
		return resource != null && "READ".equals(resource.getAccessMode()) && "MODEL".equals(resource.getExposureMode());
	}

	private AgentExecutionResource snapshot(AgentExecutionResourceVersion resource) {
		try {
			AgentExecutionResource snapshot = objectMapper.readValue(resource.getSnapshot(), AgentExecutionResource.class);
			if (snapshot == null || !resource.getResourceKey().equals(snapshot.getResourceKey())) {
				throw new IllegalStateException("Published tool snapshot does not match its resource key");
			}
			return snapshot;
		}
		catch (Exception ex) {
			throw new IllegalStateException("Published tool snapshot is invalid: " + resource.getResourceKey(), ex);
		}
	}

	private String toolName(String skillCode, String resourceKey) {
		return AgentModelToolName.skill(skillCode, resourceKey);
	}

	private Long parseLong(String value) {
		try {
			return StringUtils.hasText(value) ? Long.valueOf(value.trim()) : null;
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private String firstText(String... values) {
		for (String value : values == null ? new String[0] : values) {
			if (StringUtils.hasText(value)) {
				return value.trim();
			}
		}
		return null;
	}

	private static final class VersionedToolCallback implements ToolCallback {

		private final ToolDefinition definition;

		private final Long agentId;

		private final Long skillVersionId;

		private final Long resourceVersionId;

		private final ToolInvoker toolInvoker;

		private final ObjectMapper objectMapper;

		private VersionedToolCallback(ToolDefinition definition, Long agentId, Long skillVersionId,
				Long resourceVersionId, ToolInvoker toolInvoker, ObjectMapper objectMapper) {
			this.definition = definition;
			this.agentId = agentId;
			this.skillVersionId = skillVersionId;
			this.resourceVersionId = resourceVersionId;
			this.toolInvoker = toolInvoker;
			this.objectMapper = objectMapper;
		}

		@Override
		public ToolDefinition getToolDefinition() {
			return definition;
		}

		@Override
		public String call(String toolInput) {
			return call(toolInput, null);
		}

		@Override
		public String call(String toolInput, ToolContext toolContext) {
			try {
				Map<String, Object> arguments = StringUtils.hasText(toolInput)
						? objectMapper.readValue(toolInput, MAP_TYPE) : Map.of();
				AgentRequest request = ToolContextRequestResolver.resolveGraphRequest(toolContext);
				Map<String, Object> enriched = AgentRequestSnapshotSupport.enrichArguments(arguments, request);
				String tenantId = request == null ? null : request.getTenantIdSnapshot();
				Map<String, Object> result = toolInvoker.invoke(new ToolInvocationContext(agentId, tenantId,
						skillVersionId, resourceVersionId, "READ", false, null, enriched));
				return objectMapper.writeValueAsString(result);
			}
			catch (Exception ex) {
				throw new IllegalStateException("Versioned Skill tool call failed: " + ex.getMessage(), ex);
			}
		}

	}

}
