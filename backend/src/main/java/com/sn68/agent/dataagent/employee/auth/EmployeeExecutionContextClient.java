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
 * 员工执行上下文客户端（PR-5 契约）。
 *
 * <p>实现方负责：调用 IAM execution-context 签发 token；Redis 缓存
 * {@code sp_auth_snapshot:v2:{tenantId}:{principalId}}（Redis TTL 不超过 token 剩余寿命）；
 * 每次取用前与 IAM 最新 auth_revision 比对，不一致即踢出旧缓存并重签发；
 * IAM 不可用时抛 {@link EmployeeAuthContextException}（WAITING_AUTH，按 PR-3a 冻结契约
 * DIGITAL_EMPLOYEE 主体硬拒绝，禁止静默降级为真人身份）。</p>
 */
public interface EmployeeExecutionContextClient {

	/**
	 * 获取员工执行身份 token（带 Redis 缓存与 auth_revision 比对）。
	 * @param tenantId 租户 ID
	 * @param principalId IAM Service Principal ID（sp_ 前缀）
	 * @param displayName 签发请求显示名（员工名称）
	 * @return token 上下文（tokenValue + authRevision）
	 * @throws EmployeeAuthContextException IAM 不可用或签发失败（WAITING_AUTH）
	 */
	EmployeeAuthTokenContext issueContext(String tenantId, String principalId, String displayName);

	/**
	 * 查询 IAM 侧最新授权版本号（轻量 GET，用于比对基准；失败抛 WAITING_AUTH）。
	 * @param tenantId 租户 ID
	 * @param principalId IAM Service Principal ID
	 * @return 最新 auth_revision
	 * @throws EmployeeAuthContextException IAM 不可用（WAITING_AUTH）
	 */
	Long latestRevision(String tenantId, String principalId);

	/**
	 * 主动失效缓存（角色替换/停用 Principal 等授权变更后调用）。
	 * @param tenantId 租户 ID
	 * @param principalId IAM Service Principal ID
	 */
	void invalidate(String tenantId, String principalId);

}
