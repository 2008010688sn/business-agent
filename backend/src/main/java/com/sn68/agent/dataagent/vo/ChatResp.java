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
package com.sn68.agent.dataagent.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 数据问答会话的单条响应消息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "会话响应")
public class ChatResp {

	@Schema(description = "会话ID")
	private String sessionId;

	@Schema(description = "消息内容")
	private String message;

	@Schema(description = "消息类型（text/sql/result/error）")
	private String messageType;

	@Schema(description = "生成的SQL语句")
	private String sql;

	@Schema(description = "SQL查询结果")
	private Object result;

	@Schema(description = "错误信息")
	private String error;

	public ChatResp(String sessionId, String message, String messageType) {
		this.sessionId = sessionId;
		this.message = message;
		this.messageType = messageType;
	}

}
