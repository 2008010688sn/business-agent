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
package com.sn68.agent.dataagent.employee.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * 数字员工对话响应。
 */
@Builder
@Schema(description = "数字员工对话响应")
public record EmployeeConversationResp(
		@Schema(description = "会话ID") @JsonSerialize(using = ToStringSerializer.class) Long sessionId,
		@Schema(description = "员工回复文本") String reply,
		@Schema(description = "本次对话执行模式：MODEL_ONLY（rollout 未开启/无授权数据）或 PRINCIPAL（Principal token 执行）") String executionMode,
		@Schema(description = "本次对话对应的运行ID，可跳转员工运行详情")
		@JsonSerialize(using = ToStringSerializer.class)
		Long runtimeRunId) {
}
