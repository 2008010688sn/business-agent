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

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import com.sn68.agent.dataagent.repository.typehandler.JsonbMapTypeHandler;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Agent协作者实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "agent_collaborator", autoResultMap = true)
@Schema(description = "DataAgent编排智能体协作配置")
public class AgentCollaborator extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;
	@Schema(description = "所属租户ID")
	private String tenantId;

	@Override
	@JsonSerialize(using = ToStringSerializer.class)
	public Long getId() {
		return super.getId();
	}

	@Schema(description = "编排智能体ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long agentId;

	@Schema(description = "协作智能体ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long collaboratorAgentId;

	@Schema(description = "协作角色名称")
	private String roleName;

	@Schema(description = "协作能力描述")
	private String capabilityDescription;

	@Schema(description = "委托模式：AUTO_READ_ONLY、PLAN_CONFIRM、INTERACTIVE")
	private String delegationMode;

	@TableField(typeHandler = JsonbMapTypeHandler.class)
	@Schema(description = "结构化路由规则：exact、phrases、aliases、positiveExamples、negativeExamples、hardExcludes")
	private Map<String, Object> routingRules;

	@Schema(description = "优先级，数值越大越靠前")
	private Integer priority;

	@Schema(description = "是否启用该协作配置")
	private Boolean enabled;

}
