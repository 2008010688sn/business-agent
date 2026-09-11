/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.entity.DataAgentFlowEvent;
import com.sn68.agent.dataagent.entity.DataAgentFlowInstance;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.flow.definition.FlowNode;
import com.sn68.agent.dataagent.repository.DataAgentFlowEventMapper;
import com.sn68.agent.dataagent.repository.DataAgentFlowInstanceMapper;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceVersionMapper;
import com.sn68.agent.dataagent.runtime.hook.service.RuntimeHookDispatcher;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Database persistence for FLOW state and append-only audit events.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FlowPersistenceService implements FlowInstanceService, FlowEventService {

	private final DataAgentFlowInstanceMapper instanceMapper;

	private final DataAgentFlowEventMapper eventMapper;

	private final AuthenticationContext authenticationContext;

	private final ObjectMapper objectMapper;

	private final AgentExecutionResourceVersionMapper resourceVersionMapper;

	private final RuntimeHookDispatcher runtimeHookDispatcher;

	@Override
	@Transactional(rollbackFor = Exception.class)
	public DataAgentFlowInstance loadOrCreate(AgentRequest request, DataAgentSkill skill, DataAgentSkillVersion version,
			String startNode) {
		DataAgentFlowInstance active = findActive(request);
		if (active != null) {
			if (!active.getSkillVersionId().equals(version.getId())) {
				throw CheckedException.badRequest("Another Skill FLOW is already active in this conversation");
			}
			return active;
		}
		if (StringUtils.hasText(request.getFlowInstanceId())) {
			throw CheckedException.badRequest("FLOW 实例不存在、已结束或不属于当前会话");
		}
		String tenantId = tenantId(request);
		String userId = userId(request);
		DataAgentFlowInstance created = DataAgentFlowInstance.builder()
			.tenantId(tenantId)
			.agentId(skillAgentId(request))
			.skillId(skill.getId())
			.skillVersionId(version.getId())
			.skillCode(skill.getSkillCode())
			.sessionId(parseLong(request.getThreadId()))
			.threadId(request.getThreadId())
			.userId(userId)
			.status(FlowInstanceStatus.RUNNING.name())
			.currentNodeId(startNode)
			.contextData("{}")
			.waitingPayload("{}")
			.lockVersion(0)
			.contextRevision(0L)
			.resumeVersion(0)
			.lastRuntimeRequestId(request.getRuntimeRequestId())
			.expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
			.build();
		try {
			instanceMapper.insert(created);
			return created;
		}
		catch (RuntimeException ex) {
			DataAgentFlowInstance concurrent = findActive(request);
			if (concurrent != null) {
				return concurrent;
			}
			throw ex;
		}
	}

	@Override
	public DataAgentFlowInstance findActive(AgentRequest request) {
		if (request == null || !StringUtils.hasText(request.getThreadId())) {
			return null;
		}
		String tenantId = tenantId(request);
		Long agentId = skillAgentId(request);
		String userId = userId(request);
		if (StringUtils.hasText(request.getFlowInstanceId())) {
			Long flowInstanceId = parseLong(request.getFlowInstanceId());
			if (flowInstanceId == null) {
				throw CheckedException.badRequest("FLOW instance id 无效");
			}
			return instanceMapper.findActiveById(flowInstanceId, tenantId, agentId, request.getThreadId(), userId);
		}
		return instanceMapper.findActive(tenantId, agentId, request.getThreadId(), userId);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public DataAgentFlowInstance advance(DataAgentFlowInstance instance, FlowInstanceStatus status, String nodeId,
			Map<String, Object> context, Map<String, Object> waitingPayload, String idempotencyKey,
			String runtimeRequestId, String errorCode, String errorMessage, Instant finishedAt) {
		// 恢复防重三要素与状态同一次 CAS 落库：input_hash 来自引擎当前回合，context_revision 与
		// 上下文内 /runtime/contextRevision 保持一致，resume_version 在每次进入 WAITING 时递增。
		long contextRevision = contextRevision(context, instance);
		int resumeVersion = instance.getResumeVersion() == null ? 0 : instance.getResumeVersion();
		if (status == FlowInstanceStatus.WAITING) {
			resumeVersion = resumeVersion + 1;
		}
		int updated = instanceMapper.advance(instance.getId(), instance.getTenantId(), instance.getLockVersion(),
				status.name(), nodeId, write(context), write(waitingPayload), idempotencyKey, instance.getInputHash(),
				contextRevision, resumeVersion, runtimeRequestId, errorCode, errorMessage, finishedAt);
		if (updated != 1) {
			throw CheckedException.badRequest("FLOW state changed concurrently; refresh and retry the last action");
		}
		DataAgentFlowInstance refreshed = instanceMapper.selectById(instance.getId());
		if (refreshed == null) {
			throw CheckedException.notFound("FLOW instance no longer exists");
		}
		return refreshed;
	}

	private long contextRevision(Map<String, Object> context, DataAgentFlowInstance instance) {
		Object runtime = context == null ? null : context.get("runtime");
		Object revision = runtime instanceof Map<?, ?> values ? values.get("contextRevision") : null;
		if (revision instanceof Number number) {
			return number.longValue();
		}
		return instance.getContextRevision() == null ? 0L : instance.getContextRevision();
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public DataAgentFlowInstance advanceBatch(DataAgentFlowInstance instance, FlowInstanceStatus status, String nodeId,
			Map<String, Object> context, Map<String, Object> waitingPayload, String idempotencyKey,
			String runtimeRequestId, String errorCode, String errorMessage, Instant finishedAt) {
		// A resolver batch is already merged in memory by the coordinator. Reuse the
		// same single CAS update so parallel workers never advance the instance.
		return advance(instance, status, nodeId, context, waitingPayload, idempotencyKey, runtimeRequestId, errorCode,
				errorMessage, finishedAt);
	}

	@Override
	public void record(DataAgentFlowInstance instance, String runtimeRequestId, FlowNode node, String eventType,
			String status, long durationMs, Map<String, Object> input, Map<String, Object> output, Throwable error) {
		if (instance == null) {
			return;
		}
		DataAgentFlowEvent event = DataAgentFlowEvent.builder()
			.tenantId(instance.getTenantId())
			.flowInstanceId(instance.getId())
			.runtimeRequestId(runtimeRequestId)
			.nodeId(node == null ? null : node.id())
			.nodeType(node == null ? null : node.type())
			.eventType(eventType)
			.status(status)
			.durationMs(durationMs)
			.inputData(write(input))
			.outputData(write(output))
			.errorCode(error == null ? null : error.getClass().getSimpleName())
			.errorMessage(error == null ? null : error.getMessage())
			.build();
		eventMapper.insert(event);
	}

	@Override
	public void resourceSuccess(DataAgentFlowInstance instance, FlowNode node, String runtimeRequestId,
			String idempotencyKey, Map<String, Object> input, Map<String, Object> output) {
		try {
			if (instance == null || node == null || node.config() == null || runtimeHookDispatcher == null) {
				return;
			}
			Long resourceVersionId = parseLong(String.valueOf(node.config().get("resourceVersionId")));
			if (resourceVersionId == null) {
				return;
			}
			var resource = resourceVersionMapper.findPublished(resourceVersionId);
			if (resource == null || !StringUtils.hasText(resource.getResourceKey())) {
				return;
			}
			Map<String, Object> hookOutput = new LinkedHashMap<>(output == null ? Map.of() : output);
			if (!hookOutput.containsKey("data") || hookOutput.get("data") == null) {
				hookOutput.put("data", new LinkedHashMap<>(hookOutput));
			}
			runtimeHookDispatcher.dispatchAfterResourceSuccess(instance.getAgentId(), instance.getSkillCode(),
					instance.getSkillVersionId(), resource.getResourceKey(), instance.getThreadId(), runtimeRequestId,
					idempotencyKey, input, hookOutput);
		}
		catch (RuntimeException ex) {
			log.warn("FLOW resource success event dispatch failed, flowInstanceId={}, nodeId={}",
					instance == null ? null : instance.getId(), node == null ? null : node.id(), ex);
		}
	}

	private String tenantId(AgentRequest request) {
		String tenantId = request == null ? null : request.getTenantIdSnapshot();
		if (!StringUtils.hasText(tenantId)) {
			try {
				tenantId = authenticationContext.tenantId();
			}
			catch (Exception ex) {
				// The guard below still fails closed; without this the caller cannot tell absent from unresolvable.
				log.warn("解析 FLOW 执行租户上下文失败, 将按缺失租户拒绝执行", ex);
				tenantId = null;
			}
		}
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.badRequest("Tenant context is required for FLOW execution");
		}
		return tenantId;
	}

	private String userId(AgentRequest request) {
		String userId = request == null ? null : request.getUserIdSnapshot();
		if (!StringUtils.hasText(userId)) {
			try {
				userId = authenticationContext.userId();
			}
			catch (Exception ex) {
				log.warn("解析 FLOW 执行用户上下文失败, 将按缺失用户拒绝执行", ex);
				userId = null;
			}
		}
		if (!StringUtils.hasText(userId)) {
			throw CheckedException.badRequest("User context is required for FLOW execution");
		}
		return userId;
	}

	private Long skillAgentId(AgentRequest request) {
		Long agentId = parseLong(request == null ? null : request.getAgentId());
		if (agentId == null) {
			throw CheckedException.badRequest("Agent ID is required for FLOW execution");
		}
		return agentId;
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

	private String write(Map<String, Object> value) {
		try {
			return objectMapper.writeValueAsString(value == null ? Map.of() : value);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to persist FLOW JSON", ex);
		}
	}

}
