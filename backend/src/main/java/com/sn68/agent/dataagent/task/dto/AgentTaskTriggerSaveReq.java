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
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.Data;

/**
 * Agent任务触发器创建请求（create 与 modify 不共用 DTO）。
 */
@Data
@Schema(description = "Agent任务触发器创建请求")
public class AgentTaskTriggerSaveReq {

	@NotBlank(message = "触发类型不能为空")
	@Schema(description = "触发类型（CHAT/SCHEDULE/EVENT/API/IM）")
	private String triggerType;

	@Schema(description = "任务版本ID（缺省绑定当前最新版本）")
	private Long taskVersionId;

	@Schema(description = "触发配置（SCHEDULE 需 cron；EVENT 需 eventTopic；API 可配签名要求；IM 可配 provider/connectorCode）")
	private Map<String, Object> triggerConfig;

	@Schema(description = "时区（IANA 名称，缺省 Asia/Shanghai）")
	private String timezone;

	@Schema(description = "错过执行策略（SKIP/FIRE_ONCE，缺省 SKIP）")
	private String misfirePolicy;

	@Schema(description = "并发策略（ALLOW/FORBID，缺省 FORBID）")
	private String concurrencyPolicy;

	@Schema(description = "状态（enabled/disabled，缺省 enabled）")
	private String status;

}
