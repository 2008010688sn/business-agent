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
package com.sn68.agent.dataagent.im.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * IM 接入向导完成请求。
 */
@Schema(description = "IMSetupComplete请求")
public record ImSetupCompleteRequest(
		@Schema(description = "接入配置会话ID") String setupId,
		@Schema(description = "连接器编码") String connectorCode,
		@Schema(description = "连接器名称") String connectorName,
		@Schema(description = "Agent默认标识字段") Long defaultAgentId,
		@Schema(description = "客户端ID") String clientId,
		@Schema(description = "客户端密钥敏感配置") String clientSecret,
		@Schema(description = "名称") String botName,
		@Schema(description = "描述") String botDescription,
		@Schema(description = "启用状态") Boolean directEnabled,
		@Schema(description = "启用状态") Boolean groupEnabled
) {
}
