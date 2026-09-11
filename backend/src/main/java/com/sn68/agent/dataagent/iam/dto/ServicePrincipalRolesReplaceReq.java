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
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Replace service-principal roles (local standalone DTO).
 *
 * @author sn68
 */
@Data
@Schema(description = "全量替换 Service Principal 角色请求")
public class ServicePrincipalRolesReplaceReq {

	@Schema(description = "角色 ID 列表（空数组=清空=MODEL_ONLY）")
	private List<String> roleIds = new ArrayList<>();

}
