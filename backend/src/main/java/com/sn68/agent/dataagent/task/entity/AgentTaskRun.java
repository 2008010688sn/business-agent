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
 * 触发产生的一次任务运行。幂等键在触发入口构造并落唯一索引，
 * 重复触发（重复事件、API 重放、定时重扫）命中唯一索引冲突时返回已有运行记录。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_task_run")
@Schema(description = "Agent任务运行实体")
public class AgentTaskRun extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "任务定义ID")
	private Long definitionId;

	@Schema(description = "任务版本ID")
	private Long taskVersionId;

	@Schema(description = "触发器ID")
	private Long triggerId;

	@Schema(description = "触发类型（CHAT/SCHEDULE/EVENT/API/IM）")
	private String triggerType;

	/**
	 * 触发幂等键，构造规则见 TaskRunIdempotency：
	 * SCHEDULE=triggerId+计划时刻；EVENT=triggerId+eventId；API=triggerId+Idempotency-Key；
	 * IM=triggerId+provider messageId。
	 */
	@Schema(description = "触发幂等键（唯一索引）")
	private String idempotencyKey;

	@Schema(description = "外部事件ID（RocketMQ eventId / IM messageId / API Idempotency-Key 原值）")
	private String externalEventId;

	@Schema(description = "计划执行时刻（SCHEDULE 触发的计划时刻，其他类型为受理时刻）")
	private Instant scheduledTime;

	@Schema(description = "运行状态（PENDING/RUNNING/SUCCESS/FAILED/SKIPPED/CANCELLED）")
	private String runStatus;

	/**
	 * 关联统一运行时 Run（agent_runtime_run.id），由 TaskRunLauncher 幂等创建 RuntimeRun 后回填。
	 */
	@Schema(description = "关联运行时 RunID（可空）")
	private Long runtimeRunId;

	/**
	 * 本次运行使用的受限执行主体快照。自动任务禁止使用创建者长期 Token。
	 */
	@Schema(description = "受限执行主体标识快照（service principal）")
	private String servicePrincipal;

	/**
	 * 触发器并发策略快照（ALLOW/FORBID，落库时取自触发器）。
	 * FORBID 行参与 idx_forbid_slot 部分唯一索引的并发槽竞争：同一任务定义同时最多一行活跃。
	 */
	@Schema(description = "并发策略快照（ALLOW/FORBID）")
	private String concurrencyPolicy;

	@Schema(description = "错误信息")
	private String errorMessage;

	@Schema(description = "开始执行时刻")
	private Instant startedTime;

	@Schema(description = "结束时刻")
	private Instant finishedTime;

}
