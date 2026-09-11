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
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 数字员工岗位模板（创建向导预填）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "数字员工岗位模板")
public class EmployeeJobTemplateResp {

	@Schema(description = "模板编码")
	private String templateCode;

	@Schema(description = "模板展示名")
	private String displayName;

	@Schema(description = "岗位")
	private String jobTitle;

	@Schema(description = "员工描述")
	private String description;

	@Schema(description = "系统提示词")
	private String systemInstruction;

	@Schema(description = "开场白")
	private String greeting;

	@Schema(description = "自治级别")
	private String autonomyLevel;

	@Schema(description = "推荐技能（只读提示，不预填版本 ID）")
	private List<EmployeeJobTemplateSkillHint> recommendedSkills;

	@Schema(description = "推荐任务（发布生产后再创建）")
	private EmployeeJobTemplateTaskHint recommendedTask;

}
