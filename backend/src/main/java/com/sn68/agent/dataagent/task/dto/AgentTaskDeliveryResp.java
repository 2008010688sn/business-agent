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
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 任务结果投递摘要。
 */
@Schema(description = "任务结果投递摘要")
public record AgentTaskDeliveryResp(

		@Schema(description = "投递记录ID")
		@JsonSerialize(using = ToStringSerializer.class)
		Long id,

		@Schema(description = "渠道")
		String channel,

		@Schema(description = "投递目标")
		String target,

		@Schema(description = "投递状态")
		String deliveryStatus,

		@Schema(description = "最近投递时刻")
		Instant lastDeliveryTime,

		@Schema(description = "最近一次错误")
		String lastError) {
}
