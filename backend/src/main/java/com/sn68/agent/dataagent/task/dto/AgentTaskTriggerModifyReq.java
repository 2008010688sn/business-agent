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
 * Agent任务触发器修改请求。字段为空表示不修改；触发类型创建后不可变更。
 */
@Data
@Schema(description = "Agent任务触发器修改请求")
public class AgentTaskTriggerModifyReq {

	@Schema(description = "任务版本ID")
	private Long taskVersionId;

	@Schema(description = "触发配置（整体替换）")
	private Map<String, Object> triggerConfig;

	@Schema(description = "时区（IANA 名称）")
	private String timezone;

	@Schema(description = "错过执行策略（SKIP/FIRE_ONCE）")
	private String misfirePolicy;

	@Schema(description = "并发策略（ALLOW/FORBID）")
	private String concurrencyPolicy;

	@Schema(description = "状态（enabled/disabled）")
	private String status;

}
