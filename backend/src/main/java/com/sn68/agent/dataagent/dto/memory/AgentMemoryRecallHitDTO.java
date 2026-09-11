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
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/**
 * 长期记忆召回命中项。
 */
@Builder
@Schema(description = "Agent记忆召回命中项")
public record AgentMemoryRecallHitDTO(
		@Schema(description = "主键ID") @JsonSerialize(using = ToStringSerializer.class) Long id,
		@Schema(description = "记忆类型") AgentMemoryType memoryType,
		@Schema(description = "摘要") String summary,
		@Schema(description = "内容") String content,
		@Schema(description = "相似度得分") Double similarity,
		@Schema(description = "重要性") Double importance,
		@Schema(description = "置信度") Double confidence,
		@Schema(description = "记忆来源时间") Instant sourceTime,
		@Schema(description = "是否已注入提示词") boolean injected
) {
}
