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
 * 数字员工发布状态（DRAFT → SEALED → PUBLISHED → RETIRED）。
 *
 * <p>Seal 冻结能力清单进 snapshot（此后草稿改动不影响已 Seal 的 Release）；
 * Publish 后 Deployment 只能激活 PUBLISHED 状态的 Release；
 * 被员工 Deployment 或任务定义引用的 Release 不可退役。
 */
public enum EmployeeReleaseStatusDict implements DictEnum<String> {

	DRAFT("DRAFT", "草稿"),

	SEALED("SEALED", "已封版"),

	PUBLISHED("PUBLISHED", "已发布"),

	RETIRED("RETIRED", "已退役");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

	EmployeeReleaseStatusDict(String value, String label) {
		this.value = value;
		this.label = label;
	}

	/**
	 * 按存储值解析枚举，未匹配返回 null。
	 */
	public static EmployeeReleaseStatusDict of(String value) {
		for (EmployeeReleaseStatusDict item : values()) {
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
