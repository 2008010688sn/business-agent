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

import com.sn68.agent.dataagent.im.dto.ImProviderConfigDTO;
import com.sn68.agent.dataagent.im.entity.AgentImProviderConfig;
import com.sn68.agent.dataagent.im.event.ImProviderConfigChangedEvent;
import com.sn68.agent.dataagent.im.enums.ImConstants;
import com.sn68.agent.dataagent.im.enums.ImErrorDict;
import com.sn68.agent.dataagent.im.repository.AgentImProviderConfigMapper;
import com.sn68.agent.dataagent.notification.service.NotificationJsonSupport;
import com.sn68.agent.dataagent.service.permission.PlatformScopePermissionService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * IM 平台配置管理服务。
 */
@Service
@RequiredArgsConstructor
public class ImProviderConfigService {

	private final AgentImProviderConfigMapper providerConfigMapper;

	private final NotificationJsonSupport jsonSupport;

	private final ApplicationEventPublisher eventPublisher;

	private final PlatformScopePermissionService platformScopePermissionService;

	/**
	 * 查询ImProvider配置。
	 */
	public List<ImProviderConfigDTO> list() {
		return providerConfigMapper.findAllOrdered(requireCurrentTenantId()).stream().map(this::toDTO).toList();
	}

	/**
	 * 查询ImProvider配置。
	 */
	public ImProviderConfigDTO get(String provider) {
		return toDTO(require(provider));
	}

	/**
	 * 校验ImProvider配置。
	 */
	public AgentImProviderConfig require(String provider) {
		AgentImProviderConfig config = providerConfigMapper.findByProvider(provider);
		if (config == null || !requireCurrentTenantId().equals(config.getTenantId())) {
			throw CheckedException.notFound(ImErrorDict.PROVIDER_CONFIG_NOT_INITIALIZED.getValue(),
					ImErrorDict.PROVIDER_CONFIG_NOT_INITIALIZED.getLabel());
		}
		return config;
	}

	/**
	 * 执行ImProvider配置。
	 */
	public Map<String, Object> runtimeConfig(AgentImProviderConfig providerConfig) {
		return providerConfig == null ? Map.of() : jsonSupport.readEncryptedMap(providerConfig.getEncryptedConfig());
	}

	/**
	 * 保存ImProvider配置。
	 */
	@Transactional(rollbackFor = Exception.class)
	public ImProviderConfigDTO save(String provider, ImProviderConfigDTO request) {
		String normalizedProvider = normalizeProvider(firstText(provider, request == null ? null : request.provider()));
		AgentImProviderConfig config = providerConfigMapper.findByProvider(normalizedProvider);
		boolean create = config == null;
		if (create) {
			config = new AgentImProviderConfig();
			config.setProvider(normalizedProvider);
			config.setTenantId(requireCurrentTenantId());
		}
		else if (!requireCurrentTenantId().equals(config.getTenantId())) {
			throw CheckedException.notFound(ImErrorDict.PROVIDER_CONFIG_NOT_INITIALIZED.getValue(),
					ImErrorDict.PROVIDER_CONFIG_NOT_INITIALIZED.getLabel());
		}
		apply(config, request);
		if (create) {
			providerConfigMapper.insert(config);
		}
		else {
			providerConfigMapper.updateById(config);
		}
		eventPublisher.publishEvent(new ImProviderConfigChangedEvent(config.getProvider()));
		return toDTO(config);
	}

	private void apply(AgentImProviderConfig config, ImProviderConfigDTO request) {
		if (request == null) {
			throw CheckedException.badRequest(ImErrorDict.PROVIDER_CONFIG_NOT_INITIALIZED.getValue(),
					ImErrorDict.PROVIDER_CONFIG_NOT_INITIALIZED.getLabel());
		}
		config.setStatus(firstText(request.status(), config.getStatus(), ImConstants.STATUS_ENABLED));
		config.setDisplayOrder(request.displayOrder() == null ? config.getDisplayOrder() : request.displayOrder());
		if (request.config() != null) {
			Map<String, Object> merged = jsonSupport.mergePreservingPlaceholders(runtimeConfig(config), request.config());
			config.setEncryptedConfig(jsonSupport.writeEncryptedMap(merged));
		}
	}

	private ImProviderConfigDTO toDTO(AgentImProviderConfig config) {
		Map<String, Object> visibleConfig = new LinkedHashMap<>(runtimeConfig(config));
		return new ImProviderConfigDTO(config.getId(), config.getProvider(), visibleConfig, config.getStatus(),
				config.getDisplayOrder());
	}

	private String normalizeProvider(String provider) {
		if (!StringUtils.hasText(provider)) {
			throw CheckedException.badRequest(ImErrorDict.PROVIDER_CONFIG_NOT_INITIALIZED.getValue(),
					ImErrorDict.PROVIDER_CONFIG_NOT_INITIALIZED.getLabel());
		}
		return provider.trim().toUpperCase();
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
		return platformScopePermissionService.requireCurrentTenantId("IM 平台配置");
	}

}
