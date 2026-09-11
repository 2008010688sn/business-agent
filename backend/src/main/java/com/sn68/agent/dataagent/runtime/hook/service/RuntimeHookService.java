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
package com.sn68.agent.dataagent.runtime.hook.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.notification.service.NotificationAuthorizationService;
import com.sn68.agent.dataagent.runtime.hook.dto.RuntimeHookLogPageQueryRequest;
import com.sn68.agent.dataagent.runtime.hook.dto.RuntimeHookDTO;
import com.sn68.agent.dataagent.runtime.hook.dto.RuntimeHookEvent;
import com.sn68.agent.dataagent.runtime.hook.dto.RuntimeHookLogDTO;
import com.sn68.agent.dataagent.runtime.hook.dto.RuntimeHookPageQueryRequest;
import com.sn68.agent.dataagent.runtime.hook.entity.AgentRuntimeHook;
import com.sn68.agent.dataagent.runtime.hook.entity.AgentRuntimeHookLog;
import com.sn68.agent.dataagent.runtime.hook.repository.AgentRuntimeHookLogMapper;
import com.sn68.agent.dataagent.runtime.hook.repository.AgentRuntimeHookMapper;
import com.sn68.agent.dataagent.service.permission.PlatformScopePermissionService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 运行时钩子组件，封装 DataAgent 对应业务入口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeHookService {

	private static final String STATUS_ENABLED = "enabled";

	private static final String STATUS_DISABLED = "disabled";

	private static final String ACTION_TOOL_CALL = "TOOL_CALL";

	private static final String ACTION_CONFIG_TOOL_KEY = "toolKey";

	private static final String ACTION_CONFIG_RESOURCE_KEY = "resourceKey";

	private static final String ACTION_CONFIG_IDEMPOTENCY_KEY = "idempotencyKey";

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final AgentRuntimeHookMapper hookMapper;

	private final AgentRuntimeHookLogMapper logMapper;

	private final ObjectMapper objectMapper;

	private final PlatformScopePermissionService platformScopePermissionService;

	/**
	 * 查询运行时钩子。
	 */
	public List<RuntimeHookDTO> list(String eventType, Long agentId, String skillCode, Long skillVersionId,
			String resourceKey) {
		return hookMapper.findAllOrdered(requireCurrentTenantId(), eventType, agentId, skillCode, skillVersionId,
				resourceKey)
			.stream()
			.map(this::toDTO)
			.toList();
	}

	/**
	 * 处理运行时钩子。
	 */
	public IPage<RuntimeHookDTO> page(RuntimeHookPageQueryRequest request) {
		RuntimeHookPageQueryRequest query = request == null ? new RuntimeHookPageQueryRequest() : request;
		return hookMapper.selectHookPage(query.buildPage(), query, requireCurrentTenantId()).convert(this::toDTO);
	}

	/**
	 * 查询运行时钩子。
	 */
	public List<AgentRuntimeHook> findMatching(RuntimeHookEvent event) {
		if (event == null || !StringUtils.hasText(event.eventType())) {
			return List.of();
		}
		return hookMapper.findMatching(requireCurrentTenantId(), event.eventType(), event.agentId(), event.skillCode(),
				event.skillVersionId(), event.resourceKey());
	}

	/**
	 * 查询运行时钩子。
	 */
	@Deprecated(since = "2026-07", forRemoval = true)
	public List<RuntimeHookLogDTO> listLogs(String hookCode, String eventType, String status, Long agentId,
			String skillCode, Long skillVersionId, String resourceKey, String runtimeRequestId) {
		return logMapper
			.findRecent(hookCode, eventType, status, agentId, skillCode, skillVersionId, resourceKey, runtimeRequestId)
			.stream()
			.map(this::toLogDTO)
			.toList();
	}

	/**
	 * 处理运行时钩子。
	 */
	public IPage<RuntimeHookLogDTO> pageLogs(RuntimeHookLogPageQueryRequest request) {
		RuntimeHookLogPageQueryRequest query = request == null ? new RuntimeHookLogPageQueryRequest() : request;
		return logMapper.selectLogPage(query.buildPage(), query).convert(this::toLogDTO);
	}

	/**
	 * 创建运行时钩子。
	 */
	@Transactional(rollbackFor = Exception.class)
	public RuntimeHookDTO create(RuntimeHookDTO request) {
		if (request == null) {
			throw CheckedException.badRequest("Runtime hook config is required.");
		}
		String hookCode = requiredText(request.hookCode(), "Hook code is required.");
		if (hookMapper.findByHookCode(hookCode) != null) {
			throw CheckedException.badRequest("Hook code already exists.");
		}
		AgentRuntimeHook hook = new AgentRuntimeHook();
		apply(hook, request, hookCode);
		hookMapper.insert(hook);
		return toDTO(hookMapper.findByHookCode(hookCode));
	}

	/**
	 * 保存运行时钩子。
	 */
	@Transactional(rollbackFor = Exception.class)
	public RuntimeHookDTO update(Long id, RuntimeHookDTO request) {
		if (id == null) {
			throw CheckedException.badRequest("Hook ID is required.");
		}
		if (request == null) {
			throw CheckedException.badRequest("Runtime hook config is required.");
		}
		AgentRuntimeHook hook = hookMapper.selectById(id);
		if (hook == null || Boolean.TRUE.equals(hook.getDeleted())) {
			throw CheckedException.notFound("Runtime hook does not exist.");
		}
		apply(hook, request, firstText(request.hookCode(), hook.getHookCode()));
		hookMapper.updateById(hook);
		return toDTO(hookMapper.selectById(id));
	}

	/**
	 * 清理运行时钩子。
	 */
	@Transactional(rollbackFor = Exception.class)
	public void delete(Long id) {
		if (id != null) {
			hookMapper.deleteById(id);
		}
	}

	/**
	 * 处理运行时钩子。
	 */
	@Transactional(rollbackFor = Exception.class)
	public void writeLog(AgentRuntimeHookLog logEntry) {
		if (logEntry != null) {
			logMapper.insert(logEntry);
		}
	}

	private void apply(AgentRuntimeHook hook, RuntimeHookDTO request, String hookCode) {
		hook.setHookCode(requiredText(hookCode, "Hook code is required."));
		hook.setHookName(firstText(request.hookName(), hook.getHookCode()));
		hook.setEventType(requiredText(request.eventType(), "Hook event type is required."));
		hook.setAgentId(request.agentId());
		hook.setSkillCode(trimToNull(request.skillCode()));
		hook.setSkillVersionId(request.skillVersionId());
		hook.setResourceKey(trimToNull(request.resourceKey()));
		String actionType = requiredText(request.actionType(), "Hook action type is required.").toUpperCase();
		Map<String, Object> actionConfig = sanitizeNotificationIdempotencyKey(actionType, request.actionConfig());
		hook.setActionType(actionType);
		hook.setActionConfig(writeJson(actionConfig));
		hook.setAsyncEnabled(!Boolean.FALSE.equals(request.asyncEnabled()));
		hook.setContinueOnError(!Boolean.FALSE.equals(request.continueOnError()));
		hook.setStatus(firstText(request.status(), STATUS_ENABLED));
		hook.setDisplayOrder(request.displayOrder() == null ? 0 : request.displayOrder());
		hook.setExtConfig(writeJson(request.extConfig()));
		if (STATUS_DISABLED.equalsIgnoreCase(hook.getStatus())) {
			hook.setStatus(STATUS_DISABLED);
		}
	}

	private Map<String, Object> sanitizeNotificationIdempotencyKey(String actionType, Map<String, Object> actionConfig) {
		if (!ACTION_TOOL_CALL.equalsIgnoreCase(actionType) || actionConfig == null || actionConfig.isEmpty()) {
			return actionConfig;
		}
		String toolKey = firstText(stringValue(actionConfig.get(ACTION_CONFIG_TOOL_KEY)),
				stringValue(actionConfig.get(ACTION_CONFIG_RESOURCE_KEY)));
		if (!NotificationAuthorizationService.RESOURCE_KEY.equalsIgnoreCase(toolKey)) {
			return actionConfig;
		}
		String idempotencyKey = stringValue(actionConfig.get(ACTION_CONFIG_IDEMPOTENCY_KEY));
		if (!StringUtils.hasText(idempotencyKey)) {
			return actionConfig;
		}
		boolean hasEventOrStatus = hasPlaceholder(idempotencyKey, "eventType")
				|| hasPlaceholder(idempotencyKey, "output.status");
		boolean hasRuntimeIdentity = hasPlaceholder(idempotencyKey, "runtimeRequestId")
				|| hasPlaceholder(idempotencyKey, "idempotencyKey");
		if (!hasEventOrStatus || !hasRuntimeIdentity) {
			Map<String, Object> sanitized = new LinkedHashMap<>(actionConfig);
			sanitized.remove(ACTION_CONFIG_IDEMPOTENCY_KEY);
			return sanitized;
		}
		return actionConfig;
	}

	private boolean hasPlaceholder(String value, String placeholder) {
		return StringUtils.hasText(value) && value.contains("${" + placeholder + "}");
	}

	private RuntimeHookDTO toDTO(AgentRuntimeHook hook) {
		return new RuntimeHookDTO(hook.getId(), hook.getHookCode(), hook.getHookName(), hook.getEventType(),
				hook.getAgentId(), hook.getSkillCode(), hook.getSkillVersionId(), hook.getResourceKey(),
				hook.getActionType(), readJsonObject(hook.getActionConfig()), hook.getAsyncEnabled(),
				hook.getContinueOnError(), hook.getStatus(), hook.getDisplayOrder(), readJsonObject(hook.getExtConfig()));
	}

	private RuntimeHookLogDTO toLogDTO(AgentRuntimeHookLog logEntry) {
		return new RuntimeHookLogDTO(logEntry.getId(), logEntry.getHookCode(), logEntry.getEventType(),
				logEntry.getStatus(), logEntry.getActionType(), logEntry.getToolKey(), logEntry.getAgentId(),
				logEntry.getSkillCode(), logEntry.getSkillVersionId(), logEntry.getResourceKey(),
				logEntry.getSessionId(), logEntry.getRuntimeRequestId(), logEntry.getIdempotencyKey(),
				logEntry.getRequestSummary(), logEntry.getResponseSummary(), logEntry.getErrorMessage(),
				logEntry.getElapsedMs(), logEntry.getCreateTime());
	}

	private String writeJson(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Collection<?> collection && collection.isEmpty()) {
			return null;
		}
		if (value instanceof Map<?, ?> map && map.isEmpty()) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(value instanceof Map<?, ?> map ? new LinkedHashMap<>(map) : value);
		}
		catch (Exception ex) {
			log.debug("Failed to write runtime hook json", ex);
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
			log.debug("Failed to parse runtime hook json", ex);
			return Map.of();
		}
	}

	private String requiredText(String value, String message) {
		if (!StringUtils.hasText(value)) {
			throw CheckedException.badRequest(message);
		}
		return value.trim();
	}

	private String trimToNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
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

	private String requireCurrentTenantId() {
		return platformScopePermissionService.requireCurrentTenantId("运行时钩子");
	}

}
