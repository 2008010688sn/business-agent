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
package com.sn68.agent.dataagent.employee.service;

import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;

/**
 * 数字员工 Service Principal 开通服务（对接 PR-2 ServicePrincipalFeign）。
 *
 * <p>幂等键：tenantId + DIGITAL_EMPLOYEE + employeeId（IAM 侧保证已存在时返回既有记录）。
 * rollout 开关（{@code spring.ai.agent.digital-employee.rollout.enabled}）默认 false：
 * 未开启时<b>不调用任何 IAM 新接口</b>，返回明确 SKIPPED 状态；员工创建不受影响但不可启用。</p>
 */
public interface PrincipalProvisioningService {

	/**
	 * 为员工开通（或幂等确认）IAM Service Principal。
	 *
	 * <p>失败不抛 500：回写 FAILED 状态并返回失败结果（含原因），由调用方决定是否以 CheckedException 拒绝业务动作；
	 * 绝不回退为创建人真人身份。</p>
	 * @param employee 数字员工（须已落库）
	 * @return 开通结果
	 */
	ProvisionOutcome provision(DigitalEmployee employee);

	/**
	 * 开通结果。
	 *
	 * @param status 结果状态：READY（成功/已就绪）、SKIPPED_ROLLOUT_DISABLED（灰度未开启）、FAILED（开通失败）
	 * @param principalId 成功时的 sp_ 主体 ID（其余为 null）
	 * @param message 面向管理端的说明（失败原因/跳过原因）
	 */
	record ProvisionOutcome(String status, String principalId, String message) {

		public static final String STATUS_READY = "READY";

		public static final String STATUS_SKIPPED = "SKIPPED_ROLLOUT_DISABLED";

		public static final String STATUS_FAILED = "FAILED";

		public boolean ready() {
			return STATUS_READY.equals(status);
		}

	}

}
