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

import com.sn68.agent.dataagent.enums.AgentMemoryType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 更新 DataAgent 长期记忆配置请求。
 */
@Schema(description = "UpdateAgent记忆配置请求")
public record UpdateAgentMemoryConfigReq(
		@Schema(description = "Agent ID") @NotNull(message = "agentId不能为空") Long agentId,
		@Schema(description = "是否启用记忆召回") Boolean recallEnabled,
		@Schema(description = "是否启用记忆写入") Boolean writeEnabled,
		@Schema(description = "召回记忆类型") List<AgentMemoryType> recallTypes,
		@Schema(description = "写入记忆类型") List<AgentMemoryType> writeTypes,
		@Schema(description = "召回数量") Integer topK,
		@Schema(description = "相似度阈值") Double similarityThreshold,
		@Schema(description = "注入上下文Token预算") Integer injectionTokenBudget,
		@Schema(description = "最低重要性") Double minImportance,
		@Schema(description = "是否仅使用已确认记忆") Boolean confirmedOnly
) {
}
