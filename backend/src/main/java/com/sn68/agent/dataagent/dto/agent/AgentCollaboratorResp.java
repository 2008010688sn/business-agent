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
package com.sn68.agent.dataagent.dto.agent;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.dataagent.dto.routing.RouteRulesDTO;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent协作者数据传输对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Agent协作者数据传输对象")
public class AgentCollaboratorResp {

	@Schema(description = "主键ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long id;

	@Schema(description = "Agent ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long agentId;

	@Schema(description = "协作者Agent ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long collaboratorAgentId;

	@Schema(description = "协作角色名称")
	private String roleName;

	@Schema(description = "能力描述")
	private String capabilityDescription;

	@Schema(description = "委托模式：AUTO_READ_ONLY、PLAN_CONFIRM、INTERACTIVE")
	private String delegationMode;

	@Schema(description = "服务端按已发布能力计算的有效风险")
	private RouteRisk effectiveRisk;

	@Schema(description = "是否满足PLAN_CONFIRM的已发布能力约束")
	private Boolean planConfirmEligible;

	@Schema(description = "完整结构化路由规则")
	private RouteRulesDTO routingRules;

	@Schema(description = "优先级，数值越大越靠前")
	private Integer priority;

	@Schema(description = "是否启用")
	private Boolean enabled;

	@Schema(description = "协作者Agent信息")
	private DataAgent collaboratorDataAgent;

}
