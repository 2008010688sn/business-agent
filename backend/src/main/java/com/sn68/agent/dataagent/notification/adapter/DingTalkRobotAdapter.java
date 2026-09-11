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
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 钉钉群机器人通知适配器（DINGTALK / DINGTALK_ROBOT）：通过群自定义机器人 Webhook 推送消息。
 */
@Component
public class DingTalkRobotAdapter extends AbstractWebhookNotificationAdapter {

	public DingTalkRobotAdapter(WebClient.Builder webClientBuilder) {
		super(webClientBuilder);
	}

	@Override
	public boolean supports(String provider, String channelType) {
		return "DINGTALK".equalsIgnoreCase(provider) && "DINGTALK_ROBOT".equalsIgnoreCase(channelType);
	}

	@Override
	public NotificationAdapterResult send(AgentNotificationConnector connector, AgentNotificationTarget target,
			AgentNotificationTemplate template, NotificationRenderResult rendered, Map<String, Object> connectorConfig,
			Map<String, Object> targetConfig) {
		String webhook = firstText(stringValue(targetConfig.get("webhook")), stringValue(connectorConfig.get("webhook")));
		String secret = firstText(stringValue(targetConfig.get("secret")), stringValue(connectorConfig.get("secret")));
		String messageType = firstText(template.getMessageType(), "markdown");
		Map<String, Object> body = "text".equalsIgnoreCase(messageType)
				? Map.of("msgtype", "text", "text", Map.of("content", rendered.content()))
				: Map.of("msgtype", "markdown", "markdown",
						Map.of("title", firstText(rendered.title(), template.getTemplateName()), "text", rendered.content()));
		return postJson(dingtalkSignedUrl(webhook, secret), body);
	}

}
