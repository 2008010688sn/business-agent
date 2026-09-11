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
package com.sn68.agent.dataagent.notification.adapter;

import com.sn68.agent.dataagent.notification.dto.NotificationAdapterResult;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Webhook 类通知适配器基类：封装 WebClient POST 调用、超时与异常到失败结果的转换，
 * 子类只需拼装平台报文与判定平台回执。
 */
@Slf4j
@RequiredArgsConstructor
abstract class AbstractWebhookNotificationAdapter implements NotificationAdapter {

	private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {
	};

	private final WebClient.Builder webClientBuilder;

	protected NotificationAdapterResult postJson(String url, Map<String, Object> body) {
		return postJson(url, body, Map.of());
	}

	protected NotificationAdapterResult postJson(String url, Map<String, Object> body, Map<String, String> headers) {
		if (!StringUtils.hasText(url)) {
			throw CheckedException.badRequest("Notification webhook URL is required.");
		}
		try {
			Map<String, Object> response = webClientBuilder.clone()
				.build()
				.post()
				.uri(url)
				.accept(MediaType.APPLICATION_JSON)
				.contentType(MediaType.APPLICATION_JSON)
				.headers(httpHeaders -> applyHeaders(httpHeaders, headers))
				.bodyValue(body == null ? Map.of() : body)
				.retrieve()
				.bodyToMono(MAP_TYPE)
				.block();
			return toResult(response);
		}
		catch (Exception ex) {
			log.warn("Notification HTTP send failed", ex);
			return new NotificationAdapterResult(false, null, "HTTP_ERROR", ex.getMessage(), Map.of());
		}
	}

	protected NotificationAdapterResult postForm(String url, MultiValueMap<String, String> form) {
		if (!StringUtils.hasText(url)) {
			throw CheckedException.badRequest("Notification form URL is required.");
		}
		try {
			Map<String, Object> response = webClientBuilder.clone()
				.build()
				.post()
				.uri(url)
				.accept(MediaType.APPLICATION_JSON)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.bodyValue(form)
				.retrieve()
				.bodyToMono(MAP_TYPE)
				.block();
			return toResult(response);
		}
		catch (Exception ex) {
			log.warn("Notification form send failed", ex);
			return new NotificationAdapterResult(false, null, "HTTP_ERROR", ex.getMessage(), Map.of());
		}
	}

	protected Map<String, Object> getJson(String url) {
		if (!StringUtils.hasText(url)) {
			throw CheckedException.badRequest("Notification GET URL is required.");
		}
		try {
			Map<String, Object> response = webClientBuilder.clone()
				.build()
				.get()
				.uri(url)
				.accept(MediaType.APPLICATION_JSON)
				.retrieve()
				.bodyToMono(MAP_TYPE)
				.block();
			return new LinkedHashMap<>(response == null ? Map.of() : response);
		}
		catch (Exception ex) {
			throw CheckedException.badRequest("Notification GET request failed: " + ex.getMessage());
		}
	}

	private NotificationAdapterResult toResult(Map<String, Object> response) {
		Map<String, Object> data = new LinkedHashMap<>(response == null ? Map.of() : response);
		String code = firstText(stringValue(data.get("errcode")), stringValue(data.get("code")),
				stringValue(data.get("Code")), "0");
		String message = firstText(stringValue(data.get("errmsg")), stringValue(data.get("message")),
				stringValue(data.get("Message")), "OK");
		boolean success = "0".equals(code) || "OK".equalsIgnoreCase(code) || "success".equalsIgnoreCase(code);
		return new NotificationAdapterResult(success,
				firstText(stringValue(data.get("request_id")), stringValue(data.get("RequestId"))), code, message, data);
	}

	private void applyHeaders(HttpHeaders httpHeaders, Map<String, String> headers) {
		if (headers == null || headers.isEmpty()) {
			return;
		}
		headers.forEach((key, value) -> {
			if (StringUtils.hasText(key) && StringUtils.hasText(value)) {
				httpHeaders.set(key, value);
			}
		});
	}

	protected String dingtalkSignedUrl(String webhook, String secret) {
		if (!StringUtils.hasText(secret)) {
			return webhook;
		}
		try {
			long timestamp = System.currentTimeMillis();
			String stringToSign = timestamp + "\n" + secret;
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			String sign = java.util.Base64.getEncoder()
				.encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
			String encodedSign = URLEncoder.encode(sign, StandardCharsets.UTF_8);
			String separator = webhook.contains("?") ? "&" : "?";
			return webhook + separator + "timestamp=" + timestamp + "&sign=" + encodedSign;
		}
		catch (Exception ex) {
			throw CheckedException.badRequest("Failed to sign DingTalk webhook.");
		}
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

	protected String stringValue(Object value) {
		return value == null ? null : String.valueOf(value);
	}

}
