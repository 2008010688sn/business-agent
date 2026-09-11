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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Change service-principal status (local standalone DTO).
 *
 * @author sn68
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "变更 Service Principal 状态请求")
public class ServicePrincipalStatusReq {

	@Schema(description = "目标状态（ENABLED/DISABLED）", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotBlank(message = "状态不能为空")
	@Pattern(regexp = "ENABLED|DISABLED", message = "状态仅支持 ENABLED/DISABLED")
	private String status;

}
