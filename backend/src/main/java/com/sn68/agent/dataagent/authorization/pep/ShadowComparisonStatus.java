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
package com.sn68.agent.dataagent.authorization.pep;

import cn.hutool.core.util.StrUtil;

/**
 * 影子比对状态（PR-3c，detailed_decision_log.comparison_status 取值）。
 *
 * <p>SHADOW 影子日志的核心口径：现网判定（original_decision）与 PDP 新判定（shadow_decision）逐次比对，
 * 差异率是 PR-9 灰度门禁的量化输入（PR-10 报告消费）。</p>
 *
 * @author James (PR-3c PEP 内核扩展)
 */
public enum ShadowComparisonStatus {

	/**
	 * 现网与新 PDP 判定一致（同为放行或同为拒绝）。
	 */
	MATCHED("MATCHED"),

	/**
	 * 现网放行但 PDP 拒绝，或现网拒绝但 PDP 放行。
	 */
	MISMATCHED("MISMATCHED"),

	/**
	 * 缺少现网判定输入（original_decision 为空），只记录影子侧决策。
	 */
	ORIGINAL_ONLY("ORIGINAL_ONLY");

	private final String code;

	ShadowComparisonStatus(String code) {
		this.code = code;
	}

	/**
	 * 获取状态码。
	 *
	 * @return 状态码
	 */
	public String getCode() {
		return code;
	}

	/**
	 * 按现网/影子两侧的放行结论计算比对状态；现网结论缺失时返回 ORIGINAL_ONLY。
	 *
	 * @param originalAllowed 现网链路实际结论（null 表示无法观测）
	 * @param shadowAllowed   PDP 影子决策结论
	 * @return 比对状态
	 */
	public static ShadowComparisonStatus of(Boolean originalAllowed, boolean shadowAllowed) {
		if (originalAllowed == null) {
			return ORIGINAL_ONLY;
		}
		return originalAllowed == shadowAllowed ? MATCHED : MISMATCHED;
	}

	/**
	 * 按代码查找枚举值（不区分大小写）。
	 *
	 * @param code 状态码
	 * @return 枚举值，未找到返回 null
	 */
	public static ShadowComparisonStatus fromCode(String code) {
		if (StrUtil.isBlank(code)) {
			return null;
		}
		for (ShadowComparisonStatus status : values()) {
			if (status.getCode().equalsIgnoreCase(code.trim())) {
				return status;
			}
		}
		return null;
	}
}
