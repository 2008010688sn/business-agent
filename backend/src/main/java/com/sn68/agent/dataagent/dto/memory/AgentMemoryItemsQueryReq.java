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

import com.sn68.agent.dataagent.enums.AgentMemoryStatus;
import com.sn68.agent.dataagent.enums.AgentMemoryType;
import com.sn68.agent.dataagent.enums.MemoryConsentStatus;
import com.sn68.agent.dataagent.enums.MemoryScope;
import com.sn68.agent.dataagent.enums.MemorySensitivity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Agent记忆Items查询请求。
 *
 * <p>scope/sensitivity/consentStatus 为治理筛选项，均可不传（不传即不参与过滤）。
 * scope 只在用户个人侧范围内生效，传 WORKSPACE/SESSION 查不到数据，记忆范围隔离约定不被绕过。
 */
@Schema(description = "Agent记忆Items查询请求")
public record AgentMemoryItemsQueryReq(
		@Schema(description = "Agent ID") @NotNull(message = "agentId不能为空") Long agentId,
		@Schema(description = "记忆类型") List<AgentMemoryType> memoryTypes,
		@Schema(description = "状态") AgentMemoryStatus status,
		@Schema(description = "记忆范围（主体类型），可选筛选项") MemoryScope scope,
		@Schema(description = "敏感级别，可选筛选项") MemorySensitivity sensitivity,
		@Schema(description = "用户同意状态，可选筛选项") MemoryConsentStatus consentStatus
) {
}
