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
 * 授权效果枚举。
 *
 * <p>PDP 内核契约的一部分，表示规则的决策结果。</p>
 *
 * @author Felix (PR-3a PDP 内核)
 */
public enum AuthorizationEffect {

	/**
	 * 允许：授权通过。
	 */
	ALLOW("ALLOW"),

	/**
	 * 拒绝：授权被拒。
	 */
	DENY("DENY");

	private final String code;

	AuthorizationEffect(String code) {
		this.code = code;
	}

	/**
	 * 获取效果码。
	 *
	 * @return 效果码
	 */
	public String getCode() {
		return code;
	}

	/**
	 * 按代码查找枚举值（不区分大小写）。
	 *
	 * @param code 效果码
	 * @return 枚举值，未找到返回 null
	 */
	public static AuthorizationEffect fromCode(String code) {
		if (code == null) {
			return null;
		}
		for (AuthorizationEffect effect : values()) {
			if (effect.getCode().equalsIgnoreCase(code)) {
				return effect;
			}
		}
		return null;
	}
}
