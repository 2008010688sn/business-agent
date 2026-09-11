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
package com.sn68.agent.dataagent.authorization.gate.dto;

import com.sn68.agent.dataagent.authorization.gate.EnforceGateDecision;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

/**
 * PR-9 灰度 ENFORCE 门禁评估结果（单租户）：差异率达标校验的完整证据链。
 *
 * @author Terry (PR-9 灰度 ENFORCE 门禁)
 */
@Getter
@Builder
@Schema(description = "ENFORCE 门禁单租户评估结果（放行决策证据链）")
public class EnforceGateEvaluation {

	@Schema(description = "租户ID（与 enforceTenantIds 同口径）")
	private String tenantId;

	@Schema(description = "是否放行（true=可进入 ENFORCE 白名单）")
	private boolean allowed;

	@Schema(description = "评估结论码（ADMITTED 或 REJECTED_*）")
	private EnforceGateDecision decision;

	@Schema(description = "结论中文说明（拒绝时为明确的失败原因）")
	private String reason;

	@Schema(description = "评估依据：报告窗口起点（报告缺失时为 null）")
	private Instant reportWindowFrom;

	@Schema(description = "评估依据：报告窗口终点（报告缺失时为 null）")
	private Instant reportWindowTo;

	@Schema(description = "租户可比样本数（matched+mismatched；无样本时为 0）")
	private long comparable;

	@Schema(description = "判定一致数（MATCHED；无样本时为 0）")
	private long matched;

	@Schema(description = "判定不一致数（MISMATCHED；无样本时为 0）")
	private long mismatched;

	@Schema(description = "现网结论缺失数（ORIGINAL_ONLY，不进差异率分母）")
	private long originalOnly;

	@Schema(description = "影子差异率（mismatched/comparable；报告缺失时为 0）")
	private double mismatchRate;

	@Schema(description = "报告口径的 ENFORCE 门禁阈值（0~1）")
	private double enforceGateThreshold;

	@Schema(description = "报告口径的差异率达标标记（enforceGatePassed）")
	private boolean reportGatePassed;

}
