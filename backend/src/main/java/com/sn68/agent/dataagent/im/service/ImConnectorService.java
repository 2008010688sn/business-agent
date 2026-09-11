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
package com.sn68.agent.dataagent.im.service;

import com.sn68.agent.dataagent.im.adapter.ImAdapterRegistry;
import com.sn68.agent.dataagent.im.dto.ImConnectorDTO;
import com.sn68.agent.dataagent.im.entity.AgentImConnector;
import com.sn68.agent.dataagent.im.event.ImConnectorChangedEvent;
import com.sn68.agent.dataagent.im.enums.ImConstants;
import com.sn68.agent.dataagent.im.enums.ImErrorDict;
import com.sn68.agent.dataagent.im.repository.AgentImConnectorMapper;
import com.sn68.agent.dataagent.notification.dto.NotificationAdapterResult;
import com.sn68.agent.dataagent.notification.service.NotificationJsonSupport;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * IM 对话连接器配置服务（管理端，全部操作限定在当前登录租户内）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImConnectorService {

	private static final String REPLY_WEBHOOK_ALLOWED_HOSTS = "replyWebhookAllowedHosts";

	private static final Pattern HOST_PATTERN = Pattern.compile(
			"(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+"
					+ "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

	private final AgentImConnectorMapper connectorMapper;

	private final ImAdapterRegistry adapterRegistry;

	private final NotificationJsonSupport jsonSupport;

	private final DingTalkCredentialService dingTalkCredentialService;

	private final ObjectProvider<DingTalkStreamLifecycleService> streamLifecycleServiceProvider;

	private final ApplicationEventPublisher eventPublisher;

	private final AuthenticationContext authenticationContext;

	/**
	 * 查询Im连接器。
	 */
	public List<ImConnectorDTO> list() {
		return connectorMapper.findAllOrdered(requireCurrentTenantId()).stream().map(this::toDTO).toList();
	}

	/**
	 * 查询Im连接器。
	 */
	public ImConnectorDTO get(String connectorCode) {
		return toDTO(require(connectorCode));
	}

	/**
	 * 校验Im连接器（限定当前租户）。
	 */
	public AgentImConnector require(String connectorCode) {
		AgentImConnector connector = connectorMapper.findByTenantAndConnectorCode(requireCurrentTenantId(), connectorCode);
		if (connector == null) {
			throw CheckedException.notFound(ImErrorDict.CONNECTOR_NOT_FOUND.getValue(), ImErrorDict.CONNECTOR_NOT_FOUND.getLabel());
		}
		return connector;
	}

	/**
	 * 校验Im连接器（限定当前租户）。
	 */
	public AgentImConnector requireEnabled(String connectorCode) {
		AgentImConnector connector = connectorMapper.findEnabledByTenantAndConnectorCode(requireCurrentTenantId(),
				connectorCode);
		if (connector == null) {
			throw CheckedException.notFound(ImErrorDict.CONNECTOR_NOT_FOUND.getValue(), ImErrorDict.CONNECTOR_NOT_FOUND.getLabel());
		}
		return connector;
	}

	private String requireCurrentTenantId() {
		String tenantId;
		try {
			tenantId = authenticationContext.tenantId();
		}
		catch (Exception ex) {
			log.warn("解析当前租户上下文失败, 将按缺失租户拒绝本次 IM 连接器操作", ex);
			tenantId = null;
		}
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.badRequest("租户上下文缺失, 无法操作 IM 连接器");
		}
		return tenantId;
	}

	/**
	 * 执行Im连接器。
	 */
	public Map<String, Object> runtimeConfig(AgentImConnector connector) {
		return connector == null ? Map.of() : jsonSupport.readEncryptedMap(connector.getEncryptedConfig());
	}

	/**
	 * 创建Im连接器。
	 */
	@Transactional(rollbackFor = Exception.class)
	public ImConnectorDTO create(ImConnectorDTO request) {
		if (request == null || !StringUtils.hasText(request.connectorCode())) {
			throw CheckedException.badRequest("IM 连接器编码不能为空");
		}
		if (!StringUtils.hasText(request.connectorName())) {
			throw CheckedException.badRequest("IM 连接器名称不能为空");
		}
		if (!StringUtils.hasText(request.provider())) {
			throw CheckedException.badRequest("IM 平台不能为空");
		}
		String tenantId = requireCurrentTenantId();
		// 唯一键为 (tenant_id, provider, connector_code)，只在当前租户内查重。
		if (connectorMapper.findByTenantProviderAndCode(tenantId, request.provider(), request.connectorCode()) != null) {
			throw CheckedException.badRequest("IM 连接器编码已存在");
		}
		AgentImConnector connector = new AgentImConnector();
		connector.setTenantId(tenantId);
		apply(connector, request, true);
		connectorMapper.insert(connector);
		publishChanged(connector);
		return toDTO(connector);
	}

	/**
	 * 保存Im连接器。
	 */
	@Transactional(rollbackFor = Exception.class)
	public ImConnectorDTO update(Long id, ImConnectorDTO request) {
		AgentImConnector connector = requireOwnedById(id);
		apply(connector, request, false);
		connectorMapper.updateById(connector);
		publishChanged(connector);
		return toDTO(connector);
	}

	/**
	 * 清理Im连接器。
	 */
	@Transactional(rollbackFor = Exception.class)
	public void delete(Long id) {
		if (id == null) {
			return;
		}
		AgentImConnector connector = requireOwnedById(id);
		connectorMapper.deleteById(id);
		publishChanged(connector);
	}

	/**
	 * 按主键读取并校验归属租户，跨租户访问一律按不存在处理。
	 */
	private AgentImConnector requireOwnedById(Long id) {
		if (id == null) {
			throw CheckedException.badRequest("IM 连接器 ID 不能为空");
		}
		AgentImConnector connector = connectorMapper.selectById(id);
		if (connector == null || !requireCurrentTenantId().equals(connector.getTenantId())) {
			throw CheckedException.notFound(ImErrorDict.CONNECTOR_NOT_FOUND.getValue(), ImErrorDict.CONNECTOR_NOT_FOUND.getLabel());
		}
		return connector;
	}

	/**
	 * 校验Im连接器。
	 */
	public NotificationAdapterResult test(String connectorCode) {
		AgentImConnector connector = requireEnabled(connectorCode);
		adapterRegistry.get(connector.getProvider());
		Map<String, Object> config = runtimeConfig(connector);
		validateReplyWebhookConfig(connector.getProvider(), config, false);
		if (ImConstants.PROVIDER_DINGTALK.equalsIgnoreCase(connector.getProvider())
				&& ImConstants.CONNECT_MODE_STREAM.equalsIgnoreCase(stringValue(config.get("mode")))) {
			dingTalkCredentialService.validate(stringValue(config.get("clientId")), stringValue(config.get("clientSecret")));
			DingTalkStreamLifecycleService lifecycleService = streamLifecycleServiceProvider.getIfAvailable();
			Map<String, Object> status = lifecycleService == null ? Map.of("streamStatus", "UNKNOWN")
					: lifecycleService.status(connector.getId());
			Map<String, Object> details = new LinkedHashMap<>(status);
			details.put("replyWebhook", Map.of("valid", true,
					"allowedHosts", effectiveReplyWebhookAllowedHosts(connector.getProvider(), config)));
			return new NotificationAdapterResult(true, null, "OK", "钉钉 Stream 凭据和回复域名配置均有效", details);
		}
		return new NotificationAdapterResult(true, null, "OK", "IM 对话连接器和回复域名配置均有效", Map.of("replyWebhook",
				Map.of("valid", true, "allowedHosts", effectiveReplyWebhookAllowedHosts(connector.getProvider(), config))));
	}

	private void apply(AgentImConnector connector, ImConnectorDTO request, boolean create) {
		if (request == null) {
			throw CheckedException.badRequest("IM 连接器配置不能为空");
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
		connector.setAuthType(firstText(request.authType(), connector.getAuthType()));
		connector.setCredentialRef(firstText(request.credentialRef(), connector.getCredentialRef()));
		connector.setDefaultAgentId(request.defaultAgentId() == null ? connector.getDefaultAgentId() : request.defaultAgentId());
		connector.setDirectEnabled(request.directEnabled() == null ? defaultBoolean(connector.getDirectEnabled(), true)
				: request.directEnabled());
		connector.setGroupEnabled(request.groupEnabled() == null ? defaultBoolean(connector.getGroupEnabled(), false)
				: request.groupEnabled());
		connector.setStatus(firstText(request.status(), connector.getStatus(), ImConstants.STATUS_ENABLED));
		connector.setDisplayOrder(request.displayOrder() == null ? connector.getDisplayOrder() : request.displayOrder());
		if (request.config() != null) {
			Map<String, Object> config = jsonSupport.mergePreservingPlaceholders(runtimeConfig(connector), request.config());
			normalizeReplyWebhookConfig(connector.getProvider(), config, create);
			connector.setEncryptedConfig(jsonSupport.writeEncryptedMap(config));
		}
	}

	private ImConnectorDTO toDTO(AgentImConnector connector) {
		Map<String, Object> config = jsonSupport.maskMap(effectiveRuntimeConfig(connector));
		Map<String, Object> stream = streamStatus(connector.getId());
		return new ImConnectorDTO(connector.getId(), connector.getConnectorCode(), connector.getConnectorName(),
				connector.getProvider(), connector.getAuthType(), config, connector.getCredentialRef(),
				connector.getDefaultAgentId(), connector.getDirectEnabled(), connector.getGroupEnabled(),
				connector.getStatus(), connector.getDisplayOrder(), stringValue(stream.get("streamStatus")),
				stringValue(stream.get("leaseOwner")), stringValue(stream.get("lastError")));
	}

	private Map<String, Object> streamStatus(Long connectorId) {
		DingTalkStreamLifecycleService lifecycleService = streamLifecycleServiceProvider.getIfAvailable();
		if (lifecycleService == null || connectorId == null) {
			return Map.of();
		}
		return lifecycleService.status(connectorId);
	}

	private Map<String, Object> effectiveRuntimeConfig(AgentImConnector connector) {
		Map<String, Object> config = new LinkedHashMap<>(runtimeConfig(connector));
		if (!config.containsKey(REPLY_WEBHOOK_ALLOWED_HOSTS)
				|| isLegacyMaskedWhitelist(config.get(REPLY_WEBHOOK_ALLOWED_HOSTS))) {
			config.put(REPLY_WEBHOOK_ALLOWED_HOSTS,
					effectiveReplyWebhookAllowedHosts(connector == null ? null : connector.getProvider(), config));
		}
		return config;
	}

	private List<String> effectiveReplyWebhookAllowedHosts(String provider, Map<String, Object> config) {
		if (config != null && config.containsKey(REPLY_WEBHOOK_ALLOWED_HOSTS)
				&& !isLegacyMaskedWhitelist(config.get(REPLY_WEBHOOK_ALLOWED_HOSTS))) {
			return normalizeReplyWebhookAllowedHosts(config.get(REPLY_WEBHOOK_ALLOWED_HOSTS));
		}
		return ImConstants.defaultReplyWebhookAllowedHosts(provider);
	}

	private void normalizeReplyWebhookConfig(String provider, Map<String, Object> config, boolean create) {
		if (config == null) {
			return;
		}
		boolean enabled = Boolean.TRUE.equals(config.get("webhookReplyEnabled"))
				|| "true".equalsIgnoreCase(stringValue(config.get("webhookReplyEnabled")));
		if (!config.containsKey(REPLY_WEBHOOK_ALLOWED_HOSTS)) {
			if (create && enabled) {
				List<String> defaults = ImConstants.defaultReplyWebhookAllowedHosts(provider);
				if (defaults.isEmpty()) {
					throw CheckedException.badRequest(ImErrorDict.REPLY_WEBHOOK_INVALID.getValue(),
							ImErrorDict.REPLY_WEBHOOK_INVALID.getLabel());
				}
				config.put(REPLY_WEBHOOK_ALLOWED_HOSTS, defaults);
			}
			return;
		}
		List<String> normalized = normalizeReplyWebhookAllowedHosts(config.get(REPLY_WEBHOOK_ALLOWED_HOSTS));
		if (enabled && normalized.isEmpty()) {
			throw CheckedException.badRequest(ImErrorDict.REPLY_WEBHOOK_INVALID.getValue(),
					"启用 Webhook 回复时必须配置回复域名白名单");
		}
		config.put(REPLY_WEBHOOK_ALLOWED_HOSTS, normalized);
	}

	private void validateReplyWebhookConfig(String provider, Map<String, Object> config, boolean create) {
		if (config == null) {
			return;
		}
		normalizeReplyWebhookConfig(provider, new LinkedHashMap<>(config), create);
	}

	private List<String> normalizeReplyWebhookAllowedHosts(Object value) {
		Set<String> normalized = new LinkedHashSet<>();
		if (value instanceof Iterable<?> values) {
			for (Object item : values) {
				addReplyWebhookHost(normalized, stringValue(item));
			}
		}
		else if (value != null && value.getClass().isArray()) {
			for (int i = 0; i < Array.getLength(value); i++) {
				addReplyWebhookHost(normalized, stringValue(Array.get(value, i)));
			}
		}
		else if (value != null) {
			for (String item : stringValue(value).split(",")) {
				addReplyWebhookHost(normalized, item);
			}
		}
		return List.copyOf(normalized);
	}

	private boolean isLegacyMaskedWhitelist(Object value) {
		List<String> hosts = new ArrayList<>();
		if (value instanceof Iterable<?> values) {
			for (Object item : values) {
				if (StringUtils.hasText(stringValue(item))) {
					hosts.add(stringValue(item));
				}
			}
		}
		else if (value != null) {
			hosts.add(stringValue(value));
		}
		return !hosts.isEmpty() && hosts.stream().allMatch(host -> host.contains("****"));
	}

	private void addReplyWebhookHost(Set<String> normalized, String value) {
		if (!StringUtils.hasText(value)) {
			return;
		}
		String host = value.trim().toLowerCase(Locale.ROOT);
		if (host.contains("://") || host.contains("/") || host.contains("@") || host.contains(":")
				|| host.contains("*") || "localhost".equals(host) || isIpLiteral(host)
				|| !HOST_PATTERN.matcher(host).matches()) {
			throw CheckedException.badRequest(ImErrorDict.REPLY_WEBHOOK_INVALID.getValue(),
					"回复域名白名单必须是纯域名，不得包含协议、端口、路径、用户信息、通配符、IP 或 localhost");
		}
		normalized.add(host);
	}

	private boolean isIpLiteral(String value) {
		if (value.contains(":")) {
			return true;
		}
		String[] parts = value.split("\\.");
		if (parts.length != 4) {
			return false;
		}
		for (String part : parts) {
			if (!part.matches("\\d+")) {
				return false;
			}
		}
		return true;
	}

	private Boolean defaultBoolean(Boolean value, boolean defaultValue) {
		return value == null ? defaultValue : value;
	}

	private void publishChanged(AgentImConnector connector) {
		if (connector != null && connector.getId() != null) {
			eventPublisher.publishEvent(new ImConnectorChangedEvent(connector.getId(), connector.getConnectorCode()));
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
