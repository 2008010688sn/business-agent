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
package com.sn68.agent.dataagent.runtime.hook.entity;

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
 * Agent运行时钩子实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_runtime_hook")
@Schema(description = "Agent运行时钩子实体")
public class AgentRuntimeHook extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;
	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "编码")
	private String hookCode;

	@Schema(description = "名称")
	private String hookName;

	@Schema(description = "类型")
	private String eventType;

	@Schema(description = "Agent ID")
	private Long agentId;

	@Schema(description = "技能字段")
	private String skillCode;

	@Schema(description = "能力编码")
	private Long skillVersionId;

	@Schema(description = "资源来源字段")
	private String resourceKey;

	@Schema(description = "类型")
	private String actionType;

	@Schema(description = "配置")
	private String actionConfig;

	@Schema(description = "启用状态")
	private Boolean asyncEnabled;

	@Schema(description = "错误字段")
	private Boolean continueOnError;

	@Schema(description = "状态")
	private String status;

	@Schema(description = "displayOrder字段")
	private Integer displayOrder;

	@Schema(description = "配置")
	private String extConfig;

}
