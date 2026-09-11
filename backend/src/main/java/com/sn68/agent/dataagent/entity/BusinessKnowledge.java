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
package com.sn68.agent.dataagent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sn68.agent.dataagent.enums.EmbeddingStatus;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.io.Serial;

/**
 * 业务知识实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("business_knowledge")
@Schema(description = "DataAgent业务知识")
public class BusinessKnowledge extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;
	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "业务术语")
	private String businessTerm;

	@Schema(description = "描述")
	private String description;

	@Schema(description = "同义词，多个用逗号分隔")
	private String synonyms;

	@Schema(description = "是否召回")
	private Boolean isRecall;

	@Schema(description = "Skill ID")
	private Long skillId;

	@Schema(description = "Replaced by newer business knowledge resource ID")
	private Long supersededById;

	@Schema(description = "向量化状态")
	private EmbeddingStatus embeddingStatus;

	@Schema(description = "错误信息")
	private String errorMsg;

}
