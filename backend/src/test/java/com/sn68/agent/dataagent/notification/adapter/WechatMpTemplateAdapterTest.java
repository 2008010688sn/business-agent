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
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WechatMpTemplateAdapterTest {

	@Test
	void templateMessageUsesVariablesUrlAsJumpUrl() {
		CapturingWechatMpTemplateAdapter adapter = new CapturingWechatMpTemplateAdapter();
		AgentNotificationTemplate template = new AgentNotificationTemplate();
		template.setPlatformTemplateId("template-1");
		NotificationRenderResult rendered = new NotificationRenderResult("", "", "",
				Map.of("url", "https://platform.example.com/order/detail?id=demand-1", "demandNo", "XQ202607090001"));

		adapter.send(new AgentNotificationConnector(), new AgentNotificationTarget(), template, rendered,
				Map.of("accessToken", "token-1", "sendUrl", "https://wechat.example.com/send"),
				Map.of("openId", "open-1"));

		assertEquals("https://platform.example.com/order/detail?id=demand-1", adapter.body.get("url"));
		assertFalse(((Map<?, ?>) adapter.body.get("data")).containsKey("url"));
	}

	private static class CapturingWechatMpTemplateAdapter extends WechatMpTemplateAdapter {

		private Map<String, Object> body;

		CapturingWechatMpTemplateAdapter() {
			super(WebClient.builder());
		}

		@Override
		protected NotificationAdapterResult postJson(String url, Map<String, Object> body) {
			this.body = body;
			return new NotificationAdapterResult(true, "request-1", "0", "OK", Map.of());
		}

	}

}
