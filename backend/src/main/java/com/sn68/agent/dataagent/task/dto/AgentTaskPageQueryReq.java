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
 * Agent任务定义分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "Agent任务定义分页查询参数")
public class AgentTaskPageQueryReq extends PageRequest {

	@Schema(description = "所属数字员工 ID")
	private Long digitalEmployeeId;

	@Schema(description = "任务类型")
	private String taskType;

	@Schema(description = "状态（enabled/disabled）")
	private String status;

	@Schema(description = "关键字（任务名/描述模糊匹配）")
	private String keyword;

	@Schema(description = "触发类型（SCHEDULE/EVENT/API；CHAT/IM 枚举已有但实现未齐）")
	private String triggerType;

	public AgentTaskPageQueryReq() {
		setSize(10);
	}

}
