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

/**
 * 通知渠道适配器契约：每种（服务商, 渠道类型）组合一个实现，负责把渲染结果投递到具体平台。
 */
public interface NotificationAdapter {

	/**
	 * 判断本适配器是否支持给定的服务商与渠道类型组合。
	 */
	boolean supports(String provider, String channelType);

	/**
	 * 将渲染后的通知内容通过平台接口发送到目标，返回含平台回执/错误信息的投递结果，不抛出平台异常。
	 */
	NotificationAdapterResult send(AgentNotificationConnector connector, AgentNotificationTarget target,
			AgentNotificationTemplate template, NotificationRenderResult rendered, Map<String, Object> connectorConfig,
			Map<String, Object> targetConfig);

	/**
	 * 连通性自检（管理端"测试连接"用），默认实现直接返回可用，不真正外呼。
	 */
	default NotificationAdapterResult test(AgentNotificationConnector connector, Map<String, Object> connectorConfig) {
		return new NotificationAdapterResult(true, null, "OK", "Connector adapter is available.", Map.of());
	}

}
