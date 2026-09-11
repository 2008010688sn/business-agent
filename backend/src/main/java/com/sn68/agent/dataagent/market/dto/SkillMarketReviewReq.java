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

import com.sn68.agent.dataagent.market.enums.MarketReviewConclusion;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

/**
 * 能力市场平台审核请求（平台管理员）。
 */
@Builder
@Schema(description = "能力市场平台审核请求")
public record SkillMarketReviewReq(
		@Schema(description = "审核结论") @NotNull(message = "审核结论不能为空") MarketReviewConclusion conclusion,
		@Schema(description = "审核意见") String opinion
) {
}
