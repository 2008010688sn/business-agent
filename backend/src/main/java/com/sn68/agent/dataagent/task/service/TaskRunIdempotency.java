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
package com.sn68.agent.dataagent.task.service;

import com.sn68.agent.dataagent.task.enums.TaskTriggerType;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.util.StringUtils;

/**
 * 触发幂等键构造规则（对应 agent_task_run.idempotency_key 唯一索引）：
 *
 * <pre>
 * SCHEDULE : SCHEDULE:{triggerId}:{计划时刻 ISO-8601}   —— 同一 trigger 同一计划时刻只跑一次
 * EVENT    : EVENT:{triggerId}:{RocketMQ eventId}      —— 重复投递的事件只跑一次
 * API      : API:{triggerId}:{Idempotency-Key}         —— API 重放只跑一次
 * IM       : IM:{triggerId}:{provider messageId}       —— IM 消息重投只跑一次
 * CHAT     : CHAT:{triggerId}:{随机UUID}                —— 会话内显式发起，天然一次一发
 * </pre>
 */
public final class TaskRunIdempotency {

	private TaskRunIdempotency() {
	}

	/**
	 * 构造触发幂等键。SCHEDULE 必须提供计划时刻；EVENT/API/IM 必须提供外部ID，缺失直接失败，
	 * 不允许退化成随机键（随机键等于关闭幂等保护）。
	 */
	public static String buildKey(TaskTriggerType triggerType, Long triggerId, String externalEventId,
			Instant scheduledTime) {
		if (triggerType == null || triggerId == null) {
			throw CheckedException.badRequest("触发类型与触发器ID不能为空");
		}
		return switch (triggerType) {
			case SCHEDULE -> {
				if (scheduledTime == null) {
					throw CheckedException.badRequest("SCHEDULE 触发必须提供计划时刻");
				}
				yield "SCHEDULE:" + triggerId + ":" + scheduledTime;
			}
			case EVENT -> "EVENT:" + triggerId + ":" + requireExternalId(externalEventId, "EVENT 触发必须提供 eventId");
			case API -> "API:" + triggerId + ":" + requireExternalId(externalEventId, "API 触发必须提供 Idempotency-Key");
			case IM -> "IM:" + triggerId + ":" + requireExternalId(externalEventId, "IM 触发必须提供 provider messageId");
			case CHAT -> "CHAT:" + triggerId + ":" + UUID.randomUUID();
		};
	}

	private static String requireExternalId(String externalEventId, String message) {
		if (!StringUtils.hasText(externalEventId)) {
			throw CheckedException.badRequest(message);
		}
		return externalEventId.trim();
	}

}
