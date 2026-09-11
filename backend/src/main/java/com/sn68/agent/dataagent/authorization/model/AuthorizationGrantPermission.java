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
 * 对象级授权动作枚举（Grant 域，PR-3b）。
 *
 * <p>与 agent_authorization_grant.permission 的 CHECK 约束保持一致：DISCOVER-可见，USE-可进入业务场景。</p>
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
public enum AuthorizationGrantPermission {

	/**
	 * 可见（目录/列表出现）。
	 */
	DISCOVER("DISCOVER"),

	/**
	 * 可使用（进入业务场景/对话）。
	 */
	USE("USE");

	private final String code;

	AuthorizationGrantPermission(String code) {
		this.code = code;
	}

	/**
	 * 获取编码。
	 *
	 * @return 编码
	 */
	public String getCode() {
		return code;
	}

	/**
	 * 按编码查找枚举值（不区分大小写）。
	 *
	 * @param code 编码
	 * @return 枚举值，未找到返回 null
	 */
	public static AuthorizationGrantPermission fromCode(String code) {
		if (code == null) {
			return null;
		}
		for (AuthorizationGrantPermission permission : values()) {
			if (permission.getCode().equalsIgnoreCase(code)) {
				return permission;
			}
		}
		return null;
	}
}
