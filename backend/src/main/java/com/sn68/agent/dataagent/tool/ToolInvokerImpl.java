/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.context.ExecutionIntentContext;
import com.sn68.agent.dataagent.dto.tool.ToolPermissionResult;
import com.sn68.agent.dataagent.entity.AgentExecutionResource;
import com.sn68.agent.dataagent.entity.AgentExecutionResourceVersion;
import com.sn68.agent.dataagent.entity.DataAgentFlowInstance;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillBinding;
import com.sn68.agent.dataagent.entity.DataAgentSkillToolRef;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceMapper;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceVersionMapper;
import com.sn68.agent.dataagent.repository.DataAgentFlowInstanceMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillToolRefMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.service.skill.SkillBindingService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 在调用传输层前校验版本、绑定、权限、确认状态与幂等契约。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolInvokerImpl implements ToolInvoker {

	private static final Set<String> ACTIVE_FLOW_STATUSES = Set.of("RUNNING", "WAITING", "EXECUTING", "UNKNOWN");

	private final AgentExecutionResourceVersionMapper resourceVersionMapper;

	private final AgentExecutionResourceMapper resourceMapper;

	private final DataAgentSkillToolRefMapper toolRefMapper;

	private final DataAgentFlowInstanceMapper flowInstanceMapper;

	private final DataAgentSkillVersionMapper skillVersionMapper;

	private final DataAgentSkillMapper skillMapper;

	private final SkillBindingService skillBindingService;

	private final ToolPermissionService permissionService;

	private final ToolTransportInvoker toolTransportInvoker;

	private final ObjectMapper objectMapper;

	@Override
	public Map<String, Object> invoke(ToolInvocationContext context) {
		validateRequiredContext(context);
		DataAgentFlowInstance flowInstance = context.flowReference() == null ? null : validateFlowReference(context);
		if (flowInstance == null) {
			requireCurrentVersionBinding(context);
		}
		AgentExecutionResourceVersion resource = requirePublishedResource(context, flowInstance);
		rejectWriteUnderDryRun(context, resource);
		validateToolBinding(context, resource);
		validatePermission(context, resource);
		validateAccessContract(context, resource, flowInstance);

		Map<String, Object> arguments = new LinkedHashMap<>(context.arguments() == null ? Map.of() : context.arguments());
		if (StringUtils.hasText(context.idempotencyKey())) {
			arguments.put("idempotencyKey", context.idempotencyKey());
		}
		arguments.put("_agentId", context.agentId());
		arguments.put("_skillVersionId", context.skillVersionId());
		arguments.put("_resourceVersionId", context.resourceVersionId());
		try {
			return toolTransportInvoker.invoke(snapshot(resource), arguments);
		}
		catch (WebClientResponseException ex) {
			if (ex.getStatusCode().value() == 401) {
				throw failure(context, ToolInvocationException.Code.TOOL_TOKEN_INVALID,
						"业务服务拒绝当前授权，请重新发起当前操作", ex);
			}
			if (ex.getStatusCode().value() == 403) {
				throw failure(context, ToolInvocationException.Code.TOOL_PERMISSION_DENIED,
						"业务服务拒绝当前账号访问，请确认功能权限和数据范围", ex);
			}
			throw ex;
		}
	}

	private void validateRequiredContext(ToolInvocationContext context) {
		if (context == null || context.agentId() == null || !StringUtils.hasText(context.tenantId())
				|| context.skillVersionId() == null || context.resourceVersionId() == null) {
			throw CheckedException.badRequest("Skill and tool versions are required");
		}
	}

	/**
	 * DRY_RUN 副作用禁令（方案第十四章）：离线评估干跑下，写工具在进传输层前失败关闭地拦截并记违规。
	 * 本关口覆盖两条写路径：versioned skill 写工具与 FLOW 写提交（写工具 FLOW_ONLY，必经此处）。
	 * 依赖 ThreadLocal 通道（评估执行线程内同步调用链）；若运行时内部切换线程导致上下文缺失，
	 * 兜底仍有 CapabilityGateway 按 AgentRequest 显式意图拦截 skill 写工具的上游模型调用。
	 */
	private void rejectWriteUnderDryRun(ToolInvocationContext context, AgentExecutionResourceVersion resource) {
		if (!"WRITE".equals(resource.getAccessMode()) || !ExecutionIntentContext.dryRunActive()) {
			return;
		}
		ExecutionIntentContext.recordViolation(null, ExecutionIntentContext.VIOLATION_WRITE_ATTEMPT,
				resource.getResourceKey(), "DRY_RUN 下调用写工具（ToolInvoker 关口拦截）");
		throw CheckedException.fail("DRY_RUN 副作用禁令拒绝：离线评估禁止执行写工具，已记违规，resourceKey="
				+ resource.getResourceKey());
	}

	private void requireCurrentVersionBinding(ToolInvocationContext context) {
		DataAgentSkillBinding binding = skillBindingService.listEnabled(context.agentId(), context.tenantId()).stream()
			.filter(item -> Objects.equals(item.getPinnedSkillVersionId(), context.skillVersionId()))
			.findFirst()
			.orElse(null);
		if (binding == null) {
			throw new ToolInvocationException(ToolInvocationException.Code.FLOW_VERSION_UNAVAILABLE,
					"当前智能体未启用该 Skill 版本");
		}
	}

	private DataAgentFlowInstance validateFlowReference(ToolInvocationContext context) {
		FlowInvocationReference reference = context.flowReference();
		if (reference.instanceId() == null || reference.expectedLockVersion() == null
				|| !StringUtils.hasText(reference.expectedCurrentNodeId())
				|| !StringUtils.hasText(reference.invokingNodeId()) || !StringUtils.hasText(reference.threadId())
				|| !StringUtils.hasText(reference.userId()) || !StringUtils.hasText(reference.runtimeRequestId())) {
			throw failure(context, ToolInvocationException.Code.FLOW_REFERENCE_INVALID, "FLOW 工具调用引用不完整");
		}
		DataAgentFlowInstance instance = flowInstanceMapper.selectById(reference.instanceId());
		if (instance == null || Boolean.TRUE.equals(instance.getDeleted())
				|| !Objects.equals(context.agentId(), instance.getAgentId())
				|| !Objects.equals(context.tenantId(), instance.getTenantId())
				|| !Objects.equals(context.skillVersionId(), instance.getSkillVersionId())
				|| !Objects.equals(reference.threadId(), instance.getThreadId())
				|| !Objects.equals(reference.userId(), instance.getUserId())) {
			throw failure(context, ToolInvocationException.Code.FLOW_REFERENCE_INVALID,
					"FLOW 工具调用引用与活动实例不匹配");
		}
		if (!Objects.equals(reference.expectedLockVersion(), instance.getLockVersion())
				|| !Objects.equals(reference.expectedCurrentNodeId(), instance.getCurrentNodeId())) {
			throw failure(context, ToolInvocationException.Code.FLOW_STATE_STALE, "FLOW 状态已变化，请按最新提示重试");
		}
		if (!ACTIVE_FLOW_STATUSES.contains(instance.getStatus())
				|| instance.getExpiresAt() != null && !instance.getExpiresAt().isAfter(Instant.now())) {
			throw failure(context, ToolInvocationException.Code.FLOW_STATE_STALE, "FLOW 已结束、暂停或过期");
		}
		DataAgentSkillVersion version = requireAvailableFlowVersion(context, instance);
		validateInvokingNode(context, version, reference.invokingNodeId());
		return instance;
	}

	private DataAgentSkillVersion requireAvailableFlowVersion(ToolInvocationContext context,
			DataAgentFlowInstance instance) {
		DataAgentSkillVersion version = skillVersionMapper.selectById(instance.getSkillVersionId());
		if (version == null || Boolean.TRUE.equals(version.getDeleted()) || !"PUBLISHED".equals(version.getStatus())) {
			throw failure(context, ToolInvocationException.Code.FLOW_VERSION_UNAVAILABLE,
					"当前流程固定的 Skill 版本已不可用");
		}
		DataAgentSkill skill = skillMapper.selectById(version.getSkillId());
		if (skill == null || Boolean.TRUE.equals(skill.getDeleted()) || !"PUBLISHED".equalsIgnoreCase(skill.getStatus())
				|| !Objects.equals(instance.getSkillCode(), skill.getSkillCode())) {
			throw failure(context, ToolInvocationException.Code.FLOW_VERSION_UNAVAILABLE, "当前流程 Skill 已不可用");
		}
		// 只校验该 Skill 对当前智能体仍启用，不要求智能体此刻仍固定在实例那一版：
		// 发布新版会把绑定推进到新 pinnedSkillVersionId，若在此处比对版本号，所有进行中的流程会在
		// 发布瞬间集体报「已停用」而无法收尾。实例固定版本的可用性已由上面两处 PUBLISHED 校验覆盖，
		// 调用节点与工具归属另由 validateInvokingNode / validateToolBinding 锁定，放宽这一条不扩大权限。
		boolean sameSkillEnabled = skillBindingService.listEnabled(context.agentId(), context.tenantId()).stream()
				.anyMatch(binding -> Objects.equals(instance.getSkillId(), binding.getSkillId()));
		if (!sameSkillEnabled) {
			throw failure(context, ToolInvocationException.Code.FLOW_VERSION_UNAVAILABLE,
					"当前智能体已停用该流程 Skill");
		}
		return version;
	}

	private AgentExecutionResourceVersion requirePublishedResource(ToolInvocationContext context,
			DataAgentFlowInstance flowInstance) {
		AgentExecutionResourceVersion resource = resourceVersionMapper.findPublished(context.resourceVersionId());
		if (resource == null) {
			if (flowInstance != null) {
				throw failure(context, ToolInvocationException.Code.FLOW_VERSION_UNAVAILABLE, "流程工具版本已不可用");
			}
			throw CheckedException.notFound("Published tool version does not exist");
		}
		if (resource.getTenantId() != null && !context.tenantId().equals(resource.getTenantId())) {
			throw new ToolInvocationException(ToolInvocationException.Code.TOOL_PERMISSION_DENIED,
					"当前租户不能使用该工具版本");
		}
		AgentExecutionResource current = resourceMapper.selectById(resource.getResourceId());
		if (current == null || Boolean.TRUE.equals(current.getDeleted()) || !Boolean.TRUE.equals(current.getEnabled())
				|| "disabled".equalsIgnoreCase(current.getStatus())
				|| !Objects.equals(resource.getResourceKey(), current.getResourceKey())) {
			throw failure(context, ToolInvocationException.Code.FLOW_VERSION_UNAVAILABLE, "执行资源已停用或不存在");
		}
		return resource;
	}

	private void validateToolBinding(ToolInvocationContext context, AgentExecutionResourceVersion resource) {
		List<DataAgentSkillToolRef> refs = toolRefMapper.findBySkillVersionId(context.skillVersionId());
		boolean bound = refs.stream().anyMatch(ref -> context.resourceVersionId().equals(ref.getResourceVersionId())
				&& !"disabled".equalsIgnoreCase(ref.getStatus()));
		if (!bound) {
			throw failure(context, context.flowReference() == null
					? ToolInvocationException.Code.FLOW_VERSION_UNAVAILABLE
					: ToolInvocationException.Code.FLOW_REFERENCE_INVALID,
					"工具版本未绑定到当前 Skill 版本");
		}
	}

	private void validateInvokingNode(ToolInvocationContext context, DataAgentSkillVersion version,
			String invokingNodeId) {
		try {
			JsonNode nodes = objectMapper.readTree(version.getFlowDefinition()).path("nodes");
			for (JsonNode node : nodes) {
				if (!invokingNodeId.equals(node.path("id").asText())) {
					continue;
				}
				JsonNode config = node.path("config");
				if (referencesResource(config, context.resourceVersionId())) {
					return;
				}
				break;
			}
		}
		catch (Exception ex) {
			throw failure(context, ToolInvocationException.Code.FLOW_VERSION_UNAVAILABLE,
					"当前流程定义无法校验", ex);
		}
		throw failure(context, ToolInvocationException.Code.FLOW_REFERENCE_INVALID,
				"FLOW 节点未声明本次工具版本");
	}

	private boolean referencesResource(JsonNode config, Long resourceVersionId) {
		if (resourceVersionId.equals(longValue(config.path("resourceVersionId")))) {
			return true;
		}
		if (resourceVersionId.equals(longValue(config.path("resultQuery").path("resourceVersionId")))) {
			return true;
		}
		for (JsonNode resolver : config.path("parallelResolvers")) {
			if (resourceVersionId.equals(longValue(resolver.path("resourceVersionId")))) {
				return true;
			}
		}
		return false;
	}

	private Long longValue(JsonNode value) {
		if (value == null || value.isMissingNode() || value.isNull()) {
			return null;
		}
		if (value.canConvertToLong()) {
			return value.longValue();
		}
		try {
			return Long.valueOf(value.asText());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private void validatePermission(ToolInvocationContext context, AgentExecutionResourceVersion resource) {
		List<String> permissions = StringUtils.hasText(resource.getPermissionCode())
				? List.of(resource.getPermissionCode()) : List.of();
		ToolPermissionResult permission = permissionService.canAccess(permissions, null);
		if (!permission.allowed()) {
			throw failure(context, ToolInvocationException.Code.TOOL_PERMISSION_DENIED,
					StringUtils.hasText(permission.denyMessage()) ? permission.denyMessage() : "当前账号缺少工具权限");
		}
	}

	private void validateAccessContract(ToolInvocationContext context, AgentExecutionResourceVersion resource,
			DataAgentFlowInstance flowInstance) {
		if (StringUtils.hasText(context.expectedAccessMode())
				&& !context.expectedAccessMode().equals(resource.getAccessMode())) {
			throw CheckedException.badRequest("Tool access mode does not match FLOW node type");
		}
		if (!"WRITE".equals(resource.getAccessMode())) {
			return;
		}
		if (!"FLOW_ONLY".equals(resource.getExposureMode())) {
			throw CheckedException.badRequest("Write tools must be FLOW_ONLY");
		}
		if (!context.confirmed()) {
			throw CheckedException.badRequest("Write tool cannot run before confirmation");
		}
		if (flowInstance == null || !"EXECUTING".equals(flowInstance.getStatus())) {
			throw failure(context, ToolInvocationException.Code.FLOW_STATE_STALE,
					"写入工具只能由正在执行的 FLOW 调用");
		}
		FlowInvocationReference reference = context.flowReference();
		if (!Objects.equals(reference.expectedCurrentNodeId(), reference.invokingNodeId())
				|| !Objects.equals(reference.runtimeRequestId(), flowInstance.getLastRuntimeRequestId())
				|| !Objects.equals(context.idempotencyKey(), flowInstance.getIdempotencyKey())) {
			throw failure(context, ToolInvocationException.Code.FLOW_STATE_STALE,
					"FLOW 写入状态、运行请求或幂等键已变化");
		}
		if (Boolean.TRUE.equals(resource.getIdempotencyRequired())
				&& !StringUtils.hasText(context.idempotencyKey())) {
			throw CheckedException.badRequest("Idempotency key is required for this write tool");
		}
	}

	private AgentExecutionResource snapshot(AgentExecutionResourceVersion version) {
		try {
			AgentExecutionResource snapshot = objectMapper.readValue(version.getSnapshot(), AgentExecutionResource.class);
			if (snapshot == null || !version.getResourceKey().equals(snapshot.getResourceKey())) {
				throw CheckedException.badRequest("Published tool snapshot does not match its resource key");
			}
			return snapshot;
		}
		catch (CheckedException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw CheckedException.badRequest("Published tool snapshot is invalid");
		}
	}

	private ToolInvocationException failure(ToolInvocationContext context, ToolInvocationException.Code code,
			String message) {
		return failure(context, code, message, null);
	}

	private ToolInvocationException failure(ToolInvocationContext context, ToolInvocationException.Code code,
			String message, Throwable cause) {
		FlowInvocationReference reference = context == null ? null : context.flowReference();
		log.warn(
				"工具调用被拒绝. runtimeRequestId={}, agentId={}, flowInstanceId={}, currentNodeId={}, invokingNodeId={}, errorCode={}",
				reference == null ? null : reference.runtimeRequestId(), context == null ? null : context.agentId(),
				reference == null ? null : reference.instanceId(),
				reference == null ? null : reference.expectedCurrentNodeId(),
				reference == null ? null : reference.invokingNodeId(), code);
		return cause == null ? new ToolInvocationException(code, message)
				: new ToolInvocationException(code, message, cause);
	}

}
