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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.notification.dto.NotificationAdapterResult;
import com.sn68.agent.dataagent.notification.dto.NotificationRenderResult;
import com.sn68.agent.dataagent.notification.entity.AgentNotificationConnector;
import com.sn68.agent.dataagent.notification.entity.AgentNotificationTarget;
import com.sn68.agent.dataagent.notification.entity.AgentNotificationTemplate;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 阿里云短信通知适配器（SMS / SMS_ALIYUN）：走阿里云短信 OpenAPI 发送模板短信，签名/模板号取自连接器配置。
 */
@Component
public class SmsAliyunAdapter extends AbstractWebhookNotificationAdapter {

	private final ObjectMapper objectMapper;

	public SmsAliyunAdapter(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
		super(webClientBuilder);
		this.objectMapper = objectMapper;
	}

	@Override
	public boolean supports(String provider, String channelType) {
		return "SMS".equalsIgnoreCase(provider) && "SMS_ALIYUN".equalsIgnoreCase(channelType);
	}

	@Override
	public NotificationAdapterResult send(AgentNotificationConnector connector, AgentNotificationTarget target,
			AgentNotificationTemplate template, NotificationRenderResult rendered, Map<String, Object> connectorConfig,
			Map<String, Object> targetConfig) {
		String endpoint = firstText(stringValue(connectorConfig.get("endpoint")), "https://dysmsapi.aliyuncs.com/");
		String accessKeyId = required(stringValue(connectorConfig.get("accessKeyId")), "Aliyun SMS accessKeyId is required.");
		String accessKeySecret = required(stringValue(connectorConfig.get("accessKeySecret")),
				"Aliyun SMS accessKeySecret is required.");
		TreeMap<String, String> params = new TreeMap<>();
		params.put("AccessKeyId", accessKeyId);
		params.put("Action", "SendSms");
		params.put("Format", "JSON");
		params.put("PhoneNumbers", required(firstText(stringValue(targetConfig.get("phoneNumbers")),
				stringValue(targetConfig.get("phone")), stringValue(targetConfig.get("mobile"))),
				"Aliyun SMS phone number is required."));
		params.put("RegionId", firstText(stringValue(connectorConfig.get("regionId")), "cn-hangzhou"));
		params.put("SignName",
				required(firstText(stringValue(targetConfig.get("signName")), stringValue(connectorConfig.get("signName"))),
						"Aliyun SMS signName is required."));
		params.put("SignatureMethod", "HMAC-SHA1");
		params.put("SignatureNonce", UUID.randomUUID().toString());
		params.put("SignatureVersion", "1.0");
		params.put("TemplateCode", required(firstText(template.getPlatformTemplateId(),
				stringValue(targetConfig.get("templateCode")), stringValue(connectorConfig.get("templateCode"))),
				"Aliyun SMS templateCode is required."));
		params.put("TemplateParam", writeJson(rendered.variables()));
		params.put("Timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
		params.put("Version", "2017-05-25");
		params.put("Signature", signature(params, accessKeySecret));
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		params.forEach(form::add);
		return postForm(endpoint, form);
	}

	private String signature(TreeMap<String, String> params, String accessKeySecret) {
		try {
			StringBuilder canonicalized = new StringBuilder();
			for (Map.Entry<String, String> entry : params.entrySet()) {
				if (canonicalized.length() > 0) {
					canonicalized.append('&');
				}
				canonicalized.append(percentEncode(entry.getKey())).append('=').append(percentEncode(entry.getValue()));
			}
			String stringToSign = "POST&%2F&" + percentEncode(canonicalized.toString());
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec((accessKeySecret + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
			return java.util.Base64.getEncoder()
				.encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception ex) {
			throw CheckedException.badRequest("Aliyun SMS signature failed.");
		}
	}

	private String percentEncode(String value) {
		return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
			.replace("+", "%20")
			.replace("*", "%2A")
			.replace("%7E", "~");
	}

	private String required(String value, String message) {
		if (!StringUtils.hasText(value)) {
			throw CheckedException.badRequest(message);
		}
		return value.trim();
	}

	private String writeJson(Map<String, Object> variables) {
		try {
			return objectMapper.writeValueAsString(variables == null ? Map.of() : variables);
		}
		catch (Exception ex) {
			throw CheckedException.badRequest("Aliyun SMS template parameters are invalid.");
		}
	}

}
