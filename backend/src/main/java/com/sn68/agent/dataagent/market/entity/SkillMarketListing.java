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
package com.sn68.agent.dataagent.market.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.dataagent.market.enums.MarketListingStatus;
import com.sn68.agent.dataagent.market.enums.MarketRiskLevel;
import com.sn68.agent.dataagent.repository.typehandler.JsonbStringTypeHandler;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 能力市场条目（平台级，方案第十三章）。首期平台审核 + 使用配额，不做计费。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "skill_market_listing", autoResultMap = true)
@Schema(description = "能力市场条目")
public class SkillMarketListing extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Schema(description = "条目名称")
	private String listingName;

	@Schema(description = "条目说明")
	private String description;

	@Schema(description = "分类")
	private String category;

	@Schema(description = "发布者用户ID")
	private String publisherId;

	@Schema(description = "发布者名称")
	private String publisherName;

	@Schema(description = "发布者租户ID")
	private String publisherTenantId;

	@Schema(description = "当前版本引用（skill_market_listing_version.id，审核通过后指向最新通过版本）")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long currentVersionId;

	@Schema(description = "输入输出 Schema 摘要（JSONB）")
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String ioSchemaSummary;

	@Schema(description = "权限范围说明（所需权限码/能力边界）")
	private String permissionScope;

	@Schema(description = "数据范围说明（可触达的数据边界）")
	private String dataScope;

	@Schema(description = "风险等级")
	private MarketRiskLevel riskLevel;

	@Schema(description = "依赖资源（数据源/凭据/工具等，JSONB）")
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String dependentResources;

	@Schema(description = "使用配额默认值（次/日），安装时作为租户配额初始值")
	private Integer defaultUsageQuota;

	@Schema(description = "兼容引擎版本")
	private String compatibleEngineVersion;

	@Schema(description = "撤销状态")
	private Boolean revoked;

	@Schema(description = "审核状态")
	private MarketListingStatus reviewStatus;

}
