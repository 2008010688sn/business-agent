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
 * 任务结果投递状态。
 */
@Getter
@AllArgsConstructor
public enum TaskDeliveryStatus implements DictEnum<String> {

	PENDING("PENDING", "待投递"),

	SUCCESS("SUCCESS", "投递成功"),

	/** 投递失败且已达最大重试次数，需人工介入。 */
	FAILED("FAILED", "投递失败"),

	/** 投递失败，等待下一轮重试。 */
	RETRYING("RETRYING", "重试中");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

}
