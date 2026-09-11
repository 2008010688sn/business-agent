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
package com.sn68.agent.dataagent.authorization.observability.controller;

import com.sn68.agent.dataagent.authorization.observability.ShadowDiffReport;
import com.sn68.agent.dataagent.authorization.observability.ShadowDiffReportGenerator;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import com.sn68.agent.framework.commons.exception.CheckedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 授权影子差异报告查询入口（PR-10 交付物 4 的 REST 侧）。
 *
 * <p>只读查询最新报告（观测作业产出）与手动重建（PR-9 逐租户 ENFORCE 前人工取数）。
 * 权限复用授权查询码 {@code agent:authorization:query}（观测视图不新增菜单项）。</p>
 *
 * @author Ray (PR-10 可观测性与灰度基建)
 */
@Slf4j
@RestController
@RequestMapping("/authorization-shadow-reports")
@RequiredArgsConstructor
@Tag(name = "授权影子差异报告", description = "SHADOW 比对差异率报告查询（PR-10 观测基建，PR-9 ENFORCE 门禁数据来源）")
public class AuthorizationShadowReportController {

	private final ShadowDiffReportGenerator reportGenerator;

	@Operation(summary = "查询最新影子差异报告 - [DONE] - [Ray]", description = "返回观测作业最近一次生成的差异报告（按租户聚合 MATCHED/MISMATCHED/ORIGINAL_ONLY 与差异率，附 MISMATCHED 样本明细与任务观测段）；尚未生成时提示等待或手动重建。")
	@AccessLog(module = "授权影子差异报告", description = "查询最新影子差异报告")
	@GetMapping("/latest")
	public ShadowDiffReport latest() {
		ShadowDiffReport report = reportGenerator.latestReport();
		if (report == null) {
			throw CheckedException.notFound("尚未生成授权影子差异报告, 请等待观测作业(agentAuthorizationShadowReportJob)首轮执行或先手动重建");
		}
		return report;
	}

	@Operation(summary = "手动重建影子差异报告 - [DONE] - [Ray]", description = "按当前配置窗口（默认近 24h）立即重算差异报告并覆盖最新；幂等只读聚合，供 PR-9 门禁评估前人工取数。")
	@AccessLog(module = "授权影子差异报告", description = "手动重建影子差异报告")
	@PostMapping("/rebuild")
	public void rebuild() {
		ShadowDiffReport report = reportGenerator.generateWindowReport();
		if (report == null) {
			throw CheckedException.fail("授权影子差异报告未开启(spring.ai.agent.observability.report-enabled=false), 无法重建");
		}
		log.info("授权影子差异报告手动重建完成. windowFrom={}, windowTo={}, totalEvents={}", report.getWindowFrom(),
				report.getWindowTo(), report.getTotalEvents());
	}

}
