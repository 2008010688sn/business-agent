/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.capability;

import cn.hutool.crypto.SecureUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeArtifact;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeEventType;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeArtifactMapper;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeEventService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.util.StringUtils;

/**
 * 经过统一能力网关的 Spring AI 工具回调包装器。AgentScopeToolkitFactory 装配的每个工具回调都会
 * 先经 {@link CapabilityGateway}（capabilityKind=TOOL）完成检查链与调用记录，再执行原始回调；
 * 检查拒绝时抛出 {@link CheckedException}，由 SpringToolCallbackAgentAdapter 归类为可见失败。
 * 智能体类型级授权（AgentToolPolicyService）仍在工具集装配期执行，本包装器不改变工具定义与
 * 结果文本，模型可见的输入输出契约保持不变。{@code runId} 只传权威
 * {@code agent_runtime_run.id}（{@link AgentRequest#getDurableRunId()}），不用编排遥测 id。
 */
@Slf4j
public class CapabilityGatewayToolCallback implements ToolCallback {

	public static final String SOURCE_AGENT_SCOPE = "AGENT_SCOPE";

	private static final String CONTEXT_GRAPH_REQUEST = "graphRequest";

	private static final String OBSERVATION_SCHEMA = "tool-observation/v1";

	private static final int PREVIEW_LIMIT = 512;

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final ToolCallback delegate;

	private final CapabilityGateway capabilityGateway;

	private final ObjectMapper objectMapper;

	private final RuntimeEventService runtimeEventService;

	private final AgentRuntimeArtifactMapper artifactMapper;

	public CapabilityGatewayToolCallback(ToolCallback delegate, CapabilityGateway capabilityGateway,
			ObjectMapper objectMapper) {
		this(delegate, capabilityGateway, objectMapper, null, null);
	}

	public CapabilityGatewayToolCallback(ToolCallback delegate, CapabilityGateway capabilityGateway,
			ObjectMapper objectMapper, RuntimeEventService runtimeEventService,
			AgentRuntimeArtifactMapper artifactMapper) {
		this.delegate = delegate;
		this.capabilityGateway = capabilityGateway;
		this.objectMapper = objectMapper;
		this.runtimeEventService = runtimeEventService;
		this.artifactMapper = artifactMapper;
	}

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
		return call(toolInput, null);
	}

	@Override
	public String call(String toolInput, ToolContext toolContext) {
		AgentRequest agentRequest = agentRequest(toolContext);
		String toolName = delegate.getToolDefinition().name();
		String inputHash = fingerprint(toolInput);
		InvocationRequest request = InvocationRequest.builder()
			.tenantId(agentRequest == null ? null : agentRequest.getTenantIdSnapshot())
			.ownerType(agentRequest == null ? null : agentRequest.getOwnerType())
			.ownerId(agentRequest == null ? null : agentRequest.getOwnerId())
			.releaseId(agentRequest == null ? null : agentRequest.getReleaseId())
			.capabilityKind(CapabilityKind.TOOL)
			.capabilityCode(toolName)
			.arguments(readArguments(toolInput))
			.source(SOURCE_AGENT_SCOPE)
			.runId(agentRequest == null ? null : agentRequest.getDurableRunId())
			.stepKey(agentRequest == null ? null : agentRequest.getRuntimeRequestId())
			.agentId(parseLong(agentRequest == null ? null : agentRequest.getAgentId()))
			.userId(agentRequest == null ? null : agentRequest.getUserIdSnapshot())
			.executionIntent(agentRequest == null ? null : agentRequest.getExecutionIntent())
			.executionScopeKey(agentRequest == null ? null : agentRequest.getExecutionScopeKey())
			.build();
		appendInvocationEvent(agentRequest, toolName, inputHash, "STARTED", null, null, null);
		try {
			ResultEnvelope envelope = capabilityGateway.invoke(request, () -> delegate.call(toolInput, toolContext));
			Object data = envelope.data();
			String observation = data == null ? "" : String.valueOf(data);
			Long artifactId = storeObservation(agentRequest, toolName, observation);
			appendInvocationEvent(agentRequest, toolName, inputHash, "SUCCESS", artifactId, fingerprint(observation),
					preview(observation));
			return observation;
		}
		catch (RuntimeException ex) {
			appendInvocationEvent(agentRequest, toolName, inputHash, "FAILED", null, null, preview(ex.getMessage()));
			throw ex;
		}
	}

	private AgentRequest agentRequest(ToolContext toolContext) {
		if (toolContext == null || toolContext.getContext() == null) {
			return null;
		}
		Object request = toolContext.getContext().get(CONTEXT_GRAPH_REQUEST);
		return request instanceof AgentRequest agentRequest ? agentRequest : null;
	}

	private Map<String, Object> readArguments(String toolInput) {
		if (!StringUtils.hasText(toolInput)) {
			return Map.of();
		}
		try {
			Map<String, Object> arguments = objectMapper.readValue(toolInput, MAP_TYPE);
			return arguments == null ? Map.of() : arguments;
		}
		catch (Exception ex) {
			// 模型入参按契约必须是 JSON 对象，解析失败时失败关闭，不带着不可检查的入参执行工具。
			throw CheckedException.badRequest("参数 Schema 校验拒绝：工具入参不是合法 JSON 对象，toolName="
					+ delegate.getToolDefinition().name());
		}
	}

	private void appendInvocationEvent(AgentRequest request, String toolName, String inputHash, String state,
			Long artifactId, String contentHash, String preview) {
		if (runtimeEventService == null || request == null || request.getDurableRunId() == null
				|| request.getFenceToken() == null || !StringUtils.hasText(request.getLeaseOwner())) {
			return;
		}
		String eventKey = "INVOCATION:" + request.getDurableRunId() + ":" + toolName + ":" + inputHash + ":" + state;
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("capabilityCode", toolName);
		payload.put("state", state);
		payload.put("toolCallId", inputHash);
		if (artifactId != null) {
			payload.put("artifactId", artifactId);
		}
		if (StringUtils.hasText(contentHash)) {
			payload.put("contentHash", contentHash);
		}
		if (preview != null) {
			payload.put("preview", preview);
		}
		runtimeEventService.appendFenced(request.getTenantIdSnapshot(), request.getDurableRunId(), eventKey,
				RuntimeEventType.INVOCATION_STATE_CHANGED, request.getRuntimeRequestId(), payload,
				request.getFenceToken(), request.getLeaseOwner());
	}

	private Long storeObservation(AgentRequest request, String toolName, String observation) {
		if (artifactMapper == null || request == null || request.getDurableRunId() == null
				|| !StringUtils.hasText(observation)) {
			return null;
		}
		AgentRuntimeArtifact artifact = AgentRuntimeArtifact.builder()
			.tenantId(request.getTenantIdSnapshot())
			.runId(request.getDurableRunId())
			.stepKey(StringUtils.hasText(request.getRuntimeRequestId()) ? request.getRuntimeRequestId() : toolName)
			.schemaVersion(OBSERVATION_SCHEMA)
			.data(observation)
			.contentHash(fingerprint(observation))
			.sensitivity("INTERNAL")
			.createTime(Instant.now())
			.lastModifyTime(Instant.now())
			.deleted(false)
			.build();
		artifactMapper.insert(artifact);
		return artifact.getId();
	}

	private static String fingerprint(String value) {
		return SecureUtil.md5(value == null ? "" : value);
	}

	private static String preview(String text) {
		if (!StringUtils.hasText(text)) {
			return "";
		}
		String trimmed = text.trim();
		return trimmed.length() <= PREVIEW_LIMIT ? trimmed : trimmed.substring(0, PREVIEW_LIMIT);
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

}
