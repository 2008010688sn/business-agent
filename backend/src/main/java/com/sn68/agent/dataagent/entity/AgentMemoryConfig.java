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
 * Agent记忆配置实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("data_agent_memory_config")
@Schema(description = "DataAgent 长期记忆配置")
public class AgentMemoryConfig extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;
	@Schema(description = "所属租户ID")
	private String tenantId;

	@Override
	@JsonSerialize(using = ToStringSerializer.class)
	public Long getId() {
		return super.getId();
	}

	@Schema(description = "智能体ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long agentId;

	@Schema(description = "用户ID")
	private String userId;

	@Schema(description = "是否启用记忆召回")
	private Boolean recallEnabled;

	@Schema(description = "是否启用自动写入")
	private Boolean writeEnabled;

	@Schema(description = "允许召回的记忆类型，逗号分隔")
	private String recallTypes;

	@Schema(description = "允许自动写入的记忆类型，逗号分隔")
	private String writeTypes;

	@Schema(description = "召回数量 TopK")
	private Integer topK;

	@Schema(description = "相似度阈值")
	private Double similarityThreshold;

	@Schema(description = "注入预算 token")
	private Integer injectionTokenBudget;

	@Schema(description = "最小重要度")
	private Double minImportance;

	@Schema(description = "是否仅召回已确认记忆")
	private Boolean confirmedOnly;

}
