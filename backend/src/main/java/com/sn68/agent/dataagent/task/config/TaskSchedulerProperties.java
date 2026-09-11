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
package com.sn68.agent.dataagent.task.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 任务扫描作业的基础设施心跳（不是数字员工任务的业务 cron）。
 * 业务到点仍只看任务中心 trigger_config + next_fire_time。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "spring.ai.agent.task.scheduler")
public class TaskSchedulerProperties {

	/**
	 * 启动时是否向 Snail 注册 Cluster 扫描作业（定时触发、运行时拉起、任务运行对账）。
	 */
	private boolean enabled = true;

	/**
	 * Snail CRON（Quartz 6 段），扫描间隔，默认每 10 秒看一眼库。
	 */
	private String scanCron = "0/10 * * * * ?";

	/**
	 * 扫描作业超时秒数；回调内不得跑模型，保持短超时。
	 */
	private int executorTimeoutSeconds = 30;

}
