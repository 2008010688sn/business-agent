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
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * DataAgent技能工具Ref实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("data_agent_skill_tool_ref")
@Schema(description = "DataAgent技能工具Ref实体")
public class DataAgentSkillToolRef extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Schema(description = "租户ID")
	private String tenantId;

	@Schema(description = "Skill版本ID")
	private Long skillVersionId;

	@Schema(description = "工具资源版本ID（固定引用）")
	private Long resourceVersionId;

	@Schema(description = "工具资源标识")
	private String resourceKey;

	@Schema(description = "用途说明")
	private String usage;

	@Schema(description = "状态")
	private String status;

	@Schema(description = "展示顺序")
	private Integer displayOrder;

	@Schema(description = "配置")
	private String extConfig;

}
