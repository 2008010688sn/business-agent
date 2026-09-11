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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.notification.dto.NotificationRenderResult;
import com.sn68.agent.dataagent.notification.entity.AgentNotificationTemplate;
import com.sn68.agent.dataagent.notification.repository.AgentNotificationTemplateMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class NotificationTemplateServiceTest {

	private final NotificationTemplateService service = new NotificationTemplateService(
			mock(AgentNotificationTemplateMapper.class), mock(NotificationConnectorService.class),
			new NotificationJsonSupport(new ObjectMapper(), null),
			mock(com.sn68.agent.dataagent.service.permission.PlatformScopePermissionService.class));

	@Test
	void renderReplacesUrlVariable() {
		AgentNotificationTemplate template = new AgentNotificationTemplate();
		template.setTitleTemplate("下单成功");
		template.setContentTemplate("需求单 ${demandNo} 已创建，查看详情：${url}");
		template.setVariableSchema("{\"required\":[\"url\"]}");

		NotificationRenderResult result = service.render(template, Map.of("demandNo", "XQ202607090001",
				"url", "https://platform.example.com/order/detail?id=demand-1"));

		assertEquals("需求单 XQ202607090001 已创建，查看详情：https://platform.example.com/order/detail?id=demand-1",
				result.content());
		assertEquals("https://platform.example.com/order/detail?id=demand-1", result.variables().get("url"));
	}

}
