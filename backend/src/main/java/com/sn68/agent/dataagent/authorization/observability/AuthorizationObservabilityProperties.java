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

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 授权观测配置（PR-10 差异报告与任务观测聚合）。
 *
 * <p>前缀 {@code spring.ai.agent.observability}，与授权灰度开关
 * （{@code spring.ai.agent.authorization.*}）同族。默认值即开箱可用：
 * 报告窗口 24h、差异样本上限 100、任务观测开启；报告文件输出目录默认
 * {@code ./logs/authorization-shadow-reports}（置空则不落盘，仅指标 + 内存 + 日志）。</p>
 *
 * <p>注意：本配置只约束报告聚合侧；{@code AuthorizationMetrics} 指标挂点为常开旁路，
 * 不受 {@link #reportEnabled} 影响（指标丢失不可补，报告可随时重建）。</p>
 *
 * @author Ray (PR-10 可观测性与灰度基建)
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "spring.ai.agent.observability")
public class AuthorizationObservabilityProperties {

	/**
	 * 报告生成总开关（关闭时观测作业直接跳过；指标挂点不受影响）。
	 */
	private boolean reportEnabled = true;

	/**
	 * 聚合窗口长度：报告统计 [now - window, now) 区间内的授权决策影子事件。
	 */
	private Duration reportWindow = Duration.ofHours(24);

	/**
	 * 报告文件输出目录（相对路径按服务工作目录解析；置空表示不落盘）。
	 */
	private String reportOutputDir = "./logs/authorization-shadow-reports";

	/**
	 * MISMATCHED 差异样本明细上限（防大窗口爆量，报告文件与 REST 响应同限）。
	 */
	private int mismatchSampleLimit = 100;

	/**
	 * 任务侧观测聚合开关（SKIPPED 台账行 + 受理延迟 P95；关闭时报告内任务观测段为空）。
	 */
	private boolean taskObservationEnabled = true;

}
