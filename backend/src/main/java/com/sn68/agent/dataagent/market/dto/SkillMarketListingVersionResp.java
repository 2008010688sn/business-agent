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
package com.sn68.agent.dataagent.market.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/**
 * 能力市场条目版本展示对象。
 */
@Builder
@Schema(description = "能力市场条目版本展示对象")
public record SkillMarketListingVersionResp(
		@Schema(description = "版本ID") @JsonSerialize(using = ToStringSerializer.class) Long id,
		@Schema(description = "市场条目ID") @JsonSerialize(using = ToStringSerializer.class) Long listingId,
		@Schema(description = "条目内版本号") Integer versionNo,
		@Schema(description = "引用的 Skill 版本ID") @JsonSerialize(using = ToStringSerializer.class) Long skillVersionId,
		@Schema(description = "内容哈希") String contentHash,
		@Schema(description = "变更说明") String changeNote,
		@Schema(description = "创建时间") Instant createTime,
		@Schema(description = "创建人") String createName
) {
}
