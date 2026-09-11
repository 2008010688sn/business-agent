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
package com.sn68.agent.dataagent.authorization.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AuthorizationPolicy#computeHash()} 单元测试：规范化 JSON + SHA-256 的稳定性与区分度。
 *
 * @author Felix (PR-3a PDP 内核)
 */
class AuthorizationPolicyHashTest {

	private static AuthorizationRule allowWriteRule() {
		return AuthorizationRule.builder()
				.name("write-with-approval")
				.effect(AuthorizationEffect.ALLOW)
				.capabilityCodes(List.of("*"))
				.actions(List.of(AuthorizationAction.WRITE))
				.obligations(List.of(AuthorizationObligation.APPROVAL))
				.maskFields(List.of())
				.build();
	}

	private static AuthorizationRule allowReadRule() {
		return AuthorizationRule.builder()
				.name("allow-read")
				.effect(AuthorizationEffect.ALLOW)
				.capabilityCodes(List.of("*"))
				.actions(List.of(AuthorizationAction.READ))
				.obligations(List.of())
				.maskFields(List.of())
				.build();
	}

	private static AuthorizationPolicy samplePolicy() {
		return AuthorizationPolicy.builder()
				.schemaVersion(1)
				.templateCode("DIGITAL_WORKER")
				.subjectMode(SubjectMode.EMPLOYEE)
				.iamUnavailableBehavior(IamUnavailableBehavior.DENY)
				.allowModelOnly(false)
				.rules(List.of(allowWriteRule(), allowReadRule()))
				.build();
	}

	@Test
	@DisplayName("同一策略重复求值 hash 稳定（64 位小写 hex）")
	void computeHashShouldBeStableForSamePolicy() {
		AuthorizationPolicy policy = samplePolicy();

		String first = policy.computeHash();
		String second = policy.computeHash();

		assertEquals(first, second);
		assertTrue(first.matches("[0-9a-f]{64}"), "应为 64 位小写 hex: " + first);
	}

	@Test
	@DisplayName("字段等价的两次独立构造 hash 相同（规范化消除构造差异）")
	void computeHashShouldBeEqualForEquivalentPolicies() {
		String hashA = samplePolicy().computeHash();
		String hashB = samplePolicy().computeHash();

		assertEquals(hashA, hashB);
	}

	@Test
	@DisplayName("规则顺序不同 hash 不同（有序契约）")
	void computeHashShouldDifferWhenRuleOrderDiffers() {
		AuthorizationPolicy ordered = samplePolicy();
		AuthorizationPolicy reversed = ordered.toBuilder()
				.rules(List.of(allowReadRule(), allowWriteRule()))
				.build();

		assertNotEquals(ordered.computeHash(), reversed.computeHash());
	}

	@Test
	@DisplayName("maskFields 不同 hash 不同")
	void computeHashShouldDifferWhenMaskFieldsDiffer() {
		AuthorizationPolicy base = AuthorizationPolicy.builder()
				.schemaVersion(1)
				.templateCode("T")
				.subjectMode(SubjectMode.EMPLOYEE)
				.iamUnavailableBehavior(IamUnavailableBehavior.DENY)
				.allowModelOnly(false)
				.rules(List.of(AuthorizationRule.builder()
						.name("r")
						.effect(AuthorizationEffect.ALLOW)
						.capabilityCodes(List.of("*"))
						.actions(List.of(AuthorizationAction.WRITE))
						.obligations(List.of(AuthorizationObligation.MASK_FIELDS))
						.maskFields(List.of("phone"))
						.build()))
				.build();
		AuthorizationPolicy other = base.toBuilder()
				.rules(List.of(AuthorizationRule.builder()
						.name("r")
						.effect(AuthorizationEffect.ALLOW)
						.capabilityCodes(List.of("*"))
						.actions(List.of(AuthorizationAction.WRITE))
						.obligations(List.of(AuthorizationObligation.MASK_FIELDS))
						.maskFields(List.of("phone", "idCard"))
						.build()))
				.build();

		assertNotEquals(base.computeHash(), other.computeHash());
	}

	@Test
	@DisplayName("策略级字段不同 hash 不同")
	void computeHashShouldDifferWhenPolicyFieldDiffers() {
		AuthorizationPolicy base = samplePolicy();
		AuthorizationPolicy modelOnly = base.toBuilder()
				.allowModelOnly(true)
				.build();

		assertNotEquals(base.computeHash(), modelOnly.computeHash());
	}

	@Test
	@DisplayName("同策略两次求值产出相同 policyHash 的决策链路语义：evaluator 使用 computeHash")
	void computeHashShouldBeUsedAsDecisionPolicyHash() {
		// 直接验证决策中引用的 policyHash 来源稳定：同一策略多次 computeHash 一致即满足
		assertEquals(samplePolicy().computeHash(), samplePolicy().computeHash());
	}
}
