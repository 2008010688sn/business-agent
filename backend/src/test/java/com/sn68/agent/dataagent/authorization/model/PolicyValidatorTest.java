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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sn68.agent.framework.commons.exception.CheckedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PolicyValidator} 单元测试：严格模式校验（未知字段、版本、必填、枚举值）与合法解析路径。
 *
 * @author Felix (PR-3a PDP 内核)
 */
class PolicyValidatorTest {

	private static final String VALID_FULL_JSON = """
			{
			  "schemaVersion": 1,
			  "templateCode": "DIGITAL_WORKER",
			  "subjectMode": "EMPLOYEE",
			  "iamUnavailableBehavior": "DENY",
			  "allowModelOnly": false,
			  "rules": [
			    {
			      "name": "write-with-approval-and-mask",
			      "effect": "ALLOW",
			      "capabilityCodes": ["*"],
			      "actions": ["WRITE"],
			      "obligations": ["APPROVAL", "MASK_FIELDS"],
			      "maskFields": ["phone", "idCard"]
			    }
			  ]
			}
			""";

	@Test
	@DisplayName("合法完整策略 JSON 解析成功，字段与规则逐项还原")
	void validateAndParseShouldResolveValidFullPolicy() {
		AuthorizationPolicy policy = PolicyValidator.validateAndParse(VALID_FULL_JSON);

		assertEquals(1, policy.getSchemaVersion());
		assertEquals("DIGITAL_WORKER", policy.getTemplateCode());
		assertEquals(SubjectMode.EMPLOYEE, policy.getSubjectMode());
		assertEquals(IamUnavailableBehavior.DENY, policy.getIamUnavailableBehavior());
		assertEquals(Boolean.FALSE, policy.getAllowModelOnly());
		assertEquals(1, policy.getRules().size());

		AuthorizationRule rule = policy.getRules().get(0);
		assertEquals("write-with-approval-and-mask", rule.getName());
		assertEquals(AuthorizationEffect.ALLOW, rule.getEffect());
		assertEquals(java.util.List.of("*"), rule.getCapabilityCodes());
		assertEquals(java.util.List.of(AuthorizationAction.WRITE), rule.getActions());
		assertEquals(java.util.List.of(AuthorizationObligation.APPROVAL, AuthorizationObligation.MASK_FIELDS),
				rule.getObligations());
		assertEquals(java.util.List.of("phone", "idCard"), rule.getMaskFields());
	}

	@Test
	@DisplayName("空 rules 数组合法（语义为默认拒绝）")
	void validateAndParseShouldAcceptEmptyRules() {
		AuthorizationPolicy policy = PolicyValidator.validateAndParse("""
				{
				  "schemaVersion": 1,
				  "templateCode": "MODEL_ONLY",
				  "subjectMode": "CALLER",
				  "iamUnavailableBehavior": "ALLOW",
				  "allowModelOnly": true,
				  "rules": []
				}
				""");

		assertNotNull(policy);
		assertTrue(policy.getRules().isEmpty());
	}

	@Test
	@DisplayName("未知顶层字段拒绝（严格模式）")
	void validateAndParseShouldRejectUnknownTopLevelField() {
		CheckedException ex = assertThrows(CheckedException.class,
				() -> PolicyValidator.validateAndParse("""
						{
						  "schemaVersion": 1,
						  "templateCode": "MODEL_ONLY",
						  "subjectMode": "CALLER",
						  "iamUnavailableBehavior": "ALLOW",
						  "allowModelOnly": true,
						  "spelExpression": "#{T(java.lang.Runtime).getRuntime()}"
						}
						"""));

		assertTrue(ex.getMessage().contains("spelExpression"), "错误消息应精确定位未知字段: " + ex.getMessage());
	}

	@Test
	@DisplayName("规则内未知字段拒绝（严格模式，禁止 SpEL 等扩展字段）")
	void validateAndParseShouldRejectUnknownRuleField() {
		CheckedException ex = assertThrows(CheckedException.class,
				() -> PolicyValidator.validateAndParse("""
						{
						  "schemaVersion": 1,
						  "templateCode": "T",
						  "subjectMode": "CALLER",
						  "iamUnavailableBehavior": "ALLOW",
						  "allowModelOnly": false,
						  "rules": [
						    {
						      "name": "r1",
						      "effect": "ALLOW",
						      "condition": "#{subject.isAdmin()}"
						    }
						  ]
						}
						"""));

		assertTrue(ex.getMessage().contains("condition"), "错误消息应精确定位规则内未知字段: " + ex.getMessage());
	}

	@Test
	@DisplayName("schemaVersion 不等于 1 拒绝")
	void validateAndParseShouldRejectUnsupportedSchemaVersion() {
		CheckedException ex = assertThrows(CheckedException.class,
				() -> PolicyValidator.validateAndParse("""
						{
						  "schemaVersion": 2,
						  "templateCode": "T",
						  "subjectMode": "CALLER",
						  "iamUnavailableBehavior": "ALLOW",
						  "allowModelOnly": true
						}
						"""));

		assertTrue(ex.getMessage().contains("schemaVersion"), ex.getMessage());
	}

	@Test
	@DisplayName("schemaVersion 缺失拒绝")
	void validateAndParseShouldRejectMissingSchemaVersion() {
		assertThrows(CheckedException.class, () -> PolicyValidator.validateAndParse("""
				{
				  "templateCode": "T",
				  "subjectMode": "CALLER",
				  "iamUnavailableBehavior": "ALLOW",
				  "allowModelOnly": true
				}
				"""));
	}

	@Test
	@DisplayName("空 templateCode 拒绝")
	void validateAndParseShouldRejectBlankTemplateCode() {
		CheckedException ex = assertThrows(CheckedException.class,
				() -> PolicyValidator.validateAndParse("""
						{
						  "schemaVersion": 1,
						  "templateCode": "",
						  "subjectMode": "CALLER",
						  "iamUnavailableBehavior": "ALLOW",
						  "allowModelOnly": true
						}
						"""));

		assertTrue(ex.getMessage().contains("templateCode"), ex.getMessage());
	}

	@Test
	@DisplayName("subjectMode 缺失或非法值拒绝")
	void validateAndParseShouldRejectInvalidSubjectMode() {
		String missing = """
				{
				  "schemaVersion": 1,
				  "templateCode": "T",
				  "iamUnavailableBehavior": "ALLOW",
				  "allowModelOnly": true
				}
				""";
		assertThrows(CheckedException.class, () -> PolicyValidator.validateAndParse(missing));

		String illegal = missing.replace("\"iamUnavailableBehavior\"", "\"subjectMode\": \"ROOT\", \"iamUnavailableBehavior\"");
		CheckedException ex = assertThrows(CheckedException.class, () -> PolicyValidator.validateAndParse(illegal));
		assertTrue(ex.getMessage().contains("subjectMode"), ex.getMessage());
	}

	@Test
	@DisplayName("iamUnavailableBehavior 非法值拒绝")
	void validateAndParseShouldRejectInvalidIamUnavailableBehavior() {
		CheckedException ex = assertThrows(CheckedException.class,
				() -> PolicyValidator.validateAndParse("""
						{
						  "schemaVersion": 1,
						  "templateCode": "T",
						  "subjectMode": "CALLER",
						  "iamUnavailableBehavior": "MAYBE",
						  "allowModelOnly": true
						}
						"""));

		assertTrue(ex.getMessage().contains("iamUnavailableBehavior"), ex.getMessage());
	}

	@Test
	@DisplayName("allowModelOnly 缺失或类型错误拒绝")
	void validateAndParseShouldRejectInvalidAllowModelOnly() {
		assertThrows(CheckedException.class, () -> PolicyValidator.validateAndParse("""
				{
				  "schemaVersion": 1,
				  "templateCode": "T",
				  "subjectMode": "CALLER",
				  "iamUnavailableBehavior": "ALLOW"
				}
				"""));

		assertThrows(CheckedException.class, () -> PolicyValidator.validateAndParse("""
				{
				  "schemaVersion": 1,
				  "templateCode": "T",
				  "subjectMode": "CALLER",
				  "iamUnavailableBehavior": "ALLOW",
				  "allowModelOnly": "yes"
				}
				"""));
	}

	@Test
	@DisplayName("规则缺 name 或 name 为空白拒绝")
	void validateAndParseShouldRejectRuleWithoutName() {
		String missing = """
				{
				  "schemaVersion": 1,
				  "templateCode": "T",
				  "subjectMode": "CALLER",
				  "iamUnavailableBehavior": "ALLOW",
				  "allowModelOnly": false,
				  "rules": [
				    {
				      "effect": "ALLOW"
				    }
				  ]
				}
				""";
		assertThrows(CheckedException.class, () -> PolicyValidator.validateAndParse(missing));

		String blank = missing.replace("\"effect\": \"ALLOW\"", "\"name\": \"  \", \"effect\": \"ALLOW\"");
		assertThrows(CheckedException.class, () -> PolicyValidator.validateAndParse(blank));
	}

	@Test
	@DisplayName("规则 effect 非法值拒绝")
	void validateAndParseShouldRejectInvalidRuleEffect() {
		CheckedException ex = assertThrows(CheckedException.class,
				() -> PolicyValidator.validateAndParse("""
						{
						  "schemaVersion": 1,
						  "templateCode": "T",
						  "subjectMode": "CALLER",
						  "iamUnavailableBehavior": "ALLOW",
						  "allowModelOnly": false,
						  "rules": [
						    {
						      "name": "r1",
						      "effect": "PERMIT"
						    }
						  ]
						}
						"""));

		assertTrue(ex.getMessage().contains("effect"), ex.getMessage());
	}

	@Test
	@DisplayName("规则 actions/obligations 含未知枚举值拒绝且精确定位字段")
	void validateAndParseShouldRejectInvalidEnumArrayValue() {
		CheckedException actionEx = assertThrows(CheckedException.class,
				() -> PolicyValidator.validateAndParse("""
						{
						  "schemaVersion": 1,
						  "templateCode": "T",
						  "subjectMode": "CALLER",
						  "iamUnavailableBehavior": "ALLOW",
						  "allowModelOnly": false,
						  "rules": [
						    {
						      "name": "r1",
						      "effect": "ALLOW",
						      "actions": ["READ", "DELETE_ALL"]
						    }
						  ]
						}
						"""));
		assertTrue(actionEx.getMessage().contains("actions"), actionEx.getMessage());

		CheckedException obligationEx = assertThrows(CheckedException.class,
				() -> PolicyValidator.validateAndParse("""
						{
						  "schemaVersion": 1,
						  "templateCode": "T",
						  "subjectMode": "CALLER",
						  "iamUnavailableBehavior": "ALLOW",
						  "allowModelOnly": false,
						  "rules": [
						    {
						      "name": "r1",
						      "effect": "ALLOW",
						      "obligations": ["SUDO"]
						    }
						  ]
						}
						"""));
		assertTrue(obligationEx.getMessage().contains("obligations"), obligationEx.getMessage());
	}

	@Test
	@DisplayName("空串、非 JSON、非对象根均拒绝")
	void validateAndParseShouldRejectMalformedJson() {
		assertThrows(CheckedException.class, () -> PolicyValidator.validateAndParse(null));
		assertThrows(CheckedException.class, () -> PolicyValidator.validateAndParse("   "));
		assertThrows(CheckedException.class, () -> PolicyValidator.validateAndParse("not-a-json"));
		assertThrows(CheckedException.class, () -> PolicyValidator.validateAndParse("[1, 2, 3]"));
	}
}
