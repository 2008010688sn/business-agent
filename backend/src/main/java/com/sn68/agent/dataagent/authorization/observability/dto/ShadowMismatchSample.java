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
package com.sn68.agent.dataagent.authorization.observability.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Data;

/**
 * MISMATCHED 差异样本明细（清单 PR-10 钦定四字段 + 租户/原因码定位字段）。
 *
 * @author Ray (PR-10 可观测性与灰度基建)
 */
@Data
@Schema(description = "影子差异样本明细（MISMATCHED 决策定位行）")
public class ShadowMismatchSample {

	@Schema(description = "授权决策ID（贯穿影子日志与 invocation 审计列的对账定位键）")
	private String decisionId;

	@Schema(description = "租户ID（事件表数字口径）")
	private Long tenantId;

	@Schema(description = "参与判定的策略哈希（决策重放口径之一）")
	private String policyHash;

	@Schema(description = "PDP 原因码（现网放行但 PDP 拒绝的拒绝原因）")
	private String reasonCode;

	@Schema(description = "比对状态（样本恒为 MISMATCHED）")
	private String comparisonStatus;

	@Schema(description = "比对时间戳（事件 occurred_at）")
	private Instant comparisonTimestamp;

}
