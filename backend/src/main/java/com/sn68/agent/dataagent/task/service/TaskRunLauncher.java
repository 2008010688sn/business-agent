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
package com.sn68.agent.dataagent.task.service;

import com.sn68.agent.dataagent.task.entity.AgentTaskRun;

/**
 * 任务运行发起器。所有触发类型（CHAT/SCHEDULE/EVENT/API/IM）统一从这里进入，
 * 幂等创建 task_run 后最终创建同一种 RuntimeRun。
 *
 * <p>约束：
 * <ul>
 * <li>重复触发（重复事件、API 重放、定时重扫、IM 消息重投）必须返回已存在的 task_run，不得重复执行；</li>
 * <li>自动任务使用任务定义上的受限 service principal，禁止使用创建者长期 Token；</li>
 * <li>高风险写任务按 ASSISTED 处理，写动作须经运行时审批后执行。</li>
 * </ul>
 */
public interface TaskRunLauncher {

	/**
	 * 幂等发起一次任务运行。
	 *
	 * @param request 统一触发载体
	 * @return 本次（或已存在的）任务运行记录
	 */
	AgentTaskRun launch(TaskLaunchRequest request);

	/**
	 * 审批通过后的续发路径：把 ASSISTED 任务对应、处于待审批状态的统一运行时 Run 恢复为可执行。
	 * 幂等：重复调用（outbox at-least-once 重投）时运行已非待审批状态则跳过，不重复推进。
	 *
	 * @param taskRunId 任务运行ID（agent_task_run.id）
	 * @return 对应任务运行记录
	 */
	AgentTaskRun launchApproved(Long taskRunId);

}
