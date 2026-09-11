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
 * 对象级授权被授权主体类型枚举（Grant 域，PR-3b）。
 *
 * <p>与 agent_authorization_grant.subject_type 的 CHECK 约束保持一致，枚举值与 legacy visibility
 * 的 subject_type（USER/TEAM/PERMISSION/TENANT）对齐，便于后续等价迁移评估。</p>
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
public enum AuthorizationGrantSubjectType {

	/**
	 * 用户维度。
	 */
	USER("USER"),

	/**
	 * 团队维度。
	 */
	TEAM("TEAM"),

	/**
	 * 功能权限码维度。
	 */
	PERMISSION("PERMISSION"),

	/**
	 * 租户维度。
	 */
	TENANT("TENANT");

	private final String code;

	AuthorizationGrantSubjectType(String code) {
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
	public static AuthorizationGrantSubjectType fromCode(String code) {
		if (code == null) {
			return null;
		}
		for (AuthorizationGrantSubjectType type : values()) {
			if (type.getCode().equalsIgnoreCase(code)) {
				return type;
			}
		}
		return null;
	}
}
