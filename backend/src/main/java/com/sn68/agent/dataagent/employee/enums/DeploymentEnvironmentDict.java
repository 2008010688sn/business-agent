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
package com.sn68.agent.dataagent.employee.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.sn68.agent.framework.commons.entity.DictEnum;

/**
 * 数字员工部署环境。
 */
public enum DeploymentEnvironmentDict implements DictEnum<String> {

	SANDBOX("SANDBOX", "沙箱"),

	PRODUCTION("PRODUCTION", "生产");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

	DeploymentEnvironmentDict(String value, String label) {
		this.value = value;
		this.label = label;
	}

	/**
	 * 按存储值解析枚举，未匹配返回 null。
	 */
	public static DeploymentEnvironmentDict of(String value) {
		for (DeploymentEnvironmentDict item : values()) {
			if (item.value.equals(value)) {
				return item;
			}
		}
		return null;
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
