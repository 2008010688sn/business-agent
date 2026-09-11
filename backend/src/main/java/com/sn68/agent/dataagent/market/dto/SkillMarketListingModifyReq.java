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

import com.sn68.agent.dataagent.market.enums.MarketRiskLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

/**
 * 能力市场条目修改请求（仅 DRAFT/REJECTED 状态可修改）。
 */
@Builder
@Schema(description = "能力市场条目修改请求")
public record SkillMarketListingModifyReq(
		@Schema(description = "条目名称") @NotBlank(message = "条目名称不能为空") @Size(max = 128, message = "条目名称不能超过128字符") String listingName,
		@Schema(description = "条目说明") String description,
		@Schema(description = "分类") @Size(max = 128, message = "分类不能超过128字符") String category,
		@Schema(description = "输入输出 Schema 摘要（JSON 文本）") String ioSchemaSummary,
		@Schema(description = "权限范围说明") @Size(max = 512, message = "权限范围说明不能超过512字符") String permissionScope,
		@Schema(description = "数据范围说明") @Size(max = 512, message = "数据范围说明不能超过512字符") String dataScope,
		@Schema(description = "风险等级") MarketRiskLevel riskLevel,
		@Schema(description = "依赖资源（JSON 文本）") String dependentResources,
		@Schema(description = "使用配额默认值（次/日）") Integer defaultUsageQuota,
		@Schema(description = "兼容引擎版本") @Size(max = 64, message = "兼容引擎版本不能超过64字符") String compatibleEngineVersion
) {
}
