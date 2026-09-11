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
package com.sn68.agent.dataagent.agentscope.vo;

import com.sn68.agent.dataagent.enums.TextType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent响应。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Schema(description = "Agent响应")
public class AgentResponse {

	@Schema(description = "Agent ID")
	private String agentId;

	@Schema(description = "线程ID")
	private String threadId;

	@Schema(description = "名称")
	private String nodeName;

	@Schema(description = "类型文本字段")
	private TextType textType;

	@Schema(description = "文本字段")
	private String text;

	@Schema(description = "元数据")
	private Map<String, Object> metadata;

	@Schema(description = "错误字段")
	@Builder.Default
	private boolean error = false;

	@Schema(description = "complete字段")
	@Builder.Default
	private boolean complete = false;

	public static AgentResponse error(String agentId, String threadId, String text) {
		return error(agentId, threadId, text, null);
	}

	public static AgentResponse error(String agentId, String threadId, String text, Map<String, Object> metadata) {
		return AgentResponse.builder()
			.agentId(agentId)
			.threadId(threadId)
			.text(text)
			.metadata(metadata)
			.error(true)
			.textType(TextType.TEXT)
			.build();
	}

	public static AgentResponse complete(String agentId, String threadId) {
		return AgentResponse.builder()
			.agentId(agentId)
			.threadId(threadId)
			.complete(true)
			.textType(TextType.TEXT)
			.build();
	}

}
