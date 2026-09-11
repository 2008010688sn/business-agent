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
package com.sn68.agent.dataagent.agentscope.runtime;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.authorization.dto.AuthorizationSubjectSnapshot;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.dataagent.authorization.pep.InvocationSubjectGuard;
import com.sn68.agent.dataagent.authorization.pep.PepAuthorizationMode;
import com.sn68.agent.dataagent.authorization.pep.PepAuthorizationProperties;
import com.sn68.agent.dataagent.authorization.pep.PepDecisionContext;
import com.sn68.agent.dataagent.authorization.pep.PepDecisionResult;
import com.sn68.agent.dataagent.authorization.pep.RuntimePolicyEvaluator;
import com.sn68.agent.dataagent.authorization.pep.ShadowRecorder;
import com.sn68.agent.dataagent.authorization.service.OwnerAuthorizationQueryGateway;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationDecision;
import com.sn68.agent.dataagent.authorization.pdp.DecisionReasonCode;
import com.sn68.agent.dataagent.authorization.pdp.SubjectKind;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话入口 USE 判定守卫测试（PR-4 接线点 1/2）。
 *
 * <p>SHADOW/ENFORCE 双模式矩阵：SHADOW 只记录不拦截（评估失败 quiet 吞掉、现网行为零变化）；
 * ENFORCE 下 PDP deny / 空主体 / X-Auto-Token 均抛 {@link CheckedException}（BUSINESS_DENIED）。</p>
 *
 * @author Leo (PR-4 遗留接线：对话入口 USE 判定)
 */
class ConversationAuthorizationGuardTest {

	/** ENFORCE 灰度白名单租户。 */
	private static final String ENFORCE_TENANT = "1001";

	/** 非白名单租户（SHADOW）。 */
	private static final String SHADOW_TENANT = "2002";

	private final RuntimePolicyEvaluator runtimePolicyEvaluator = mock(RuntimePolicyEvaluator.class);

	private final ShadowRecorder shadowRecorder = mock(ShadowRecorder.class);

	private final PepAuthorizationProperties pepProperties = new PepAuthorizationProperties();

	private final AuthenticationContext authenticationContext = mock(AuthenticationContext.class);

	private final OwnerAuthorizationQueryGateway ownerQueryGateway = mock(OwnerAuthorizationQueryGateway.class);

	private final ConversationAuthorizationGuard guard = new ConversationAuthorizationGuard(runtimePolicyEvaluator,
			shadowRecorder, new InvocationSubjectGuard(), pepProperties, authenticationContext, ownerQueryGateway);

	@BeforeEach
	void setUp() {
		pepProperties.setEnforceTenantIds(List.of(ENFORCE_TENANT));
		when(ownerQueryGateway.canUse(any(AuthorizationOwnerType.class), any(), any(AuthorizationSubjectSnapshot.class)))
			.thenReturn(true);
	}

	@AfterEach
	void tearDown() {
		RequestContextHolder.resetRequestAttributes();
	}

	@Test
	void shadowModeRecordsDenyWithoutBlocking() {
		when(runtimePolicyEvaluator.evaluate(any(PepDecisionContext.class)))
			.thenReturn(decision(false, PepAuthorizationMode.SHADOW));

		assertDoesNotThrow(() -> guard.authorizeConversationUse(request(SHADOW_TENANT)));

		// Legacy USE=true：SHADOW deny 即 MISMATCHED，供 PR-10 差异比对
		verify(shadowRecorder).recordShadowDecision(any(PepDecisionResult.class),
				eq(ConversationAuthorizationGuard.SCENE_CONVERSATION_USE), eq(Boolean.TRUE), eq(2002L), isNull(),
				isNull());
	}

	@Test
	void enforceModeRejectsDenyDecision() {
		when(runtimePolicyEvaluator.evaluate(any(PepDecisionContext.class)))
			.thenReturn(decision(false, PepAuthorizationMode.ENFORCE));

		assertThrows(CheckedException.class, () -> guard.authorizeConversationUse(request(ENFORCE_TENANT)));

		// ENFORCE deny 仍记录 Legacy USE（默认 true），差异率按真实对账，不改写 original
		verify(shadowRecorder).recordShadowDecision(any(PepDecisionResult.class),
				eq(ConversationAuthorizationGuard.SCENE_CONVERSATION_USE), eq(Boolean.TRUE), eq(1001L), isNull(),
				isNull());
	}

	@Test
	void legacyDenyRecordsOriginalFalse() {
		when(runtimePolicyEvaluator.evaluate(any(PepDecisionContext.class)))
			.thenReturn(decision(false, PepAuthorizationMode.SHADOW));
		when(ownerQueryGateway.canUse(any(AuthorizationOwnerType.class), any(), any(AuthorizationSubjectSnapshot.class)))
			.thenReturn(false);

		assertDoesNotThrow(() -> guard.authorizeConversationUse(request(SHADOW_TENANT)));

		verify(shadowRecorder).recordShadowDecision(any(PepDecisionResult.class),
				eq(ConversationAuthorizationGuard.SCENE_CONVERSATION_USE), eq(Boolean.FALSE), eq(2002L), isNull(),
				isNull());
	}

	@Test
	void shadowModeQuietlySkipsEvaluatorFailure() {
		when(runtimePolicyEvaluator.evaluate(any(PepDecisionContext.class)))
			.thenThrow(new IllegalStateException("pdp unavailable"));

		assertDoesNotThrow(() -> guard.authorizeConversationUse(request(SHADOW_TENANT)));

		verify(shadowRecorder, never()).recordShadowDecision(any(), anyString(), any(), any(), any(), any());
	}

	@Test
	void enforceModeRejectsMissingSubject() {
		AgentRequest request = request(ENFORCE_TENANT);
		request.setUserIdSnapshot(null);

		// ENFORCE 空主体 fail-closed：主体不完备直接拒绝，不进入 PDP 求值
		assertThrows(CheckedException.class, () -> guard.authorizeConversationUse(request));
		verify(runtimePolicyEvaluator, never()).evaluate(any());
	}

	@Test
	void allowDecisionRecordedInShadow() {
		when(runtimePolicyEvaluator.evaluate(any(PepDecisionContext.class)))
			.thenReturn(decision(true, PepAuthorizationMode.SHADOW));

		assertDoesNotThrow(() -> guard.authorizeConversationUse(request(SHADOW_TENANT)));

		verify(shadowRecorder).recordShadowDecision(any(PepDecisionResult.class),
				eq(ConversationAuthorizationGuard.SCENE_CONVERSATION_USE), eq(Boolean.TRUE), eq(2002L), isNull(),
				isNull());
	}

	@Test
	void enforceModeRejectsAutoTokenHeader() {
		MockHttpServletRequest servletRequest = new MockHttpServletRequest();
		servletRequest.addHeader("X-Auto-Token", "auto-token-1");
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));

		assertThrows(CheckedException.class, () -> guard.authorizeConversationUse(request(ENFORCE_TENANT)));

		verify(shadowRecorder).recordShadowDecision(any(PepDecisionResult.class),
				eq(ConversationAuthorizationGuard.SCENE_AUTO_TOKEN), eq(Boolean.FALSE), eq(1001L), isNull(), isNull());
		verify(runtimePolicyEvaluator, never()).evaluate(any());
	}

	@Test
	void shadowModeRecordsAutoTokenWithoutBlocking() {
		MockHttpServletRequest servletRequest = new MockHttpServletRequest();
		servletRequest.addHeader("X-Auto-Token", "auto-token-1");
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));
		when(runtimePolicyEvaluator.evaluate(any(PepDecisionContext.class)))
			.thenReturn(decision(true, PepAuthorizationMode.SHADOW));

		assertDoesNotThrow(() -> guard.authorizeConversationUse(request(SHADOW_TENANT)));

		verify(shadowRecorder).recordShadowDecision(any(PepDecisionResult.class),
				eq(ConversationAuthorizationGuard.SCENE_AUTO_TOKEN), eq(Boolean.TRUE), eq(2002L), isNull(), isNull());
	}

	@Test
	void employeeOwnedConversationWithRealUserDerivesCallerSubject() {
		// 评审低-1：真人发起的 employeeOwned 对话主体是 CALLER（kind 与 id 语义对齐，PDP 双锚）
		when(runtimePolicyEvaluator.evaluate(any(PepDecisionContext.class)))
			.thenReturn(decision(true, PepAuthorizationMode.SHADOW));
		AgentRequest request = request(SHADOW_TENANT);
		request.setOwnerType("DIGITAL_EMPLOYEE");
		request.setOwnerId(20L);

		assertDoesNotThrow(() -> guard.authorizeConversationUse(request));

		ArgumentCaptor<PepDecisionContext> captor = ArgumentCaptor.forClass(PepDecisionContext.class);
		verify(runtimePolicyEvaluator).evaluate(captor.capture());
		assertEquals(SubjectKind.CALLER, captor.getValue().getSubjectKind());
		assertEquals("u1", captor.getValue().getSubjectId());
	}

	@Test
	void employeeOwnedConversationWithoutRealUserDerivesEmployeeSubject() {
		// 仅服务身份（无真人快照）：主体是员工自身 DIGITAL_EMPLOYEE
		when(runtimePolicyEvaluator.evaluate(any(PepDecisionContext.class)))
			.thenReturn(decision(true, PepAuthorizationMode.SHADOW));
		when(authenticationContext.anonymous()).thenReturn(true);
		AgentRequest request = request(SHADOW_TENANT);
		request.setUserIdSnapshot(null);
		request.setOwnerType("DIGITAL_EMPLOYEE");
		request.setOwnerId(20L);

		assertDoesNotThrow(() -> guard.authorizeConversationUse(request));

		ArgumentCaptor<PepDecisionContext> captor = ArgumentCaptor.forClass(PepDecisionContext.class);
		verify(runtimePolicyEvaluator).evaluate(captor.capture());
		assertEquals(SubjectKind.DIGITAL_EMPLOYEE, captor.getValue().getSubjectKind());
		assertEquals("20", captor.getValue().getSubjectId());
	}

	@Test
	void employeeOwnedConversationWithServicePrincipalIdDerivesEmployeeSubject() {
		when(runtimePolicyEvaluator.evaluate(any(PepDecisionContext.class)))
			.thenReturn(decision(true, PepAuthorizationMode.SHADOW));
		AgentRequest request = request(SHADOW_TENANT);
		request.setUserIdSnapshot("sp_abc");
		request.setOwnerType("DIGITAL_EMPLOYEE");
		request.setOwnerId(20L);

		assertDoesNotThrow(() -> guard.authorizeConversationUse(request));

		ArgumentCaptor<PepDecisionContext> captor = ArgumentCaptor.forClass(PepDecisionContext.class);
		verify(runtimePolicyEvaluator).evaluate(captor.capture());
		assertEquals(SubjectKind.DIGITAL_EMPLOYEE, captor.getValue().getSubjectKind());
		assertEquals("sp_abc", captor.getValue().getSubjectId());
	}

	private AgentRequest request(String tenantId) {
		AgentRequest request = new AgentRequest();
		request.setAgentId("1");
		request.setThreadId("thread-1");
		request.setUserIdSnapshot("u1");
		request.setTenantIdSnapshot(tenantId);
		return request;
	}

	private PepDecisionResult decision(boolean allowed, PepAuthorizationMode mode) {
		return PepDecisionResult.builder()
			.decisionId("dec-" + System.nanoTime())
			.subjectKind(SubjectKind.CALLER)
			.decision(AuthorizationDecision.builder()
				.allowed(allowed)
				.reasonCode(allowed ? DecisionReasonCode.POLICY_ALLOWED : DecisionReasonCode.POLICY_DENIED)
				.obligations(List.of())
				.maskFields(List.of())
				.evaluatedAt(Instant.now())
				.build())
			.effectiveMode(mode)
			.evaluatedAt(Instant.now())
			.build();
	}

}
