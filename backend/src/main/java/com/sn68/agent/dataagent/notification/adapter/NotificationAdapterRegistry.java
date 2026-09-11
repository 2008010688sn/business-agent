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

import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 通知适配器注册表：收集全部 {@link NotificationAdapter} 实现，按（服务商, 渠道类型）路由到匹配的适配器。
 */
@Component
@RequiredArgsConstructor
public class NotificationAdapterRegistry {

	private final List<NotificationAdapter> adapters;

	public NotificationAdapter get(String provider, String channelType) {
		return adapters.stream()
			.filter(adapter -> adapter.supports(provider, channelType))
			.findFirst()
			.orElseThrow(() -> CheckedException.badRequest("Notification adapter is not available."));
	}

}
