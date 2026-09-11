/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.im.adapter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.im.dto.ImCallbackMessage;
import com.sn68.agent.dataagent.im.enums.ImConstants;
import com.sn68.agent.dataagent.im.enums.ImErrorDict;
import com.sn68.agent.dataagent.im.service.ImRuntimeConfigService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * IM 回调验签和回复地址安全测试。
 *
 * <p>H-5 覆盖：时间窗、常量时间比较、防重放占用、BODY 模式（签名覆盖请求体）与报文身份交叉校验。
 * BODY 模式与身份校验是基类行为，两个平台适配器共用，这里统一在钉钉适配器上验证。
 */
class DingTalkImAdapterSecurityTest {

	private static final Instant NOW = Instant.parse("2026-08-12T10:00:00Z");

	private static final String SECRET = "callback-secret";

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

	private final DingTalkImAdapter adapter = new DingTalkImAdapter(new ObjectMapper(), WebClient.builder(),
			mock(ImRuntimeConfigService.class), redisProvider(), Clock.fixed(NOW, ZoneOffset.UTC));

	private ObjectProvider<StringRedisTemplate> redisProvider() {
		@SuppressWarnings("unchecked")
		ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(redisTemplate);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		return provider;
	}

	@Test
	void rejectsCallbackWithoutConfiguredSecret() {
		assertThrows(CheckedException.class, () -> adapter.verify("conn", Map.of(), Map.of(), Map.of(), "{}"));
	}

	@Test
	void acceptsPlatformSignatureInsideTimestampWindow() {
		String timestamp = String.valueOf(NOW.toEpochMilli());

		assertDoesNotThrow(() -> adapter.verify("conn", Map.of("callbackSecret", SECRET),
				Map.of("timestamp", timestamp, "sign", platformSign(timestamp)), Map.of(), "{\"msgId\":\"m-1\"}"));
	}

	/** H-5：签名与请求体无关，时间窗是限制"泄露一次即可长期伪造"的第一道控制。 */
	@Test
	void rejectsExpiredTimestampBeforeTouchingReplayStore() {
		String stale = String.valueOf(NOW.minus(Duration.ofMinutes(6)).toEpochMilli());

		CheckedException ex = assertThrows(CheckedException.class, () -> adapter.verify("conn",
				Map.of("callbackSecret", SECRET), Map.of("timestamp", stale, "sign", platformSign(stale)), Map.of(),
				"{}"));

		assertEquals(ImErrorDict.CALLBACK_TIMESTAMP_EXPIRED.getValue(), ex.getCode());
		verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
	}

	@Test
	void rejectsFutureTimestampOutsideWindow() {
		String ahead = String.valueOf(NOW.plus(Duration.ofMinutes(6)).toEpochMilli());

		CheckedException ex = assertThrows(CheckedException.class, () -> adapter.verify("conn",
				Map.of("callbackSecret", SECRET), Map.of("timestamp", ahead, "sign", platformSign(ahead)), Map.of(),
				"{}"));

		assertEquals(ImErrorDict.CALLBACK_TIMESTAMP_EXPIRED.getValue(), ex.getCode());
	}

	/** 防重放占用键必须带租户与连接器编码，避免跨租户互相顶掉。 */
	@Test
	void replayGuardClaimsCredentialOncePerTenantAndConnector() {
		String timestamp = String.valueOf(NOW.toEpochMilli());
		String sign = platformSign(timestamp);
		when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(Boolean.TRUE);

		adapter.claimReplayGuard("100", "conn", Map.of("timestamp", timestamp, "sign", sign), Map.of(), "{}");

		verify(valueOperations).setIfAbsent(
				"dataagent:im:callback-replay:DINGTALK:100:conn:" + sha256Hex(timestamp + "\n" + sign), "1",
				AbstractJsonImAdapter.REPLAY_TTL);
	}

	@Test
	void rejectsReplayedCallbackCredential() {
		String timestamp = String.valueOf(NOW.toEpochMilli());
		when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(Boolean.FALSE);

		CheckedException ex = assertThrows(CheckedException.class, () -> adapter.claimReplayGuard("100", "conn",
				Map.of("timestamp", timestamp, "sign", platformSign(timestamp)), Map.of(), "{}"));

		assertEquals(ImErrorDict.CALLBACK_REPLAYED.getValue(), ex.getCode());
	}

	/** Redis 不可用时放行等于关闭防重放，必须失败关闭。 */
	@Test
	void replayGuardFailsClosedWhenRedisIsUnavailable() {
		@SuppressWarnings("unchecked")
		ObjectProvider<StringRedisTemplate> emptyProvider = mock(ObjectProvider.class);
		when(emptyProvider.getIfAvailable()).thenReturn(null);
		DingTalkImAdapter redislessAdapter = new DingTalkImAdapter(new ObjectMapper(), WebClient.builder(),
				mock(ImRuntimeConfigService.class), emptyProvider, Clock.fixed(NOW, ZoneOffset.UTC));
		String timestamp = String.valueOf(NOW.toEpochMilli());

		assertThrows(CheckedException.class, () -> redislessAdapter.claimReplayGuard("100", "conn",
				Map.of("timestamp", timestamp, "sign", platformSign(timestamp)), Map.of(), "{}"));
	}

	@Test
	void parseUsesSenderStaffIdAndIgnoresEncryptedSenderId() {
		ImCallbackMessage message = adapter.parse(ImConstants.PROVIDER_DINGTALK, "conn",
				"{\"senderStaffId\":\"staff-1\",\"senderId\":\"$:LWCP_v1:$abc\",\"text\":{\"content\":\"hi\"}}");
		assertEquals("staff-1", message.externalUserId());
	}

	@Test
	void parseDoesNotUseEncryptedSenderIdWhenStaffIdMissing() {
		ImCallbackMessage message = adapter.parse(ImConstants.PROVIDER_DINGTALK, "conn",
				"{\"senderId\":\"$:LWCP_v1:$abc\",\"text\":{\"content\":\"hi\"}}");
		assertNull(message.externalUserId());
	}

	/** BODY 模式：签名覆盖请求体，改一个字节即验签失败——这是唯一能真正阻断报文伪造的模式。 */
	@Test
	void bodyBoundSignatureRejectsTamperedPayload() {
		String timestamp = String.valueOf(NOW.toEpochMilli());
		String nonce = "nonce-1";
		String body = "{\"senderStaffId\":\"staff-1\",\"text\":{\"content\":\"hello\"}}";
		Map<String, Object> config = Map.of("callbackSecret", SECRET, "callbackSignatureMode", "BODY");
		Map<String, String> headers = Map.of("timestamp", timestamp, "X-Im-Nonce", nonce, "sign",
				bodySign(timestamp, nonce, body));

		assertDoesNotThrow(() -> adapter.verify("conn", config, headers, Map.of(), body));

		String forged = "{\"senderStaffId\":\"victim\",\"text\":{\"content\":\"同意 12345\"}}";
		CheckedException ex = assertThrows(CheckedException.class,
				() -> adapter.verify("conn", config, headers, Map.of(), forged));
		assertEquals(ImErrorDict.CALLBACK_SIGNATURE_INVALID.getValue(), ex.getCode());
	}

	/** 强制 BODY 模式的连接器不接受无 nonce 的请求，杜绝退回到与 body 无关的平台签名。 */
	@Test
	void bodyModeRejectsRequestWithoutNonce() {
		String timestamp = String.valueOf(NOW.toEpochMilli());

		CheckedException ex = assertThrows(CheckedException.class,
				() -> adapter.verify("conn", Map.of("callbackSecret", SECRET, "callbackSignatureMode", "BODY"),
						Map.of("timestamp", timestamp, "sign", platformSign(timestamp)), Map.of(), "{}"));

		assertEquals(ImErrorDict.CALLBACK_SIGNATURE_INVALID.getValue(), ex.getCode());
	}

	/** PLATFORM 模式的补偿控制：报文声明的机器人身份必须与连接器配置一致。 */
	@Test
	void rejectsPayloadWhoseRobotIdentityDoesNotMatchConnectorConfig() {
		String timestamp = String.valueOf(NOW.toEpochMilli());
		Map<String, String> headers = Map.of("timestamp", timestamp, "sign", platformSign(timestamp));
		Map<String, Object> config = Map.of("callbackSecret", SECRET, "clientId", "ding-app-key");

		assertDoesNotThrow(() -> adapter.verify("conn", config, headers, Map.of(),
				"{\"robotCode\":\"ding-app-key\",\"msgId\":\"m-1\"}"));

		CheckedException ex = assertThrows(CheckedException.class, () -> adapter.verify("conn", config, headers,
				Map.of(), "{\"robotCode\":\"another-app\",\"msgId\":\"m-1\"}"));
		assertEquals(ImErrorDict.CALLBACK_IDENTITY_MISMATCH.getValue(), ex.getCode());
	}

	@Test
	void rejectsNonHttpsReplyWebhook() {
		ImCallbackMessage message = message("http://example.com/reply");

		assertThrows(CheckedException.class, () -> adapter.sendReply(
				Map.of("replyWebhookAllowedHosts", List.of("example.com")), message, "answer"));
	}

	@Test
	void usesOfficialDingTalkHostsWhenWhitelistIsMissing() {
		assertEquals(Set.of("oapi.dingtalk.com", "api.dingtalk.com"),
				adapter.allowedReplyWebhookHosts(Map.of()));
	}

	@Test
	void explicitEmptyWhitelistDoesNotUseOfficialDefaults() {
		assertTrue(adapter.allowedReplyWebhookHosts(Map.of("replyWebhookAllowedHosts", List.of())).isEmpty());
	}

	@Test
	void explicitOtherWhitelistDoesNotUseOfficialDefaults() {
		assertEquals(Set.of("example.com"),
				adapter.allowedReplyWebhookHosts(Map.of("replyWebhookAllowedHosts", List.of("example.com"))));
	}

	@Test
	void legacyMaskedWhitelistUsesOfficialDefaults() {
		assertEquals(Set.of("oapi.dingtalk.com", "api.dingtalk.com"), adapter
			.allowedReplyWebhookHosts(Map.of("replyWebhookAllowedHosts", List.of("oap****om", "api****om"))));
	}

	@Test
	void weComUsesOnlyWeComOfficialDefault() {
		WeComImAdapter weCom = new WeComImAdapter(new ObjectMapper(), WebClient.builder(),
				mock(ImRuntimeConfigService.class), redisProvider());

		assertEquals(Set.of("qyapi.weixin.qq.com"), weCom.allowedReplyWebhookHosts(Map.of()));
	}

	@Test
	void rejectsUserInfoUnauthorizedHostAndLoopbackAddress() {
		assertThrows(CheckedException.class, () -> adapter.requireSafeReplyWebhook(
				Map.of("replyWebhookAllowedHosts", List.of("example.com")), "https://user@example.com/reply",
				message("https://user@example.com/reply")));
		assertThrows(CheckedException.class, () -> adapter.requireSafeReplyWebhook(
				Map.of("replyWebhookAllowedHosts", List.of("example.com")), "https://oapi.dingtalk.com/reply",
				message("https://oapi.dingtalk.com/reply")));
		assertThrows(CheckedException.class, () -> adapter.requireSafeReplyWebhook(
				Map.of("replyWebhookAllowedHosts", List.of("127.0.0.1")), "https://127.0.0.1/reply",
				message("https://127.0.0.1/reply")));
	}

	private ImCallbackMessage message(String replyWebhook) {
		return new ImCallbackMessage("DINGTALK", "connector", "message", "SINGLE", "conversation", "user", null,
				null, "TEXT", "hello", false, replyWebhook, Map.of());
	}

	/** 钉钉 outgoing 平台固定算法：base64(HMAC-SHA256(secret, timestamp + "\n" + secret))。 */
	private String platformSign(String timestamp) {
		return Base64.getEncoder().encodeToString(hmac(timestamp + "\n" + SECRET));
	}

	/** BODY 模式：hex(HMAC-SHA256(secret, timestamp + "\n" + nonce + "\n" + rawBody))。 */
	private String bodySign(String timestamp, String nonce, String body) {
		return HexFormat.of().formatHex(hmac(timestamp + "\n" + nonce + "\n" + body));
	}

	private byte[] hmac(String signingString) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return mac.doFinal(signingString.getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private String sha256Hex(String value) {
		try {
			return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}
}
