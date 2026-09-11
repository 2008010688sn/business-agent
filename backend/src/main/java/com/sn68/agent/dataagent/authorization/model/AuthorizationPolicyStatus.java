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
 * 授权策略状态枚举（PAP 管理域，PR-3b）。
 *
 * <p>与 agent_authorization_policy.status 的 CHECK 约束保持一致：
 * DRAFT-草稿（仅此状态允许 modify），PUBLISHED-已发布（当前版本不可变），RETIRED-已停用。</p>
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
public enum AuthorizationPolicyStatus {

	/**
	 * 草稿：允许修改名称与草稿版本 JSON。
	 */
	DRAFT("DRAFT"),

	/**
	 * 已发布：当前版本 JSON 不可变；发布新内容需在草稿版本上流转。
	 */
	PUBLISHED("PUBLISHED"),

	/**
	 * 已停用（下线）：不再可被新绑定引用；存量绑定保持（运行时以 binding 为准）。
	 */
	RETIRED("RETIRED");

	private final String code;

	AuthorizationPolicyStatus(String code) {
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
	public static AuthorizationPolicyStatus fromCode(String code) {
		if (code == null) {
			return null;
		}
		for (AuthorizationPolicyStatus status : values()) {
			if (status.getCode().equalsIgnoreCase(code)) {
				return status;
			}
		}
		return null;
	}
}
