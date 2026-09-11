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
package com.sn68.agent.dataagent.authorization.gate;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * PR-9 灰度 ENFORCE 门禁评估结论（v1.2 清单 PR-9：影子差异归零后才可逐租户 ENFORCE）。
 *
 * <p>除 {@link #ADMITTED} 外全部为 fail-closed 拒绝：门禁宁可拒绝放行也不误放，
 * 门禁未启用、报告缺失、租户样本缺失、样本量不足、差异率超标一律不允许进入 ENFORCE。</p>
 *
 * @author Terry (PR-9 灰度 ENFORCE 门禁)
 */
@Getter
@AllArgsConstructor
public enum EnforceGateDecision {

	/**
	 * 放行：租户影子差异率达到门禁口径，可进入 enforceTenantIds 白名单。
	 */
	ADMITTED("ADMITTED", "放行: 影子差异率达到 ENFORCE 门禁口径"),

	/**
	 * 门禁未启用：fail-closed 拒绝（enforce-gate.enabled=false 属应急显式决策，不提供放行担保）。
	 */
	REJECTED_GATE_DISABLED("REJECTED_GATE_DISABLED", "拒绝: ENFORCE 门禁未启用(enforce-gate.enabled=false), 不提供放行担保"),

	/**
	 * 最新影子差异报告缺失（对应 GET /authorization-shadow-reports/latest 的 404 语义）：
	 * 观测作业尚未产出报告，fail-closed 拒绝。
	 */
	REJECTED_REPORT_MISSING("REJECTED_REPORT_MISSING", "拒绝: 最新影子差异报告缺失, 请等待观测作业首轮执行或先手动重建"),

	/**
	 * 最新报告中无该租户样本：影子比对证据缺失，按 fail-closed 拒绝，不以"无证据"推断达标。
	 */
	REJECTED_TENANT_MISSING("REJECTED_TENANT_MISSING", "拒绝: 最新影子差异报告中无该租户样本, 影子比对证据缺失"),

	/**
	 * 影子可比样本量（matched+mismatched，ORIGINAL_ONLY 不计）低于 enforce-gate.min-comparable：
	 * 零样本/小样本不构成放行依据（Chris 复验中危项收口：有报告但无可比样本不得放行）。
	 */
	REJECTED_INSUFFICIENT_SAMPLES("REJECTED_INSUFFICIENT_SAMPLES", "拒绝: 影子可比样本不足(matched+mismatched低于下限), 不构成放行依据"),

	/**
	 * 差异率超过 ENFORCE 门禁阈值（PR-10 口径 0.1%）：未满足"影子日志差异归零后"的灰度前置条件。
	 */
	REJECTED_THRESHOLD_EXCEEDED("REJECTED_THRESHOLD_EXCEEDED", "拒绝: 影子差异率超过 ENFORCE 门禁阈值, 须先完成差异归零治理");

	private final String code;

	private final String label;

}
