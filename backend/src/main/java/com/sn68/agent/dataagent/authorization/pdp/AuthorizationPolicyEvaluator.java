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

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.sn68.agent.dataagent.authorization.model.AuthorizationAction;
import com.sn68.agent.dataagent.authorization.model.AuthorizationEffect;
import com.sn68.agent.dataagent.authorization.model.AuthorizationObligation;
import com.sn68.agent.dataagent.authorization.model.AuthorizationPolicy;
import com.sn68.agent.dataagent.authorization.model.AuthorizationRule;
import com.sn68.agent.dataagent.authorization.model.IamUnavailableBehavior;
import com.sn68.agent.dataagent.authorization.model.SubjectMode;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 授权策略求值器（PDP 核心，纯函数、无状态 Bean）。
 *
 * <p>求值顺序（v1 冻结，PR-3b/PR-4 不得改变顺序语义）：</p>
 * <ol>
 * <li>策略为 null → {@link DecisionReasonCode#MISSING_POLICY}（默认拒绝）；</li>
 * <li>IAM 不可用：策略 iamUnavailableBehavior=DENY，或员工硬基线（策略主体为 EMPLOYEE，或请求主体为 DIGITAL_EMPLOYEE；
 * 2026-08-19 评审修订为双锚，防止单一策略锚点在误绑 CALLER 模式策略时削弱拒绝不变量）
 * → {@link DecisionReasonCode#IAM_UNAVAILABLE_DENIED}；否则（CALLER + ALLOW）降级继续求值；</li>
 * <li>allowModelOnly=true：动作属于纯模型允许集（READ_KNOWLEDGE）→
 * {@link DecisionReasonCode#CAPABILITY_ALLOWED}，其余业务动作 → {@link DecisionReasonCode#POLICY_DENIED}；</li>
 * <li>rules 按数组顺序<b>首条命中</b>（DENY 与 ALLOW 的优先关系由规则顺序表达，不全局排序）；
 * 命中 DENY → {@link DecisionReasonCode#POLICY_DENIED}；</li>
 * <li>命中 ALLOW：请求能力版本与策略绑定版本都非空且不一致 →
 * {@link DecisionReasonCode#CAPABILITY_VERSION_MISMATCH}；一致或任一为空 →
 * {@link DecisionReasonCode#POLICY_ALLOWED} 并透传义务；降级放行时原因码标记为
 * {@link DecisionReasonCode#IAM_UNAVAILABLE_ALLOWED}；</li>
 * <li>无任何规则命中 → 默认拒绝 {@link DecisionReasonCode#POLICY_DENIED}。</li>
 * </ol>
 *
 * <p>规则匹配语义：capabilityCodes 为空列表视为通配（对齐主文档"空数组表示通配"），含 "*" 通配，
 * 否则精确等值；actions 为空列表通配所有动作，否则需包含请求动作。</p>
 *
 * @author Felix (PR-3a PDP 内核)
 */
@Component
public class AuthorizationPolicyEvaluator {

	/** allowModelOnly 策略下的纯模型类允许集：仅明确公开的知识检索。 */
	private static final Set<AuthorizationAction> MODEL_ONLY_ALLOWED_ACTIONS = EnumSet.of(AuthorizationAction.READ_KNOWLEDGE);

	/** 能力码通配符。 */
	private static final String WILDCARD_CAPABILITY = "*";

	/**
	 * 求值（无策略绑定能力版本）。
	 *
	 * @param policy  参与求值的策略，可为 null（表示未绑定策略）
	 * @param request 授权请求
	 * @return 决策结果，永不返回 null
	 */
	public AuthorizationDecision evaluate(AuthorizationPolicy policy, AuthorizationRequest request) {
		return evaluate(policy, request, null);
	}

	/**
	 * 求值（携带策略绑定的能力版本）。
	 *
	 * @param policy                     参与求值的策略，可为 null（表示未绑定策略）
	 * @param request                    授权请求
	 * @param policyBoundCapabilityVersion 策略绑定的能力版本（预留参数位，可空；PR-3b 起由版本/绑定表提供）
	 * @return 决策结果，永不返回 null
	 */
	public AuthorizationDecision evaluate(AuthorizationPolicy policy, AuthorizationRequest request,
			String policyBoundCapabilityVersion) {
		Instant now = Instant.now();

		// 1. 未绑定策略：默认拒绝
		if (policy == null) {
			return deny(DecisionReasonCode.MISSING_POLICY, null, now);
		}

		String policyHash = policy.computeHash();

		// 2. IAM 不可用：DENY 行为或员工主体硬基线 → 拒绝；CALLER + ALLOW → 降级继续
		boolean iamUnavailable = request.getIamAvailable() != null && !request.getIamAvailable();
		boolean degraded = false;
		if (iamUnavailable) {
			boolean denyByBehavior = policy.getIamUnavailableBehavior() == IamUnavailableBehavior.DENY;
			// 冻结例外登记（2026-08-19 评审修订）：员工硬基线由单一策略锚点改为策略+请求主体双锚。
			// 单一策略锚点在管理员误将 CALLER 模式策略绑定到数字员工时，会削弱 IAM 不可用 × EMPLOYEE
			// 拒绝不变量（降级放行使数字员工冒用身份继续执行能力），故同时锚定请求主体 DIGITAL_EMPLOYEE。
			boolean denyByEmployeeSubject = policy.getSubjectMode() == SubjectMode.EMPLOYEE
					|| request.getSubjectKind() == SubjectKind.DIGITAL_EMPLOYEE;
			if (denyByBehavior || denyByEmployeeSubject) {
				return deny(DecisionReasonCode.IAM_UNAVAILABLE_DENIED, policyHash, now);
			}
			degraded = true;
		}

		// 3. allowModelOnly：只放纯模型类动作，业务动作一律拒绝
		if (Boolean.TRUE.equals(policy.getAllowModelOnly())) {
			if (MODEL_ONLY_ALLOWED_ACTIONS.contains(request.getAction())) {
				return AuthorizationDecision.builder()
						.allowed(true)
						.reasonCode(DecisionReasonCode.CAPABILITY_ALLOWED)
						.obligations(List.of())
						.maskFields(List.of())
						.policyHash(policyHash)
						.evaluatedAt(now)
						.build();
			}
			return deny(DecisionReasonCode.POLICY_DENIED, policyHash, now);
		}

		// 4./5. rules 有序首条命中
		List<AuthorizationRule> rules = policy.getRules();
		if (CollUtil.isNotEmpty(rules)) {
			for (AuthorizationRule rule : rules) {
				if (!matches(rule, request)) {
					continue;
				}
				if (rule.getEffect() == AuthorizationEffect.DENY) {
					return deny(DecisionReasonCode.POLICY_DENIED, policyHash, now);
				}
				// 命中 ALLOW：先校验能力版本一致性（预留参数位）
				if (StrUtil.isNotBlank(request.getCapabilityVersion())
						&& StrUtil.isNotBlank(policyBoundCapabilityVersion)
						&& !request.getCapabilityVersion().equals(policyBoundCapabilityVersion)) {
					return deny(DecisionReasonCode.CAPABILITY_VERSION_MISMATCH, policyHash, now);
				}
				List<AuthorizationObligation> obligations = CollUtil.isEmpty(rule.getObligations())
						? List.of()
						: List.copyOf(rule.getObligations());
				List<String> maskFields = obligations.contains(AuthorizationObligation.MASK_FIELDS)
						? (CollUtil.isEmpty(rule.getMaskFields()) ? List.of() : List.copyOf(rule.getMaskFields()))
						: List.of();
				return AuthorizationDecision.builder()
						.allowed(true)
						.reasonCode(degraded ? DecisionReasonCode.IAM_UNAVAILABLE_ALLOWED : DecisionReasonCode.POLICY_ALLOWED)
						.obligations(obligations)
						.maskFields(maskFields)
						.policyHash(policyHash)
						.evaluatedAt(now)
						.build();
			}
		}

		// 6. 无命中：默认拒绝
		return deny(DecisionReasonCode.POLICY_DENIED, policyHash, now);
	}

	/**
	 * 规则匹配：能力码与动作两个维度都命中才视为命中。
	 *
	 * @param rule    规则
	 * @param request 授权请求
	 * @return 是否命中
	 */
	private boolean matches(AuthorizationRule rule, AuthorizationRequest request) {
		return matchesCapability(rule.getCapabilityCodes(), request.getCapabilityCode())
				&& matchesAction(rule.getActions(), request.getAction());
	}

	/**
	 * 能力码匹配：空列表通配、含 "*" 通配、否则精确等值。
	 */
	private boolean matchesCapability(List<String> capabilityCodes, String capabilityCode) {
		if (CollUtil.isEmpty(capabilityCodes)) {
			return true;
		}
		if (capabilityCodes.contains(WILDCARD_CAPABILITY)) {
			return true;
		}
		return StrUtil.isNotBlank(capabilityCode) && capabilityCodes.contains(capabilityCode);
	}

	/**
	 * 动作匹配：空列表通配所有动作，否则需包含请求动作。
	 */
	private boolean matchesAction(List<AuthorizationAction> actions, AuthorizationAction action) {
		if (CollUtil.isEmpty(actions)) {
			return true;
		}
		return action != null && actions.contains(action);
	}

	/**
	 * 构造拒绝决策。
	 */
	private AuthorizationDecision deny(DecisionReasonCode reasonCode, String policyHash, Instant now) {
		return AuthorizationDecision.builder()
				.allowed(false)
				.reasonCode(reasonCode)
				.obligations(List.of())
				.maskFields(List.of())
				.policyHash(policyHash)
				.evaluatedAt(now)
				.build();
	}
}
