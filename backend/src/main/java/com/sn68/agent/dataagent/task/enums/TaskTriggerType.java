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
package com.sn68.agent.dataagent.task.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.sn68.agent.framework.commons.entity.DictEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.util.StringUtils;

/**
 * 任务触发类型。所有触发最终统一经 TaskRunLauncher 创建同一种 RuntimeRun。
 */
@Getter
@AllArgsConstructor
public enum TaskTriggerType implements DictEnum<String> {

	/** 会话内触发（用户在聊天中显式发起）。 */
	CHAT("CHAT", "会话触发"),

	/** 定时触发（cron），幂等键为 triggerId + 计划时刻。 */
	SCHEDULE("SCHEDULE", "定时触发"),

	/** 事件触发（RocketMQ），幂等键为 triggerId + eventId。 */
	EVENT("EVENT", "事件触发"),

	/** 外部 API 触发，要求 Idempotency-Key 请求头与签名/nonce 校验。 */
	API("API", "API触发"),

	/** IM 消息触发，幂等键为 triggerId + provider messageId。 */
	IM("IM", "IM触发");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

	/**
	 * 解析触发类型，未识别返回 null（由调用方决定失败语义）。
	 */
	public static TaskTriggerType of(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		for (TaskTriggerType type : values()) {
			if (type.value.equalsIgnoreCase(value.trim())) {
				return type;
			}
		}
		return null;
	}

}
