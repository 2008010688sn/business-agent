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
package com.sn68.agent.dataagent.task.mq;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import lombok.Data;

/**
 * Agent任务事件消息体。业务方发送到统一任务事件 topic，
 * eventTopic 为逻辑主题（与触发器配置的 eventTopic 匹配），eventId 为幂等依据（必填）。
 */
@Data
@Schema(description = "Agent任务事件消息")
public class AgentTaskEventMessage {

	@Schema(description = "事件ID（RocketMQ 侧生成的全局唯一ID，幂等依据，必填）")
	private String eventId;

	@Schema(description = "逻辑事件主题（与 agent_task_trigger.trigger_config.eventTopic 匹配）")
	private String eventTopic;

	@Schema(description = "事件参数（透传给任务运行）")
	private Map<String, Object> params;

}
