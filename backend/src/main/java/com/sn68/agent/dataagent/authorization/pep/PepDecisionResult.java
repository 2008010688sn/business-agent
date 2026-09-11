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

import com.sn68.agent.dataagent.authorization.pdp.AuthorizationDecision;
import com.sn68.agent.dataagent.authorization.pdp.SubjectKind;
import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

/**
 * PEP 决策结果（PR-3c）：串联器输出 = PDP 冻结决策 + PEP 扩展审计字段。
 *
 * <p>扩展字段（decisionId/策略版本指针/bindRevision/revision 比对/生效模式）只承载于 PEP 层，
 * 不回写 PR-3a 冻结的 {@link AuthorizationDecision} 契约（v1 冻结声明的扩展方式）。</p>
 *
 * @author James (PR-3c PEP 内核扩展)
 */
@Getter
@Builder
public class PepDecisionResult {

	/**
	 * 决策ID（UUID），贯穿影子日志与 invocation 审计列，供对账与重放定位。
	 */
	private final String decisionId;

	/**
	 * 请求主体类别（审计列 subject_kind 的来源，从决策上下文透传）。
	 */
	private final SubjectKind subjectKind;

	/**
	 * PDP 冻结决策（原样透传，原因码传播不重映射）。
	 */
	private final AuthorizationDecision decision;

	/**
	 * 参与求值的策略版本ID（未绑定策略时为 null）。
	 */
	private final Long policyVersionId;

	/**
	 * 策略绑定乐观锁版本（决策重放口径之一：policyHash + bindRevision + capabilityVersion）。
	 */
	private final Long bindRevision;

	/**
	 * IAM auth_revision 比对结论：null 表示未提供 revision（跳过比对）；false 表示不一致
	 * （ENFORCE 下消费方应按 WAITING_AUTH 处理并触发重签发，PR-5/PR-6 接线）。
	 */
	private final Boolean revisionMatched;

	/**
	 * 本次判定实际生效的模式（SHADOW/ENFORCE）。
	 */
	private final PepAuthorizationMode effectiveMode;

	/**
	 * 判定完成时间。
	 */
	private final Instant evaluatedAt;

	/**
	 * PDP 决策是否放行（空安全便捷读法）。
	 *
	 * @return decision 为 null 时返回 false（fail-closed）
	 */
	public boolean allowed() {
		return decision != null && decision.isAllowed();
	}

	/**
	 * PDP 原因码（便捷读法）。
	 *
	 * @return decision 为 null 时返回 null
	 */
	public com.sn68.agent.dataagent.authorization.pdp.DecisionReasonCode reasonCode() {
		return decision == null ? null : decision.getReasonCode();
	}
}
