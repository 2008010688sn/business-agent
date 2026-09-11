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

/**
 * 任务体系错误码（490xxx 段，避开 IM 的 480xxx 段）。
 */
@Getter
@AllArgsConstructor
public enum TaskErrorDict implements DictEnum<Integer> {

	TASK_NOT_FOUND(490001, "任务定义不存在"),

	TASK_VERSION_NOT_FOUND(490002, "任务版本不存在"),

	TRIGGER_NOT_FOUND(490003, "任务触发器不存在"),

	TRIGGER_TYPE_INVALID(490004, "任务触发类型无效"),

	CRON_INVALID(490005, "cron 表达式无效"),

	TIMEZONE_INVALID(490006, "时区无效"),

	IDEMPOTENCY_KEY_REQUIRED(490007, "缺少 Idempotency-Key 请求头"),

	TRIGGER_DISABLED(490008, "任务触发器未启用"),

	TASK_DISABLED(490009, "任务定义未启用"),

	EVENT_TOPIC_REQUIRED(490010, "事件触发必须配置事件主题"),

	SIGNATURE_REQUIRED(490011, "缺少 API 触发签名请求头（X-Signature/X-Timestamp/X-Nonce）"),

	SIGNATURE_INVALID(490012, "API 触发签名校验失败"),

	SIGNATURE_TIMESTAMP_EXPIRED(490013, "API 触发时间戳超出允许窗口"),

	NONCE_REPLAYED(490014, "API 触发 nonce 已使用（疑似重放请求）"),

	API_SECRET_NOT_CONFIGURED(490015, "API 触发器未配置签名密钥，请先生成密钥"),

	PRINCIPAL_NOT_READY(490016, "数字员工执行主体（Principal）未就绪"),

	CONCURRENT_SLOT_LOCKED(490017, "任务并发槽被占用，本次触发已跳过"),

	MANUAL_TRIGGER_UNSUPPORTED(490018, "该触发类型不支持控制台立即执行（会话/IM 触发本轮未开通）");

	@EnumValue
	@JsonValue
	private final Integer value;

	private final String label;

}
