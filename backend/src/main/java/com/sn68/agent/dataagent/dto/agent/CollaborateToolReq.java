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
package com.sn68.agent.dataagent.dto.agent;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Agent 协作工具调用请求：主 Agent 向协作者 Agent 委派任务。
 */
@Data
@Schema(description = "Agent协作工具请求")
public class CollaborateToolReq {

	@Schema(description = "协作者Agent ID")
	private Long collaboratorAgentId;

	@Schema(description = "委派的任务描述")
	private String task;

	@Schema(description = "委派原因")
	private String reason;

	@Schema(description = "期望的输出说明")
	private String expectedOutput;

}
