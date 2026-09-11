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
package com.sn68.agent.dataagent.authorization.dto.pap;

import com.sn68.agent.dataagent.authorization.model.AuthorizationGrantPermission;
import com.sn68.agent.dataagent.authorization.model.AuthorizationGrantSubjectType;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 授权记录创建请求（PAP，PR-3b）。
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
@Data
@Schema(description = "Agent 授权记录创建请求")
public class AuthorizationGrantCreateReq {

	@Schema(description = "授权对象类型：DATA_AGENT/DIGITAL_EMPLOYEE", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotNull(message = "授权对象类型不能为空")
	private AuthorizationOwnerType ownerType;

	@Schema(description = "授权对象ID", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotNull(message = "授权对象ID不能为空")
	private Long ownerId;

	@Schema(description = "授权主体类型：USER/TEAM/PERMISSION/TENANT", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotNull(message = "授权主体类型不能为空")
	private AuthorizationGrantSubjectType subjectType;

	@Schema(description = "授权主体ID", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotNull(message = "授权主体ID不能为空")
	private String subjectId;

	@Schema(description = "授权主体名称（冗余展示用，可空）")
	private String subjectName;

	@Schema(description = "授权权限：DISCOVER-目录可见；USE-可使用", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotNull(message = "授权权限不能为空")
	private AuthorizationGrantPermission permission;

	@Schema(description = "有效天数；空或非正数表示长期有效")
	private Integer expireDays;

}
