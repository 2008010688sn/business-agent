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

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.dataagent.market.enums.MarketReviewConclusion;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 能力市场平台审核记录。listing_id 为展示冗余（按条目查审核历史免联表），
 * 审核对象以 listing_version_id 为准。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("skill_market_review")
@Schema(description = "能力市场审核记录")
public class SkillMarketReview extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Schema(description = "市场条目ID（冗余，便于按条目查审核历史）")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long listingId;

	@Schema(description = "被审核的条目版本ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long listingVersionId;

	@Schema(description = "审核人用户ID")
	private String reviewerId;

	@Schema(description = "审核人名称")
	private String reviewerName;

	@Schema(description = "审核结论")
	private MarketReviewConclusion conclusion;

	@Schema(description = "审核意见")
	private String opinion;

}
