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
package com.sn68.agent.dataagent.task.service;

import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.service.security.SensitiveConfigCryptoService;
import com.sn68.agent.dataagent.task.entity.AgentTaskTrigger;
import com.sn68.agent.dataagent.task.enums.TaskConstants;
import com.sn68.agent.dataagent.task.enums.TaskErrorDict;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * API 触发签名协议测试：验签通过 / 时间窗过期 / nonce 重放拒绝 / 密钥生成与脱敏。
 */
class ApiTriggerSecurityServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-12T10:00:00Z");

	// 关闭加密便于断言明文形态；加密开启行为由 SensitiveConfigCryptoServiceTest 覆盖。
	private final SensitiveConfigCryptoService cryptoService = new SensitiveConfigCryptoService(
			cryptoDisabledProperties());

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

	private final ApiTriggerSecurityService securityService = new ApiTriggerSecurityService(cryptoService,
			redisProvider(), Clock.fixed(NOW, ZoneOffset.UTC));

	private static DataAgentProperties cryptoDisabledProperties() {
		DataAgentProperties properties = new DataAgentProperties();
		properties.getCrypto().setEnabled(false);
		return properties;
	}

	private ObjectProvider<StringRedisTemplate> redisProvider() {
		@SuppressWarnings("unchecked")
		ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(redisTemplate);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		return provider;
	}

	@Test
	void rotateSecretStoresSecretAndVerifyAcceptsValidSignature() {
		AgentTaskTrigger trigger = trigger(null);
		String secret = securityService.rotateSecret(trigger);
		assertEquals(64, secret.length());
		assertEquals(secret, trigger.getTriggerConfig().get(TaskConstants.CONFIG_API_SECRET));

		String timestamp = String.valueOf(NOW.toEpochMilli());
		String nonce = "nonce-1";
		String body = "{\"params\":{\"orderNo\":\"A1\"}}";
		when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(Boolean.TRUE);

		securityService.verify(trigger, timestamp, nonce, hmac(secret, timestamp, nonce, body), body);

		verify(valueOperations).setIfAbsent("dataagent:task:api-nonce:9:" + nonce, "1",
				ApiTriggerSecurityService.NONCE_TTL);
	}

	@Test
	void expiredTimestampIsRejectedWithoutTouchingNonceStore() {
		AgentTaskTrigger trigger = trigger("secret-x");
		String timestamp = String.valueOf(NOW.minus(Duration.ofMinutes(6)).toEpochMilli());

		CheckedException ex = assertThrows(CheckedException.class, () -> securityService.verify(trigger, timestamp,
				"nonce-1", hmac("secret-x", timestamp, "nonce-1", ""), ""));

		assertEquals(TaskErrorDict.SIGNATURE_TIMESTAMP_EXPIRED.getValue(), ex.getCode());
		verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
	}

	@Test
	void replayedNonceIsRejected() {
		AgentTaskTrigger trigger = trigger("secret-x");
		String timestamp = String.valueOf(NOW.toEpochMilli());
		when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(Boolean.FALSE);

		CheckedException ex = assertThrows(CheckedException.class, () -> securityService.verify(trigger, timestamp,
				"nonce-used", hmac("secret-x", timestamp, "nonce-used", "{}"), "{}"));

		assertEquals(TaskErrorDict.NONCE_REPLAYED.getValue(), ex.getCode());
	}

	@Test
	void invalidSignatureIsRejectedWithoutConsumingNonce() {
		AgentTaskTrigger trigger = trigger("secret-x");
		String timestamp = String.valueOf(NOW.toEpochMilli());

		CheckedException ex = assertThrows(CheckedException.class,
				() -> securityService.verify(trigger, timestamp, "nonce-1", "0".repeat(64), "{}"));

		assertEquals(TaskErrorDict.SIGNATURE_INVALID.getValue(), ex.getCode());
		verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
	}

	@Test
	void missingHeadersAndMissingSecretAreRejected() {
		AgentTaskTrigger noSecret = trigger(null);
		CheckedException secretMissing = assertThrows(CheckedException.class,
				() -> securityService.verify(noSecret, "1", "n", "s", ""));
		assertEquals(TaskErrorDict.API_SECRET_NOT_CONFIGURED.getValue(), secretMissing.getCode());

		AgentTaskTrigger trigger = trigger("secret-x");
		CheckedException headerMissing = assertThrows(CheckedException.class,
				() -> securityService.verify(trigger, String.valueOf(NOW.toEpochMilli()), null, "sig", ""));
		assertEquals(TaskErrorDict.SIGNATURE_REQUIRED.getValue(), headerMissing.getCode());
	}

	@Test
	void sanitizeConfigStripsClientSecretAndKeepsStoredOne() {
		Map<String, Object> requested = new LinkedHashMap<>();
		requested.put(TaskConstants.CONFIG_API_SECRET, "client-injected");
		requested.put("other", "value");

		Map<String, Object> created = securityService.sanitizeConfig(requested, null);
		assertFalse(created.containsKey(TaskConstants.CONFIG_API_SECRET));
		assertEquals("value", created.get("other"));

		Map<String, Object> modified = securityService.sanitizeConfig(requested,
				Map.of(TaskConstants.CONFIG_API_SECRET, "stored-cipher"));
		assertEquals("stored-cipher", modified.get(TaskConstants.CONFIG_API_SECRET));
	}

	@Test
	void maskApiSecretReplacesValueForReadResponses() {
		AgentTaskTrigger trigger = trigger("secret-x");
		ApiTriggerSecurityService.maskApiSecret(List.of(trigger));
		assertEquals("******", trigger.getTriggerConfig().get(TaskConstants.CONFIG_API_SECRET));
		assertNotEquals("secret-x", trigger.getTriggerConfig().get(TaskConstants.CONFIG_API_SECRET));
		assertTrue(trigger.getTriggerConfig().containsKey("cron"));
	}

	private AgentTaskTrigger trigger(String secret) {
		Map<String, Object> config = new LinkedHashMap<>();
		config.put("cron", "0 0 * * * ?");
		if (secret != null) {
			config.put(TaskConstants.CONFIG_API_SECRET, secret);
		}
		AgentTaskTrigger trigger = AgentTaskTrigger.builder()
			.tenantId("100")
			.definitionId(1L)
			.triggerType("API")
			.triggerConfig(config)
			.build();
		trigger.setId(9L);
		return trigger;
	}

	private String hmac(String secret, String timestamp, String nonce, String body) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of()
				.formatHex(mac.doFinal((timestamp + "\n" + nonce + "\n" + body).getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

}
