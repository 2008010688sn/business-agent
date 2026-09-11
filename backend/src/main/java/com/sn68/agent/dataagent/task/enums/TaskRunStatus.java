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
 * 任务运行状态。
 */
@Getter
@AllArgsConstructor
public enum TaskRunStatus implements DictEnum<String> {

	/** 已受理，等待创建/关联 RuntimeRun。 */
	PENDING("PENDING", "待执行"),

	/** RuntimeRun 已创建并执行中。 */
	RUNNING("RUNNING", "执行中"),

	SUCCESS("SUCCESS", "成功"),

	FAILED("FAILED", "失败"),

	/** 因并发策略/错过执行策略等被跳过，未产生实际执行。 */
	SKIPPED("SKIPPED", "已跳过"),

	CANCELLED("CANCELLED", "已取消");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

}
