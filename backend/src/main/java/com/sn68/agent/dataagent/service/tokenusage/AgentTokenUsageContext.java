/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.tokenusage;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import lombok.Builder;

/**
 * Token 用量上下文：记录一次 Agent 调用的租户、用户与模型维度信息。
 */
@Builder(toBuilder = true)
public record AgentTokenUsageContext(String tenantId, String tenantCode, String userId, String clientId,
		String userNickName, String teamIdsJson, Long agentId, String agentName, Long sessionId, String threadId, String runtimeRequestId,
		String rootRuntimeRequestId, String parentRuntimeRequestId, Long orchestrationRunId, Long orchestrationStepId,
		Long modelConfigId, String provider, String modelName, String modelType, Long maxTokens, String usageSource,
		String requestSource, Boolean cacheHit, Long estimatedPromptTokens) {

	public static AgentTokenUsageContext from(AgentRequest request, ModelConfigDTO modelConfig, String usageSource) {
		return builder()
			.tenantId(request == null ? null : request.getTenantIdSnapshot())
			.tenantCode(request == null ? null : request.getTenantCodeSnapshot())
			.userId(request == null ? null : request.getUserIdSnapshot())
			.clientId(request == null ? null : request.getClientIdSnapshot())
			.userNickName(request == null ? null : request.getUserNickNameSnapshot())
			.agentId(parseLong(request == null ? null : request.getAgentId()))
			.agentName(request == null ? null : request.getAgentNameSnapshot())
			.sessionId(parseLong(request == null ? null : request.getThreadId()))
			.threadId(request == null ? null : request.getThreadId())
			.runtimeRequestId(request == null ? null : request.getRuntimeRequestId())
			.rootRuntimeRequestId(request == null ? null : firstText(request.getRootRuntimeRequestId(), request.getRuntimeRequestId()))
			.parentRuntimeRequestId(request == null ? null : request.getParentRuntimeRequestId())
			.orchestrationRunId(request == null ? null : request.getOrchestrationRunId())
			.orchestrationStepId(request == null ? null : request.getOrchestrationStepId())
			.modelConfigId(modelConfig == null ? null : modelConfig.getId())
			.provider(modelConfig == null ? null : modelConfig.getProvider())
			.modelName(modelConfig == null ? null : modelConfig.getModelName())
			.modelType(modelConfig == null ? null : modelConfig.getModelType())
			.maxTokens(modelConfig == null ? null : modelConfig.getMaxTokens())
			.usageSource(usageSource)
			.requestSource(request == null ? null : request.getRequestSource())
			.cacheHit(false)
			.build();
	}

	private static Long parseLong(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Long.parseLong(value.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static String firstText(String first, String second) {
		if (first != null && !first.isBlank()) {
			return first;
		}
		return second;
	}

}
