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
package com.sn68.agent.dataagent.authorization.model;

/**
 * 主体模式枚举。
 *
 * <p>PDP 内核契约的一部分，表示授权决策中主体的类型。</p>
 *
 * @author Felix (PR-3a PDP 内核)
 */
public enum SubjectMode {

	/**
	 * 调用者：Web/IM 的当前登录真人用户。
	 */
	CALLER("CALLER"),

	/**
	 * 员工：数字员工的 Service Principal 身份（执行身份）。
	 */
	EMPLOYEE("EMPLOYEE");

	private final String code;

	SubjectMode(String code) {
		this.code = code;
	}

	/**
	 * 获取模式码。
	 *
	 * @return 模式码
	 */
	public String getCode() {
		return code;
	}

	/**
	 * 按代码查找枚举值（不区分大小写）。
	 *
	 * @param code 模式码
	 * @return 枚举值，未找到返回 null
	 */
	public static SubjectMode fromCode(String code) {
		if (code == null) {
			return null;
		}
		for (SubjectMode mode : values()) {
			if (mode.getCode().equalsIgnoreCase(code)) {
				return mode;
			}
		}
		return null;
	}
}
