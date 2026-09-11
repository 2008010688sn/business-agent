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
package com.sn68.agent.dataagent.task.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 任务结果投递记录。
 *
 * <p>TODO(W6→通知治理): 与现有 agent_notification_delivery 的能力重叠，
 * 待通知栈去留结论（模块 AGENTS.md 待决策事项 2）明确后收敛为一套投递记录；
 * 本轮不动 notification 包，任务投递先独立落表保证可追溯。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_task_delivery")
@Schema(description = "Agent任务投递实体")
public class AgentTaskDelivery extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "任务运行ID")
	private Long taskRunId;

	@Schema(description = "投递渠道（IM/EMAIL/WEBHOOK 等）")
	private String channel;

	@Schema(description = "投递目标（会话ID/邮箱/回调地址等，按渠道解释）")
	private String target;

	@Schema(description = "投递状态（PENDING/SUCCESS/FAILED/RETRYING）")
	private String deliveryStatus;

	@Schema(description = "已重试次数")
	private Integer retryCount;

	@Schema(description = "最近一次投递错误")
	private String lastError;

	@Schema(description = "最近一次投递时刻")
	private Instant lastDeliveryTime;

}
