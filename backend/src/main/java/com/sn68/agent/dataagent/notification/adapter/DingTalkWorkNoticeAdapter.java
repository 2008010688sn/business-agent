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
 * 钉钉工作通知适配器（DINGTALK / DINGTALK_WORK_NOTICE）：通过企业内部应用接口向指定用户推送工作通知。
 */
@Component
public class DingTalkWorkNoticeAdapter extends AbstractWebhookNotificationAdapter {

	public DingTalkWorkNoticeAdapter(WebClient.Builder webClientBuilder) {
		super(webClientBuilder);
	}

	@Override
	public boolean supports(String provider, String channelType) {
		return "DINGTALK".equalsIgnoreCase(provider) && "DINGTALK_WORK_NOTICE".equalsIgnoreCase(channelType);
	}

	@Override
	public NotificationAdapterResult send(AgentNotificationConnector connector, AgentNotificationTarget target,
			AgentNotificationTemplate template, NotificationRenderResult rendered, Map<String, Object> connectorConfig,
			Map<String, Object> targetConfig) {
		String accessToken = accessToken(connectorConfig);
		String endpoint = firstText(stringValue(connectorConfig.get("sendUrl")),
				"https://oapi.dingtalk.com/topapi/message/corpconversation/asyncsend_v2") + "?access_token="
				+ accessToken;
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("agent_id", parseLong(firstText(stringValue(targetConfig.get("agentId")),
				stringValue(connectorConfig.get("agentId")))));
		putIfText(body, "userid_list",
				firstText(stringValue(targetConfig.get("userIds")), stringValue(targetConfig.get("useridList")),
						stringValue(targetConfig.get("userid_list"))));
		putIfText(body, "dept_id_list",
				firstText(stringValue(targetConfig.get("deptIds")), stringValue(targetConfig.get("deptIdList")),
						stringValue(targetConfig.get("dept_id_list"))));
		if (targetConfig.containsKey("toAllUser")) {
			body.put("to_all_user", Boolean.TRUE.equals(targetConfig.get("toAllUser")));
		}
		body.put("msg", message(template, rendered));
		return postJson(endpoint, body);
	}

	private String accessToken(Map<String, Object> connectorConfig) {
		String token = stringValue(connectorConfig.get("accessToken"));
		if (StringUtils.hasText(token)) {
			return token.trim();
		}
		String appKey = stringValue(connectorConfig.get("appKey"));
		String appSecret = stringValue(connectorConfig.get("appSecret"));
		if (!StringUtils.hasText(appKey) || !StringUtils.hasText(appSecret)) {
			throw CheckedException.badRequest("DingTalk appKey/appSecret or accessToken is required.");
		}
		String tokenUrl = firstText(stringValue(connectorConfig.get("tokenUrl")), "https://oapi.dingtalk.com/gettoken")
				+ "?appkey=" + appKey + "&appsecret=" + appSecret;
		Map<String, Object> response = getJson(tokenUrl);
		String errCode = firstText(stringValue(response.get("errcode")), "0");
		if (!"0".equals(errCode)) {
			throw CheckedException.badRequest("DingTalk access token request failed: " + response.get("errmsg"));
		}
		return stringValue(response.get("access_token"));
	}

	private Map<String, Object> message(AgentNotificationTemplate template, NotificationRenderResult rendered) {
		String type = firstText(template.getMessageType(), "text");
		if ("markdown".equalsIgnoreCase(type)) {
			return Map.of("msgtype", "markdown", "markdown",
					Map.of("title", firstText(rendered.title(), template.getTemplateName()), "text", rendered.content()));
		}
		return Map.of("msgtype", "text", "text", Map.of("content", rendered.content()));
	}

	private void putIfText(Map<String, Object> body, String key, String value) {
		if (StringUtils.hasText(value)) {
			body.put(key, value.trim());
		}
	}

	private Long parseLong(String value) {
		if (!StringUtils.hasText(value)) {
			throw CheckedException.badRequest("DingTalk agentId is required.");
		}
		return Long.valueOf(value.trim());
	}

}
