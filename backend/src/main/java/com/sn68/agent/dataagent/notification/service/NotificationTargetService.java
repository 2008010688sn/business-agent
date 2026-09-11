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
package com.sn68.agent.dataagent.notification.service;

import com.sn68.agent.dataagent.notification.dto.NotificationTargetDTO;
import com.sn68.agent.dataagent.notification.entity.AgentNotificationConnector;
import com.sn68.agent.dataagent.notification.entity.AgentNotificationTarget;
import com.sn68.agent.dataagent.notification.repository.AgentNotificationTargetMapper;
import com.sn68.agent.dataagent.service.permission.PlatformScopePermissionService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 通知目标组件，封装 DataAgent 对应业务入口。
 */
@Service
@RequiredArgsConstructor
public class NotificationTargetService {

	private final AgentNotificationTargetMapper targetMapper;

	private final NotificationConnectorService connectorService;

	private final NotificationJsonSupport jsonSupport;

	private final PlatformScopePermissionService platformScopePermissionService;

	/**
	 * 查询通知目标。
	 */
	public List<NotificationTargetDTO> list(String connectorCode) {
		return targetMapper.findAllOrdered(requireCurrentTenantId(), connectorCode).stream().map(this::toDTO).toList();
	}

	/**
	 * 查询通知目标。
	 */
	public ResolvedTarget resolveEnabled(String targetAlias, Map<String, Object> variables) {
		if (!StringUtils.hasText(targetAlias)) {
			throw CheckedException.badRequest("Notification target alias is required.");
		}
		String requestedAlias = targetAlias.trim();
		AgentNotificationTarget target = targetMapper.findEnabledByTargetAlias(requestedAlias);
		String dynamicValue = null;
		if (target == null && requestedAlias.contains(":")) {
			String baseAlias = requestedAlias.substring(0, requestedAlias.indexOf(':'));
			dynamicValue = requestedAlias.substring(requestedAlias.indexOf(':') + 1);
			target = targetMapper.findEnabledByTargetAlias(baseAlias);
		}
		if (target == null) {
			throw CheckedException.notFound("Notification target is disabled or does not exist.");
		}
		Map<String, Object> renderVariables = new LinkedHashMap<>(variables == null ? Map.of() : variables);
		renderVariables.putIfAbsent("_targetAlias", requestedAlias);
		if (StringUtils.hasText(dynamicValue)) {
			renderVariables.putIfAbsent("_targetValue", dynamicValue);
		}
		Map<String, Object> targetConfig = resolveMap(jsonSupport.readEncryptedMap(target.getTargetConfig()),
				renderVariables);
		return new ResolvedTarget(target, requestedAlias, target.getTargetAlias(), targetConfig);
	}

	/**
	 * 创建通知目标。
	 */
	@Transactional(rollbackFor = Exception.class)
	public NotificationTargetDTO create(NotificationTargetDTO request) {
		if (request == null || !StringUtils.hasText(request.targetAlias())) {
			throw CheckedException.badRequest("Target alias is required.");
		}
		if (!StringUtils.hasText(request.targetName()) || !StringUtils.hasText(request.targetType())) {
			throw CheckedException.badRequest("Target name and type are required.");
		}
		if (targetMapper.findByTargetAlias(request.targetAlias()) != null) {
			throw CheckedException.badRequest("Target alias already exists.");
		}
		AgentNotificationTarget target = new AgentNotificationTarget();
		apply(target, request, true);
		targetMapper.insert(target);
		return toDTO(target);
	}

	/**
	 * 保存通知目标。
	 */
	@Transactional(rollbackFor = Exception.class)
	public NotificationTargetDTO update(Long id, NotificationTargetDTO request) {
		if (id == null) {
			throw CheckedException.badRequest("Target ID is required.");
		}
		AgentNotificationTarget target = targetMapper.selectById(id);
		if (target == null) {
			throw CheckedException.notFound("Notification target does not exist.");
		}
		apply(target, request, false);
		targetMapper.updateById(target);
		return toDTO(target);
	}

	/**
	 * 清理通知目标。
	 */
	@Transactional(rollbackFor = Exception.class)
	public void delete(Long id) {
		if (id != null) {
			targetMapper.deleteById(id);
		}
	}

	private void apply(AgentNotificationTarget target, NotificationTargetDTO request, boolean create) {
		if (request == null) {
			throw CheckedException.badRequest("Target config is required.");
		}
		if (create || StringUtils.hasText(request.targetAlias())) {
			target.setTargetAlias(request.targetAlias().trim());
		}
		if (StringUtils.hasText(request.targetName())) {
			target.setTargetName(request.targetName().trim());
		}
		if (StringUtils.hasText(request.targetType())) {
			target.setTargetType(request.targetType().trim().toUpperCase());
		}
		if (create || StringUtils.hasText(request.connectorCode())) {
			AgentNotificationConnector connector = connectorService.require(request.connectorCode());
			target.setConnectorCode(connector.getConnectorCode());
			target.setProvider(firstText(request.provider(), connector.getProvider()));
		}
		else if (StringUtils.hasText(request.provider())) {
			target.setProvider(request.provider().trim().toUpperCase());
		}
		target.setResolverCode(firstText(request.resolverCode(), target.getResolverCode()));
		target.setStatus(firstText(request.status(), target.getStatus(), "enabled"));
		target.setDisplayOrder(request.displayOrder() == null ? target.getDisplayOrder() : request.displayOrder());
		if (request.targetConfig() != null) {
			Map<String, Object> config = jsonSupport.mergePreservingPlaceholders(
					jsonSupport.readEncryptedMap(target.getTargetConfig()), request.targetConfig());
			target.setTargetConfig(jsonSupport.writeEncryptedMap(config));
		}
	}

	private NotificationTargetDTO toDTO(AgentNotificationTarget target) {
		Map<String, Object> config = jsonSupport.maskMap(jsonSupport.readEncryptedMap(target.getTargetConfig()));
		return new NotificationTargetDTO(target.getId(), target.getTargetAlias(), target.getTargetName(),
				target.getTargetType(), target.getConnectorCode(), target.getProvider(), config, target.getResolverCode(),
				target.getStatus(), target.getDisplayOrder());
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> resolveMap(Map<String, Object> source, Map<String, Object> variables) {
		if (source == null || source.isEmpty()) {
			return Map.of();
		}
		Map<String, Object> result = new LinkedHashMap<>();
		source.forEach((key, value) -> result.put(key, resolveValue(value, variables)));
		return result;
	}

	private Object resolveValue(Object value, Map<String, Object> variables) {
		if (value instanceof String text) {
			return render(text, variables);
		}
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> nested = new LinkedHashMap<>();
			map.forEach((key, itemValue) -> {
				if (key != null) {
					nested.put(String.valueOf(key), resolveValue(itemValue, variables));
				}
			});
			return nested;
		}
		if (value instanceof Collection<?> collection) {
			return collection.stream().map(item -> resolveValue(item, variables)).toList();
		}
		return value;
	}

	private String render(String template, Map<String, Object> variables) {
		String result = template;
		for (Map.Entry<String, Object> entry : variables.entrySet()) {
			String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
			result = result.replace("${" + entry.getKey() + "}", value).replace("{{" + entry.getKey() + "}}", value);
		}
		return result;
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

	/**
	 * 处理通知目标。
	 */
	public record ResolvedTarget(AgentNotificationTarget target, String requestedAlias, String authorizationAlias,
			Map<String, Object> targetConfig) {
	}

	private String requireCurrentTenantId() {
		return platformScopePermissionService.requireCurrentTenantId("通知目标");
	}

}
