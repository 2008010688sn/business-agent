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
package com.sn68.agent.dataagent.authorization.pdp;

/**
 * 请求主体类型枚举。
 *
 * <p>PDP 内核契约的一部分，表示授权请求的发起者类别。</p>
 *
 * @author Felix (PR-3a PDP 内核)
 */
public enum SubjectKind {

	/**
	 * Web/IM 的当前登录真人用户（DataAgent）。
	 */
	CALLER("CALLER"),

	/**
	 * 数字员工（服务调用方声明的主体类别）。
	 */
	DIGITAL_EMPLOYEE("DIGITAL_EMPLOYEE");

	private final String code;

	SubjectKind(String code) {
		this.code = code;
	}

	/**
	 * 获取类型码。
	 *
	 * @return 类型码
	 */
	public String getCode() {
		return code;
	}

	/**
	 * 按代码查找枚举值（不区分大小写）。
	 *
	 * @param code 类型码
	 * @return 枚举值，未找到返回 null
	 */
	public static SubjectKind fromCode(String code) {
		if (code == null) {
			return null;
		}
		for (SubjectKind kind : values()) {
			if (kind.getCode().equalsIgnoreCase(code)) {
				return kind;
			}
		}
		return null;
	}
}
