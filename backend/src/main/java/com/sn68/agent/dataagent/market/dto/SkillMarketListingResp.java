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
import com.sn68.agent.dataagent.market.enums.MarketListingStatus;
import com.sn68.agent.dataagent.market.enums.MarketRiskLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/**
 * 能力市场条目展示对象（方案第十三章展示清单：分类/版本/发布者/输入输出 Schema/
 * 权限范围/数据范围/风险等级/依赖资源/使用配额/兼容版本/撤销状态）。
 */
@Builder
@Schema(description = "能力市场条目展示对象")
public record SkillMarketListingResp(
		@Schema(description = "条目ID") @JsonSerialize(using = ToStringSerializer.class) Long id,
		@Schema(description = "条目名称") String listingName,
		@Schema(description = "条目说明") String description,
		@Schema(description = "分类") String category,
		@Schema(description = "发布者用户ID") String publisherId,
		@Schema(description = "发布者名称") String publisherName,
		@Schema(description = "发布者租户ID") String publisherTenantId,
		@Schema(description = "当前版本引用") @JsonSerialize(using = ToStringSerializer.class) Long currentVersionId,
		@Schema(description = "当前版本引用的 Skill 版本ID") @JsonSerialize(using = ToStringSerializer.class) Long currentSkillVersionId,
		@Schema(description = "当前版本号") Integer currentVersionNo,
		@Schema(description = "输入输出 Schema 摘要（JSON 文本）") String ioSchemaSummary,
		@Schema(description = "权限范围说明") String permissionScope,
		@Schema(description = "数据范围说明") String dataScope,
		@Schema(description = "风险等级") MarketRiskLevel riskLevel,
		@Schema(description = "依赖资源（JSON 文本）") String dependentResources,
		@Schema(description = "使用配额默认值（次/日）") Integer defaultUsageQuota,
		@Schema(description = "兼容引擎版本") String compatibleEngineVersion,
		@Schema(description = "撤销状态") Boolean revoked,
		@Schema(description = "审核状态") MarketListingStatus reviewStatus,
		@Schema(description = "创建时间") Instant createTime,
		@Schema(description = "最后修改时间") Instant lastModifyTime
) {
}
