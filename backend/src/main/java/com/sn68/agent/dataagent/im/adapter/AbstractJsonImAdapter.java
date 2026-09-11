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
package com.sn68.agent.dataagent.im.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.im.dto.ImCallbackMessage;
import com.sn68.agent.dataagent.im.enums.ImConstants;
import com.sn68.agent.dataagent.im.enums.ImErrorDict;
import com.sn68.agent.dataagent.im.service.ImRuntimeConfigService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * JSON 型 IM 回调适配器基类。
 *
 * <p><b>回调验签协议（H-5 修复后）。</b>两种模式，按请求是否携带 nonce 自动选择：
 * <ul>
 * <li><b>BODY（推荐，签名覆盖请求体）</b>：请求带 {@code X-Im-Nonce}（或 {@code nonce}）时启用，
 * 签名为 {@code HMAC-SHA256(secret, timestamp + "\n" + nonce + "\n" + rawBody)} 的小写 hex，
 * 与 {@code ApiTriggerSecurityService} 同一构造，body 被签名覆盖，改一个字节即验签失败；</li>
 * <li><b>PLATFORM（协议兼容回退）</b>：不带 nonce 时沿用平台固定算法
 * {@code base64(HMAC-SHA256(secret, timestamp + "\n" + secret))}。钉钉机器人 outgoing 回调的签名算法由平台规定，
 * <b>与请求体无关且不提供 nonce</b>，无法把 body 纳入签名，强行修改会直接断掉线上连接器。
 * 该模式改用补偿控制：时间窗 + 凭据一次性 + 报文身份交叉校验。</li>
 * </ul>
 * 连接器配置 {@code callbackSignatureMode=BODY} 可强制只接受 BODY 模式（拒绝无 nonce 的请求），
 * 供已能自行实现签名的对接方彻底关闭 PLATFORM 模式的残余风险。
 *
 * <p>两种模式都会校验 timestamp 落在 ±5 分钟窗口内、用 {@link MessageDigest#isEqual} 常量时间比较签名，
 * 并在候选连接器唯一确定后经 {@link #claimReplayGuard} 做 Redis 防重放占用。
 * 任一环节不通过一律拒绝（失败关闭）；日志不输出 secret、签名值与报文内容。
 */
@Slf4j
abstract class AbstractJsonImAdapter implements ImAdapter {

	/** 时间戳允许窗口（±5 分钟），与 ApiTriggerSecurityService 保持一致。 */
	static final Duration TIMESTAMP_WINDOW = Duration.ofMinutes(5);

	/**
	 * 防重放占用 TTL：请求时间戳最多超前 5 分钟、其有效期到 timestamp + 5 分钟，
	 * 取 15 分钟覆盖整个可用窗口并留余量。
	 */
	static final Duration REPLAY_TTL = Duration.ofMinutes(15);

	/** 强制要求 BODY 模式（签名覆盖请求体）的配置取值。 */
	static final String SIGNATURE_MODE_BODY = "BODY";

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private static final String REPLAY_KEY_PREFIX = "dataagent:im:callback-replay:";

	private static final String HMAC_ALGORITHM = "HmacSHA256";

	/**
	 * 报文身份交叉校验项：连接器配置键 → 报文中的等价字段名。
	 * PLATFORM 模式下签名与 body 无关，这层校验用于挡住「拿着某连接器的签名去投递另一套身份的报文」，
	 * 同时能暴露同码连接器串台的配置事故。
	 */
	private static final Map<String, List<String>> IDENTITY_CROSS_CHECKS = Map.of(
			"clientId", List.of("robotCode"),
			"corpId", List.of("chatbotCorpId", "senderCorpId", "corpid", "corpId"),
			"agentId", List.of("agentid", "agentId", "AgentID"));

	protected final ObjectMapper objectMapper;

	private final WebClient.Builder webClientBuilder;

	private final ImRuntimeConfigService runtimeConfigService;

	private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;

	private final Clock clock;

	AbstractJsonImAdapter(ObjectMapper objectMapper, WebClient.Builder webClientBuilder,
			ImRuntimeConfigService runtimeConfigService, ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
		this(objectMapper, webClientBuilder, runtimeConfigService, redisTemplateProvider, Clock.systemUTC());
	}

	AbstractJsonImAdapter(ObjectMapper objectMapper, WebClient.Builder webClientBuilder,
			ImRuntimeConfigService runtimeConfigService, ObjectProvider<StringRedisTemplate> redisTemplateProvider,
			Clock clock) {
		this.objectMapper = objectMapper;
		this.webClientBuilder = webClientBuilder;
		this.runtimeConfigService = runtimeConfigService;
		this.redisTemplateProvider = redisTemplateProvider;
		this.clock = clock;
	}

	@Override
	public void verify(String connectorCode, Map<String, Object> config, Map<String, String> headers,
			Map<String, String> queryParams, String rawBody) {
		String secret = firstText(configValue(config, "callbackSecret"), configValue(config, "secret"));
		if (!StringUtils.hasText(secret)) {
			throw rejectCallback(connectorCode, "secret-not-configured", ImErrorDict.CALLBACK_SIGNATURE_INVALID);
		}
		String timestamp = firstText(findIgnoreCase(headers, "x-dingtalk-timestamp"), findIgnoreCase(headers, "timestamp"),
				queryParams == null ? null : queryParams.get("timestamp"));
		String sign = firstText(findIgnoreCase(headers, "x-dingtalk-sign"), findIgnoreCase(headers, "sign"),
				queryParams == null ? null : queryParams.get("sign"));
		if (!StringUtils.hasText(timestamp) || !StringUtils.hasText(sign)) {
			throw rejectCallback(connectorCode, "signature-headers-missing", ImErrorDict.CALLBACK_SIGNATURE_INVALID);
		}
		requireFreshTimestamp(connectorCode, timestamp);
		String nonce = callbackNonce(headers, queryParams);
		boolean bodyModeRequired = SIGNATURE_MODE_BODY
			.equalsIgnoreCase(firstText(configValue(config, "callbackSignatureMode"), ""));
		if (bodyModeRequired && !StringUtils.hasText(nonce)) {
			throw rejectCallback(connectorCode, "nonce-required-by-body-mode", ImErrorDict.CALLBACK_SIGNATURE_INVALID);
		}
		if (StringUtils.hasText(nonce)) {
			verifyBodyBoundSignature(connectorCode, secret, timestamp, nonce, sign, rawBody);
			return;
		}
		verifyPlatformSignature(connectorCode, secret, timestamp, sign);
		requireMatchingPayloadIdentity(connectorCode, config, rawBody);
	}

	/**
	 * 防重放占用：Redis SETNX + TTL，键含租户与连接器编码，同一凭据只能用一次。
	 *
	 * <p>PLATFORM 模式下签名与 body 无关，等同于一枚 bearer 凭据，一次性消费是把「泄露一次即可长期伪造」
	 * 压缩成「窗口内至多一次」的关键控制，因此键取签名本身而不是报文 ID。
	 * BODY 模式下签名已覆盖 body，键取 nonce 即可。
	 *
	 * <p>Redis 不可用按失败关闭：放行等于关闭防重放。
	 */
	@Override
	public void claimReplayGuard(String tenantId, String connectorCode, Map<String, String> headers,
			Map<String, String> queryParams, String rawBody) {
		String timestamp = firstText(findIgnoreCase(headers, "x-dingtalk-timestamp"), findIgnoreCase(headers, "timestamp"),
				queryParams == null ? null : queryParams.get("timestamp"));
		String credential = firstText(callbackNonce(headers, queryParams),
				findIgnoreCase(headers, "x-dingtalk-sign"), findIgnoreCase(headers, "sign"),
				queryParams == null ? null : queryParams.get("sign"));
		if (!StringUtils.hasText(timestamp) || !StringUtils.hasText(credential)) {
			// verify 已保证两者齐备，走到这里说明调用顺序被改坏，失败关闭而不是静默放过。
			throw rejectCallback(connectorCode, "replay-guard-material-missing",
					ImErrorDict.CALLBACK_SIGNATURE_INVALID);
		}
		StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
		if (redisTemplate == null) {
			log.error("IM 回调防重放失败：Redis 不可用, 按失败关闭拒绝请求。provider={}, tenantId={}, connectorCode={}",
					provider(), tenantId, connectorCode);
			throw CheckedException.badRequest(ImErrorDict.CALLBACK_REPLAYED.getValue(),
					"Redis 不可用，无法执行 IM 回调防重放校验");
		}
		String key = REPLAY_KEY_PREFIX + provider() + ":" + tenantId + ":" + connectorCode + ":"
				+ sha256Hex(timestamp + "\n" + credential);
		if (!Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, "1", REPLAY_TTL))) {
			throw rejectCallback(connectorCode, "callback-replayed", ImErrorDict.CALLBACK_REPLAYED);
		}
	}

	/** 签名覆盖请求体：body 改一个字节即失败，是本类唯一能真正阻断报文伪造的模式。 */
	private void verifyBodyBoundSignature(String connectorCode, String secret, String timestamp, String nonce,
			String sign, String rawBody) {
		String signingString = timestamp + "\n" + nonce + "\n" + (rawBody == null ? "" : rawBody);
		String expected = hmacHex(connectorCode, secret, signingString);
		if (!constantTimeEquals(expected, decode(sign).toLowerCase(Locale.ROOT))
				&& !constantTimeEquals(expected, sign.toLowerCase(Locale.ROOT))) {
			throw rejectCallback(connectorCode, "body-signature-mismatch", ImErrorDict.CALLBACK_SIGNATURE_INVALID);
		}
	}

	/** 平台固定算法（签名与 body 无关），仅为兼容钉钉 outgoing 协议保留。 */
	private void verifyPlatformSignature(String connectorCode, String secret, String timestamp, String sign) {
		String expected = sign(connectorCode, timestamp, secret);
		if (!constantTimeEquals(expected, decode(sign)) && !constantTimeEquals(expected, sign)) {
			throw rejectCallback(connectorCode, "platform-signature-mismatch",
					ImErrorDict.CALLBACK_SIGNATURE_INVALID);
		}
	}

	/**
	 * 报文身份交叉校验：连接器配置声明了某项身份、且报文也带了对应字段时，两者必须一致。
	 *
	 * <p>只在两侧都存在时比对——平台是否下发这些字段随机器人类型而异，强制要求会误伤线上连接器；
	 * 不一致则一律拒绝。
	 */
	private void requireMatchingPayloadIdentity(String connectorCode, Map<String, Object> config, String rawBody) {
		if (config == null || config.isEmpty()) {
			return;
		}
		Map<String, Object> payload = readMap(rawBody);
		if (payload.isEmpty()) {
			return;
		}
		for (Map.Entry<String, List<String>> entry : IDENTITY_CROSS_CHECKS.entrySet()) {
			String configured = firstText(configValue(config, entry.getKey()));
			if (!StringUtils.hasText(configured)) {
				continue;
			}
			for (String payloadKey : entry.getValue()) {
				String actual = firstText(string(payload.get(payloadKey)));
				if (StringUtils.hasText(actual) && !configured.equalsIgnoreCase(actual)) {
					log.warn("IM 回调报文身份与连接器配置不一致, 已拒绝。provider={}, connectorCode={}, field={}",
							provider(), connectorCode, entry.getKey());
					throw CheckedException.badRequest(ImErrorDict.CALLBACK_IDENTITY_MISMATCH.getValue(),
							ImErrorDict.CALLBACK_IDENTITY_MISMATCH.getLabel());
				}
			}
		}
	}

	private void requireFreshTimestamp(String connectorCode, String timestamp) {
		long requestMillis;
		try {
			requestMillis = Long.parseLong(timestamp);
		}
		catch (NumberFormatException ex) {
			throw rejectCallback(connectorCode, "timestamp-not-numeric", ImErrorDict.CALLBACK_TIMESTAMP_EXPIRED);
		}
		if (Math.abs(clock.millis() - requestMillis) > TIMESTAMP_WINDOW.toMillis()) {
			throw rejectCallback(connectorCode, "timestamp-out-of-window", ImErrorDict.CALLBACK_TIMESTAMP_EXPIRED);
		}
	}

	private String callbackNonce(Map<String, String> headers, Map<String, String> queryParams) {
		return firstText(findIgnoreCase(headers, "x-im-nonce"), findIgnoreCase(headers, "nonce"),
				queryParams == null ? null : queryParams.get("nonce"));
	}

	private boolean constantTimeEquals(String expected, String actual) {
		return actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
				actual.getBytes(StandardCharsets.UTF_8));
	}

	/** 审计日志 + 拒绝：只记失败原因与连接器定位信息，不输出 secret、签名值与报文内容。 */
	private CheckedException rejectCallback(String connectorCode, String reason, ImErrorDict error) {
		log.warn("IM 回调校验拒绝。provider={}, connectorCode={}, reason={}", provider(), connectorCode, reason);
		return CheckedException.badRequest(error.getValue(), error.getLabel());
	}

	@Override
	public ImCallbackMessage parse(String provider, String connectorCode, String rawBody) {
		Map<String, Object> payload = readMap(rawBody);
		String messageType = firstText(string(payload.get("messageType")), string(payload.get("msgtype")), "text");
		String text = text(payload);
		String externalUserId = resolveExternalUserId(payload);
		String externalConversationId = firstText(string(payload.get("externalConversationId")),
				string(payload.get("conversationId")), string(payload.get("chatId")), string(payload.get("groupId")),
				externalUserId);
		String conversationType = resolveConversationType(payload, externalConversationId, externalUserId);
		return new ImCallbackMessage(normalize(provider), connectorCode,
				firstText(string(payload.get("externalMessageId")), string(payload.get("msgId")),
						string(payload.get("messageId")), string(payload.get("msgid"))),
				conversationType, externalConversationId, externalUserId,
				firstText(string(payload.get("unionId")), string(payload.get("unionid"))),
				firstText(string(payload.get("contact")), string(payload.get("mobile")), string(payload.get("email"))),
				normalizeMessageType(messageType), text, mentioned(payload), firstText(string(payload.get("replyWebhook")),
						string(payload.get("sessionWebhook")), string(payload.get("webhook"))),
				payload);
	}

	@Override
	public Map<String, Object> responsePayload(String text) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("msgtype", "text");
		result.put("text", Map.of("content", text == null ? "" : text));
		return result;
	}

	@Override
	public void sendReply(Map<String, Object> config, ImCallbackMessage message, String text) {
		String webhook = firstText(message == null ? null : message.replyWebhook(), configValue(config, "replyWebhook"),
				configValue(config, "webhook"));
		if (!StringUtils.hasText(webhook)) {
			return;
		}
		webhook = requireSafeReplyWebhook(config, webhook, message);
		try {
			webClientBuilder.clone()
				.build()
				.post()
				.uri(webhook)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(responsePayload(text))
				.retrieve()
				.bodyToMono(String.class)
				.block(Duration.ofMillis(Math.max(1000, platformSendTimeoutMs(message))));
		}
		catch (Exception ex) {
			log.warn("Send IM reply failed. provider={}, connectorCode={}", provider(),
					message == null ? null : message.connectorCode(), ex);
			throw CheckedException.badRequest(ImErrorDict.AGENT_INVOKE_FAILED.getValue(),
					ImErrorDict.AGENT_INVOKE_FAILED.getLabel());
		}
	}

	String requireSafeReplyWebhook(Map<String, Object> config, String webhook, ImCallbackMessage message) {
		try {
			URI uri = URI.create(webhook.trim());
			String host = uri.getHost();
			if (!"https".equalsIgnoreCase(uri.getScheme()) || !StringUtils.hasText(host)
					|| StringUtils.hasText(uri.getUserInfo())) {
				throw invalidReplyWebhook(message, host, "invalid-uri");
			}
			Set<String> allowedHosts = allowedReplyWebhookHosts(config);
			String normalizedHost = host.toLowerCase(Locale.ROOT);
			if (allowedHosts.isEmpty() || allowedHosts.stream()
					.noneMatch(allowed -> normalizedHost.equals(allowed) || normalizedHost.endsWith("." + allowed))) {
				throw invalidReplyWebhook(message, normalizedHost, "host-not-allowed");
			}
			for (InetAddress address : InetAddress.getAllByName(host)) {
				if (isPrivateAddress(address)) {
					throw invalidReplyWebhook(message, normalizedHost, "private-address");
				}
			}
			return uri.toASCIIString();
		}
		catch (CheckedException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw invalidReplyWebhook(message, null, "invalid-or-unresolvable");
		}
	}

	Set<String> allowedReplyWebhookHosts(Map<String, Object> config) {
		if (config == null || !config.containsKey("replyWebhookAllowedHosts")) {
			return Set.copyOf(ImConstants.defaultReplyWebhookAllowedHosts(provider()));
		}
		Object value = config.get("replyWebhookAllowedHosts");
		if (value instanceof Iterable<?> values) {
			Set<String> result = new java.util.LinkedHashSet<>();
			for (Object item : values) {
				if (StringUtils.hasText(string(item))) {
					result.add(string(item).trim().toLowerCase(Locale.ROOT));
				}
			}
			if (isLegacyMaskedWhitelist(result)) {
				return Set.copyOf(ImConstants.defaultReplyWebhookAllowedHosts(provider()));
			}
			return Set.copyOf(result);
		}
		if (!StringUtils.hasText(string(value))) {
			return Set.of();
		}
		Set<String> result = new java.util.LinkedHashSet<>();
		for (String item : List.of(string(value).split(","))) {
			if (StringUtils.hasText(item)) {
				result.add(item.trim().toLowerCase(Locale.ROOT));
			}
		}
		if (isLegacyMaskedWhitelist(result)) {
			return Set.copyOf(ImConstants.defaultReplyWebhookAllowedHosts(provider()));
		}
		return Set.copyOf(result);
	}

	private boolean isLegacyMaskedWhitelist(Set<String> hosts) {
		return hosts != null && !hosts.isEmpty() && hosts.stream().allMatch(host -> host.contains("****"));
	}

	private boolean isPrivateAddress(InetAddress address) {
		if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
				|| address.isSiteLocalAddress() || address.isMulticastAddress()) {
			return true;
		}
		byte[] bytes = address.getAddress();
		return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
	}

	private CheckedException invalidReplyWebhook(ImCallbackMessage message, String host, String reason) {
		log.warn("Rejected IM reply webhook. provider={}, connectorCode={}, host={}, reason={}", provider(),
				message == null ? null : message.connectorCode(), host, reason);
		return CheckedException.badRequest(ImErrorDict.REPLY_WEBHOOK_INVALID.getValue(),
				ImErrorDict.REPLY_WEBHOOK_INVALID.getLabel());
	}

	protected Map<String, Object> readMap(String rawBody) {
		if (!StringUtils.hasText(rawBody)) {
			return Map.of();
		}
		try {
			Map<String, Object> map = objectMapper.readValue(rawBody, MAP_TYPE);
			return map == null ? Map.of() : new LinkedHashMap<>(map);
		}
		catch (Exception ex) {
			// An unparseable payload means the inbound IM message is dropped entirely.
			log.warn("Failed to parse IM callback payload, the message will be ignored. length={}", rawBody.length(), ex);
			return Map.of();
		}
	}

	@SuppressWarnings("unchecked")
	private String text(Map<String, Object> payload) {
		Object direct = payload.get("text");
		if (direct instanceof Map<?, ?> map) {
			return firstText(string(map.get("content")), string(map.get("text")));
		}
		if (direct instanceof String value) {
			return value;
		}
		Object content = payload.get("content");
		return content == null ? null : String.valueOf(content);
	}

	private String resolveConversationType(Map<String, Object> payload, String externalConversationId,
			String externalUserId) {
		String raw = firstText(string(payload.get("conversationType")), string(payload.get("chatType")),
				string(payload.get("type")));
		if (StringUtils.hasText(raw)) {
			String normalized = raw.trim().toUpperCase();
			if ("1".equals(normalized) || "SINGLE".equals(normalized) || "PRIVATE".equals(normalized)) {
				return ImConstants.CONVERSATION_SINGLE;
			}
			return ImConstants.CONVERSATION_GROUP;
		}
		return StringUtils.hasText(externalConversationId) && !externalConversationId.equals(externalUserId)
				? ImConstants.CONVERSATION_GROUP : ImConstants.CONVERSATION_SINGLE;
	}

	private boolean mentioned(Map<String, Object> payload) {
		Object mentioned = firstValue(payload, "mentionedBot", "isInAtList", "isAt");
		if (mentioned instanceof Boolean value) {
			return value;
		}
		if (mentioned != null) {
			return Boolean.parseBoolean(String.valueOf(mentioned));
		}
		Object atUsers = payload.get("atUsers");
		return atUsers instanceof List<?> list && !list.isEmpty();
	}

	private Object firstValue(Map<String, Object> payload, String... keys) {
		for (String key : keys) {
			if (payload.containsKey(key)) {
				return payload.get(key);
			}
		}
		return null;
	}

	private String normalizeMessageType(String messageType) {
		String text = firstText(messageType, "text").trim();
		return "text".equalsIgnoreCase(text) ? "TEXT" : text.toUpperCase();
	}

	private String configValue(Map<String, Object> config, String key) {
		Object value = config == null ? null : config.get(key);
		return value == null ? null : String.valueOf(value);
	}

	private String findIgnoreCase(Map<String, String> values, String key) {
		if (values == null || !StringUtils.hasText(key)) {
			return null;
		}
		for (Map.Entry<String, String> entry : values.entrySet()) {
			if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
				return entry.getValue();
			}
		}
		return null;
	}

	private String sign(String connectorCode, String timestamp, String secret) {
		return Base64.getEncoder().encodeToString(hmac(connectorCode, secret, timestamp + "\n" + secret));
	}

	private String hmacHex(String connectorCode, String secret, String signingString) {
		return HexFormat.of().formatHex(hmac(connectorCode, secret, signingString));
	}

	private byte[] hmac(String connectorCode, String secret, String signingString) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
			return mac.doFinal(signingString.getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception ex) {
			// HmacSHA256 是 Java SE 必备算法，异常只可能来自环境损坏，失败关闭并保留现场。
			log.error("IM 回调签名计算失败, 拒绝请求。provider={}, connectorCode={}", provider(), connectorCode, ex);
			throw CheckedException.badRequest(ImErrorDict.CALLBACK_SIGNATURE_INVALID.getValue(),
					ImErrorDict.CALLBACK_SIGNATURE_INVALID.getLabel());
		}
	}

	private String sha256Hex(String value) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception ex) {
			// 防重放键算不出来就没有去重，只能失败关闭。
			log.error("IM 回调防重放键计算失败, 拒绝请求。provider={}", provider(), ex);
			throw CheckedException.badRequest(ImErrorDict.CALLBACK_REPLAYED.getValue(),
					ImErrorDict.CALLBACK_REPLAYED.getLabel());
		}
	}

	private String decode(String value) {
		try {
			return URLDecoder.decode(value, StandardCharsets.UTF_8);
		}
		catch (Exception ex) {
			log.warn("URL decode failed, falling back to the raw value. provider={}", provider(), ex);
			return value;
		}
	}

	private int platformSendTimeoutMs(ImCallbackMessage message) {
		String provider = firstText(message == null ? null : message.provider(), provider());
		return runtimeConfigService.providerRuntimeConfig(provider).platformSendTimeoutMs();
	}

	/**
	 * 解析外部用户身份键。企微等平台可回退 fromUserId/userid；钉钉覆盖为只认 senderStaffId。
	 */
	protected String resolveExternalUserId(Map<String, Object> payload) {
		if (payload == null || payload.isEmpty()) {
			return null;
		}
		return firstText(string(payload.get("externalUserId")), string(payload.get("senderStaffId")),
				string(payload.get("senderId")), string(payload.get("fromUserId")), string(payload.get("userid")));
	}

	protected String normalize(String value) {
		return value == null ? null : value.trim().toUpperCase();
	}

	protected String string(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	protected String firstText(String... values) {
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
