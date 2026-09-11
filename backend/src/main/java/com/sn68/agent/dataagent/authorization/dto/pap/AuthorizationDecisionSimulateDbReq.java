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

import com.sn68.agent.dataagent.authorization.model.AuthorizationAction;
import com.sn68.agent.dataagent.authorization.model.AuthorizationEnvironment;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.dataagent.authorization.pdp.SubjectKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 授权决策 DB 模拟请求（PAP，PR-3b）。
 *
 * <p>与 PR-3a simulate 的差异：策略来源不是 inline JSON/模板，而是从 DB 按绑定关系
 * （owner + environment）读取已发布策略版本后复用同一冻结求值器。</p>
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
@Data
@Schema(description = "授权决策 DB 模拟请求")
public class AuthorizationDecisionSimulateDbReq {

	@Schema(description = "绑定主体类型：DATA_AGENT/DIGITAL_EMPLOYEE", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotNull(message = "绑定主体类型不能为空")
	private AuthorizationOwnerType ownerType;

	@Schema(description = "绑定主体ID", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotNull(message = "绑定主体ID不能为空")
	private Long ownerId;

	@Schema(description = "生效环境：SANDBOX/PRODUCTION", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotNull(message = "生效环境不能为空")
	private AuthorizationEnvironment environment;

	@Schema(description = "请求主体类别：CALLER/DIGITAL_EMPLOYEE", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotNull(message = "subjectKind不能为空")
	private SubjectKind subjectKind;

	@Schema(description = "能力码", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotBlank(message = "capabilityCode不能为空")
	private String capabilityCode;

	@Schema(description = "请求动作", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotNull(message = "action不能为空")
	private AuthorizationAction action;

	@Schema(description = "请求的能力版本（可空）")
	private String capabilityVersion;

	@Schema(description = "IAM 是否可用，默认 true")
	private Boolean iamAvailable;

}
