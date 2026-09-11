/*
 * Copyright (c) sn68. All Rights Reserved.
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
package com.sn68.agent.dataagent.iam.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bound service-principal role (local standalone DTO).
 *
 * @author sn68
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Service Principal 已绑定角色")
public class ServicePrincipalRoleResp {

	@Schema(description = "角色 ID")
	private String id;

	@Schema(description = "租户 ID")
	private String tenantId;

	@Schema(description = "角色名称")
	private String name;

	@Schema(description = "角色编码")
	private String code;

	@Schema(description = "描述")
	private String description;

	@Schema(description = "状态（enabled/disabled）")
	private String status;

	@Schema(description = "是否可分配给 Service Principal")
	private Boolean serviceAssignable;

	@Schema(description = "超级角色")
	private Boolean superRole;

	@Schema(description = "内置只读角色")
	private Boolean readonly;

}
