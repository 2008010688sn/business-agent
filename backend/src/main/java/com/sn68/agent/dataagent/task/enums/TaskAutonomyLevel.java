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
 * 任务自治级别。高风险写任务默认 ASSISTED（需人工审批后才执行写动作）。
 */
@Getter
@AllArgsConstructor
public enum TaskAutonomyLevel implements DictEnum<String> {

	/** 全自动执行，无需人工确认（仅允许只读/低风险任务）。 */
	AUTONOMOUS("AUTONOMOUS", "全自动"),

	/** 辅助执行，关键写动作需人工审批后才继续。 */
	ASSISTED("ASSISTED", "需审批");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

	/**
	 * 解析自治级别，未识别返回 null（由调用方决定失败语义）。
	 */
	public static TaskAutonomyLevel of(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		for (TaskAutonomyLevel level : values()) {
			if (level.value.equalsIgnoreCase(value.trim())) {
				return level;
			}
		}
		return null;
	}

}
