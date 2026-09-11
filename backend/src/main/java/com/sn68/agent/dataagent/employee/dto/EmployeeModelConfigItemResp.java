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

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 数字员工可用模型配置条目。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "数字员工可用模型配置条目")
public class EmployeeModelConfigItemResp {

	@Schema(description = "主键ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long id;

	@Schema(description = "数字员工ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long employeeId;

	@Schema(description = "模型配置ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long modelConfigId;

	@Schema(description = "是否默认")
	private Boolean isDefault;

	@Schema(description = "是否允许用户选择")
	private Boolean userSelectable;

	@Schema(description = "是否启用")
	private Boolean enabled;

	@Schema(description = "模型配置")
	private ModelConfigDTO modelConfig;

}
