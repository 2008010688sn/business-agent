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

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeArtifactResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeStepResp;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * 任务运行结果详情：台账 + RuntimeRun 结论/步骤/产物，不返回实体。
 */
@Schema(description = "任务运行结果详情")
public record AgentTaskRunDetailResp(

		@Schema(description = "任务运行ID")
		@JsonSerialize(using = ToStringSerializer.class)
		Long taskRunId,

		@Schema(description = "任务定义ID")
		@JsonSerialize(using = ToStringSerializer.class)
		Long definitionId,

		@Schema(description = "关联运行时 RunID")
		@JsonSerialize(using = ToStringSerializer.class)
		Long runtimeRunId,

		@Schema(description = "触发类型")
		String triggerType,

		@Schema(description = "台账运行状态")
		String runStatus,

		@Schema(description = "错误信息")
		String errorMessage,

		@Schema(description = "计划/受理时刻")
		Instant scheduledTime,

		@Schema(description = "开始时刻")
		Instant startedTime,

		@Schema(description = "结束时刻")
		Instant finishedTime,

		@Schema(description = "执行主体")
		String servicePrincipal,

		@Schema(description = "最终回答（空则回退产物 answer）")
		String finalAnswer,

		@Schema(description = "步骤清单")
		List<RuntimeStepResp> steps,

		@Schema(description = "产物摘要")
		List<RuntimeArtifactResp> artifacts,

		@Schema(description = "投递记录")
		List<AgentTaskDeliveryResp> deliveries) {
}
