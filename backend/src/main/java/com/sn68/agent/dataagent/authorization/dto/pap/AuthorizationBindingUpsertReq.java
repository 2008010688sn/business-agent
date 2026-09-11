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

import com.sn68.agent.dataagent.authorization.model.AuthorizationEnvironment;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 策略绑定 upsert 请求（PAP，PR-3b）。
 *
 * <p>expectedBindRevision 语义：首次绑定（当前无绑定行）时忽略；更新已有绑定时必须携带调用方读到的
 * bind_revision，并发冲突由服务端 CAS 检测并拒绝。</p>
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
@Data
@Schema(description = "Agent 授权策略绑定 upsert 请求")
public class AuthorizationBindingUpsertReq {

	@Schema(description = "绑定主体类型：DATA_AGENT/DIGITAL_EMPLOYEE", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotNull(message = "绑定主体类型不能为空")
	private AuthorizationOwnerType ownerType;

	@Schema(description = "绑定主体ID", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotNull(message = "绑定主体ID不能为空")
	private Long ownerId;

	@Schema(description = "生效环境：SANDBOX/PRODUCTION", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotNull(message = "生效环境不能为空")
	private AuthorizationEnvironment environment;

	@Schema(description = "绑定的策略版本ID", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotNull(message = "策略版本ID不能为空")
	private Long policyVersionId;

	@Schema(description = "期望的 bind_revision（更新已有绑定时必填，并发冲突返回 400 与当前值）")
	private Long expectedBindRevision;

}
