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

import com.sn68.agent.dataagent.authorization.model.AuthorizationAction;
import com.sn68.agent.dataagent.authorization.model.AuthorizationEnvironment;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.dataagent.authorization.pdp.SubjectKind;
import lombok.Builder;
import lombok.Getter;

/**
 * PEP 决策上下文（PR-3c）：三线接缝（Hook/输出/记忆）组装的一次判定输入。
 *
 * <p>与 PDP 冻结输入契约 {@code AuthorizationRequest} 的关系：本上下文是 PEP 侧的富输入
 * （含 owner/租户/run/revision 等接线字段），串联器负责裁剪为最小求值字段后提交 PDP，
 * 不改动冻结契约。</p>
 *
 * @author James (PR-3c PEP 内核扩展)
 */
@Getter
@Builder
public class PepDecisionContext {

	/**
	 * 租户ID（策略绑定与 ENFORCE 白名单的匹配键，String 口径与授权表一致）。
	 */
	private final String tenantId;

	/**
	 * 运行ID（审计事件挂靠；可空，空时影子日志降级为结构化日志输出）。
	 */
	private final Long runId;

	/**
	 * 步骤键（审计事件归属步骤，可空）。
	 */
	private final String stepKey;

	/**
	 * 授权对象类型（策略绑定维度）。
	 */
	private final AuthorizationOwnerType ownerType;

	/**
	 * 授权对象ID（普通智能体ID或数字员工ID）。
	 */
	private final Long ownerId;

	/**
	 * 环境（SANDBOX/PRODUCTION，策略绑定维度，缺省 PRODUCTION）。
	 */
	@Builder.Default
	private final AuthorizationEnvironment environment = AuthorizationEnvironment.PRODUCTION;

	/**
	 * 请求主体类别（CALLER/DIGITAL_EMPLOYEE）。
	 */
	private final SubjectKind subjectKind;

	/**
	 * 请求主体标识（真人用户ID或 principalId；PEP 空主体拒绝的判空字段）。
	 */
	private final String subjectId;

	/**
	 * 能力码（可空；空按任意能力参与规则匹配）。
	 */
	private final String capabilityCode;

	/**
	 * 请求的能力版本（可空；与策略绑定版本都非空且不一致时 PDP 返回 CAPABILITY_VERSION_MISMATCH）。
	 */
	private final String capabilityVersion;

	/**
	 * 请求动作。
	 */
	private final AuthorizationAction action;

	/**
	 * IAM 是否可用（缺省 true）。
	 */
	@Builder.Default
	private final Boolean iamAvailable = Boolean.TRUE;

	/**
	 * 期望的 IAM auth_revision（PR-5 本地缓存值；可空表示不做 revision 比对）。
	 */
	private final Long expectedAuthRevision;

	/**
	 * 当前实际的 IAM auth_revision（可空表示未观测到，跳过比对）。
	 */
	private final Long currentAuthRevision;
}
