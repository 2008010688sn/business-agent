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

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sn68.agent.dataagent.repository.typehandler.JsonbMapTypeHandler;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import java.time.Instant;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 任务触发器。一个任务版本可挂多个触发器（CHAT/SCHEDULE/EVENT/API/IM）。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "agent_task_trigger", autoResultMap = true)
@Schema(description = "Agent任务触发器实体")
public class AgentTaskTrigger extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "任务定义ID（冗余便于按任务查询触发器）")
	private Long definitionId;

	@Schema(description = "任务版本ID")
	private Long taskVersionId;

	@Schema(description = "触发类型（CHAT/SCHEDULE/EVENT/API/IM）")
	private String triggerType;

	/**
	 * 触发配置（JSONB），按触发类型解释：
	 * SCHEDULE 用 cron；EVENT 用 eventTopic/eventTag；API 用 apiSecret（HMAC 签名密钥，
	 * 密文存储、仅密钥轮换接口写入、读接口脱敏）；IM 用 provider/connectorCode。
	 */
	@TableField(typeHandler = JsonbMapTypeHandler.class)
	@Schema(description = "触发配置（JSONB：cron 表达式/事件主题/API 签名密钥密文等）")
	private Map<String, Object> triggerConfig;

	@Schema(description = "时区（IANA 名称，如 Asia/Shanghai，SCHEDULE 触发按此时区计算 cron）")
	private String timezone;

	@Schema(description = "错过执行策略（SKIP/FIRE_ONCE）")
	private String misfirePolicy;

	@Schema(description = "并发策略（ALLOW/FORBID）")
	private String concurrencyPolicy;

	@Schema(description = "下一次计划执行时刻（SCHEDULE 触发调度扫描使用）")
	private Instant nextFireTime;

	@Schema(description = "最近一次实际触发时刻")
	private Instant lastFireTime;

	@Schema(description = "状态（enabled/disabled）")
	private String status;

}
