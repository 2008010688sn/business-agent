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
import com.sn68.agent.dataagent.notification.dto.NotificationRenderResult;
import com.sn68.agent.dataagent.notification.entity.AgentNotificationConnector;
import com.sn68.agent.dataagent.notification.entity.AgentNotificationTarget;
import com.sn68.agent.dataagent.notification.entity.AgentNotificationTemplate;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 微信公众号模板消息适配器（WECHAT_MP / WECHAT_MP_TEMPLATE）：向关注用户的 openid 推送模板消息。
 */
@Component
public class WechatMpTemplateAdapter extends AbstractWebhookNotificationAdapter {

	public WechatMpTemplateAdapter(WebClient.Builder webClientBuilder) {
		super(webClientBuilder);
	}

	@Override
	public boolean supports(String provider, String channelType) {
		return "WECHAT_MP".equalsIgnoreCase(provider) && "WECHAT_MP_TEMPLATE".equalsIgnoreCase(channelType);
	}

	@Override
	public NotificationAdapterResult send(AgentNotificationConnector connector, AgentNotificationTarget target,
			AgentNotificationTemplate template, NotificationRenderResult rendered, Map<String, Object> connectorConfig,
			Map<String, Object> targetConfig) {
		String accessToken = accessToken(connectorConfig);
		String endpoint = firstText(stringValue(connectorConfig.get("sendUrl")),
				"https://api.weixin.qq.com/cgi-bin/message/template/send") + "?access_token=" + accessToken;
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("touser", required(firstText(stringValue(targetConfig.get("openId")), stringValue(targetConfig.get("touser"))),
				"WeChat MP openId is required."));
		body.put("template_id", required(firstText(template.getPlatformTemplateId(), stringValue(targetConfig.get("templateId")),
				stringValue(connectorConfig.get("templateId"))), "WeChat MP template_id is required."));
		putIfText(body, "url", firstText(stringValue(rendered.variables().get("url")), stringValue(targetConfig.get("url"))));
		Object miniProgram = firstValue(targetConfig.get("miniprogram"), connectorConfig.get("miniprogram"));
		if (miniProgram != null) {
			body.put("miniprogram", miniProgram);
		}
		body.put("data", wechatData(rendered.variables()));
		return postJson(endpoint, body);
	}

	private String accessToken(Map<String, Object> connectorConfig) {
		String token = stringValue(connectorConfig.get("accessToken"));
		if (StringUtils.hasText(token)) {
			return token.trim();
		}
		String appId = stringValue(connectorConfig.get("appId"));
		String appSecret = stringValue(connectorConfig.get("appSecret"));
		if (!StringUtils.hasText(appId) || !StringUtils.hasText(appSecret)) {
			throw CheckedException.badRequest("WeChat MP appId/appSecret or accessToken is required.");
		}
		String tokenUrl = firstText(stringValue(connectorConfig.get("tokenUrl")),
				"https://api.weixin.qq.com/cgi-bin/token") + "?grant_type=client_credential&appid=" + appId + "&secret="
				+ appSecret;
		Map<String, Object> response = getJson(tokenUrl);
		if (!StringUtils.hasText(stringValue(response.get("access_token")))) {
			throw CheckedException.badRequest("WeChat MP access token request failed: " + response.get("errmsg"));
		}
		return stringValue(response.get("access_token"));
	}

	private Map<String, Object> wechatData(Map<String, Object> variables) {
		Map<String, Object> data = new LinkedHashMap<>();
		variables.forEach((key, value) -> {
			if (!StringUtils.hasText(key) || key.startsWith("_") || "url".equals(key)) {
				return;
			}
			data.put(key, value instanceof Map<?, ?> ? value : Map.of("value", value == null ? "" : String.valueOf(value)));
		});
		return data;
	}

	private void putIfText(Map<String, Object> body, String key, String value) {
		if (StringUtils.hasText(value)) {
			body.put(key, value.trim());
		}
	}

	private Object firstValue(Object... values) {
		if (values == null) {
			return null;
		}
		for (Object value : values) {
			if (value != null) {
				return value;
			}
		}
		return null;
	}

	private String required(String value, String message) {
		if (!StringUtils.hasText(value)) {
			throw CheckedException.badRequest(message);
		}
		return value.trim();
	}

}
