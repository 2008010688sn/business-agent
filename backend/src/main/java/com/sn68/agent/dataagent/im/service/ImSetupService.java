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

import com.sn68.agent.dataagent.im.dto.DingTalkCredentialValidateRequest;
import com.sn68.agent.dataagent.im.dto.DingTalkCredentialValidateResponse;
import com.sn68.agent.dataagent.im.dto.ImConnectorDTO;
import com.sn68.agent.dataagent.im.dto.ImSetupCompleteRequest;
import com.sn68.agent.dataagent.im.dto.ImSetupInitRequest;
import com.sn68.agent.dataagent.im.dto.ImSetupInitResponse;
import com.sn68.agent.dataagent.im.dto.ImSetupStatusDTO;
import com.sn68.agent.dataagent.im.entity.AgentImConnector;
import com.sn68.agent.dataagent.im.entity.AgentImSetupSession;
import com.sn68.agent.dataagent.im.enums.ImConstants;
import com.sn68.agent.dataagent.im.enums.ImErrorDict;
import com.sn68.agent.dataagent.im.repository.AgentImConnectorMapper;
import com.sn68.agent.dataagent.im.repository.AgentImSetupSessionMapper;
import com.sn68.agent.dataagent.notification.service.NotificationJsonSupport;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * IM 接入向导服务（管理端，安装会话与产出的连接器均归属当前登录租户）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImSetupService {

	private final AgentImSetupSessionMapper setupSessionMapper;

	private final AgentImConnectorMapper connectorMapper;

	private final ImConnectorService connectorService;

	private final DingTalkCredentialService dingTalkCredentialService;

	private final NotificationJsonSupport jsonSupport;

	private final ImRuntimeConfigService runtimeConfigService;

	private final AuthenticationContext authenticationContext;

	/**
	 * 创建ImSetup。
	 */
	@Transactional(rollbackFor = Exception.class)
	public ImSetupInitResponse initDingTalk(ImSetupInitRequest request) {
		ImRuntimeConfigService.ProviderRuntimeConfig runtimeConfig = runtimeConfigService
			.providerRuntimeConfig(ImConstants.PROVIDER_DINGTALK);
		String setupId = "imsetup-" + UUID.randomUUID().toString().replace("-", "");
		LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(Math.max(1, runtimeConfig.setupSessionTtlMinutes()));
		AgentImSetupSession session = new AgentImSetupSession();
		session.setTenantId(requireCurrentTenantId());
		session.setSetupId(setupId);
		session.setProvider(ImConstants.PROVIDER_DINGTALK);
		session.setConnectMode(ImConstants.CONNECT_MODE_STREAM);
		session.setSetupSource(firstText(request == null ? null : request.setupSource(), "GUIDE"));
		session.setSetupStatus(ImConstants.SETUP_STATUS_PENDING);
		session.setConnectorName(firstText(request == null ? null : request.connectorName(), "钉钉 IM 连接器"));
		session.setDefaultAgentId(request == null ? null : request.defaultAgentId());
		session.setDirectEnabled(request == null || request.directEnabled() == null || Boolean.TRUE.equals(request.directEnabled()));
		session.setGroupEnabled(request != null && Boolean.TRUE.equals(request.groupEnabled()));
		session.setRequestedConfig(jsonSupport.writeJson(Map.of("streamAutoStart", true, "webhookReplyEnabled", true)));
		session.setExpireTime(expiresAt);
		setupSessionMapper.insert(session);
		return new ImSetupInitResponse(setupId, ImConstants.PROVIDER_DINGTALK, ImConstants.CONNECT_MODE_STREAM,
				runtimeConfig.developerConsoleUrl(), runtimeConfig.developerConsoleUrl(), expiresAt,
				List.of("defaultAgentId", "clientId", "clientSecret"));
	}

	/**
	 * 处理ImSetup。
	 */
	public ImSetupStatusDTO status(String setupId) {
		AgentImSetupSession session = requireSession(setupId);
		if (isExpired(session) && !ImConstants.SETUP_STATUS_COMPLETED.equals(session.getSetupStatus())) {
			session.setSetupStatus(ImConstants.SETUP_STATUS_EXPIRED);
			setupSessionMapper.updateById(session);
		}
		return toStatus(session);
	}

	/**
	 * 校验ImSetup。
	 */
	@Transactional(rollbackFor = Exception.class)
	public DingTalkCredentialValidateResponse validateDingTalk(DingTalkCredentialValidateRequest request) {
		if (request == null) {
			throw badRequest(ImErrorDict.SETUP_REQUEST_INVALID);
		}
		AgentImSetupSession session = requireActiveSession(request.setupId());
		DingTalkCredentialValidateResponse response = dingTalkCredentialService.validate(request.clientId(),
				request.clientSecret());
		session.setSetupStatus(ImConstants.SETUP_STATUS_VALIDATED);
		session.setValidationResult(jsonSupport.writeJson(Map.of("success", response.success(), "message", response.message(),
				"clientIdMasked", response.clientIdMasked())));
		setupSessionMapper.updateById(session);
		return response;
	}

	/**
	 * 保存ImSetup。
	 */
	@Transactional(rollbackFor = Exception.class)
	public ImConnectorDTO complete(String setupId, ImSetupCompleteRequest request) {
		AgentImSetupSession session = requireActiveSession(firstText(setupId, request == null ? null : request.setupId()));
		if (request == null || !StringUtils.hasText(request.clientId()) || !StringUtils.hasText(request.clientSecret())) {
			throw badRequest(ImErrorDict.SETUP_REQUEST_INVALID);
		}
		Long defaultAgentId = request.defaultAgentId() == null ? session.getDefaultAgentId() : request.defaultAgentId();
		if (defaultAgentId == null) {
			throw badRequest(ImErrorDict.SETUP_REQUEST_INVALID);
		}
		dingTalkCredentialService.validate(request.clientId(), request.clientSecret());
		ImRuntimeConfigService.ProviderRuntimeConfig runtimeConfig = runtimeConfigService
			.providerRuntimeConfig(ImConstants.PROVIDER_DINGTALK);
		String connectorCode = firstText(request.connectorCode(), session.getConnectorCode(), generatedConnectorCode(session));
		String connectorName = firstText(request.connectorName(), session.getConnectorName(), "钉钉 IM 连接器");
		Map<String, Object> config = new LinkedHashMap<>();
		config.put("mode", ImConstants.CONNECT_MODE_STREAM);
		config.put("clientId", request.clientId().trim());
		config.put("clientSecret", request.clientSecret().trim());
		config.put("streamAutoStart", true);
		config.put("webhookReplyEnabled", true);
		config.put("userResolveEnabled", runtimeConfig.userResolveEnabled());
		putIfText(config, "botName", request.botName());
		putIfText(config, "botDescription", request.botDescription());
		AgentImConnector existing = connectorMapper.findByTenantProviderAndCode(requireCurrentTenantId(),
				ImConstants.PROVIDER_DINGTALK, connectorCode);
		ImConnectorDTO payload = new ImConnectorDTO(existing == null ? null : existing.getId(), connectorCode, connectorName,
				ImConstants.PROVIDER_DINGTALK, ImConstants.CONNECT_MODE_STREAM, config, null, defaultAgentId,
				request.directEnabled() == null ? session.getDirectEnabled() : request.directEnabled(),
				request.groupEnabled() == null ? session.getGroupEnabled() : request.groupEnabled(),
				ImConstants.STATUS_ENABLED, existing == null ? 0 : existing.getDisplayOrder(), null, null, null);
		ImConnectorDTO connector = existing == null ? connectorService.create(payload)
				: connectorService.update(existing.getId(), payload);
		session.setSetupStatus(ImConstants.SETUP_STATUS_COMPLETED);
		session.setConnectorCode(connector.connectorCode());
		session.setConnectorName(connector.connectorName());
		session.setDefaultAgentId(connector.defaultAgentId());
		session.setDirectEnabled(connector.directEnabled());
		session.setGroupEnabled(connector.groupEnabled());
		session.setResultConnectorCode(connector.connectorCode());
		session.setValidationResult(jsonSupport.writeJson(Map.of("success", true, "message", "安装完成")));
		setupSessionMapper.updateById(session);
		return connector;
	}

	private AgentImSetupSession requireSession(String setupId) {
		AgentImSetupSession session = setupSessionMapper.findBySetupId(requireCurrentTenantId(), setupId);
		if (session == null) {
			throw CheckedException.notFound(ImErrorDict.SETUP_SESSION_NOT_FOUND.getValue(),
					ImErrorDict.SETUP_SESSION_NOT_FOUND.getLabel());
		}
		return session;
	}

	private String requireCurrentTenantId() {
		String tenantId;
		try {
			tenantId = authenticationContext.tenantId();
		}
		catch (Exception ex) {
			log.warn("解析当前租户上下文失败, 将按缺失租户拒绝本次 IM 接入向导操作", ex);
			tenantId = null;
		}
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.badRequest("租户上下文缺失, 无法执行 IM 接入向导");
		}
		return tenantId;
	}

	private AgentImSetupSession requireActiveSession(String setupId) {
		AgentImSetupSession session = requireSession(setupId);
		if (isExpired(session) || ImConstants.SETUP_STATUS_EXPIRED.equals(session.getSetupStatus())) {
			session.setSetupStatus(ImConstants.SETUP_STATUS_EXPIRED);
			setupSessionMapper.updateById(session);
			throw badRequest(ImErrorDict.SETUP_SESSION_EXPIRED);
		}
		return session;
	}

	private CheckedException badRequest(ImErrorDict error) {
		return CheckedException.badRequest(error.getValue(), error.getLabel());
	}

	private ImSetupStatusDTO toStatus(AgentImSetupSession session) {
		return new ImSetupStatusDTO(session.getSetupId(), session.getSetupStatus(), session.getConnectorCode(),
				session.getConnectorName(), session.getDefaultAgentId(), jsonSupport.readMap(session.getValidationResult()),
				session.getResultConnectorCode(), session.getExpireTime());
	}

	private boolean isExpired(AgentImSetupSession session) {
		return session.getExpireTime() != null && session.getExpireTime().isBefore(LocalDateTime.now());
	}

	private String generatedConnectorCode(AgentImSetupSession session) {
		String setupId = session.getSetupId();
		String suffix = setupId == null || setupId.length() < 8 ? UUID.randomUUID().toString().substring(0, 8)
				: setupId.substring(setupId.length() - 8);
		return "dingtalk-" + suffix;
	}

	private void putIfText(Map<String, Object> config, String key, String value) {
		if (StringUtils.hasText(value)) {
			config.put(key, value.trim());
		}
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
