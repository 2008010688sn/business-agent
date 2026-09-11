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
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 策略创建请求（PAP，PR-3b）。
 *
 * <p>策略内容来源二选一：policyJson 优先；两者都为空时按 templateCode 生成模板默认策略。</p>
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
@Data
@Schema(description = "Agent 授权策略创建请求")
public class AuthorizationPolicyCreateReq {

	@Schema(description = "策略编码，租户内唯一", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotBlank(message = "策略编码不能为空")
	private String code;

	@Schema(description = "策略名称", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotBlank(message = "策略名称不能为空")
	private String name;

	@Schema(description = "来源模板编码：MODEL_ONLY/CALLER_READ_ONLY/CALLER_INTERACTIVE/DIGITAL_WORKER")
	private String templateCode;

	@Schema(description = "策略 JSON 原文（与 templateCode 至少提供一个；提供时优先使用并经严格校验）")
	private String policyJson;

}
