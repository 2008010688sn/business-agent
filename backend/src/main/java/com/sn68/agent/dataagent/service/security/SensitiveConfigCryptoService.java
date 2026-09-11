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
package com.sn68.agent.dataagent.service.security;

import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.framework.commons.crypto.AesGcmTextCrypto;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * DataAgent 敏感配置加解密服务。
 */
@Service
@RequiredArgsConstructor
public class SensitiveConfigCryptoService {

	public static final String PREFIX = "enc:gcm:";

	static final String ENABLED_PROPERTY = "spring.ai.agent.crypto.enabled";

	static final String KEY_PROPERTY = "spring.ai.agent.crypto.key";

	private final DataAgentProperties properties;

	/**
	 * 加密默认开启，因此这里必须失败关闭：缺 key 时启动直接失败，而不是退回明文入库。
	 * 静默降级的代价是所有租户的数据库口令以明文落库且没有任何信号。
	 */
	@PostConstruct
	void validate() {
		if (!properties.getCrypto().isEnabled()) {
			return;
		}
		if (!StringUtils.hasText(properties.getCrypto().getKey())) {
			// 与下方 validateKey 保持同一异常类型：同一个校验方法抛两种异常只会让调用方更难判断。
			throw new IllegalArgumentException(ENABLED_PROPERTY + "=true 但未配置 " + KEY_PROPERTY + "。请设置 "
					+ KEY_PROPERTY + "（环境变量 DATA_AGENT_CRYPTO_KEY）为 16/24/32 字节的 AES 密钥；"
					+ "确需以明文存储敏感配置时，必须显式设置 " + ENABLED_PROPERTY + "=false。");
		}
		validateKey(properties.getCrypto().getKey(), KEY_PROPERTY + " / DATA_AGENT_CRYPTO_KEY");
		if (StringUtils.hasText(properties.getCrypto().getOldKey())) {
			validateKey(properties.getCrypto().getOldKey(),
					"spring.ai.agent.crypto.old-key / DATA_AGENT_CRYPTO_OLD_KEY");
		}
	}

	private void validateKey(String key, String propertyName) {
		try {
			AesGcmTextCrypto.validateKey(key);
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException(propertyName
					+ " 配置无效：启用敏感配置加密时，AES-GCM key 必须是 16、24 或 32 字节的普通字符串，或 Base64 编码后的 16、24 或 32 字节密钥",
					ex);
		}
	}

	/**
	 * 校验敏感配置配置加密。
	 */
	public boolean isEnabled() {
		return properties.getCrypto().isEnabled();
	}

	/**
	 * 校验敏感配置配置加密。
	 */
	public boolean isCipherText(String value) {
		return StringUtils.hasText(value) && value.startsWith(PREFIX);
	}

	/**
	 * 校验敏感配置配置加密。
	 */
	public boolean hasConfiguredSecret(String value) {
		return StringUtils.hasText(value) && !isPlaceholder(value);
	}

	/**
	 * 处理敏感配置配置加密。
	 */
	public boolean shouldKeepExisting(String value) {
		return !StringUtils.hasText(value) || isPlaceholder(value);
	}

	/**
	 * 处理敏感配置配置加密。
	 */
	public String encryptIfNecessary(String value) {
		if (!StringUtils.hasText(value) || isPlaceholder(value) || isCipherText(value) || !isEnabled()) {
			return value;
		}
		return PREFIX + AesGcmTextCrypto.encrypt(value, properties.getCrypto().getKey());
	}

	/**
	 * 处理敏感配置配置加密。
	 */
	public String decryptIfNecessary(String value) {
		if (!isCipherText(value)) {
			return value;
		}
		String payload = value.substring(PREFIX.length());
		try {
			return AesGcmTextCrypto.decrypt(payload, properties.getCrypto().getKey());
		}
		catch (IllegalStateException ex) {
			String oldKey = properties.getCrypto().getOldKey();
			if (!StringUtils.hasText(oldKey)) {
				throw ex;
			}
			return AesGcmTextCrypto.decrypt(payload, oldKey);
		}
	}

	/**
	 * 处理敏感配置配置加密。
	 */
	public String decryptRuntimeSecret(String value) {
		return decryptIfNecessary(value);
	}

	/**
	 * 校验敏感配置配置加密。
	 */
	public boolean isPlaceholder(String value) {
		if (!StringUtils.hasText(value)) {
			return false;
		}
		String trimmed = value.trim();
		return trimmed.matches(".*\\*{4,}.*") || "已配置".equals(trimmed) || "configured".equalsIgnoreCase(trimmed);
	}

}
