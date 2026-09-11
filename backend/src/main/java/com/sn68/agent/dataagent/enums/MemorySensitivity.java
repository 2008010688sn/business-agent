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
 * DataAgent 记忆敏感级别。HIGH 级别的记忆必须在写入前取得用户同意
 * （{@link MemoryConsentStatus#GRANTED}），并支持 TTL、删除与导出。
 */
public enum MemorySensitivity implements DictEnum<String> {

	LOW("LOW", "低"),

	MEDIUM("MEDIUM", "中"),

	HIGH("HIGH", "高");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

	MemorySensitivity(String value, String label) {
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

	/**
	 * 该敏感级别写入是否必须先取得用户同意。
	 */
	public boolean requiresConsent() {
		return this == HIGH;
	}

}
