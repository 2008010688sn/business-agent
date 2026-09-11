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
package com.sn68.agent.dataagent.dto.chat;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Create会话会话请求。
 */
@Data
@Schema(description = "创建会话请求")
public class CreateChatSessionReq {

	@Schema(description = "智能体ID")
	@NotNull(message = "agentId不能为空")
	private Long agentId;

	@Schema(description = "会话标题")
	private String title;

	@Schema(description = "用户ID")
	private Long userId;

	@Schema(description = "会话渠道类型")
	private String channelType;

}
