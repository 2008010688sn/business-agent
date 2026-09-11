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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Service-principal auth snapshot (local standalone DTO).
 *
 * @author sn68
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Service Principal 授权快照")
public class ServicePrincipalAuthSnapshotResp {

	@Schema(description = "principal ID（sp_ 前缀）")
	private String principalId;

	@Schema(description = "租户 ID")
	private String tenantId;

	@Builder.Default
	@Schema(description = "功能权限码列表")
	private List<String> funcPermissions = new ArrayList<>();

	@Schema(description = "授权版本号")
	private Long authRevision;

}
