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
package com.sn68.agent.dataagent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 会话消息数据传输对象。
 */
@Data
@Schema(description = "保存会话消息请求")
public class ChatMessageReq {

	@Schema(description = "会话ID")
	@NotNull(message = "sessionId不能为空")
	private Long sessionId;

	@Schema(description = "智能体ID")
	private Long agentId;

	@Schema(description = "消息角色")
	private String role;

	@Schema(description = "消息内容")
	private String content;

	@Schema(description = "消息类型")
	private String messageType;

	@Schema(description = "消息元数据")
	private String metadata;

	@Schema(description = "客户端请求幂等键，写入消息 metadata.clientRequestId，同会话重复提交返回已有用户消息")
	private String clientRequestId;

	@Schema(description = "是否需要生成标题")
	private boolean titleNeeded;

}
