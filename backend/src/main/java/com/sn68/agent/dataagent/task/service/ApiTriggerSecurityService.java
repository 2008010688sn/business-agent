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

import com.sn68.agent.dataagent.service.security.SensitiveConfigCryptoService;
import com.sn68.agent.dataagent.task.entity.AgentTaskTrigger;
import com.sn68.agent.dataagent.task.enums.TaskConstants;
import com.sn68.agent.dataagent.task.enums.TaskErrorDict;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * API 触发开放接口安全组件：签名密钥生成/轮换、HMAC-SHA256 验签与 nonce 防重放。
 *
 * <p><b>签名协议（对接方按此实现）：</b>
 * <ul>
 * <li>{@code X-Timestamp}：epoch 毫秒，允许与服务端时钟偏差 ±5 分钟；</li>
 * <li>{@code X-Nonce}：调用方生成的一次性随机串（1~128 字符），签名窗口内不得重复；</li>
 * <li>{@code X-Signature}：HMAC-SHA256(secret, timestamp + "\n" + nonce + "\n" + rawBody)
 * 的小写 hex；rawBody 为请求体原始字符串，无请求体按空串参与签名。
 * 三段以换行分隔，避免直接拼接时 timestamp/nonce 边界歧义被用来构造重放。</li>
 * </ul>
 *
 * <p>密钥密文落库（沿用 DataAgent 敏感配置加密 {@code enc:gcm:} 模式，HMAC 需还原明文故不做单向哈希），
 * 明文仅在生成/轮换时返回一次；nonce 以 Redis SETNX + TTL 去重，Redis 不可用按失败关闭处理（拒绝请求）。
 * 校验失败一律拒绝并留审计日志；日志不输出 secret 与签名值。
 */
@Slf4j
@Service
public class ApiTriggerSecurityService {

	/** 时间戳允许窗口（±5 分钟）。 */
	static final Duration TIMESTAMP_WINDOW = Duration.ofMinutes(5);

	/**
	 * nonce 去重 TTL。原始请求时间戳最长在（收到时刻 + 10 分钟）内仍处于允许窗口
	 * （timestamp 最多超前 5 分钟、其有效期到 timestamp + 5 分钟），取 15 分钟覆盖并留余量。
	 */
	static final Duration NONCE_TTL = Duration.ofMinutes(15);

	static final int NONCE_MAX_LENGTH = 128;

	private static final String NONCE_KEY_PREFIX = "dataagent:task:api-nonce:";

	private static final String HMAC_ALGORITHM = "HmacSHA256";

	private static final String MASKED_SECRET = "******";

	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	private final SensitiveConfigCryptoService cryptoService;

	private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;

	private final Clock clock;

	// 类中另有测试专用构造器，必须显式指定注入构造器，否则 Spring 会回退到不存在的无参构造导致启动失败
	@Autowired
	public ApiTriggerSecurityService(SensitiveConfigCryptoService cryptoService,
			ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
		this(cryptoService, redisTemplateProvider, Clock.systemUTC());
	}

	ApiTriggerSecurityService(SensitiveConfigCryptoService cryptoService,
			ObjectProvider<StringRedisTemplate> redisTemplateProvider, Clock clock) {
		this.cryptoService = cryptoService;
		this.redisTemplateProvider = redisTemplateProvider;
		this.clock = clock;
	}

	/**
	 * 生成新签名密钥并写入触发器配置（密文），返回明文（仅此一次）。旧密钥即刻失效。
	 * 只修改内存对象，落库由调用方完成。
	 */
	public String rotateSecret(AgentTaskTrigger trigger) {
		byte[] bytes = new byte[32];
		SECURE_RANDOM.nextBytes(bytes);
		String secret = HexFormat.of().formatHex(bytes);
		Map<String, Object> config = new LinkedHashMap<>(
				trigger.getTriggerConfig() == null ? Map.of() : trigger.getTriggerConfig());
		config.put(TaskConstants.CONFIG_API_SECRET, cryptoService.encryptIfNecessary(secret));
		trigger.setTriggerConfig(config);
		return secret;
	}

	/**
	 * 校验 API 触发请求签名，任一环节不通过即抛 CheckedException 拒绝（失败关闭）。
	 * 校验顺序：密钥已配置 → 请求头齐全 → 时间窗 → 签名 → nonce 去重（签名不合法的请求不占用 nonce 存储）。
	 */
	public void verify(AgentTaskTrigger trigger, String timestamp, String nonce, String signature, String rawBody) {
		String encryptedSecret = configText(trigger.getTriggerConfig(), TaskConstants.CONFIG_API_SECRET);
		if (!StringUtils.hasText(encryptedSecret)) {
			throw reject(trigger, "secret-not-configured", TaskErrorDict.API_SECRET_NOT_CONFIGURED);
		}
		if (!StringUtils.hasText(timestamp) || !StringUtils.hasText(nonce) || !StringUtils.hasText(signature)) {
			throw reject(trigger, "signature-headers-missing", TaskErrorDict.SIGNATURE_REQUIRED);
		}
		long requestMillis = parseTimestamp(trigger, timestamp.trim());
		long skewMillis = Math.abs(clock.millis() - requestMillis);
		if (skewMillis > TIMESTAMP_WINDOW.toMillis()) {
			throw reject(trigger, "timestamp-out-of-window", TaskErrorDict.SIGNATURE_TIMESTAMP_EXPIRED);
		}
		String trimmedNonce = nonce.trim();
		if (trimmedNonce.length() > NONCE_MAX_LENGTH) {
			throw reject(trigger, "nonce-too-long", TaskErrorDict.SIGNATURE_INVALID);
		}
		String secret = cryptoService.decryptRuntimeSecret(encryptedSecret);
		String signingString = timestamp.trim() + "\n" + trimmedNonce + "\n" + (rawBody == null ? "" : rawBody);
		String expected = hmacHex(secret, signingString);
		String provided = signature.trim().toLowerCase(Locale.ROOT);
		if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
				provided.getBytes(StandardCharsets.UTF_8))) {
			throw reject(trigger, "signature-mismatch", TaskErrorDict.SIGNATURE_INVALID);
		}
		claimNonce(trigger, trimmedNonce);
	}

	/**
	 * 剔除调用方在 triggerConfig 里直接传入的 apiSecret（密钥只能经轮换接口生成），
	 * 修改场景把已存储的密文密钥带回，避免整体替换配置时把密钥冲掉。
	 */
	public Map<String, Object> sanitizeConfig(Map<String, Object> requested, Map<String, Object> existing) {
		if (requested == null) {
			return null;
		}
		Map<String, Object> config = new LinkedHashMap<>(requested);
		config.remove(TaskConstants.CONFIG_API_SECRET);
		String stored = configText(existing, TaskConstants.CONFIG_API_SECRET);
		if (StringUtils.hasText(stored)) {
			config.put(TaskConstants.CONFIG_API_SECRET, stored);
		}
		return config;
	}

	/**
	 * 读接口脱敏：apiSecret 密文不回显，统一替换为占位符（仅改内存对象，不落库）。
	 */
	public static void maskApiSecret(List<AgentTaskTrigger> triggers) {
		if (triggers == null) {
			return;
		}
		for (AgentTaskTrigger trigger : triggers) {
			Map<String, Object> config = trigger.getTriggerConfig();
			if (config == null || !config.containsKey(TaskConstants.CONFIG_API_SECRET)) {
				continue;
			}
			Map<String, Object> masked = new LinkedHashMap<>(config);
			masked.put(TaskConstants.CONFIG_API_SECRET, MASKED_SECRET);
			trigger.setTriggerConfig(masked);
		}
	}

	private long parseTimestamp(AgentTaskTrigger trigger, String timestamp) {
		try {
			return Long.parseLong(timestamp);
		}
		catch (NumberFormatException ex) {
			throw reject(trigger, "timestamp-not-numeric", TaskErrorDict.SIGNATURE_TIMESTAMP_EXPIRED);
		}
	}

	/**
	 * nonce 去重：SETNX 成功即占用；已存在说明同一 nonce 在窗口内重复使用，判为重放。
	 * Redis 不可用时失败关闭——放行等于关闭防重放，宁可拒绝请求。
	 */
	private void claimNonce(AgentTaskTrigger trigger, String nonce) {
		StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
		if (redisTemplate == null) {
			log.error("API 触发 nonce 去重失败：Redis 不可用, 按失败关闭拒绝请求。triggerId={}", trigger.getId());
			throw CheckedException.badRequest(TaskErrorDict.SIGNATURE_INVALID.getValue(),
					"Redis 不可用，无法执行 API 触发防重放校验");
		}
		String key = NONCE_KEY_PREFIX + trigger.getId() + ":" + nonce;
		Boolean claimed = redisTemplate.opsForValue().setIfAbsent(key, "1", NONCE_TTL);
		if (!Boolean.TRUE.equals(claimed)) {
			throw reject(trigger, "nonce-replayed", TaskErrorDict.NONCE_REPLAYED);
		}
	}

	/**
	 * 审计日志 + 拒绝。日志只记录失败原因与触发器定位信息，不输出 secret、签名与 nonce 原文。
	 */
	private CheckedException reject(AgentTaskTrigger trigger, String reason, TaskErrorDict error) {
		log.warn("API 触发签名校验拒绝。tenantId={}, definitionId={}, triggerId={}, reason={}",
				trigger.getTenantId(), trigger.getDefinitionId(), trigger.getId(), reason);
		return CheckedException.badRequest(error.getValue(), error.getLabel());
	}

	private String hmacHex(String secret, String signingString) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
			return HexFormat.of().formatHex(mac.doFinal(signingString.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception ex) {
			// HmacSHA256 是 Java SE 必备算法，异常只可能来自环境损坏，失败关闭并保留现场。
			log.error("API 触发签名计算失败, 拒绝请求", ex);
			throw CheckedException.badRequest(TaskErrorDict.SIGNATURE_INVALID.getValue(),
					TaskErrorDict.SIGNATURE_INVALID.getLabel());
		}
	}

	private String configText(Map<String, Object> config, String key) {
		Object value = config == null ? null : config.get(key);
		String text = value == null ? null : String.valueOf(value);
		return StringUtils.hasText(text) ? text.trim() : null;
	}

}
