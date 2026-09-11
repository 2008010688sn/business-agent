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
 * 记忆导出条目（按主体全量导出，含治理字段）。
 */
@Builder
@Schema(description = "Agent记忆导出条目")
public record AgentMemoryExportItemResp(
		@Schema(description = "主键ID") @JsonSerialize(using = ToStringSerializer.class) Long id,
		@Schema(description = "租户ID") String tenantId,
		@Schema(description = "记忆命名空间") String namespaceId,
		@Schema(description = "Agent ID") @JsonSerialize(using = ToStringSerializer.class) Long agentId,
		@Schema(description = "数字员工共享记忆归属ID（WORKSPACE 共享记忆冗余列，PR-7 归属键切换）") @JsonSerialize(using = ToStringSerializer.class) Long digitalEmployeeId,
		@Schema(description = "用户ID") String userId,
		@Schema(description = "记忆范围（主体类型）") MemoryScope subjectType,
		@Schema(description = "记忆主体ID") String subjectId,
		@Schema(description = "事实键") String factKey,
		@Schema(description = "修订版本") Integer revision,
		@Schema(description = "记忆内容类型") AgentMemoryType memoryType,
		@Schema(description = "摘要") String summary,
		@Schema(description = "完整内容") String content,
		@Schema(description = "来源说明（JSON 文本）") String provenance,
		@Schema(description = "来源根 Run ID") @JsonSerialize(using = ToStringSerializer.class) Long sourceRunId,
		@Schema(description = "敏感级别") MemorySensitivity sensitivity,
		@Schema(description = "用户同意状态") MemoryConsentStatus consentStatus,
		@Schema(description = "生效时间") Instant validFrom,
		@Schema(description = "失效时间（TTL）") Instant validTo,
		@Schema(description = "来源会话ID") String sourceSessionId,
		@Schema(description = "来源消息ID") String sourceMessageId,
		@Schema(description = "重要度") Double importance,
		@Schema(description = "置信度") Double confidence,
		@Schema(description = "状态") AgentMemoryStatus status,
		@Schema(description = "使用次数") Integer useCount,
		@Schema(description = "最后使用时间") Instant lastUsedTime,
		@Schema(description = "过期时间") Instant expireTime,
		@Schema(description = "存储TTL过期时间（PR-7 新增）") Instant expiresAt,
		@Schema(description = "创建时间") Instant createTime,
		@Schema(description = "最后修改时间") Instant lastModifyTime
) {
}
