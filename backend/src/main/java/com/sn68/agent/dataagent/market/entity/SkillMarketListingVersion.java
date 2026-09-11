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
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 能力市场条目版本（不可变）：提交审核时对引用的 Skill 版本做快照，
 * 记录来源版本与内容哈希，供安装追溯与撤销级联。落库后不允许修改。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("skill_market_listing_version")
@Schema(description = "能力市场条目版本（不可变）")
public class SkillMarketListingVersion extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Schema(description = "市场条目ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long listingId;

	@Schema(description = "条目内版本号，从 1 递增")
	private Integer versionNo;

	@Schema(description = "引用的 Skill 版本ID（data_agent_skill_version.id，Long 引用）")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long skillVersionId;

	@Schema(description = "内容哈希（Skill 版本内容摘要，安装侧校验一致性）")
	private String contentHash;

	@Schema(description = "变更说明")
	private String changeNote;

}
