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
 * 企业微信应用消息适配器（WECOM / WECOM_APP）：通过自建应用接口向成员/部门/标签推送应用消息。
 */
@Component
public class WeComAppMessageAdapter extends AbstractWebhookNotificationAdapter {

	public WeComAppMessageAdapter(WebClient.Builder webClientBuilder) {
		super(webClientBuilder);
	}

	@Override
	public boolean supports(String provider, String channelType) {
		return "WECOM".equalsIgnoreCase(provider) && "WECOM_APP".equalsIgnoreCase(channelType);
	}

	@Override
	public NotificationAdapterResult send(AgentNotificationConnector connector, AgentNotificationTarget target,
			AgentNotificationTemplate template, NotificationRenderResult rendered, Map<String, Object> connectorConfig,
			Map<String, Object> targetConfig) {
		String accessToken = accessToken(connectorConfig);
		String endpoint = firstText(stringValue(connectorConfig.get("sendUrl")),
				"https://qyapi.weixin.qq.com/cgi-bin/message/send") + "?access_token=" + accessToken;
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("agentid", parseInt(firstText(stringValue(targetConfig.get("agentId")),
				stringValue(connectorConfig.get("agentId")))));
		body.put("touser", firstText(stringValue(targetConfig.get("toUser")), stringValue(targetConfig.get("touser")),
				"@all"));
		putIfText(body, "toparty",
				firstText(stringValue(targetConfig.get("toParty")), stringValue(targetConfig.get("toparty"))));
		putIfText(body, "totag", firstText(stringValue(targetConfig.get("toTag")), stringValue(targetConfig.get("totag"))));
		String type = firstText(template.getMessageType(), "text").toLowerCase();
		body.put("msgtype", type);
		body.put(type, Map.of("content", rendered.content()));
		return postJson(endpoint, body);
	}

	private String accessToken(Map<String, Object> connectorConfig) {
		String token = stringValue(connectorConfig.get("accessToken"));
		if (StringUtils.hasText(token)) {
			return token.trim();
		}
		String corpId = stringValue(connectorConfig.get("corpId"));
		String corpSecret = stringValue(connectorConfig.get("corpSecret"));
		if (!StringUtils.hasText(corpId) || !StringUtils.hasText(corpSecret)) {
			throw CheckedException.badRequest("WeCom corpId/corpSecret or accessToken is required.");
		}
		String tokenUrl = firstText(stringValue(connectorConfig.get("tokenUrl")),
				"https://qyapi.weixin.qq.com/cgi-bin/gettoken") + "?corpid=" + corpId + "&corpsecret=" + corpSecret;
		Map<String, Object> response = getJson(tokenUrl);
		String errCode = firstText(stringValue(response.get("errcode")), "0");
		if (!"0".equals(errCode)) {
			throw CheckedException.badRequest("WeCom access token request failed: " + response.get("errmsg"));
		}
		return stringValue(response.get("access_token"));
	}

	private void putIfText(Map<String, Object> body, String key, String value) {
		if (StringUtils.hasText(value)) {
			body.put(key, value.trim());
		}
	}

	private Integer parseInt(String value) {
		if (!StringUtils.hasText(value)) {
			throw CheckedException.badRequest("WeCom agentId is required.");
		}
		return Integer.valueOf(value.trim());
	}

}
