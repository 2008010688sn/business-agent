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
package com.sn68.agent.dataagent.authorization.gate.controller;

import com.sn68.agent.dataagent.authorization.gate.EnforceGateService;
import com.sn68.agent.dataagent.authorization.gate.dto.EnforceGateEvaluation;
import com.sn68.agent.dataagent.authorization.gate.dto.EnforceGateOverview;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PR-9 灰度 ENFORCE 门禁入口（放行评估 / 放行登记 / 灰度 checklist）。
 *
 * <p>操作语义：准入评估只读；放行登记（admit）在门禁通过后写入进程级登记簿，
 * 供 ENFORCE 切换保护比对；checklist 汇总供上线操作前后各查一次。权限复用
 * 授权域既有码：查询 {@code agent:authorization:query}、管理 {@code agent:authorization:manage}。</p>
 *
 * @author Terry (PR-9 灰度 ENFORCE 门禁)
 */
@Slf4j
@RestController
@RequestMapping("/authorization-enforce-gates")
@RequiredArgsConstructor
@Tag(name = "授权ENFORCE门禁", description = "PR-9 灰度 ENFORCE 门禁：影子差异达标校验、逐租户放行登记与切换保护 checklist")
public class EnforceGateController {

	private final EnforceGateService enforceGateService;

	@Operation(summary = "评估租户 ENFORCE 准入 - [DONE] - [Terry]", description = "按最新影子差异报告校验租户差异率是否达到 ENFORCE 门禁口径（<0.1%）；报告缺失、租户样本缺失一律 fail-closed 拒绝。只读评估，不做放行登记。")
	@AccessLog(module = "授权ENFORCE门禁", description = "评估租户 ENFORCE 准入")
	@GetMapping("/tenants/{tenantId}/admission")
	public EnforceGateEvaluation evaluateAdmission(
			@Parameter(description = "租户ID（与 enforceTenantIds 同口径）") @PathVariable String tenantId) {
		return enforceGateService.evaluate(tenantId);
	}

	@Operation(summary = "登记租户 ENFORCE 放行 - [DONE] - [Terry]", description = "门禁放行登记：评估不通过抛业务异常（fail-closed，不能误放），通过后写入进程级登记簿供切换保护比对；进程重启后须重建影子差异报告并重新登记。")
	@AccessLog(module = "授权ENFORCE门禁", description = "登记租户 ENFORCE 放行")
	@PostMapping("/tenants/{tenantId}/admission")
	public EnforceGateEvaluation admitTenant(
			@Parameter(description = "租户ID（与 enforceTenantIds 同口径）") @PathVariable String tenantId) {
		return enforceGateService.admitTenant(tenantId);
	}

	@Operation(summary = "查询 ENFORCE 门禁灰度 checklist - [DONE] - [Terry]", description = "白名单逐租户门禁评估 + 放行登记簿 + ENFORCE 切换保护检测（静默回退/未验证租户）；上线操作前后各查一次，rolloutIntegrityPassed=false 时按整改项逐条处理。")
	@AccessLog(module = "授权ENFORCE门禁", description = "查询 ENFORCE 门禁灰度 checklist")
	@GetMapping("/overview")
	public EnforceGateOverview overview() {
		return enforceGateService.overview();
	}

}
