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
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * PR-9 灰度 ENFORCE 门禁 checklist 汇总（上线操作前后各查一次）：白名单逐租户门禁评估
 * + 已放行登记簿 + ENFORCE 切换保护（静默回退 / 未验证租户检测）。
 *
 * @author Terry (PR-9 灰度 ENFORCE 门禁)
 */
@Getter
@Builder
@Schema(description = "ENFORCE 门禁灰度 checklist 汇总")
public class EnforceGateOverview {

	@Schema(description = "门禁是否启用（enforce-gate.enabled）")
	private boolean gateEnabled;

	@Schema(description = "可比样本下限（enforce-gate.minComparable）")
	private long minComparable;

	@Schema(description = "最新影子差异报告是否就绪（false 对应 latest 404）")
	private boolean reportPresent;

	@Schema(description = "最新报告生成时刻（报告缺失时为 null）")
	private Instant reportGeneratedAt;

	@Schema(description = "当前 ENFORCE 租户白名单快照")
	private List<String> enforceTenantIds;

	@Schema(description = "本进程内已通过门禁放行登记的租户（进程重启后清零，须重新登记）")
	private List<String> admittedTenantIds;

	@Schema(description = "白名单逐租户门禁评估明细")
	private List<EnforceGateEvaluation> tenantEvaluations;

	@Schema(description = "已 ENFORCE 但未在本进程完成门禁登记的租户（重启/新进白名单后须逐租户 admit）")
	private List<String> unverifiedEnforcedTenantIds;

	@Schema(description = "已放行登记但当前已不受 ENFORCE 保护的租户（静默回退，禁止发生）")
	private List<String> silentRollbackTenantIds;

	@Schema(description = "checklist 是否通过（无静默回退且无未验证 ENFORCE 租户）")
	private boolean rolloutIntegrityPassed;

	@Schema(description = "checklist 不通过时的整改项（中文，逐条列明）")
	private String integrityActionRequired;

	@Schema(description = "评估覆盖的结论分布提示（各结论码×数量，供大盘/checklist 快读）")
	private List<DecisionCount> decisionCounts;

	/**
	 * 结论计数（checklist 快读）。
	 */
	@Getter
	@Builder
	@Schema(description = "门禁结论计数")
	public static class DecisionCount {

		@Schema(description = "结论码")
		private EnforceGateDecision decision;

		@Schema(description = "租户数量")
		private long count;

	}

}
