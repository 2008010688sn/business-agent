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
 * IAM 不可用行为枚举。
 *
 * <p>PDP 内核契约的一部分，当 IAM 服务不可用时决定默认授权策略。</p>
 *
 * @author Felix (PR-3a PDP 内核)
 */
public enum IamUnavailableBehavior {

	/**
	 * 允许：IAM 不可用时允许访问。
	 */
	ALLOW("ALLOW"),

	/**
	 * 拒绝：IAM 不可用时拒绝访问。
	 */
	DENY("DENY");

	private final String code;

	IamUnavailableBehavior(String code) {
		this.code = code;
	}

	/**
	 * 获取行为码。
	 *
	 * @return 行为码
	 */
	public String getCode() {
		return code;
	}

	/**
	 * 按代码查找枚举值（不区分大小写）。
	 *
	 * @param code 行为码
	 * @return 枚举值，未找到返回 null
	 */
	public static IamUnavailableBehavior fromCode(String code) {
		if (code == null) {
			return null;
		}
		for (IamUnavailableBehavior behavior : values()) {
			if (behavior.getCode().equalsIgnoreCase(code)) {
				return behavior;
			}
		}
		return null;
	}
}
