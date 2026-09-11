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
package com.sn68.agent.dataagent.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 通知授权Decision实体。
 */
@Schema(description = "通知授权Decision实体")
public record NotificationAuthorizationDecision(
		@Schema(description = "是否允许") boolean allowed,
		@Schema(description = "策略字段") String confirmPolicy,
		@Schema(description = "消息内容") String message
) {
}
