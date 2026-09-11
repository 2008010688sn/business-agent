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

/**
 * PEP 执行模式（PR-3c）。
 *
 * <p>SHADOW（默认）：只记录影子日志（original vs shadow 决策比对），不拦截、不改变现网语义；
 * ENFORCE：按租户白名单灰度开启，空主体与 PDP 拒绝路径开始实际拒绝。
 * PR-4 全面接线与 PR-9 灰度门禁复用本枚举；模式解析见 {@link PepAuthorizationProperties#resolveMode(String)}。</p>
 *
 * @author James (PR-3c PEP 内核扩展)
 */
public enum PepAuthorizationMode {

	/**
	 * 影子模式（默认）：只记录不拦截，DataAgent 现网行为不变。
	 */
	SHADOW("SHADOW"),

	/**
	 * 强制模式：ENFORCE 租户的实际拦截路径开启。
	 */
	ENFORCE("ENFORCE");

	private final String code;

	PepAuthorizationMode(String code) {
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
	 * 是否强制模式。
	 *
	 * @return true 表示 ENFORCE
	 */
	public boolean enforce() {
		return this == ENFORCE;
	}
}
