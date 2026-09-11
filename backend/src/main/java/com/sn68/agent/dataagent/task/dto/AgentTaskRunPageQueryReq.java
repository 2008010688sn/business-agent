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

import com.sn68.agent.framework.db.mybatisplus.page.PageRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent任务运行分页查询请求（定义维度子资源）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "Agent任务运行分页查询参数")
public class AgentTaskRunPageQueryReq extends PageRequest {

	@Schema(description = "触发器ID")
	private Long triggerId;

	@Schema(description = "触发类型（CHAT/SCHEDULE/EVENT/API/IM）")
	private String triggerType;

	@Schema(description = "运行状态（PENDING/RUNNING/SUCCESS/FAILED/SKIPPED/CANCELLED）")
	private String runStatus;

	public AgentTaskRunPageQueryReq() {
		setSize(10);
	}

}
