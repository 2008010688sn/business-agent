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
 * 授权绑定环境枚举（PAP 管理域，PR-3b）。
 *
 * <p>与 agent_authorization_binding.environment 的 CHECK 约束、digital_employee_deployment.environment 枚举统一为 SANDBOX/PRODUCTION。</p>
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
public enum AuthorizationEnvironment {

	/**
	 * 沙箱环境。
	 */
	SANDBOX("SANDBOX"),

	/**
	 * 生产环境。
	 */
	PRODUCTION("PRODUCTION");

	private final String code;

	AuthorizationEnvironment(String code) {
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
	public static AuthorizationEnvironment fromCode(String code) {
		if (code == null) {
			return null;
		}
		for (AuthorizationEnvironment environment : values()) {
			if (environment.getCode().equalsIgnoreCase(code)) {
				return environment;
			}
		}
		return null;
	}
}
