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
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.io.Serial;

/**
 * DataAgent技能绑定实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("data_agent_skill_binding")
@Schema(description = "DataAgent智能体技能绑定")
public class DataAgentSkillBinding extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Schema(description = "智能体ID")
	private Long agentId;

	@Schema(description = "租户ID")
	private String tenantId;

	@Schema(description = "Skill ID")
	private Long skillId;

	/**
	 * 必填：绑定固定锁到某个已发布版本，运行期不做"跟随最新发布版"的回退。
	 *
	 * <p>库侧由 {@code NOT NULL} + 复合外键 + 触发器共同保证，写入路径也强制校验；仍为空的只会是历史脏数据，
	 * 路由时按生命周期漂移跳过该绑定。
	 */
	@Schema(description = "固定使用的Skill版本ID（必填，指向该Skill的已发布版本）")
	private Long pinnedSkillVersionId;

	@Schema(description = "优先级（越大越优先）")
	private Integer priority;

	@Schema(description = "是否启用")
	private Boolean enabled;

}
