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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.im.entity.AgentImConnector;
import com.sn68.agent.dataagent.im.enums.ImConstants;
import com.sn68.agent.dataagent.im.event.ImProviderConfigChangedEvent;
import com.sn68.agent.dataagent.im.repository.AgentImConnectorMapper;
import com.sn68.agent.dataagent.notification.service.NotificationJsonSupport;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class DingTalkStreamLifecycleServiceTest {

	private AgentImConnectorMapper connectorMapper;
	private NotificationJsonSupport jsonSupport;
	private ImRuntimeConfigService runtimeConfigService;
	private StringRedisTemplate redisTemplate;
	private ValueOperations<String, String> valueOperations;
	private DingTalkStreamLifecycleService service;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		connectorMapper = Mockito.mock(AgentImConnectorMapper.class);
		jsonSupport = Mockito.mock(NotificationJsonSupport.class);
		runtimeConfigService = Mockito.mock(ImRuntimeConfigService.class);
		redisTemplate = Mockito.mock(StringRedisTemplate.class);
		valueOperations = Mockito.mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(connectorMapper.findAllOrderedAllTenants()).thenReturn(List.of());
		ObjectProvider<StringRedisTemplate> redisProvider = Mockito.mock(ObjectProvider.class);
		when(redisProvider.getIfAvailable()).thenReturn(redisTemplate);
		when(runtimeConfigService.providerRuntimeConfig(ImConstants.PROVIDER_DINGTALK))
			.thenReturn(defaultRuntimeConfig());
		service = new DingTalkStreamLifecycleService(connectorMapper, jsonSupport, runtimeConfigService,
				Mockito.mock(DingTalkStreamMessageHandler.class), redisProvider);
	}

	@Test
	void occupancyValueRoundTripsConnectorId() {
		AgentImConnector connector = connector(11L, "dingtalk-customer-service", "100");
		String value = DingTalkStreamLifecycleService.occupancyValue(connector, "inst-1");
		assertEquals(11L, DingTalkStreamLifecycleService.parseOccupancyConnectorId(value));
		assertTrue(DingTalkStreamLifecycleService.occupancyConflictMessage(value).contains("dingtalk-customer-service"));
		assertTrue(DingTalkStreamLifecycleService.occupancyConflictMessage(value).contains("100"));
	}

	@Test
	void claimAppOccupancyRejectsDifferentConnector() {
		AgentImConnector connector = connector(11L, "current", "100");
		DingTalkStreamLifecycleService.StreamRuntime runtime = DingTalkStreamLifecycleService.StreamRuntime
			.connecting(11L, "current");
		when(valueOperations.setIfAbsent(eq(DingTalkStreamLifecycleService.APP_OCCUPANCY_PREFIX + "ding-app"), any(),
				any(Duration.class))).thenReturn(false);
		when(valueOperations.get(DingTalkStreamLifecycleService.APP_OCCUPANCY_PREFIX + "ding-app"))
			.thenReturn(DingTalkStreamLifecycleService.occupancyValue(connector(99L, "other", "200"), "other-inst"));

		String error = service.claimAppOccupancy(connector, "ding-app", runtime, defaultRuntimeConfig());

		assertNotNull(error);
		assertTrue(error.contains("other"));
		assertTrue(error.contains("200"));
		verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
	}

	@Test
	void claimAppOccupancyAllowsSameConnectorToTakeOver() {
		AgentImConnector connector = connector(11L, "current", "100");
		DingTalkStreamLifecycleService.StreamRuntime runtime = DingTalkStreamLifecycleService.StreamRuntime
			.connecting(11L, "current");
		when(valueOperations.setIfAbsent(eq(DingTalkStreamLifecycleService.APP_OCCUPANCY_PREFIX + "ding-app"), any(),
				any(Duration.class))).thenReturn(false);
		when(valueOperations.get(DingTalkStreamLifecycleService.APP_OCCUPANCY_PREFIX + "ding-app"))
			.thenReturn(DingTalkStreamLifecycleService.occupancyValue(connector, "old-inst"));

		assertNull(service.claimAppOccupancy(connector, "ding-app", runtime, defaultRuntimeConfig()));
		verify(valueOperations).set(eq(DingTalkStreamLifecycleService.APP_OCCUPANCY_PREFIX + "ding-app"), anyString(),
				any(Duration.class));
	}

	@Test
	void refreshDoesNotOpenStreamWhenConnectorLeaseHeldByOtherInstance() {
		AgentImConnector connector = connector(11L, "current", "100");
		when(connectorMapper.selectById(11L)).thenReturn(connector);
		when(jsonSupport.readEncryptedMap(any())).thenReturn(Map.of("mode", ImConstants.CONNECT_MODE_STREAM,
				"streamAutoStart", true, "clientId", "ding-app", "clientSecret", "secret"));
		when(valueOperations.setIfAbsent(eq(DingTalkStreamLifecycleService.LEASE_PREFIX + "11"), any(), any(Duration.class)))
			.thenReturn(false);
		when(valueOperations.get(DingTalkStreamLifecycleService.LEASE_PREFIX + "11")).thenReturn("other-instance:11");

		service.refresh(11L);

		Map<String, Object> status = service.status(11L);
		assertEquals("STOPPED", status.get("streamStatus"));
		assertEquals("其他实例已持有 Stream 租约", status.get("lastError"));
		verify(valueOperations, never()).setIfAbsent(eq(DingTalkStreamLifecycleService.APP_OCCUPANCY_PREFIX + "ding-app"),
				any(), any(Duration.class));
	}

	@Test
	void refreshDoesNotOpenStreamWhenClientIdOccupiedByAnotherConnector() {
		AgentImConnector connector = connector(11L, "current", "100");
		when(connectorMapper.selectById(11L)).thenReturn(connector);
		when(jsonSupport.readEncryptedMap(any())).thenReturn(Map.of("mode", ImConstants.CONNECT_MODE_STREAM,
				"streamAutoStart", true, "clientId", "ding-app", "clientSecret", "secret"));
		when(valueOperations.setIfAbsent(eq(DingTalkStreamLifecycleService.LEASE_PREFIX + "11"), any(), any(Duration.class)))
			.thenAnswer(invocation -> {
				when(valueOperations.get(DingTalkStreamLifecycleService.LEASE_PREFIX + "11"))
					.thenReturn(invocation.getArgument(1));
				return true;
			});
		when(valueOperations.setIfAbsent(eq(DingTalkStreamLifecycleService.APP_OCCUPANCY_PREFIX + "ding-app"), any(),
				any(Duration.class))).thenReturn(false);
		when(valueOperations.get(DingTalkStreamLifecycleService.APP_OCCUPANCY_PREFIX + "ding-app"))
			.thenReturn("200|99|other|inst");

		service.refresh(11L);

		Map<String, Object> status = service.status(11L);
		assertEquals("FAILED", status.get("streamStatus"));
		assertTrue(String.valueOf(status.get("lastError")).contains("other"));
		verify(redisTemplate).delete(DingTalkStreamLifecycleService.LEASE_PREFIX + "11");
	}

	@Test
	void providerConfigChangeWithSameStreamSettingsDoesNotRefreshConnectors() {
		service.startAll();
		when(connectorMapper.findAllOrderedAllTenants()).thenReturn(List.of(connector(11L, "current", "100")));

		service.onProviderConfigChanged(new ImProviderConfigChangedEvent(ImConstants.PROVIDER_DINGTALK));

		verify(connectorMapper, Mockito.times(1)).findAllOrderedAllTenants();
		verify(connectorMapper, never()).selectById(11L);
	}

	@Test
	void maskClientIdHidesMiddle() {
		assertEquals("din****pp", DingTalkStreamLifecycleService.maskClientId("ding-app"));
		assertEquals("****", DingTalkStreamLifecycleService.maskClientId("abc"));
	}

	@Test
	void streamWorkerFingerprintIgnoresMessageCopy() {
		ImRuntimeConfigService.ProviderRuntimeConfig a = defaultRuntimeConfig();
		ImRuntimeConfigService.ProviderRuntimeConfig b = new ImRuntimeConfigService.ProviderRuntimeConfig(
				a.provider(), "https://example.invalid", a.setupSessionTtlMinutes(), a.credentialValidationTimeoutMs(),
				a.streamConnectTimeoutMs(), a.streamReconnectDelayMs(), a.streamWorkerEnabled(),
				a.streamLeaseTtlSeconds(), a.streamLeaseRenewSeconds(), a.userResolveEnabled(), a.userResolveTimeoutMs(),
				a.tokenUrl(), a.userInfoUrlTemplate(), a.platformSendTimeoutMs(), a.iamTimeoutMs(),
				a.delegatedTokenTtlSeconds(), a.delegatedClientId(), a.delegatedDevice(), a.autoBindByContact(),
				a.defaultSingleTriggerPolicy(), a.defaultGroupTriggerPolicy(), "other unbound message",
				a.authSnapshotFailedMessage(), a.disabledConnectorMessage(), a.unsupportedMessageTypeMessage(),
				a.thinkingMessageEnabled(), a.thinkingMessageText(), a.agentInvokeFailedMessage(),
				a.agentInvokeTimeoutMessage());
		assertEquals(DingTalkStreamLifecycleService.streamWorkerFingerprint(a),
				DingTalkStreamLifecycleService.streamWorkerFingerprint(b));
	}

	private AgentImConnector connector(Long id, String code, String tenantId) {
		AgentImConnector connector = new AgentImConnector();
		connector.setId(id);
		connector.setTenantId(tenantId);
		connector.setProvider(ImConstants.PROVIDER_DINGTALK);
		connector.setConnectorCode(code);
		connector.setStatus(ImConstants.STATUS_ENABLED);
		connector.setEncryptedConfig("{}");
		return connector;
	}

	private ImRuntimeConfigService.ProviderRuntimeConfig defaultRuntimeConfig() {
		return new ImRuntimeConfigService.ProviderRuntimeConfig(ImConstants.PROVIDER_DINGTALK,
				"https://open-dev.dingtalk.com", 30, 5000, 5000, 5000, true, 90, 30, true, 5000,
				"https://api.dingtalk.com/v1.0/oauth2/accessToken",
				"https://api.dingtalk.com/v1.0/contact/users/{userId}", 5000, 5000, 900L, "pc-web", "delegated-agent",
				true, ImConstants.TRIGGER_ALWAYS, ImConstants.TRIGGER_MENTION, "unbound", "auth-failed", "disabled",
				"unsupported", true, "thinking", "failed", "timeout");
	}

}
