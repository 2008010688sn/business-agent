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

import com.sn68.agent.dataagent.entity.DataChatMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.beans.BeanUtils;

/**
 * 会话消息视图对象，在消息实体基础上补充运行请求和单轮耗时。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "DataAgent 会话消息视图")
public class DataChatMessageVO extends DataChatMessage {

	@Schema(description = "运行请求ID")
	private String runtimeRequestId;

	@Schema(description = "单轮对话耗时，单位毫秒")
	private Long durationMs;

	@Schema(description = "单轮对话状态")
	private String turnStatus;

	public static DataChatMessageVO from(DataChatMessage message) {
		DataChatMessageVO view = new DataChatMessageVO();
		if (message != null) {
			BeanUtils.copyProperties(message, view);
		}
		return view;
	}

}
