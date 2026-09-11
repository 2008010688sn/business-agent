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

import com.sn68.agent.dataagent.authorization.model.AuthorizationObligation;
import com.sn68.agent.dataagent.authorization.pdp.DecisionReasonCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * 授权决策模拟响应 DTO（AuthorizationDecision 契约的 REST 投影）。
 *
 * @author Felix (PR-3a PDP 内核)
 */
@Getter
@Builder
@Schema(description = "授权决策模拟响应")
public class AuthorizationDecisionResp {

	@Schema(description = "是否允许")
	private final boolean allowed;

	@Schema(description = "原因码（v1 冻结枚举）")
	private final DecisionReasonCode reasonCode;

	@Schema(description = "允许时需执行的义务列表")
	private final List<AuthorizationObligation> obligations;

	@Schema(description = "MASK_FIELDS 义务对应的敏感字段名列表")
	private final List<String> maskFields;

	@Schema(description = "参与求值的策略哈希（策略缺失时为 null）")
	private final String policyHash;

	@Schema(description = "决策时间")
	private final Instant evaluatedAt;
}
