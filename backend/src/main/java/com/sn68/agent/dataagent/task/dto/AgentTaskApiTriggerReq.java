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

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import lombok.Data;

/**
 * API 触发任务运行请求。幂等键从 Idempotency-Key 请求头取，缺失直接拒绝。
 */
@Data
@Schema(description = "API 触发任务运行请求")
public class AgentTaskApiTriggerReq {

	@Schema(description = "本次触发的业务参数（透传给任务运行）")
	private Map<String, Object> params;

}
