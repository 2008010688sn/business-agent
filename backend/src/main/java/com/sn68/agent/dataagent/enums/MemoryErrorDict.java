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
package com.sn68.agent.dataagent.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.sn68.agent.framework.commons.entity.DictEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 记忆域错误码（492xxx 段，避开评估 490xxx / 自优化 491xxx）。
 */
@Getter
@AllArgsConstructor
public enum MemoryErrorDict implements DictEnum<Integer> {

	MEMORY_OWNER_NOT_FOUND(492001, "记忆主体不存在"),

	MEMORY_EXPORT_SUBJECT_FORBIDDEN(492002, "只能导出当前用户自己的记忆"),

	MEMORY_EXPORT_SESSION_FORBIDDEN(492003, "只能导出当前用户自己的会话记忆");

	@EnumValue
	@JsonValue
	private final Integer value;

	private final String label;

}
