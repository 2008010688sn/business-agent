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

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

/**
 * 授权决策审计回显（读取 agent_runtime_event 审计列，按当前租户隔离）。
 */
@Getter
@Builder
@Schema(description = "授权决策审计")
public class AuthorizationDecisionAuditResp {

	@Schema(description = "决策ID")
	private String decisionId;

	@Schema(description = "所属运行ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long runId;

	@Schema(description = "租户ID")
	private String tenantId;

	@Schema(description = "策略哈希")
	private String policyHash;

	@Schema(description = "决策主体类别")
	private String subjectKind;

	@Schema(description = "PDP 原因码")
	private String reasonCode;

	@Schema(description = "事件发生时间")
	private Instant occurredAt;

	@Schema(description = "决策明细 JSON（影子比对等）")
	private String detailedDecisionLog;

}
