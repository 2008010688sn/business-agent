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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.im.adapter.ImAdapterRegistry;
import com.sn68.agent.dataagent.im.dto.DingTalkCredentialValidateResponse;
import com.sn68.agent.dataagent.im.dto.ImConnectorDTO;
import com.sn68.agent.dataagent.im.dto.ImSetupCompleteRequest;
import com.sn68.agent.dataagent.im.dto.ImSetupInitRequest;
import com.sn68.agent.dataagent.im.dto.ImSetupInitResponse;
import com.sn68.agent.dataagent.im.entity.AgentImConnector;
import com.sn68.agent.dataagent.im.entity.AgentImSetupSession;
import com.sn68.agent.dataagent.im.enums.ImConstants;
import com.sn68.agent.dataagent.im.repository.AgentImConnectorMapper;
import com.sn68.agent.dataagent.im.repository.AgentImSetupSessionMapper;
import com.sn68.agent.dataagent.notification.service.NotificationJsonSupport;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.service.security.SensitiveConfigCryptoService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImSetupServiceTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	// 本类断言的是密钥「只存连接器配置、占位符提交时保留原值」的落库形态，需保持明文可比；
	// S-6 已把加密默认改为开启（缺 key 即启动失败），故这里显式关掉。
	// 加密默认值与缺 key 时的启动失败由 SensitiveConfigCryptoServiceTest 覆盖。
	private final DataAgentProperties properties = cryptoDisabledProperties();

	private final NotificationJsonSupport jsonSupport = new NotificationJsonSupport(objectMapper,
			new SensitiveConfigCryptoService(properties));

	// IM 接入向导/连接器已租户化：服务内经 AuthenticationContext 解析当前租户，测试固定为租户 100。
	private final AuthenticationContext authenticationContext = tenantAuthenticationContext();

	private static DataAgentProperties cryptoDisabledProperties() {
		DataAgentProperties properties = new DataAgentProperties();
		properties.getCrypto().setEnabled(false);
		return properties;
	}

	private static AuthenticationContext tenantAuthenticationContext() {
		AuthenticationContext context = mock(AuthenticationContext.class);
		when(context.tenantId()).thenReturn("100");
		return context;
	}

	@Test
	void completeDingTalkSetupStoresSecretOnlyInConnectorConfig() {
		AgentImSetupSessionMapper setupMapper = mock(AgentImSetupSessionMapper.class);
		AgentImConnectorMapper connectorMapper = mock(AgentImConnectorMapper.class);
		DingTalkCredentialService credentialService = mock(DingTalkCredentialService.class);
		AtomicReference<AgentImSetupSession> savedSession = new AtomicReference<>();
		AtomicReference<AgentImConnector> savedConnector = new AtomicReference<>();
		when(credentialService.validate("client-id", "client-secret"))
			.thenReturn(new DingTalkCredentialValidateResponse(true, "OK", "cli****id"));
		doAnswer(invocation -> {
			AgentImSetupSession session = invocation.getArgument(0);
			session.setId(1L);
			savedSession.set(session);
			return 1;
		}).when(setupMapper).insert(any(AgentImSetupSession.class));
		doAnswer(invocation -> {
			savedSession.set(invocation.getArgument(0));
			return 1;
		}).when(setupMapper).updateById(any(AgentImSetupSession.class));
		when(setupMapper.findBySetupId(any(), any())).thenAnswer(invocation -> savedSession.get());
		when(connectorMapper.findByTenantProviderAndCode(any(), any(), any())).thenReturn(null);
		doAnswer(invocation -> {
			AgentImConnector connector = invocation.getArgument(0);
			connector.setId(9L);
			savedConnector.set(connector);
			return 1;
		}).when(connectorMapper).insert(any(AgentImConnector.class));
		ImSetupService setupService = setupService(setupMapper, connectorMapper, credentialService);
		ImSetupInitResponse init = setupService
			.initDingTalk(new ImSetupInitRequest("测试钉钉", 100L, true, false, "GUIDE"));

		ImConnectorDTO connector = setupService.complete(init.setupId(), new ImSetupCompleteRequest(init.setupId(),
				"dingtalk-demo", "测试钉钉", 100L, "client-id", "client-secret", "机器人", "简介", true, false));

		assertEquals("dingtalk-demo", connector.connectorCode());
		assertEquals(ImConstants.SETUP_STATUS_COMPLETED, savedSession.get().getSetupStatus());
		assertFalse(String.valueOf(savedSession.get().getRequestedConfig()).contains("client-secret"));
		Map<String, Object> runtimeConfig = jsonSupport.readEncryptedMap(savedConnector.get().getEncryptedConfig());
		assertEquals(ImConstants.CONNECT_MODE_STREAM, runtimeConfig.get("mode"));
		assertEquals("client-secret", runtimeConfig.get("clientSecret"));
		assertTrue(Boolean.TRUE.equals(runtimeConfig.get("streamAutoStart")));
	}

	@Test
	void connectorUpdatePreservesExistingSecretWhenPlaceholderIsSubmitted() {
		AgentImConnectorMapper connectorMapper = mock(AgentImConnectorMapper.class);
		AgentImConnector existing = new AgentImConnector();
		existing.setId(7L);
		existing.setTenantId("100");
		existing.setConnectorCode("dingtalk-demo");
		existing.setConnectorName("旧名称");
		existing.setProvider(ImConstants.PROVIDER_DINGTALK);
		existing.setEncryptedConfig(jsonSupport.writeEncryptedMap(Map.of("clientSecret", "old-secret", "clientId", "old-id")));
		when(connectorMapper.selectById(7L)).thenReturn(existing);
		doAnswer(invocation -> 1).when(connectorMapper).updateById(any(AgentImConnector.class));
		ImConnectorService connectorService = connectorService(connectorMapper, mock(DingTalkCredentialService.class));

		connectorService.update(7L, new ImConnectorDTO(7L, "dingtalk-demo", "新名称", ImConstants.PROVIDER_DINGTALK,
				ImConstants.CONNECT_MODE_STREAM, Map.of("clientSecret", "****", "clientId", "new-id"), null, 100L, true,
				false, ImConstants.STATUS_ENABLED, 0, null, null, null));

		Map<String, Object> runtimeConfig = jsonSupport.readEncryptedMap(existing.getEncryptedConfig());
		assertEquals("old-secret", runtimeConfig.get("clientSecret"));
		assertEquals("new-id", runtimeConfig.get("clientId"));
	}

	@Test
	void connectorCreateAddsAndNormalizesOfficialReplyWhitelist() {
		AgentImConnectorMapper connectorMapper = mock(AgentImConnectorMapper.class);
		AtomicReference<AgentImConnector> saved = new AtomicReference<>();
		when(connectorMapper.findByTenantProviderAndCode(any(), any(), any())).thenReturn(null);
		doAnswer(invocation -> {
			AgentImConnector connector = invocation.getArgument(0);
			connector.setId(9L);
			saved.set(connector);
			return 1;
		}).when(connectorMapper).insert(any(AgentImConnector.class));
		ImConnectorService connectorService = connectorService(connectorMapper, mock(DingTalkCredentialService.class));

		connectorService.create(new ImConnectorDTO(null, "dingtalk-demo", "测试钉钉", ImConstants.PROVIDER_DINGTALK,
				ImConstants.CONNECT_MODE_STREAM, Map.of("webhookReplyEnabled", true), null, 100L, true, false,
				ImConstants.STATUS_ENABLED, 0, null, null, null));

		Map<String, Object> runtimeConfig = jsonSupport.readEncryptedMap(saved.get().getEncryptedConfig());
		assertEquals(ImConstants.DINGTALK_REPLY_WEBHOOK_ALLOWED_HOSTS,
				runtimeConfig.get("replyWebhookAllowedHosts"));
	}

	@Test
	void connectorRejectsExplicitEmptyOrInvalidReplyWhitelist() {
		AgentImConnectorMapper connectorMapper = mock(AgentImConnectorMapper.class);
		when(connectorMapper.findByTenantProviderAndCode(any(), any(), any())).thenReturn(null);
		ImConnectorService connectorService = connectorService(connectorMapper, mock(DingTalkCredentialService.class));
		ImConnectorDTO emptyWhitelist = new ImConnectorDTO(null, "dingtalk-empty", "测试钉钉",
				ImConstants.PROVIDER_DINGTALK, ImConstants.CONNECT_MODE_STREAM,
				Map.of("webhookReplyEnabled", true, "replyWebhookAllowedHosts", List.of()), null, 100L, true, false,
				ImConstants.STATUS_ENABLED, 0, null, null, null);
		ImConnectorDTO invalidWhitelist = new ImConnectorDTO(null, "dingtalk-invalid", "测试钉钉",
				ImConstants.PROVIDER_DINGTALK, ImConstants.CONNECT_MODE_STREAM,
				Map.of("webhookReplyEnabled", true, "replyWebhookAllowedHosts", List.of("https://oapi.dingtalk.com")),
				null, 100L, true, false, ImConstants.STATUS_ENABLED, 0, null, null, null);

		assertThrows(CheckedException.class, () -> connectorService.create(emptyWhitelist));
		assertThrows(CheckedException.class, () -> connectorService.create(invalidWhitelist));
	}

	@Test
	void replyWhitelistDomainsAreNotMaskedForPageEditing() {
		Map<String, Object> masked = jsonSupport.maskMap(
				Map.of("replyWebhookAllowedHosts", ImConstants.DINGTALK_REPLY_WEBHOOK_ALLOWED_HOSTS));

		assertEquals(ImConstants.DINGTALK_REPLY_WEBHOOK_ALLOWED_HOSTS,
				masked.get("replyWebhookAllowedHosts"));
	}

	private ImSetupService setupService(AgentImSetupSessionMapper setupMapper, AgentImConnectorMapper connectorMapper,
			DingTalkCredentialService credentialService) {
		return new ImSetupService(setupMapper, connectorMapper, connectorService(connectorMapper, credentialService),
				credentialService, jsonSupport, runtimeConfigService(), authenticationContext);
	}

	private ImRuntimeConfigService runtimeConfigService() {
		ImRuntimeConfigService runtimeConfigService = mock(ImRuntimeConfigService.class);
		when(runtimeConfigService.providerRuntimeConfig(ImConstants.PROVIDER_DINGTALK))
			.thenReturn(defaultRuntimeConfig());
		return runtimeConfigService;
	}

	private ImRuntimeConfigService.ProviderRuntimeConfig defaultRuntimeConfig() {
		return new ImRuntimeConfigService.ProviderRuntimeConfig(ImConstants.PROVIDER_DINGTALK,
				"https://open-dev.dingtalk.com", 30, 5000, 5000, 5000, true, 90, 30, true, 5000,
				"https://api.dingtalk.com/v1.0/oauth2/accessToken",
				"https://api.dingtalk.com/v1.0/contact/users/{userId}", 5000, 5000, 900L, "pc-web",
				"delegated-agent", true, ImConstants.TRIGGER_ALWAYS, ImConstants.TRIGGER_MENTION,
				"未识别到您的系统账号，请先联系管理员完成 IM 用户绑定。", "暂时无法确认您的登录身份，请稍后重试。",
				"当前 IM 对话连接器未启用。", "暂时只支持文本消息。", true,
				"正在思考中，请耐心等候...", "Agent 执行失败，请稍后再试。",
				"本次分析超时，请缩小查询范围或补充筛选条件后重试；如果已触发后台任务，请稍后查看结果。");
	}

	@SuppressWarnings("unchecked")
	private ImConnectorService connectorService(AgentImConnectorMapper connectorMapper,
			DingTalkCredentialService credentialService) {
		ObjectProvider<DingTalkStreamLifecycleService> streamLifecycleProvider = mock(ObjectProvider.class);
		return new ImConnectorService(connectorMapper, mock(ImAdapterRegistry.class), jsonSupport, credentialService,
				streamLifecycleProvider, mock(ApplicationEventPublisher.class), authenticationContext);
	}

}
