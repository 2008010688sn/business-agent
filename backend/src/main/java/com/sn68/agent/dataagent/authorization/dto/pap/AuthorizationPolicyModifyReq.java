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

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 策略修改请求（PAP，PR-3b）。
 *
 * <p>仅 DRAFT 状态策略可修改；名称与草稿 JSON 均为可选字段，缺省保持原值。
 * 已发布策略需等待/创建新草稿版本流转（modify 幂等拒绝非 DRAFT 主档）。</p>
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
@Data
@Schema(description = "Agent 授权策略修改请求")
public class AuthorizationPolicyModifyReq {

	@Schema(description = "策略名称；为空保持原值")
	private String name;

	@Schema(description = "草稿策略 JSON 原文；为空保持原值，提供时经严格校验后覆盖草稿版本")
	private String policyJson;

}
