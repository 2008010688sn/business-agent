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

import com.sn68.agent.dataagent.im.dto.DingTalkCredentialValidateResponse;
import com.sn68.agent.dataagent.im.enums.ImConstants;
import com.sn68.agent.dataagent.im.enums.ImErrorDict;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 钉钉凭据和用户解析服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DingTalkCredentialService {

	private static final ParameterizedTypeReference<Map<String, Object>> MAP_RESPONSE = new ParameterizedTypeReference<>() {
	};

	private final WebClient.Builder webClientBuilder;

	private final ImRuntimeConfigService runtimeConfigService;

	/**
	 * 校验DingTalkCredential。
	 */
	public DingTalkCredentialValidateResponse validate(String clientId, String clientSecret) {
		ImRuntimeConfigService.ProviderRuntimeConfig runtimeConfig = runtimeConfigService
			.providerRuntimeConfig(ImConstants.PROVIDER_DINGTALK);
		accessToken(clientId, clientSecret, runtimeConfig.tokenUrl(), runtimeConfig.credentialValidationTimeoutMs());
		return new DingTalkCredentialValidateResponse(true, "钉钉凭据校验通过", mask(clientId));
	}

	/**
	 * 查询DingTalkCredential。
	 */
	public String resolveUserContact(Map<String, Object> config, String externalUserId) {
		ImRuntimeConfigService.ProviderRuntimeConfig runtimeConfig = runtimeConfigService
			.providerRuntimeConfig(ImConstants.PROVIDER_DINGTALK);
		if (!connectorUserResolveEnabled(config, runtimeConfig.userResolveEnabled()) || !StringUtils.hasText(externalUserId)) {
			return null;
		}
		Map<String, Object> connectorConfig = config == null ? Map.of() : config;
		String clientId = stringValue(connectorConfig.get("clientId"));
		String clientSecret = stringValue(connectorConfig.get("clientSecret"));
		if (!StringUtils.hasText(clientId) || !StringUtils.hasText(clientSecret)) {
			return null;
		}
		try {
			String token = accessToken(clientId, clientSecret, runtimeConfig.tokenUrl(), runtimeConfig.userResolveTimeoutMs());
			String url = runtimeConfig.userInfoUrlTemplate().replace("{userId}", externalUserId.trim());
			Map<String, Object> response = webClientBuilder.clone()
				.build()
				.get()
				.uri(url)
				.header("x-acs-dingtalk-access-token", token)
				.retrieve()
				.bodyToMono(MAP_RESPONSE)
				.block(Duration.ofMillis(Math.max(1000, runtimeConfig.userResolveTimeoutMs())));
			return firstText(stringValue(response == null ? null : response.get("mobile")),
					stringValue(response == null ? null : response.get("email")));
		}
		catch (Exception ex) {
			log.warn("解析钉钉用户联系方式失败。externalUserId={}", externalUserId, ex);
			return null;
		}
	}

	private String accessToken(String clientId, String clientSecret, String tokenUrl, int timeoutMs) {
		if (!StringUtils.hasText(clientId) || !StringUtils.hasText(clientSecret)) {
			throw badRequest(ImErrorDict.DINGTALK_CREDENTIAL_INVALID);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("appKey", clientId.trim());
		body.put("appSecret", clientSecret.trim());
		try {
			Map<String, Object> response = webClientBuilder.clone()
				.build()
				.post()
				.uri(tokenUrl)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body)
				.retrieve()
				.bodyToMono(MAP_RESPONSE)
				.block(Duration.ofMillis(Math.max(1000, timeoutMs)));
			String token = firstText(stringValue(response == null ? null : response.get("accessToken")),
					stringValue(response == null ? null : response.get("access_token")));
			if (!StringUtils.hasText(token)) {
				throw badRequest(ImErrorDict.DINGTALK_CREDENTIAL_INVALID);
			}
			return token;
		}
		catch (CheckedException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.warn("钉钉凭据校验失败。clientId={}", mask(clientId), ex);
			throw badRequest(ImErrorDict.DINGTALK_CREDENTIAL_INVALID);
		}
	}

	private CheckedException badRequest(ImErrorDict error) {
		return CheckedException.badRequest(error.getValue(), error.getLabel());
	}

	private boolean connectorUserResolveEnabled(Map<String, Object> config, boolean defaultValue) {
		Object enabled = config == null ? null : config.get("userResolveEnabled");
		if (enabled == null) {
			return defaultValue;
		}
		return Boolean.TRUE.equals(enabled) || "true".equalsIgnoreCase(String.valueOf(enabled));
	}

	private String stringValue(Object value) {
		return value == null ? null : String.valueOf(value);
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

	private String mask(String value) {
		if (!StringUtils.hasText(value)) {
			return value;
		}
		String text = value.trim();
		if (text.length() <= 6) {
			return "****";
		}
		return text.substring(0, 3) + "****" + text.substring(text.length() - 2);
	}

}
