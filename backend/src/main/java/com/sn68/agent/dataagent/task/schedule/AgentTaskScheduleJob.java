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
package com.sn68.agent.dataagent.task.schedule;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SCHEDULE 触发入口（Snail Job）。
 *
 * <p>与仓库其他模块（iam/zeus/trove）一致使用 Snail Job 调度，
 * 由启动代码注册为 Snail Cluster 作业（多实例只派一台）。扫描间隔是基础设施心跳，
 * 业务到点仍看任务中心 cron / next_fire_time；到期判断在 {@link TaskScheduleExecutor}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentTaskScheduleJob {

	private final TaskScheduleExecutor scheduleExecutor;

	/**
	 * Snail Job 回调入口。
	 */
	public void jobExecute() {
		int fired = scheduleExecutor.executeDueTriggers();
		log.info("Agent 定时任务扫描完成, 本轮触发 {} 个任务运行", fired);
	}

}
