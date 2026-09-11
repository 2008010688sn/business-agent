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
 * 数字员工部署状态。
 *
 * <p>CAS 激活/回滚经 deployment_version 条件更新保证并发安全，
 * ROLLING_BACK 为回滚中间态保留值（当前实现激活/回滚均为原子 CAS 直达 ACTIVE）。
 */
public enum DeploymentStatusDict implements DictEnum<String> {

	INACTIVE("INACTIVE", "未激活"),

	ACTIVE("ACTIVE", "活跃"),

	ROLLING_BACK("ROLLING_BACK", "回滚中");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

	DeploymentStatusDict(String value, String label) {
		this.value = value;
		this.label = label;
	}

	/**
	 * 按存储值解析枚举，未匹配返回 null。
	 */
	public static DeploymentStatusDict of(String value) {
		for (DeploymentStatusDict item : values()) {
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
