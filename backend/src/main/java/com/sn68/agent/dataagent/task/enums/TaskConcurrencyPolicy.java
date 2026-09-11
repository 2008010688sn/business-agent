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
 * 触发器并发策略：上一次运行未结束时，新的触发如何处理。
 */
@Getter
@AllArgsConstructor
public enum TaskConcurrencyPolicy implements DictEnum<String> {

	/** 允许并发运行。 */
	ALLOW("ALLOW", "允许并发"),

	/** 禁止并发，存在未结束运行时跳过本次触发（落 SKIPPED 记录可追溯）。 */
	FORBID("FORBID", "禁止并发");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

	/**
	 * 解析策略，未识别回退 FORBID（宁可跳过也不叠加执行）。
	 */
	public static TaskConcurrencyPolicy ofOrDefault(String value) {
		if (!StringUtils.hasText(value)) {
			return FORBID;
		}
		for (TaskConcurrencyPolicy policy : values()) {
			if (policy.value.equalsIgnoreCase(value.trim())) {
				return policy;
			}
		}
		return FORBID;
	}

}
