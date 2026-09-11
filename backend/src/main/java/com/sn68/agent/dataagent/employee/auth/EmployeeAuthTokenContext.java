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
package com.sn68.agent.dataagent.employee.auth;

/**
 * 员工执行身份 token 上下文（Redis 缓存值对象）。
 *
 * @param tokenValue 签发的 token 值（禁止外泄）
 * @param tokenType token 类型（Bearer）
 * @param expiresIn 有效期（秒）
 * @param authRevision 签发时的授权版本号（缓存比对基准）
 * @param issuedAtEpochMillis 本机签发时刻；旧缓存缺该字段视为过期
 */
public record EmployeeAuthTokenContext(String tokenValue, String tokenType, Long expiresIn, Long authRevision,
		Long issuedAtEpochMillis) {

	public EmployeeAuthTokenContext(String tokenValue, String tokenType, Long expiresIn, Long authRevision) {
		this(tokenValue, tokenType, expiresIn, authRevision, System.currentTimeMillis());
	}

	/**
	 * 缓存 JSON 反序列化用无参构造等价物（Jackson 需要的目标形状）。
	 */
	public static EmployeeAuthTokenContext of(String tokenValue, String tokenType, Long expiresIn,
			Long authRevision) {
		return new EmployeeAuthTokenContext(tokenValue, tokenType, expiresIn, authRevision);
	}

}
