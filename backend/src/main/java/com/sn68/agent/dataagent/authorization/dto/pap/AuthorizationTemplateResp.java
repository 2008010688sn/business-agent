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
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 授权模板列表行（PAP，PR-3b）：模板编码 + 默认策略 JSON（可直接作为 create 的 policyJson 输入）。
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Agent 授权模板")
public class AuthorizationTemplateResp {

	@Schema(description = "模板编码：MODEL_ONLY/CALLER_READ_ONLY/CALLER_INTERACTIVE/DIGITAL_WORKER")
	private String code;

	@Schema(description = "模板默认策略 JSON（与 PolicyValidator 严格契约一致）")
	private String defaultPolicyJson;

}
