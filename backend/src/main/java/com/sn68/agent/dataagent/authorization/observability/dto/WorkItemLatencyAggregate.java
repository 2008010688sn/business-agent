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

import lombok.Data;

/**
 * 任务受理延迟聚合（WorkItem 创建延迟 P95 口径的报表侧数据源，PR-10 P3 告警）。
 *
 * @author Ray (PR-10 可观测性与灰度基建)
 */
@Data
public class WorkItemLatencyAggregate {

	/**
	 * 窗口内受理延迟 P95（秒；无样本为 0）。
	 */
	private Double p95Seconds;

	/**
	 * 参与聚合的样本数（scheduled_time ≤ create_time 的行）。
	 */
	private Long sampleTotal;

}
