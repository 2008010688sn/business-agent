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
import com.sn68.agent.dataagent.enums.AgentMemoryStatus;
import com.sn68.agent.dataagent.enums.AgentMemoryType;
import com.sn68.agent.dataagent.enums.MemoryConsentStatus;
import com.sn68.agent.dataagent.enums.MemoryScope;
import com.sn68.agent.dataagent.enums.MemorySensitivity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/**
 * DataAgent 长期记忆列表项。
 *
 * <p>治理字段（subjectType/subjectId/factKey/revision/sensitivity/consentStatus/validFrom/validTo）
 * 与导出条目 {@link AgentMemoryExportItemResp} 同名同类型，前端列表与导出可共用同一套字段定义。
 */
@Builder
@Schema(description = "Agent记忆Item数据传输对象")
public record AgentMemoryItemResp(
		@Schema(description = "主键ID") @JsonSerialize(using = ToStringSerializer.class) Long id,
		@Schema(description = "Agent ID") @JsonSerialize(using = ToStringSerializer.class) Long agentId,
		@Schema(description = "用户ID") String userId,
		@Schema(description = "记忆范围（主体类型）") MemoryScope subjectType,
		@Schema(description = "记忆主体ID") String subjectId,
		@Schema(description = "事实键") String factKey,
		@Schema(description = "修订版本") Integer revision,
		@Schema(description = "记忆类型") AgentMemoryType memoryType,
		@Schema(description = "摘要") String summary,
		@Schema(description = "来源会话ID") String sourceSessionId,
		@Schema(description = "敏感级别") MemorySensitivity sensitivity,
		@Schema(description = "用户同意状态") MemoryConsentStatus consentStatus,
		@Schema(description = "生效时间") Instant validFrom,
		@Schema(description = "失效时间（TTL）") Instant validTo,
		@Schema(description = "置信度") Double confidence,
		@Schema(description = "重要性") Double importance,
		@Schema(description = "使用次数") Integer useCount,
		@Schema(description = "最后使用时间") Instant lastUsedTime,
		@Schema(description = "状态") AgentMemoryStatus status,
		@Schema(description = "创建时间") Instant createTime
) {
}
