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
package com.sn68.agent.dataagent.employee.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 岗位模板推荐任务。创建员工时不落任务定义，须生产 PUBLISHED Release 后再建。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "岗位模板推荐任务")
public class EmployeeJobTemplateTaskHint {

	@Schema(description = "任务名称")
	private String taskName;

	@Schema(description = "任务描述（无人值守运行时作为自然语言任务）")
	private String taskDescription;

	@Schema(description = "任务类型")
	private String taskType;

	@Schema(description = "默认自治级别")
	private String defaultAutonomyLevel;

	@Schema(description = "是否高风险写任务")
	private Boolean highRiskWrite;

	@Schema(description = "触发器建议（不自动创建）")
	private String triggerHint;

}
