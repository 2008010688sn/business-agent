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

import com.sn68.agent.dataagent.task.entity.AgentTaskTrigger;
import com.sn68.agent.dataagent.task.enums.TaskTriggerType;
import java.time.Instant;
import java.util.Map;

/**
 * 任务运行发起请求。所有触发类型（CHAT/SCHEDULE/EVENT/API/IM）统一收敛到该载体，
 * 由 TaskRunLauncher 幂等创建 task_run 并最终创建同一种 RuntimeRun。
 *
 * @param trigger 触发器（含租户与任务归属）
 * @param triggerType 触发类型
 * @param externalEventId 外部事件ID（EVENT=eventId / IM=provider messageId / API=Idempotency-Key；SCHEDULE 为空）
 * @param scheduledTime 计划时刻（SCHEDULE 为 cron 计划时刻；其余类型为受理时刻）
 * @param params 本次触发的业务参数（透传给运行时）
 */
public record TaskLaunchRequest(
		AgentTaskTrigger trigger,
		TaskTriggerType triggerType,
		String externalEventId,
		Instant scheduledTime,
		Map<String, Object> params) {
}
