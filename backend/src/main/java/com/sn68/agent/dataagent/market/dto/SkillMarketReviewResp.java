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
import com.sn68.agent.dataagent.market.enums.MarketReviewConclusion;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/**
 * 能力市场审核记录展示对象。
 */
@Builder
@Schema(description = "能力市场审核记录展示对象")
public record SkillMarketReviewResp(
		@Schema(description = "审核记录ID") @JsonSerialize(using = ToStringSerializer.class) Long id,
		@Schema(description = "市场条目ID") @JsonSerialize(using = ToStringSerializer.class) Long listingId,
		@Schema(description = "被审核的条目版本ID") @JsonSerialize(using = ToStringSerializer.class) Long listingVersionId,
		@Schema(description = "审核人用户ID") String reviewerId,
		@Schema(description = "审核人名称") String reviewerName,
		@Schema(description = "审核结论") MarketReviewConclusion conclusion,
		@Schema(description = "审核意见") String opinion,
		@Schema(description = "审核时间") Instant createTime
) {
}
