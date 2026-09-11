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
import com.sn68.agent.dataagent.authorization.pdp.SubjectKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Data;

/**
 * 授权决策模拟请求 DTO。
 *
 * <p>策略来源二选一：policyJson（inline 策略 JSON，走 PolicyValidator 严格校验）
 * 或 templateCode（预设模板，可用 rules 覆盖默认规则）；两者都缺省或同时提供均拒绝。</p>
 *
 * @author Felix (PR-3a PDP 内核)
 */
@Data
@Schema(description = "授权决策模拟请求")
public class AuthorizationDecisionSimulateReq {

	@Schema(description = "inline 策略 JSON（与 templateCode 二选一）")
	private String policyJson;

	@Schema(description = "模板代码：MODEL_ONLY/CALLER_READ_ONLY/CALLER_INTERACTIVE/DIGITAL_WORKER（与 policyJson 二选一）")
	private String templateCode;

	@Schema(description = "模板规则覆盖（仅 templateCode 方式生效，空则用模板默认规则）")
	private List<AuthorizationRuleReq> rules;

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
