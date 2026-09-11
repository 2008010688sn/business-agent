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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sn68.agent.dataagent.authorization.model.AuthorizationAction;
import com.sn68.agent.dataagent.authorization.model.AuthorizationEffect;
import com.sn68.agent.dataagent.authorization.model.AuthorizationObligation;
import com.sn68.agent.dataagent.authorization.model.AuthorizationPolicy;
import com.sn68.agent.dataagent.authorization.model.AuthorizationRule;
import com.sn68.agent.dataagent.authorization.model.IamUnavailableBehavior;
import com.sn68.agent.dataagent.authorization.model.SubjectMode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AuthorizationPolicyEvaluator} 单元测试：求值顺序（v1 冻结语义）全矩阵覆盖。
 *
 * @author Felix (PR-3a PDP 内核)
 */
class AuthorizationPolicyEvaluatorTest {

	private final AuthorizationPolicyEvaluator evaluator = new AuthorizationPolicyEvaluator();

	private static AuthorizationRequest.AuthorizationRequestBuilder request(SubjectKind subjectKind, AuthorizationAction action) {
		return AuthorizationRequest.builder()
				.subjectKind(subjectKind)
				.capabilityCode("cap:order")
				.action(action);
	}

	private static AuthorizationRule.AuthorizationRuleBuilder rule(String name, AuthorizationEffect effect) {
		return AuthorizationRule.builder()
				.name(name)
				.effect(effect)
				.capabilityCodes(List.of("*"))
				.maskFields(List.of());
	}

	private static AuthorizationPolicy.AuthorizationPolicyBuilder callerPolicy() {
		return AuthorizationPolicy.builder()
				.schemaVersion(1)
				.templateCode("T")
				.subjectMode(SubjectMode.CALLER)
				.iamUnavailableBehavior(IamUnavailableBehavior.DENY)
				.allowModelOnly(false);
	}

	@Test
	@DisplayName("策略为 null → MISSING_POLICY 默认拒绝")
	void missingPolicyShouldDeny() {
		AuthorizationDecision decision = evaluator.evaluate(null,
				request(SubjectKind.CALLER, AuthorizationAction.READ).build());

		assertFalse(decision.isAllowed());
		assertEquals(DecisionReasonCode.MISSING_POLICY, decision.getReasonCode());
		assertNull(decision.getPolicyHash());
		assertNotNull(decision.getEvaluatedAt());
	}

	@Test
	@DisplayName("空 rules 默认拒绝 → POLICY_DENIED")
	void emptyRulesShouldDefaultDeny() {
		AuthorizationPolicy policy = callerPolicy().rules(List.of()).build();

		AuthorizationDecision decision = evaluator.evaluate(policy,
				request(SubjectKind.CALLER, AuthorizationAction.READ).build());

		assertFalse(decision.isAllowed());
		assertEquals(DecisionReasonCode.POLICY_DENIED, decision.getReasonCode());
	}

	@Test
	@DisplayName("rules 有序首条命中：DENY 在前则同能力同动作被拒绝")
	void firstMatchDenyBeforeAllowShouldDeny() {
		AuthorizationPolicy policy = callerPolicy()
				.rules(List.of(
						rule("deny-write", AuthorizationEffect.DENY)
								.actions(List.of(AuthorizationAction.WRITE)).build(),
						rule("allow-all", AuthorizationEffect.ALLOW)
								.actions(List.of(AuthorizationAction.WRITE, AuthorizationAction.READ)).build()))
				.build();

		AuthorizationDecision decision = evaluator.evaluate(policy,
				request(SubjectKind.CALLER, AuthorizationAction.WRITE).build());

		assertFalse(decision.isAllowed());
		assertEquals(DecisionReasonCode.POLICY_DENIED, decision.getReasonCode());
	}

	@Test
	@DisplayName("rules 有序首条命中：ALLOW 在前则直接放行（顺序表达 DENY/ALLOW 优先级）")
	void firstMatchAllowBeforeDenyShouldAllow() {
		AuthorizationPolicy policy = callerPolicy()
				.rules(List.of(
						rule("allow-all", AuthorizationEffect.ALLOW)
								.actions(List.of(AuthorizationAction.WRITE)).build(),
						rule("deny-write", AuthorizationEffect.DENY)
								.actions(List.of(AuthorizationAction.WRITE)).build()))
				.build();

		AuthorizationDecision decision = evaluator.evaluate(policy,
				request(SubjectKind.CALLER, AuthorizationAction.WRITE).build());

		assertTrue(decision.isAllowed());
		assertEquals(DecisionReasonCode.POLICY_ALLOWED, decision.getReasonCode());
	}

	@Test
	@DisplayName("'*' 通配能力码与空能力码列表通配均命中；精确能力码不匹配则落默认拒绝")
	void capabilityWildcardMatching() {
		// "*" 通配
		AuthorizationPolicy wildcard = callerPolicy()
				.rules(List.of(rule("allow", AuthorizationEffect.ALLOW).actions(List.of(AuthorizationAction.READ)).build()))
				.build();
		assertTrue(evaluator.evaluate(wildcard, request(SubjectKind.CALLER, AuthorizationAction.READ).build()).isAllowed());

		// 空列表通配
		AuthorizationPolicy emptyCodes = callerPolicy()
				.rules(List.of(rule("allow", AuthorizationEffect.ALLOW)
						.capabilityCodes(List.of())
						.actions(List.of(AuthorizationAction.READ)).build()))
				.build();
		assertTrue(evaluator.evaluate(emptyCodes, request(SubjectKind.CALLER, AuthorizationAction.READ).build()).isAllowed());

		// 精确能力码匹配
		AuthorizationPolicy exact = callerPolicy()
				.rules(List.of(rule("allow", AuthorizationEffect.ALLOW)
						.capabilityCodes(List.of("cap:order"))
						.actions(List.of(AuthorizationAction.READ)).build()))
				.build();
		assertTrue(evaluator.evaluate(exact, request(SubjectKind.CALLER, AuthorizationAction.READ).build()).isAllowed());

		// 精确能力码不匹配 → 默认拒绝
		AuthorizationDecision mismatch = evaluator.evaluate(exact,
				AuthorizationRequest.builder()
						.subjectKind(SubjectKind.CALLER)
						.capabilityCode("cap:other")
						.action(AuthorizationAction.READ)
						.build());
		assertFalse(mismatch.isAllowed());
		assertEquals(DecisionReasonCode.POLICY_DENIED, mismatch.getReasonCode());
	}

	@Test
	@DisplayName("空 actions 列表通配所有动作")
	void emptyActionsShouldMatchAnyAction() {
		AuthorizationPolicy policy = callerPolicy()
				.rules(List.of(rule("allow-any-action", AuthorizationEffect.ALLOW)
						.actions(List.of())
						.build()))
				.build();

		AuthorizationDecision decision = evaluator.evaluate(policy,
				request(SubjectKind.CALLER, AuthorizationAction.EXECUTE).build());

		assertTrue(decision.isAllowed());
	}

	@Test
	@DisplayName("allowModelOnly=true 拦截业务动作（EXECUTE/WRITE）→ POLICY_DENIED")
	void allowModelOnlyShouldDenyBusinessActions() {
		AuthorizationPolicy policy = callerPolicy()
				.allowModelOnly(true)
				.rules(List.of(rule("allow-all", AuthorizationEffect.ALLOW).actions(List.of()).build()))
				.build();

		for (AuthorizationAction action : List.of(AuthorizationAction.EXECUTE, AuthorizationAction.WRITE,
				AuthorizationAction.USE, AuthorizationAction.START_UNATTENDED, AuthorizationAction.READ_MEMORY)) {
			AuthorizationDecision decision = evaluator.evaluate(policy, request(SubjectKind.CALLER, action).build());
			assertFalse(decision.isAllowed(), "allowModelOnly 应拒绝业务动作 " + action);
			assertEquals(DecisionReasonCode.POLICY_DENIED, decision.getReasonCode(), action.toString());
		}
	}

	@Test
	@DisplayName("allowModelOnly=true 放行纯模型类动作 READ_KNOWLEDGE → CAPABILITY_ALLOWED")
	void allowModelOnlyShouldAllowKnowledgeRead() {
		AuthorizationPolicy policy = callerPolicy()
				.allowModelOnly(true)
				.rules(List.of())
				.build();

		AuthorizationDecision decision = evaluator.evaluate(policy,
				request(SubjectKind.CALLER, AuthorizationAction.READ_KNOWLEDGE).build());

		assertTrue(decision.isAllowed());
		assertEquals(DecisionReasonCode.CAPABILITY_ALLOWED, decision.getReasonCode());
	}

	@Test
	@DisplayName("IAM 不可用 × CALLER + DENY → IAM_UNAVAILABLE_DENIED")
	void iamUnavailableCallerDenyShouldDeny() {
		AuthorizationPolicy policy = callerPolicy()
				.iamUnavailableBehavior(IamUnavailableBehavior.DENY)
				.rules(List.of(rule("allow-all", AuthorizationEffect.ALLOW).actions(List.of()).build()))
				.build();

		AuthorizationDecision decision = evaluator.evaluate(policy,
				request(SubjectKind.CALLER, AuthorizationAction.READ).iamAvailable(false).build());

		assertFalse(decision.isAllowed());
		assertEquals(DecisionReasonCode.IAM_UNAVAILABLE_DENIED, decision.getReasonCode());
	}

	@Test
	@DisplayName("IAM 不可用 × CALLER + ALLOW + 规则命中 → IAM_UNAVAILABLE_ALLOWED（降级放行）")
	void iamUnavailableCallerAllowShouldDegradeAllow() {
		AuthorizationPolicy policy = callerPolicy()
				.iamUnavailableBehavior(IamUnavailableBehavior.ALLOW)
				.rules(List.of(rule("allow-all", AuthorizationEffect.ALLOW).actions(List.of()).build()))
				.build();

		AuthorizationDecision decision = evaluator.evaluate(policy,
				request(SubjectKind.CALLER, AuthorizationAction.READ).iamAvailable(false).build());

		assertTrue(decision.isAllowed());
		assertEquals(DecisionReasonCode.IAM_UNAVAILABLE_ALLOWED, decision.getReasonCode());
	}

	@Test
	@DisplayName("IAM 不可用 × CALLER + ALLOW + 无命中 → 仍默认拒绝 POLICY_DENIED")
	void iamUnavailableCallerAllowWithoutHitShouldStillDeny() {
		AuthorizationPolicy policy = callerPolicy()
				.iamUnavailableBehavior(IamUnavailableBehavior.ALLOW)
				.rules(List.of())
				.build();

		AuthorizationDecision decision = evaluator.evaluate(policy,
				request(SubjectKind.CALLER, AuthorizationAction.READ).iamAvailable(false).build());

		assertFalse(decision.isAllowed());
		assertEquals(DecisionReasonCode.POLICY_DENIED, decision.getReasonCode());
	}

	@Test
	@DisplayName("IAM 不可用 × EMPLOYEE 主体：即使策略 ALLOW 也一律拒绝（员工硬基线）")
	void iamUnavailableEmployeeShouldAlwaysDeny() {
		AuthorizationPolicy policy = callerPolicy()
				.subjectMode(SubjectMode.EMPLOYEE)
				.iamUnavailableBehavior(IamUnavailableBehavior.ALLOW)
				.rules(List.of(rule("allow-all", AuthorizationEffect.ALLOW).actions(List.of()).build()))
				.build();

		AuthorizationDecision decision = evaluator.evaluate(policy,
				request(SubjectKind.DIGITAL_EMPLOYEE, AuthorizationAction.READ).iamAvailable(false).build());

		assertFalse(decision.isAllowed());
		assertEquals(DecisionReasonCode.IAM_UNAVAILABLE_DENIED, decision.getReasonCode());
	}

	// B1 评审修订新增：CALLER 策略 + DIGITAL_EMPLOYEE 请求主体应被员工硬基线双锚拦截
	@Test
	@DisplayName("B1: IAM 不可用 × DIGITAL_EMPLOYEE 请求主体 × CALLER 策略 → IAM_UNAVAILABLE_DENIED (修复前后语义翻转)")
	void iamUnavailableDigitalEmployeeWithCallerStrategyShouldDenyByDoubleAnchor() {
		// 构造：策略 subjectMode=CALLER、iamUnavailableBehavior=ALLOW；请求主体=DIGITAL_EMPLOYEE
		AuthorizationPolicy policy = AuthorizationPolicy.builder()
				.schemaVersion(1)
				.templateCode("CALLER_READ_ONLY")
				.subjectMode(SubjectMode.CALLER)
				.iamUnavailableBehavior(IamUnavailableBehavior.ALLOW)
				.allowModelOnly(false)
				.rules(List.of(rule("allow-read", AuthorizationEffect.ALLOW)
						.actions(List.of(AuthorizationAction.READ)).build()))
				.build();

		AuthorizationDecision decision = evaluator.evaluate(policy,
				AuthorizationRequest.builder()
						.subjectKind(SubjectKind.DIGITAL_EMPLOYEE)
						.capabilityCode("cap:order")
						.action(AuthorizationAction.READ)
						.iamAvailable(false)
						.build());

		// 修复前预期：降级放行（CALLER+ALLOW），数字员工冒用身份继续执行能力——正是硬基线要封死的缺陷
		// 修复后预期：员工硬基线双锚拦截（策略主或请求主体任一命中即拒绝）
		assertFalse(decision.isAllowed(), "修复后不应允许：员工硬基线禁止 IAM 不可用期间数字员工冒用身份执行能力");
		assertEquals(DecisionReasonCode.IAM_UNAVAILABLE_DENIED, decision.getReasonCode(),
				"双锚拦截应返回 IAM_UNAVAILABLE_DENIED，与 PR-3a 冻结求值器一致");
	}

	@Test
	@DisplayName("iamAvailable 缺省按 true 处理：不触发 IAM 分支")
	void iamAvailableDefaultShouldSkipIamBranch() {
		AuthorizationPolicy policy = callerPolicy()
				.iamUnavailableBehavior(IamUnavailableBehavior.DENY)
				.rules(List.of(rule("allow-all", AuthorizationEffect.ALLOW).actions(List.of()).build()))
				.build();

		// 显式 null
		AuthorizationDecision decision = evaluator.evaluate(policy,
				AuthorizationRequest.builder()
						.subjectKind(SubjectKind.CALLER)
						.capabilityCode("cap:order")
						.action(AuthorizationAction.READ)
						.iamAvailable(null)
						.build());

		assertTrue(decision.isAllowed());
		assertEquals(DecisionReasonCode.POLICY_ALLOWED, decision.getReasonCode());
	}

	@Test
	@DisplayName("命中 ALLOW 带义务时 obligations 与 maskFields 透传")
	void obligationsShouldPassThroughOnAllow() {
		AuthorizationPolicy policy = callerPolicy()
				.rules(List.of(rule("write-guarded", AuthorizationEffect.ALLOW)
						.actions(List.of(AuthorizationAction.WRITE))
						.obligations(List.of(AuthorizationObligation.APPROVAL, AuthorizationObligation.MASK_FIELDS))
						.maskFields(List.of("phone", "idCard"))
						.build()))
				.build();

		AuthorizationDecision decision = evaluator.evaluate(policy,
				request(SubjectKind.CALLER, AuthorizationAction.WRITE).build());

		assertTrue(decision.isAllowed());
		assertEquals(DecisionReasonCode.POLICY_ALLOWED, decision.getReasonCode());
		assertEquals(List.of(AuthorizationObligation.APPROVAL, AuthorizationObligation.MASK_FIELDS),
				decision.getObligations());
		assertEquals(List.of("phone", "idCard"), decision.getMaskFields());
	}

	@Test
	@DisplayName("无 MASK_FIELDS 义务时 maskFields 为空列表")
	void maskFieldsShouldBeEmptyWithoutMaskObligation() {
		AuthorizationPolicy policy = callerPolicy()
				.rules(List.of(rule("write-guarded", AuthorizationEffect.ALLOW)
						.actions(List.of(AuthorizationAction.WRITE))
						.obligations(List.of(AuthorizationObligation.APPROVAL))
						.maskFields(List.of("phone"))
						.build()))
				.build();

		AuthorizationDecision decision = evaluator.evaluate(policy,
				request(SubjectKind.CALLER, AuthorizationAction.WRITE).build());

		assertTrue(decision.isAllowed());
		assertTrue(decision.getMaskFields().isEmpty());
	}

	@Test
	@DisplayName("能力版本不一致 → CAPABILITY_VERSION_MISMATCH（预留参数位）")
	void capabilityVersionMismatchShouldDeny() {
		AuthorizationPolicy policy = callerPolicy()
				.rules(List.of(rule("allow-all", AuthorizationEffect.ALLOW).actions(List.of()).build()))
				.build();
		AuthorizationRequest request = request(SubjectKind.CALLER, AuthorizationAction.EXECUTE)
				.capabilityVersion("v2")
				.build();

		AuthorizationDecision decision = evaluator.evaluate(policy, request, "v1");

		assertFalse(decision.isAllowed());
		assertEquals(DecisionReasonCode.CAPABILITY_VERSION_MISMATCH, decision.getReasonCode());
	}

	@Test
	@DisplayName("能力版本一致或任一侧为空不触发 mismatch")
	void capabilityVersionMatchOrAbsentShouldAllow() {
		AuthorizationPolicy policy = callerPolicy()
				.rules(List.of(rule("allow-all", AuthorizationEffect.ALLOW).actions(List.of()).build()))
				.build();
		AuthorizationRequest request = request(SubjectKind.CALLER, AuthorizationAction.EXECUTE)
				.capabilityVersion("v1")
				.build();

		assertTrue(evaluator.evaluate(policy, request, "v1").isAllowed());
		assertTrue(evaluator.evaluate(policy, request, null).isAllowed());

		AuthorizationRequest noVersion = request(SubjectKind.CALLER, AuthorizationAction.EXECUTE).build();
		assertTrue(evaluator.evaluate(policy, noVersion, "v1").isAllowed());
	}

	@Test
	@DisplayName("版本校验在规则命中 ALLOW 后执行：DENY 命中仍返回 POLICY_DENIED")
	void versionCheckShouldNotOverrideExplicitDeny() {
		AuthorizationPolicy policy = callerPolicy()
				.rules(List.of(rule("deny-all", AuthorizationEffect.DENY).actions(List.of()).build()))
				.build();
		AuthorizationRequest request = request(SubjectKind.CALLER, AuthorizationAction.EXECUTE)
				.capabilityVersion("v2")
				.build();

		AuthorizationDecision decision = evaluator.evaluate(policy, request, "v1");

		assertFalse(decision.isAllowed());
		assertEquals(DecisionReasonCode.POLICY_DENIED, decision.getReasonCode());
	}

	@Test
	@DisplayName("policyHash 稳定：同策略两次求值 hash 一致且等于 computeHash")
	void policyHashShouldBeStableAcrossEvaluations() {
		AuthorizationPolicy policy = callerPolicy()
				.rules(List.of(rule("allow-all", AuthorizationEffect.ALLOW).actions(List.of()).build()))
				.build();

		String first = evaluator.evaluate(policy, request(SubjectKind.CALLER, AuthorizationAction.READ).build())
				.getPolicyHash();
		String second = evaluator.evaluate(policy, request(SubjectKind.CALLER, AuthorizationAction.WRITE).build())
				.getPolicyHash();

		assertEquals(policy.computeHash(), first);
		assertEquals(first, second);
	}
}
