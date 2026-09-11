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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 授权影子差异报告观测作业（PR-10 交付物 4 定时侧，Snail Job）。
 *
 * <p>需在 Snail Job 控制台注册名为 {@code agentAuthorizationShadowReportJob} 的任务，
 * 建议小时级频率（报告窗口默认 24h 滚动，重复执行幂等只覆盖最新）。产出见
 * {@link ShadowDiffReportGenerator}：差异率 Gauge（P2 告警与 ENFORCE 门禁数据源）、
 * JSON/CSV 报告文件、内存最新报告（REST 查询）。</p>
 *
 * <p>生成失败不重试中断作业调度（quiet 落错误日志，下一轮自然重算——报告为窗口
 * 幂等聚合，无状态损失）。</p>
 *
 * @author Ray (PR-10 可观测性与灰度基建)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthorizationShadowReportJob {

	private final ShadowDiffReportGenerator reportGenerator;

	/**
	 * Snail Job 回调入口。
	 */
	public void jobExecute() {
		try {
			ShadowDiffReport report = reportGenerator.generateWindowReport();
			if (report == null) {
				log.debug("授权影子差异报告未开启, 本轮跳过");
				return;
			}
			log.info("授权影子差异观测作业完成. windowFrom={}, windowTo={}, totalEvents={}, tenantCount={}",
					report.getWindowFrom(), report.getWindowTo(), report.getTotalEvents(),
					report.getTenants().size());
		}
		catch (Exception ex) {
			log.error("授权影子差异观测作业执行失败(quiet, 下一轮重算). errorType={}, errorMessage={}",
					ex.getClass().getSimpleName(), ex.getMessage(), ex);
		}
	}

}
