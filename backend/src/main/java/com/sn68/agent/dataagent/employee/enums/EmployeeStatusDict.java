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
 * 数字员工状态。
 *
 * <p>启用（ENABLED）前置条件：rollout 开关打开且 principal_status=READY（服务层校验）。
 */
public enum EmployeeStatusDict implements DictEnum<String> {

	DRAFT("DRAFT", "草稿"),

	ENABLED("ENABLED", "已启用"),

	DISABLED("DISABLED", "已停用"),

	ARCHIVED("ARCHIVED", "已封存");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

	EmployeeStatusDict(String value, String label) {
		this.value = value;
		this.label = label;
	}

	/**
	 * 按存储值解析枚举，未匹配返回 null。
	 */
	public static EmployeeStatusDict of(String value) {
		for (EmployeeStatusDict item : values()) {
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
