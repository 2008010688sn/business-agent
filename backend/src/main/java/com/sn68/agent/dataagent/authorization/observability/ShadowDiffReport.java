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
package com.sn68.agent.dataagent.authorization.observability;

import com.sn68.agent.dataagent.authorization.observability.dto.ShadowMismatchSample;
import com.sn68.agent.dataagent.authorization.observability.dto.TaskSkipAggregate;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * 授权影子差异比对报告（PR-10 交付物 4：聚合 AUTHORIZATION_DECISION 事件的
 * comparison_status，按租户输出 MATCHED/MISMATCHED/ORIGINAL_ONLY 与差异率）。
 *
 * <p><b>差异率口径（ENFORCE 门禁数据链路）</b>：mismatchRate = mismatched /
 * (matched + mismatched)。ORIGINAL_ONLY（现网结论缺失，只记录影子侧）不进分母，
 * 单列观测——缺现网输入不构成"判定不一致"证据；comparable = 0 时 rate 记 0 且
 * gatePassed=true（窗口无差异证据）。门禁阈值 0.1%（见
 * {@link ShadowDiffReportGenerator#ENFORCE_GATE_THRESHOLD}），PR-9 逐租户
 * ENFORCE 前以本报告 gatePassed + 差异样本定位为放行依据。</p>
 *
 * @author Ray (PR-10 可观测性与灰度基建)
 */
@Getter
@Builder
@Schema(description = "授权影子差异比对报告（ENFORCE 灰度门禁数据来源）")
public class ShadowDiffReport {

	@Schema(description = "聚合窗口起点（含）")
	private Instant windowFrom;

	@Schema(description = "聚合窗口终点（不含）")
	private Instant windowTo;

	@Schema(description = "报告生成时刻")
	private Instant generatedAt;

	@Schema(description = "ENFORCE 门禁差异率阈值（0~1，默认 0.001 即 0.1%）")
	private double enforceGateThreshold;

	@Schema(description = "窗口内授权决策影子事件总数")
	private long totalEvents;

	@Schema(description = "租户级差异汇总（按差异率降序）")
	private List<TenantSummary> tenants;

	@Schema(description = "MISMATCHED 差异样本明细（限量，按时间倒序）")
	private List<ShadowMismatchSample> mismatchSamples;

	@Schema(description = "任务侧观测段（SKIPPED 槽位冲突 + 受理延迟 P95；关闭时为 null）")
	private TaskObservation taskObservation;

	/**
	 * 租户级差异汇总。
	 */
	@Getter
	@Builder
	@Schema(description = "租户级影子比对汇总")
	public static class TenantSummary {

		@Schema(description = "租户ID（事件表数字口径，0 表示未解析租户）")
		private String tenantId;

		@Schema(description = "窗口内事件总数")
		private long total;

		@Schema(description = "可比对事件数（matched + mismatched）")
		private long comparable;

		@Schema(description = "判定一致数（MATCHED）")
		private long matched;

		@Schema(description = "判定不一致数（MISMATCHED）")
		private long mismatched;

		@Schema(description = "现网结论缺失数（ORIGINAL_ONLY，不进差异率分母）")
		private long originalOnly;

		@Schema(description = "差异率（mismatched / comparable；comparable=0 时记 0）")
		private double mismatchRate;

		@Schema(description = "是否通过 ENFORCE 门禁（差异率 < 阈值）")
		private boolean enforceGatePassed;

	}

	/**
	 * 任务侧观测段（SKIPPED / 槽位冲突 / 受理延迟）。
	 */
	@Getter
	@Builder
	@Schema(description = "任务侧观测（SKIPPED 台账与受理延迟）")
	public static class TaskObservation {

		@Schema(description = "窗口内 SKIPPED 台账行总数")
		private long skippedTotal;

		@Schema(description = "槽位冲突跳过数（原因码 CONCURRENT_SLOT_LOCKED）")
		private long slotConflictTotal;

		@Schema(description = "其他原因跳过数")
		private long otherSkipTotal;

		@Schema(description = "任务受理延迟 P95（秒；无样本为 0）")
		private double workItemCreateP95Seconds;

		@Schema(description = "受理延迟聚合样本数")
		private long workItemSampleTotal;

		@Schema(description = "按租户 × 原因的 SKIPPED 明细")
		private List<TaskSkipAggregate> skipAggregates;

	}

}
