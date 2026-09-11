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

import com.sn68.agent.dataagent.notification.adapter.NotificationAdapterRegistry;
import com.sn68.agent.dataagent.notification.dto.NotificationAdapterResult;
import com.sn68.agent.dataagent.notification.dto.NotificationConnectorDTO;
import com.sn68.agent.dataagent.notification.entity.AgentNotificationConnector;
import com.sn68.agent.dataagent.notification.repository.AgentNotificationConnectorMapper;
import com.sn68.agent.dataagent.service.permission.PlatformScopePermissionService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 通知连接器组件，封装 DataAgent 对应业务入口。
 */
@Service
@RequiredArgsConstructor
public class NotificationConnectorService {

	private final AgentNotificationConnectorMapper connectorMapper;

	private final NotificationAdapterRegistry adapterRegistry;

	private final NotificationJsonSupport jsonSupport;

	private final PlatformScopePermissionService platformScopePermissionService;

	/**
	 * 查询通知连接器。
	 */
	public List<NotificationConnectorDTO> list() {
		return connectorMapper.findAllOrdered(requireCurrentTenantId()).stream().map(this::toDTO).toList();
	}

	/**
	 * 查询通知连接器。
	 */
	public NotificationConnectorDTO get(String connectorCode) {
		return toDTO(require(connectorCode));
	}

	/**
	 * 校验通知连接器。
	 */
	public AgentNotificationConnector require(String connectorCode) {
		AgentNotificationConnector connector = connectorMapper.findByConnectorCode(connectorCode);
		if (connector == null) {
			throw CheckedException.notFound("Notification connector does not exist.");
		}
		return connector;
	}

	/**
	 * 校验通知连接器。
	 */
	public AgentNotificationConnector requireEnabled(String connectorCode) {
		AgentNotificationConnector connector = connectorMapper.findEnabledByConnectorCode(connectorCode);
		if (connector == null) {
			throw CheckedException.notFound("Notification connector is disabled or does not exist.");
		}
		return connector;
	}

	/**
	 * 执行通知连接器。
	 */
	public Map<String, Object> runtimeConfig(AgentNotificationConnector connector) {
		return connector == null ? Map.of() : jsonSupport.readEncryptedMap(connector.getEncryptedConfig());
	}

	/**
	 * 创建通知连接器。
	 */
	@Transactional(rollbackFor = Exception.class)
	public NotificationConnectorDTO create(NotificationConnectorDTO request) {
		if (request == null || !StringUtils.hasText(request.connectorCode())) {
			throw CheckedException.badRequest("Connector code is required.");
		}
		if (!StringUtils.hasText(request.connectorName())) {
			throw CheckedException.badRequest("Connector name is required.");
		}
		if (!StringUtils.hasText(request.provider()) || !StringUtils.hasText(request.channelType())) {
			throw CheckedException.badRequest("Connector provider and channel type are required.");
		}
		if (connectorMapper.findByConnectorCode(request.connectorCode()) != null) {
			throw CheckedException.badRequest("Connector code already exists.");
		}
		AgentNotificationConnector connector = new AgentNotificationConnector();
		apply(connector, request, true);
		connectorMapper.insert(connector);
		return toDTO(connector);
	}

	/**
	 * 保存通知连接器。
	 */
	@Transactional(rollbackFor = Exception.class)
	public NotificationConnectorDTO update(Long id, NotificationConnectorDTO request) {
		if (id == null) {
			throw CheckedException.badRequest("Connector ID is required.");
		}
		AgentNotificationConnector connector = connectorMapper.selectById(id);
		if (connector == null) {
			throw CheckedException.notFound("Notification connector does not exist.");
		}
		apply(connector, request, false);
		connectorMapper.updateById(connector);
		return toDTO(connector);
	}

	/**
	 * 清理通知连接器。
	 */
	@Transactional(rollbackFor = Exception.class)
	public void delete(Long id) {
		if (id != null) {
			connectorMapper.deleteById(id);
		}
	}

	/**
	 * 校验通知连接器。
	 */
	public NotificationAdapterResult test(String connectorCode) {
		AgentNotificationConnector connector = requireEnabled(connectorCode);
		Map<String, Object> config = runtimeConfig(connector);
		return adapterRegistry.get(connector.getProvider(), connector.getChannelType()).test(connector, config);
	}

	private void apply(AgentNotificationConnector connector, NotificationConnectorDTO request, boolean create) {
		if (request == null) {
			throw CheckedException.badRequest("Connector config is required.");
		}
		if (create || StringUtils.hasText(request.connectorCode())) {
			connector.setConnectorCode(request.connectorCode().trim());
		}
		if (StringUtils.hasText(request.connectorName())) {
			connector.setConnectorName(request.connectorName().trim());
		}
		if (StringUtils.hasText(request.provider())) {
			connector.setProvider(request.provider().trim().toUpperCase());
		}
		if (StringUtils.hasText(request.channelType())) {
			connector.setChannelType(request.channelType().trim().toUpperCase());
		}
		connector.setAuthType(firstText(request.authType(), connector.getAuthType()));
		connector.setCredentialRef(firstText(request.credentialRef(), connector.getCredentialRef()));
		connector.setTenantId(firstText(request.tenantId(), connector.getTenantId()));
		connector.setStatus(firstText(request.status(), connector.getStatus(), "enabled"));
		connector.setDisplayOrder(request.displayOrder() == null ? connector.getDisplayOrder() : request.displayOrder());
		if (request.config() != null) {
			Map<String, Object> config = jsonSupport.mergePreservingPlaceholders(runtimeConfig(connector), request.config());
			connector.setEncryptedConfig(jsonSupport.writeEncryptedMap(config));
		}
	}

	private NotificationConnectorDTO toDTO(AgentNotificationConnector connector) {
		Map<String, Object> config = jsonSupport.maskMap(runtimeConfig(connector));
		return new NotificationConnectorDTO(connector.getId(), connector.getConnectorCode(), connector.getConnectorName(),
				connector.getProvider(), connector.getChannelType(), connector.getAuthType(), config,
				connector.getCredentialRef(), connector.getTenantId(), connector.getStatus(), connector.getDisplayOrder());
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
		return platformScopePermissionService.requireCurrentTenantId("通知连接器");
	}

}
