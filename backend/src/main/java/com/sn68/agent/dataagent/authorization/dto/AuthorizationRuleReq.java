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
package com.sn68.agent.dataagent.authorization.dto;

import com.sn68.agent.dataagent.authorization.model.AuthorizationAction;
import com.sn68.agent.dataagent.authorization.model.AuthorizationEffect;
import com.sn68.agent.dataagent.authorization.model.AuthorizationObligation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Data;

/**
 * 授权规则覆盖请求 DTO（simulate 接口用于覆盖模板默认 rules）。
 *
 * @author Felix (PR-3a PDP 内核)
 */
@Data
@Schema(description = "授权规则覆盖请求")
public class AuthorizationRuleReq {

	@Schema(description = "规则名称", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotBlank(message = "规则名称不能为空")
	private String name;

	@Schema(description = "效果：ALLOW/DENY", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotNull(message = "规则效果不能为空")
	private AuthorizationEffect effect;

	@Schema(description = "能力码列表，支持'*'通配，空列表通配")
	private List<String> capabilityCodes;

	@Schema(description = "动作列表，空列表通配")
	private List<AuthorizationAction> actions;

	@Schema(description = "义务列表：APPROVAL/MASK_FIELDS/FILTER_FIELDS")
	private List<AuthorizationObligation> obligations;

	@Schema(description = "MASK_FIELDS 义务对应的敏感字段名列表")
	private List<String> maskFields;
}
