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
package com.sn68.agent.dataagent.authorization.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sn68.agent.dataagent.authorization.model.AuthorizationAction;
import com.sn68.agent.dataagent.authorization.model.AuthorizationObligation;
import com.sn68.agent.dataagent.authorization.model.AuthorizationPolicy;
import com.sn68.agent.dataagent.authorization.model.IamUnavailableBehavior;
import com.sn68.agent.dataagent.authorization.model.SubjectMode;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationDecision;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationPolicyEvaluator;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationRequest;
import com.sn68.agent.dataagent.authorization.pdp.DecisionReasonCode;
import com.sn68.agent.dataagent.authorization.pdp.SubjectKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AuthorizationTemplate} 四模板 defaultPolicy 行为测试：以 Evaluator 求值结果验证模板语义。
 *
 * @author Felix (PR-3a PDP 内核)
 */
class AuthorizationTemplateTest {

	private final AuthorizationPolicyEvaluator evaluator = new AuthorizationPolicyEvaluator();

	private AuthorizationDecision evaluate(AuthorizationPolicy policy, AuthorizationAction action) {
		return evaluator.evaluate(policy, AuthorizationRequest.builder()
				.subjectKind(SubjectKind.CALLER)
				.capabilityCode("cap:order")
				.action(action)
				.build());
	}

	@Test
	@DisplayName("MODEL_ONLY：CALLER + allowModelOnly + 空 rules；READ_KNOWLEDGE 放行，业务动作拒绝")
	void modelOnlyTemplateBehavior() {
		AuthorizationPolicy policy = AuthorizationTemplate.MODEL_ONLY.defaultPolicy();

		assertEquals(SubjectMode.CALLER, policy.getSubjectMode());
		assertEquals(Boolean.TRUE, policy.getAllowModelOnly());
		assertEquals(IamUnavailableBehavior.ALLOW, policy.getIamUnavailableBehavior());
		assertTrue(policy.getRules().isEmpty());

		AuthorizationDecision knowledge = evaluate(policy, AuthorizationAction.READ_KNOWLEDGE);
		assertTrue(knowledge.isAllowed());
		assertEquals(DecisionReasonCode.CAPABILITY_ALLOWED, knowledge.getReasonCode());

		AuthorizationDecision execute = evaluate(policy, AuthorizationAction.EXECUTE);
		assertFalse(execute.isAllowed());
		assertEquals(DecisionReasonCode.POLICY_DENIED, execute.getReasonCode());
	}

	@Test
	@DisplayName("CALLER_READ_ONLY：READ/DISCOVER/USE 允许，WRITE 拒绝且无义务")
	void callerReadOnlyTemplateBehavior() {
		AuthorizationPolicy policy = AuthorizationTemplate.CALLER_READ_ONLY.defaultPolicy();

		assertEquals(SubjectMode.CALLER, policy.getSubjectMode());
		assertEquals(Boolean.FALSE, policy.getAllowModelOnly());
		assertEquals(IamUnavailableBehavior.DENY, policy.getIamUnavailableBehavior());

		for (AuthorizationAction action : AuthorizationAction.values()) {
			AuthorizationDecision decision = evaluate(policy, action);
			switch (action) {
				case READ, DISCOVER, USE -> {
					assertTrue(decision.isAllowed(), "READ_ONLY 应允许 " + action);
					assertTrue(decision.getObligations().isEmpty());
				}
				case WRITE -> {
					assertFalse(decision.isAllowed(), "READ_ONLY 应拒绝 WRITE");
					assertEquals(DecisionReasonCode.POLICY_DENIED, decision.getReasonCode());
				}
				default -> assertFalse(decision.isAllowed(), "READ_ONLY 未显式允许的动作默认拒绝 " + action);
			}
		}
	}

	@Test
	@DisplayName("CALLER_INTERACTIVE：WRITE 允许但带 APPROVAL，READ 允许无义务")
	void callerInteractiveTemplateBehavior() {
		AuthorizationPolicy policy = AuthorizationTemplate.CALLER_INTERACTIVE.defaultPolicy();

		assertEquals(SubjectMode.CALLER, policy.getSubjectMode());
		assertEquals(IamUnavailableBehavior.DENY, policy.getIamUnavailableBehavior());

		AuthorizationDecision write = evaluate(policy, AuthorizationAction.WRITE);
		assertTrue(write.isAllowed());
		assertEquals(DecisionReasonCode.POLICY_ALLOWED, write.getReasonCode());
		assertEquals(java.util.List.of(AuthorizationObligation.APPROVAL), write.getObligations());

		AuthorizationDecision read = evaluate(policy, AuthorizationAction.READ);
		assertTrue(read.isAllowed());
		assertTrue(read.getObligations().isEmpty());
	}

	@Test
	@DisplayName("DIGITAL_WORKER：EMPLOYEE 主体，写操作带 APPROVAL + MASK_FIELDS")
	void digitalWorkerTemplateBehavior() {
		AuthorizationPolicy policy = AuthorizationTemplate.DIGITAL_WORKER.defaultPolicy();

		assertEquals(SubjectMode.EMPLOYEE, policy.getSubjectMode());
		assertEquals(IamUnavailableBehavior.DENY, policy.getIamUnavailableBehavior());
		assertEquals(Boolean.FALSE, policy.getAllowModelOnly());

		AuthorizationDecision write = evaluate(policy, AuthorizationAction.WRITE);
		assertTrue(write.isAllowed());
		assertEquals(DecisionReasonCode.POLICY_ALLOWED, write.getReasonCode());
		assertTrue(write.getObligations().contains(AuthorizationObligation.APPROVAL), "写操作必须带 APPROVAL");
		assertTrue(write.getObligations().contains(AuthorizationObligation.MASK_FIELDS), "写操作必须带 MASK_FIELDS");

		// 员工可无人值守
		AuthorizationDecision unattended = evaluate(policy, AuthorizationAction.START_UNATTENDED);
		assertTrue(unattended.isAllowed(), "DIGITAL_WORKER 应允许 START_UNATTENDED");

		// 读操作不带义务
		AuthorizationDecision read = evaluate(policy, AuthorizationAction.READ);
		assertTrue(read.isAllowed());
		assertTrue(read.getObligations().isEmpty());
	}

	@Test
	@DisplayName("DIGITAL_WORKER：IAM 不可用即拒绝（员工硬基线，即使行为字段被覆盖为 ALLOW）")
	void digitalWorkerTemplateShouldDenyWhenIamUnavailable() {
		AuthorizationPolicy policy = AuthorizationTemplate.DIGITAL_WORKER.defaultPolicy()
				.toBuilder()
				.iamUnavailableBehavior(IamUnavailableBehavior.ALLOW)
				.build();

		AuthorizationDecision decision = evaluator.evaluate(policy, AuthorizationRequest.builder()
				.subjectKind(SubjectKind.DIGITAL_EMPLOYEE)
				.capabilityCode("cap:order")
				.action(AuthorizationAction.READ)
				.iamAvailable(false)
				.build());

		assertFalse(decision.isAllowed());
		assertEquals(DecisionReasonCode.IAM_UNAVAILABLE_DENIED, decision.getReasonCode());
	}

	@Test
	@DisplayName("四模板 defaultPolicy 的 templateCode 与 schemaVersion 均正确")
	void allTemplatesShouldCarryTemplateCodeAndSchemaVersion() {
		for (AuthorizationTemplate template : AuthorizationTemplate.values()) {
			AuthorizationPolicy policy = template.defaultPolicy();
			assertEquals(1, policy.getSchemaVersion(), template.name());
			assertEquals(template.name(), policy.getTemplateCode(), template.name());
		}
	}

	@Test
	@DisplayName("fromCode：精确匹配已知模板，未知/空返回 null")
	void fromCodeShouldResolveKnownTemplatesOnly() {
		assertEquals(AuthorizationTemplate.MODEL_ONLY, AuthorizationTemplate.fromCode("MODEL_ONLY"));
		assertEquals(AuthorizationTemplate.DIGITAL_WORKER, AuthorizationTemplate.fromCode("DIGITAL_WORKER"));
		assertNull(AuthorizationTemplate.fromCode("NOT_A_TEMPLATE"));
		assertNull(AuthorizationTemplate.fromCode(null));
	}
}
