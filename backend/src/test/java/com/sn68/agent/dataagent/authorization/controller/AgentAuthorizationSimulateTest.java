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
package com.sn68.agent.dataagent.authorization.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.authorization.dto.AuthorizationDecisionResp;
import com.sn68.agent.dataagent.authorization.dto.AuthorizationDecisionSimulateReq;
import com.sn68.agent.dataagent.authorization.dto.AuthorizationRuleReq;
import com.sn68.agent.dataagent.authorization.model.AuthorizationAction;
import com.sn68.agent.dataagent.authorization.model.AuthorizationEffect;
import com.sn68.agent.dataagent.authorization.model.AuthorizationObligation;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationPolicyEvaluator;
import com.sn68.agent.dataagent.authorization.pdp.DecisionReasonCode;
import com.sn68.agent.dataagent.authorization.pdp.SubjectKind;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeEventMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

/**
 * {@link AgentAuthorizationController} simulate 接口单元测试（不启动 Spring 上下文）：
 * 覆盖 JSON 反序列化路径、policyJson/templateCode 二选一、规则覆盖与决策投影。
 *
 * @author Felix (PR-3a PDP 内核)
 */
class AgentAuthorizationSimulateTest {

	private final AgentAuthorizationController controller = new AgentAuthorizationController(
			new AuthorizationPolicyEvaluator(), mock(AgentRuntimeEventMapper.class), mock(AuthenticationContext.class));

	private final ObjectMapper objectMapper = new ObjectMapper();

	private static final String INLINE_POLICY_JSON = """
			{
			  "schemaVersion": 1,
			  "templateCode": "CUSTOM",
			  "subjectMode": "CALLER",
			  "iamUnavailableBehavior": "DENY",
			  "allowModelOnly": false,
			  "rules": [
			    {
			      "name": "deny-write",
			      "effect": "DENY",
			      "capabilityCodes": ["*"],
			      "actions": ["WRITE"]
			    },
			    {
			      "name": "allow-read",
			      "effect": "ALLOW",
			      "capabilityCodes": ["*"],
			      "actions": ["READ"],
			      "obligations": ["MASK_FIELDS"],
			      "maskFields": ["phone"]
			    }
			  ]
			}
			""";

	@Test
	@DisplayName("完整请求体 JSON 反序列化 → simulate → 读允许并透传 maskFields")
	void simulateShouldWorkThroughJsonDeserializationPath() throws Exception {
		String body = """
				{
				  "policyJson": %s,
				  "subjectKind": "CALLER",
				  "capabilityCode": "cap:order",
				  "action": "READ",
				  "iamAvailable": true
				}
				""".formatted(objectMapper.writeValueAsString(INLINE_POLICY_JSON));

		AuthorizationDecisionSimulateReq req = objectMapper.readValue(body, AuthorizationDecisionSimulateReq.class);
		assertEquals("cap:order", req.getCapabilityCode());
		assertEquals(SubjectKind.CALLER, req.getSubjectKind());
		assertEquals(AuthorizationAction.READ, req.getAction());

		AuthorizationDecisionResp resp = controller.simulate(req);

		assertTrue(resp.isAllowed());
		assertEquals(DecisionReasonCode.POLICY_ALLOWED, resp.getReasonCode());
		assertEquals(List.of(AuthorizationObligation.MASK_FIELDS), resp.getObligations());
		assertEquals(List.of("phone"), resp.getMaskFields());
		assertNotNull(resp.getPolicyHash());
		assertNotNull(resp.getEvaluatedAt());
	}

	@Test
	@DisplayName("policyJson 路径：WRITE 被 inline 策略首条 DENY 拒绝")
	void simulateShouldDenyWriteViaInlinePolicy() {
		AuthorizationDecisionSimulateReq req = new AuthorizationDecisionSimulateReq();
		req.setPolicyJson(INLINE_POLICY_JSON);
		req.setSubjectKind(SubjectKind.CALLER);
		req.setCapabilityCode("cap:order");
		req.setAction(AuthorizationAction.WRITE);

		AuthorizationDecisionResp resp = controller.simulate(req);

		assertFalse(resp.isAllowed());
		assertEquals(DecisionReasonCode.POLICY_DENIED, resp.getReasonCode());
	}

	@Test
	@DisplayName("templateCode 路径：DIGITAL_WORKER 写操作带 APPROVAL + MASK_FIELDS")
	void simulateShouldResolveDigitalWorkerTemplate() {
		AuthorizationDecisionSimulateReq req = new AuthorizationDecisionSimulateReq();
		req.setTemplateCode("DIGITAL_WORKER");
		req.setSubjectKind(SubjectKind.DIGITAL_EMPLOYEE);
		req.setCapabilityCode("cap:order");
		req.setAction(AuthorizationAction.WRITE);

		AuthorizationDecisionResp resp = controller.simulate(req);

		assertTrue(resp.isAllowed());
		assertTrue(resp.getObligations().contains(AuthorizationObligation.APPROVAL));
		assertTrue(resp.getObligations().contains(AuthorizationObligation.MASK_FIELDS));
	}

	@Test
	@DisplayName("templateCode + rules 覆盖：覆盖后按自定义规则求值")
	void simulateShouldApplyRuleOverridesOnTemplate() {
		AuthorizationRuleReq override = new AuthorizationRuleReq();
		override.setName("deny-all-write");
		override.setEffect(AuthorizationEffect.DENY);
		override.setCapabilityCodes(List.of("*"));
		override.setActions(List.of(AuthorizationAction.WRITE));

		AuthorizationDecisionSimulateReq req = new AuthorizationDecisionSimulateReq();
		req.setTemplateCode("CALLER_INTERACTIVE");
		req.setRules(List.of(override));
		req.setSubjectKind(SubjectKind.CALLER);
		req.setCapabilityCode("cap:order");
		req.setAction(AuthorizationAction.WRITE);

		// CALLER_INTERACTIVE 默认 WRITE 允许（带 APPROVAL），覆盖 DENY 后应拒绝
		AuthorizationDecisionResp resp = controller.simulate(req);

		assertFalse(resp.isAllowed());
		assertEquals(DecisionReasonCode.POLICY_DENIED, resp.getReasonCode());
	}

	@Test
	@DisplayName("iamAvailable 缺省按 true：不触发 IAM 分支")
	void simulateShouldDefaultIamAvailableToTrue() {
		AuthorizationDecisionSimulateReq req = new AuthorizationDecisionSimulateReq();
		req.setTemplateCode("CALLER_READ_ONLY");
		req.setSubjectKind(SubjectKind.CALLER);
		req.setCapabilityCode("cap:order");
		req.setAction(AuthorizationAction.READ);
		// iamAvailable 不设置

		AuthorizationDecisionResp resp = controller.simulate(req);

		assertTrue(resp.isAllowed());
		assertEquals(DecisionReasonCode.POLICY_ALLOWED, resp.getReasonCode());
	}

	@Test
	@DisplayName("iamAvailable=false 透传：DENY 模板返回 IAM_UNAVAILABLE_DENIED")
	void simulateShouldPassThroughIamUnavailable() {
		AuthorizationDecisionSimulateReq req = new AuthorizationDecisionSimulateReq();
		req.setTemplateCode("CALLER_READ_ONLY");
		req.setSubjectKind(SubjectKind.CALLER);
		req.setCapabilityCode("cap:order");
		req.setAction(AuthorizationAction.READ);
		req.setIamAvailable(false);

		AuthorizationDecisionResp resp = controller.simulate(req);

		assertFalse(resp.isAllowed());
		assertEquals(DecisionReasonCode.IAM_UNAVAILABLE_DENIED, resp.getReasonCode());
	}

	@Test
	@DisplayName("policyJson 与 templateCode 同时提供或同时缺失均拒绝")
	void simulateShouldEnforceMutuallyExclusivePolicySource() {
		AuthorizationDecisionSimulateReq both = new AuthorizationDecisionSimulateReq();
		both.setPolicyJson(INLINE_POLICY_JSON);
		both.setTemplateCode("MODEL_ONLY");
		both.setSubjectKind(SubjectKind.CALLER);
		both.setCapabilityCode("cap:order");
		both.setAction(AuthorizationAction.READ);

		CheckedException bothEx = assertThrows(CheckedException.class, () -> controller.simulate(both));
		assertTrue(bothEx.getMessage().contains("其一"), bothEx.getMessage());

		AuthorizationDecisionSimulateReq neither = new AuthorizationDecisionSimulateReq();
		neither.setSubjectKind(SubjectKind.CALLER);
		neither.setCapabilityCode("cap:order");
		neither.setAction(AuthorizationAction.READ);

		CheckedException neitherEx = assertThrows(CheckedException.class, () -> controller.simulate(neither));
		assertTrue(neitherEx.getMessage().contains("其一"), neitherEx.getMessage());
	}

	@Test
	@DisplayName("未知 templateCode 拒绝")
	void simulateShouldRejectUnknownTemplateCode() {
		AuthorizationDecisionSimulateReq req = new AuthorizationDecisionSimulateReq();
		req.setTemplateCode("NOT_A_TEMPLATE");
		req.setSubjectKind(SubjectKind.CALLER);
		req.setCapabilityCode("cap:order");
		req.setAction(AuthorizationAction.READ);

		CheckedException ex = assertThrows(CheckedException.class, () -> controller.simulate(req));
		assertTrue(ex.getMessage().contains("NOT_A_TEMPLATE"), ex.getMessage());
	}

	@Test
	@DisplayName("policyJson 路径透传策略校验错误（坏 JSON 直接被校验器拒绝）")
	void simulateShouldSurfacePolicyValidationErrors() {
		AuthorizationDecisionSimulateReq req = new AuthorizationDecisionSimulateReq();
		req.setPolicyJson("{\"schemaVersion\": 9}");
		req.setSubjectKind(SubjectKind.CALLER);
		req.setCapabilityCode("cap:order");
		req.setAction(AuthorizationAction.READ);

		CheckedException ex = assertThrows(CheckedException.class, () -> controller.simulate(req));
		assertTrue(ex.getMessage().contains("schemaVersion"), ex.getMessage());
	}
}
