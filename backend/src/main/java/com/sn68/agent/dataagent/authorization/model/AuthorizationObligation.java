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
 * 授权义务枚举。
 *
 * <p>PDP 内核契约的一部分，表示授权决策附带的执行要求。</p>
 *
 * @author Felix (PR-3a PDP 内核)
 */
public enum AuthorizationObligation {

	/**
	 * 需要审批：在决策前需获得人工或工作流审批。
	 */
	APPROVAL("APPROVAL"),

	/**
	 * 字段脱敏：输出结果时需对指定敏感字段进行脱敏处理。
	 */
	MASK_FIELDS("MASK_FIELDS"),

	/**
	 * 字段过滤：输出结果时移除指定的敏感字段。
	 */
	FILTER_FIELDS("FILTER_FIELDS");

	private final String code;

	AuthorizationObligation(String code) {
		this.code = code;
	}

	/**
	 * 获取义务码。
	 *
	 * @return 义务码
	 */
	public String getCode() {
		return code;
	}

	/**
	 * 按代码查找枚举值（不区分大小写）。
	 *
	 * @param code 义务码
	 * @return 枚举值，未找到返回 null
	 */
	public static AuthorizationObligation fromCode(String code) {
		if (code == null) {
			return null;
		}
		for (AuthorizationObligation obligation : values()) {
			if (obligation.getCode().equalsIgnoreCase(code)) {
				return obligation;
			}
		}
		return null;
	}
}
