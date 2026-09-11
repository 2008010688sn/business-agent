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
package com.sn68.agent.dataagent.authorization.pdp;

/**
 * 授权决策原因码枚举（v1 冻结契约）。
 *
 * <p>PDP 内核对外契约的一部分。下游 PR-3b（PAP/Legacy 适配）与 PR-4（PEP 接线）必须按本枚举消费原因码，
 * 不得自行新增或改名；新增原因码属于契约变更，需重新冻结版本。</p>
 *
 * <ul>
 * <li>{@link #POLICY_ALLOWED}：策略规则显式允许。</li>
 * <li>{@link #POLICY_DENIED}：策略规则显式拒绝，或无任何规则命中时的默认拒绝。</li>
 * <li>{@link #MISSING_POLICY}：主体/对象未绑定任何策略，默认拒绝。</li>
 * <li>{@link #CAPABILITY_ALLOWED}：allowModelOnly 策略下纯模型类动作（READ_KNOWLEDGE）放行。</li>
 * <li>{@link #IAM_UNAVAILABLE_ALLOWED}：IAM 不可用但策略允许降级，且降级求值后放行（仅 CALLER + 非 modelOnly）。</li>
 * <li>{@link #IAM_UNAVAILABLE_DENIED}：IAM 不可用且策略拒绝降级（DENY），或员工主体（EMPLOYEE）在 IAM 不可用时一律拒绝。</li>
 * <li>{@link #CAPABILITY_VERSION_MISMATCH}：请求的能力版本与策略绑定版本不一致（预留参数位，本切片已实现比较逻辑）。</li>
 * <li>{@link #RELEASE_SNAPSHOT_MISMATCH}：运行时事实源与 Release 快照不一致（预留，PR-3d 消费）。</li>
 * <li>{@link #AUTO_TOKEN_FORBIDDEN}：命中 X-Auto-Token 平台硬基线（预留，PR-4 接线入口时消费）。</li>
 * </ul>
 *
 * @author Felix (PR-3a PDP 内核)
 */
public enum DecisionReasonCode {

	/**
	 * 策略规则显式允许。
	 */
	POLICY_ALLOWED,

	/**
	 * 策略规则显式拒绝或默认拒绝。
	 */
	POLICY_DENIED,

	/**
	 * 未绑定策略，默认拒绝。
	 */
	MISSING_POLICY,

	/**
	 * allowModelOnly 下纯模型类动作放行。
	 */
	CAPABILITY_ALLOWED,

	/**
	 * IAM 不可用但策略允许降级放行。
	 */
	IAM_UNAVAILABLE_ALLOWED,

	/**
	 * IAM 不可用被拒绝（含员工主体硬基线）。
	 */
	IAM_UNAVAILABLE_DENIED,

	/**
	 * 能力版本不一致。
	 */
	CAPABILITY_VERSION_MISMATCH,

	/**
	 * Release 快照不一致（预留）。
	 */
	RELEASE_SNAPSHOT_MISMATCH,

	/**
	 * X-Auto-Token 命中硬基线（预留）。
	 */
	AUTO_TOKEN_FORBIDDEN
}
