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

import com.sn68.agent.framework.commons.exception.CheckedException;

/**
 * 员工执行身份（IAM Service Principal）上下文获取失败的可分类异常。
 *
 * <p>按 PR-3a 冻结契约：DIGITAL_EMPLOYEE 主体在 IAM 不可用时硬拒绝，
 * 禁止降级为真人身份继续执行（员工路径不复用 IM 委托的可空降级）。
 * 原因码 WAITING_AUTH 表示调用方可安全重试（如人工重试/重签发）。</p>
 */
public class EmployeeAuthContextException extends CheckedException {

	/** 重试语义原因码：等待授权（IAM 不可用或签发失败）。 */
	public static final String REASON_WAITING_AUTH = "WAITING_AUTH";

	@java.io.Serial
	private static final long serialVersionUID = 1L;

	private final String reasonCode;

	public EmployeeAuthContextException(String reasonCode, String message, Throwable cause) {
		super(message, cause);
		this.reasonCode = reasonCode;
	}

	/**
	 * IAM 不可用/签发失败的 WAITING_AUTH 异常（调用方按「等待授权、可重试」处理，不产生可执行 Run）。
	 */
	public static EmployeeAuthContextException waitingAuth(String message, Throwable cause) {
		return new EmployeeAuthContextException(REASON_WAITING_AUTH, message, cause);
	}

	public String getReasonCode() {
		return reasonCode;
	}

}
