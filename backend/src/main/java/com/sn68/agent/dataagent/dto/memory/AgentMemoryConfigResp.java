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
package com.sn68.agent.dataagent.dto.memory;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.dataagent.enums.AgentMemoryType;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DataAgent 长期记忆配置 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Agent记忆配置数据传输对象")
public class AgentMemoryConfigResp {

	@Schema(description = "主键ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long id;

	@Schema(description = "Agent ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long agentId;

	@Schema(description = "是否启用记忆召回")
	private String userId;

	@Schema(description = "是否启用记忆召回")
	private Boolean recallEnabled;

	@Schema(description = "是否启用记忆写入")
	private Boolean writeEnabled;

	@Schema(description = "召回记忆类型")
	private List<AgentMemoryType> recallTypes;

	@Schema(description = "写入记忆类型")
	private List<AgentMemoryType> writeTypes;

	@Schema(description = "召回数量")
	private Integer topK;

	@Schema(description = "相似度阈值")
	private Double similarityThreshold;

	@Schema(description = "注入上下文Token预算")
	private Integer injectionTokenBudget;

	@Schema(description = "最低重要性")
	private Double minImportance;

	@Schema(description = "是否仅使用已确认记忆")
	private Boolean confirmedOnly;

	public static AgentMemoryConfigResp defaults(Long agentId, String userId, DataAgentProperties properties) {
		DataAgentProperties.LongTermMemory longTermMemory = properties == null ? new DataAgentProperties.LongTermMemory()
				: properties.getLongTermMemory();
		if (longTermMemory == null) {
			longTermMemory = new DataAgentProperties.LongTermMemory();
		}
		return AgentMemoryConfigResp.builder()
			.agentId(agentId)
			.userId(userId)
			.recallEnabled(longTermMemory.isRecallEnabledDefault())
			.writeEnabled(longTermMemory.isWriteEnabledDefault())
			.recallTypes(List.of(AgentMemoryType.PREFERENCE, AgentMemoryType.SEMANTIC, AgentMemoryType.EPISODIC,
					AgentMemoryType.PROCEDURAL))
			.writeTypes(List.of(AgentMemoryType.PREFERENCE, AgentMemoryType.SEMANTIC, AgentMemoryType.PROCEDURAL))
			.topK(longTermMemory.getDefaultTopK())
			.similarityThreshold(longTermMemory.getDefaultSimilarityThreshold())
			.injectionTokenBudget(longTermMemory.getDefaultInjectionTokenBudget())
			.minImportance(longTermMemory.getDefaultMinImportance())
			.confirmedOnly(false)
			.build();
	}

}
