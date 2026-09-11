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
 * 数字员工 Service Principal 开通状态。
 *
 * <p>rollout 开关未打开时不发起 IAM 开通，员工停留在 PENDING；
 * 开通成功回写 READY + iam_principal_id；失败回写 FAILED（不抛 500，可重试）。
 */
public enum PrincipalProvisionStatusDict implements DictEnum<String> {

	PENDING("PENDING", "待开通"),

	READY("READY", "已就绪"),

	FAILED("FAILED", "开通失败"),

	DISABLED("DISABLED", "已停用");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

	PrincipalProvisionStatusDict(String value, String label) {
		this.value = value;
		this.label = label;
	}

	/**
	 * 按存储值解析枚举，未匹配返回 null。
	 */
	public static PrincipalProvisionStatusDict of(String value) {
		for (PrincipalProvisionStatusDict item : values()) {
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
