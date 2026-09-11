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
package com.sn68.agent.dataagent.task.dto;

import com.sn68.agent.dataagent.task.entity.AgentTaskDefinition;
import com.sn68.agent.dataagent.task.entity.AgentTaskTrigger;
import com.sn68.agent.dataagent.task.entity.AgentTaskVersion;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Agent任务定义详情响应（定义 + 最新版本 + 触发器列表）。
 */
@Schema(description = "Agent任务定义详情")
public record AgentTaskDetailResp(
		@Schema(description = "任务定义") AgentTaskDefinition definition,
		@Schema(description = "最新任务版本") AgentTaskVersion latestVersion,
		@Schema(description = "触发器列表") List<AgentTaskTrigger> triggers
) {
}
