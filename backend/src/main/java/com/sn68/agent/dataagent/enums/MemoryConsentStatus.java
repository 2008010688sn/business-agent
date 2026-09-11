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
 * DataAgent 记忆的用户同意状态。敏感记忆（{@link MemorySensitivity#HIGH}）
 * 仅在 GRANTED 状态下允许写入。
 */
public enum MemoryConsentStatus implements DictEnum<String> {

	UNSPECIFIED("UNSPECIFIED", "未指定"),

	GRANTED("GRANTED", "已同意"),

	DENIED("DENIED", "已拒绝"),

	REVOKED("REVOKED", "已撤回");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

	MemoryConsentStatus(String value, String label) {
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
