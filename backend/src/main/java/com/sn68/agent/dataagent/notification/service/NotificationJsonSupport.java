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
package com.sn68.agent.dataagent.notification.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.service.security.SensitiveConfigCryptoService;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 通知JsonSupport组件，封装 DataAgent 对应业务入口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationJsonSupport {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)(1\\d{2})\\d{4}(\\d{4})(?!\\d)");

	private final ObjectMapper objectMapper;

	private final SensitiveConfigCryptoService cryptoService;

	/**
	 * 处理通知JsonSupport。
	 */
	public Map<String, Object> readEncryptedMap(String value) {
		if (!StringUtils.hasText(value)) {
			return Map.of();
		}
		return readMap(cryptoService.decryptIfNecessary(value));
	}

	/**
	 * 处理通知JsonSupport。
	 */
	public Map<String, Object> readMap(String value) {
		if (!StringUtils.hasText(value)) {
			return Map.of();
		}
		try {
			Map<String, Object> map = objectMapper.readValue(value, MAP_TYPE);
			return map == null ? Map.of() : new LinkedHashMap<>(map);
		}
		catch (Exception ex) {
			log.warn("Failed to parse notification JSON, falling back to an empty map. length={}", value.length(), ex);
			return Map.of();
		}
	}

	/**
	 * 处理通知JsonSupport。
	 */
	public String writeEncryptedMap(Map<String, Object> value) {
		String json = writeJson(value);
		return StringUtils.hasText(json) ? cryptoService.encryptIfNecessary(json) : null;
	}

	/**
	 * 处理通知JsonSupport。
	 */
	public String writeJson(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Map<?, ?> map && map.isEmpty()) {
			return null;
		}
		if (value instanceof Collection<?> collection && collection.isEmpty()) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (Exception ex) {
			log.warn("Failed to write notification JSON, the field will be persisted as null. valueType={}",
					value.getClass().getName(), ex);
			return null;
		}
	}

	/**
	 * 处理通知JsonSupport。
	 */
	public Map<String, Object> maskMap(Map<String, Object> value) {
		if (value == null || value.isEmpty()) {
			return Map.of();
		}
		Map<String, Object> result = new LinkedHashMap<>();
		value.forEach((key, itemValue) -> result.put(key, maskValue(key, itemValue)));
		return result;
	}

	/**
	 * 处理通知JsonSupport。
	 */
	public Map<String, Object> mergePreservingPlaceholders(Map<String, Object> existing, Map<String, Object> incoming) {
		if (incoming == null) {
			return existing == null ? Map.of() : new LinkedHashMap<>(existing);
		}
		Map<String, Object> result = new LinkedHashMap<>(existing == null ? Map.of() : existing);
		incoming.forEach((key, value) -> result.put(key, mergeValue(result.get(key), value)));
		return result;
	}

	/**
	 * 处理通知JsonSupport。
	 */
	public String maskText(String value) {
		if (!StringUtils.hasText(value)) {
			return value;
		}
		String masked = PHONE_PATTERN.matcher(value).replaceAll("$1****$2");
		masked = masked.replaceAll("(?i)(access_token=)[^&\\s]+", "$1****");
		masked = masked.replaceAll("(?i)(key=)[^&\\s]+", "$1****");
		return masked;
	}

	@SuppressWarnings("unchecked")
	private Object maskValue(String key, Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> nested = new LinkedHashMap<>();
			map.forEach((nestedKey, nestedValue) -> {
				if (nestedKey != null) {
					nested.put(String.valueOf(nestedKey), maskValue(String.valueOf(nestedKey), nestedValue));
				}
			});
			return nested;
		}
		if (value instanceof Collection<?> collection) {
			return collection.stream().map(item -> maskValue(key, item)).toList();
		}
		if (!(value instanceof String text)) {
			return value;
		}
		if (isSensitiveKey(key)) {
			return maskSecret(text);
		}
		return maskText(text);
	}

	@SuppressWarnings("unchecked")
	private Object mergeValue(Object existing, Object incoming) {
		if (incoming instanceof String text && isPlaceholder(text)) {
			return existing;
		}
		if (existing instanceof Map<?, ?> existingMap && incoming instanceof Map<?, ?> incomingMap) {
			return mergePreservingPlaceholders((Map<String, Object>) existingMap, (Map<String, Object>) incomingMap);
		}
		return incoming;
	}

	private boolean isPlaceholder(String value) {
		if (!StringUtils.hasText(value)) {
			return false;
		}
		String text = value.trim();
		return text.matches(".*\\*{4,}.*") || "configured".equalsIgnoreCase(text) || "已配置".equals(text);
	}

	private boolean isSensitiveKey(String key) {
		if (!StringUtils.hasText(key)) {
			return false;
		}
		String normalized = key.toLowerCase();
		if ("replywebhookallowedhosts".equals(normalized)) {
			return false;
		}
		return List.of("secret", "token", "key", "password", "webhook", "phone", "mobile", "openid", "open_id")
			.stream()
			.anyMatch(normalized::contains);
	}

	private String maskSecret(String value) {
		if (!StringUtils.hasText(value)) {
			return value;
		}
		String text = value.trim();
		if (text.length() <= 6) {
			return "****";
		}
		if (text.matches("1\\d{10}")) {
			return text.substring(0, 3) + "****" + text.substring(7);
		}
		return text.substring(0, Math.min(3, text.length())) + "****"
				+ text.substring(Math.max(3, text.length() - 2));
	}

}
