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
 * 定时任务错过执行（misfire）策略。
 *
 * <p>服务停机、调度延迟或 DST 跳变都可能造成计划时刻已过期未触发，
 * 该策略决定补偿行为；无论哪种策略，同一计划时刻只会产生一个 task_run（幂等键约束）。
 */
@Getter
@AllArgsConstructor
public enum TaskMisfirePolicy implements DictEnum<String> {

	/** 错过的计划时刻全部跳过，从当前时间重新计算下一次执行。 */
	SKIP("SKIP", "错过跳过"),

	/** 错过的计划时刻只补偿执行一次（取最早错过的时刻），随后回到正常节奏。 */
	FIRE_ONCE("FIRE_ONCE", "补偿一次");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

	/**
	 * 解析策略，未识别回退 SKIP（宁可少跑也不重复跑）。
	 */
	public static TaskMisfirePolicy ofOrDefault(String value) {
		if (!StringUtils.hasText(value)) {
			return SKIP;
		}
		for (TaskMisfirePolicy policy : values()) {
			if (policy.value.equalsIgnoreCase(value.trim())) {
				return policy;
			}
		}
		return SKIP;
	}

}
