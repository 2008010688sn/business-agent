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
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * 数字员工选择器摘要（权限中心 / 任务筛选，避免手填雪花 ID）。
 */
@Getter
@Builder
@Schema(description = "数字员工选择器摘要")
public class DigitalEmployeeOptionResp {

	@Schema(description = "员工ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long id;

	@Schema(description = "员工编码")
	private String employeeCode;

	@Schema(description = "员工名称")
	private String employeeName;

	@Schema(description = "状态")
	private String status;

	@Schema(description = "IAM Service Principal ID")
	private String iamPrincipalId;

	@Schema(description = "Principal 开通状态")
	private String principalStatus;

	public static DigitalEmployeeOptionResp from(DigitalEmployee employee) {
		if (employee == null) {
			return null;
		}
		return DigitalEmployeeOptionResp.builder()
			.id(employee.getId())
			.employeeCode(employee.getEmployeeCode())
			.employeeName(employee.getEmployeeName())
			.status(employee.getStatus())
			.iamPrincipalId(employee.getIamPrincipalId())
			.principalStatus(employee.getPrincipalStatus())
			.build();
	}

}
