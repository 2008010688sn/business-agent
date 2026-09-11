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
import java.util.List;

/**
 * IM 单聊或群聊绑定配置。
 */
@Schema(description = "IM会话绑定数据传输对象")
public record ImConversationBindingDTO(
		@Schema(description = "主键ID") Long id,
		@Schema(description = "平台类型") String provider,
		@Schema(description = "连接器编码") String connectorCode,
		@Schema(description = "会话类型") String conversationType,
		@Schema(description = "externalConversationId字段") String externalConversationId,
		@Schema(description = "名称") String conversationName,
		@Schema(description = "Agent ID") Long agentId,
		@Schema(description = "绑定数字员工ID（可空）") Long digitalEmployeeId,
		@Schema(description = "策略字段") String triggerPolicy,
		@Schema(description = "wakeWords字段") List<String> wakeWords,
		@Schema(description = "会话字段") String sessionScope,
		@Schema(description = "状态") String status,
		@Schema(description = "displayOrder字段") Integer displayOrder
) {
}
