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

/**
 * DataAgent 长期记忆状态。
 */
public enum AgentMemoryStatus implements DictEnum<String> {

	ACTIVE("ACTIVE", "启用"),

	/**
	 * 程序性（PROCEDURAL）记忆的默认落库状态：只有人工执行「审核通过」动作后才转为 ACTIVE。
	 * 召回链路仅注入 ACTIVE 记忆，处于该状态的记忆不会被召回。
	 */
	PENDING_REVIEW("PENDING_REVIEW", "待人工审核"),

	DISABLED("DISABLED", "禁用"),

	EXPIRED("EXPIRED", "已过期");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

	AgentMemoryStatus(String value, String label) {
		this.value = value;
		this.label = label;
	}

	@Override
	public String getValue() {
		return value;
	}

	@Override
	public String getLabel() {
		return label;
	}

}
