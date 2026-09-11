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

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

/**
 * 能力市场条目详情：条目 + 版本历史 + 审核历史。
 */
@Builder
@Schema(description = "能力市场条目详情")
public record SkillMarketListingDetailResp(
		@Schema(description = "条目信息") SkillMarketListingResp listing,
		@Schema(description = "版本历史，按版本号倒序") List<SkillMarketListingVersionResp> versions,
		@Schema(description = "审核历史，按时间倒序") List<SkillMarketReviewResp> reviews
) {
}
