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
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 数字员工能力绑定请求（幂等：同 skillVersionId 重复绑定直接成功）。
 */
@Data
@Schema(description = "数字员工能力绑定请求")
public class EmployeeCapabilityBindReq {

	@Schema(description = "Skill 版本ID（已发布 SkillVersion）", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotNull(message = "skillVersionId 不能为空")
	private Long skillVersionId;

	@Schema(description = "是否启用（默认 true）")
	private Boolean enabled;

}
