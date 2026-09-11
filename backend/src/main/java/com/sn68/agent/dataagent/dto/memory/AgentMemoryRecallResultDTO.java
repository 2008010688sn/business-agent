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

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆召回结果。
 */
@Schema(description = "Agent记忆召回结果")
public record AgentMemoryRecallResultDTO(
		@Schema(description = "记忆能力是否启用") boolean enabled,
		@Schema(description = "是否启用记忆召回") boolean recallEnabled,
		@Schema(description = "候选记忆数量") int candidateCount,
		@Schema(description = "实际注入数量") int injectedCount,
		@Schema(description = "预估Token数") int estimatedTokens,
		@Schema(description = "注入提示词的记忆文本块") String promptBlock,
		@Schema(description = "召回命中列表") List<AgentMemoryRecallHitDTO> hits,
		@Schema(description = "过滤原因统计") Map<String, Integer> filterReasons
) {

	public static AgentMemoryRecallResultDTO empty(boolean enabled, boolean recallEnabled) {
		return new AgentMemoryRecallResultDTO(enabled, recallEnabled, 0, 0, 0, "", List.of(), Map.of());
	}

}
