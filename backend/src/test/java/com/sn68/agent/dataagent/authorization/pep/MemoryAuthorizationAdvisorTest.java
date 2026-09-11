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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.authorization.model.AuthorizationAction;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationDecision;
import com.sn68.agent.dataagent.authorization.pdp.DecisionReasonCode;
import com.sn68.agent.dataagent.authorization.pdp.SubjectKind;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 记忆读写 PDP 拦截入口聚焦单测（PR-3c 交付物 6：SHADOW quiet / ENFORCE fail-closed）。
 *
 * @author James (PR-3c PEP 内核扩展)
 */
class MemoryAuthorizationAdvisorTest {

	private RuntimePolicyEvaluator runtimePolicyEvaluator;

	private ShadowRecorder shadowRecorder;

	private MemoryAuthorizationAdvisor advisor;

	@BeforeEach
	void setUp() {
		runtimePolicyEvaluator = mock(RuntimePolicyEvaluator.class);
		shadowRecorder = mock(ShadowRecorder.class);
		PepAuthorizationProperties properties = new PepAuthorizationProperties();
		// 租户 999 灰度 ENFORCE（其余租户默认 SHADOW）
		properties.getEnforceTenantIds().add("999");
		advisor = new MemoryAuthorizationAdvisor(runtimePolicyEvaluator, shadowRecorder, properties);
	}

	@Test
	void shadowEvaluationFailureIsQuietAndReturnsNull() {
		when(runtimePolicyEvaluator.evaluate(any())).thenThrow(new IllegalStateException("pdp down"));

		PepDecisionResult result = advisor.checkMemoryAccess(readContext(), Boolean.TRUE);

		assertNull(result);
	}

	@Test
	void enforceEvaluationFailureFailsClosed() {
		when(runtimePolicyEvaluator.evaluate(any())).thenThrow(new IllegalStateException("pdp down"));

		assertThrows(IllegalStateException.class,
				() -> advisor.checkMemoryAccess(enforceContext(), Boolean.TRUE));
	}

	@Test
	void shadowDenyRecordsWithoutBlocking() {
		PepDecisionResult denied = resultOf(PepAuthorizationMode.SHADOW, false);
		when(runtimePolicyEvaluator.evaluate(any())).thenReturn(denied);

		PepDecisionResult result = advisor.checkMemoryAccess(readContext(), Boolean.TRUE);

		assertEquals(denied, result);
		verify(shadowRecorder).recordShadowDecision(denied, MemoryAuthorizationAdvisor.SCENE_MEMORY_READ,
				Boolean.TRUE, 1L, null, null);
	}

	@Test
	void writeActionUsesWriteScene() {
		PepDecisionResult allowed = resultOf(PepAuthorizationMode.SHADOW, true);
		when(runtimePolicyEvaluator.evaluate(any())).thenReturn(allowed);
		PepDecisionContext context = PepDecisionContext.builder()
			.tenantId("1")
			.ownerType(AuthorizationOwnerType.DATA_AGENT)
			.ownerId(10L)
			.subjectKind(SubjectKind.CALLER)
			.subjectId("user-1")
			.action(AuthorizationAction.WRITE_MEMORY)
			.build();

		advisor.checkMemoryAccess(context, Boolean.TRUE);

		verify(shadowRecorder).recordShadowDecision(allowed, MemoryAuthorizationAdvisor.SCENE_MEMORY_WRITE,
				Boolean.TRUE, 1L, null, null);
	}

	@Test
	void enforceDenyThrowsWithBusinessDeniedSemantics() {
		PepDecisionResult denied = resultOf(PepAuthorizationMode.ENFORCE, false);
		when(runtimePolicyEvaluator.evaluate(any())).thenReturn(denied);

		CheckedException ex = assertThrows(CheckedException.class,
				() -> advisor.checkMemoryAccess(enforceContext(), Boolean.TRUE));

		assertTrue(ex.getMessage().contains("BUSINESS_DENIED"));
		assertTrue(ex.getMessage().contains(DecisionReasonCode.POLICY_DENIED.name()));
	}

	@Test
	void decisionBranchFollowsEntryResolvedModeInsteadOfResultMode() {
		// 口径统一：生效模式入口只解析一次，拒绝分支按入口口径而非评估结果携带的 effectiveMode
		PepDecisionResult denied = resultOf(PepAuthorizationMode.SHADOW, false);
		when(runtimePolicyEvaluator.evaluate(any())).thenReturn(denied);

		CheckedException ex = assertThrows(CheckedException.class,
				() -> advisor.checkMemoryAccess(enforceContext(), Boolean.TRUE));

		assertTrue(ex.getMessage().contains("BUSINESS_DENIED"));
		// 影子记录仍照常发生（记录行为不因口径统一而丢失）
		verify(shadowRecorder).recordShadowDecision(denied, MemoryAuthorizationAdvisor.SCENE_MEMORY_READ,
				Boolean.TRUE, 999L, null, null);
	}

	@Test
	void enforceAllowPassesThrough() {
		PepDecisionResult allowed = resultOf(PepAuthorizationMode.ENFORCE, true);
		when(runtimePolicyEvaluator.evaluate(any())).thenReturn(allowed);

		PepDecisionResult result = advisor.checkMemoryAccess(enforceContext(), Boolean.TRUE);

		assertEquals(allowed, result);
	}

	private PepDecisionContext readContext() {
		return PepDecisionContext.builder()
			.tenantId("1")
			.ownerType(AuthorizationOwnerType.DATA_AGENT)
			.ownerId(10L)
			.subjectKind(SubjectKind.CALLER)
			.subjectId("user-1")
			.action(AuthorizationAction.READ_MEMORY)
			.build();
	}

	private PepDecisionContext enforceContext() {
		return PepDecisionContext.builder()
			.tenantId("999")
			.ownerType(AuthorizationOwnerType.DATA_AGENT)
			.ownerId(10L)
			.subjectKind(SubjectKind.CALLER)
			.subjectId("user-1")
			.action(AuthorizationAction.READ_MEMORY)
			.build();
	}

	private PepDecisionResult resultOf(PepAuthorizationMode mode, boolean allowed) {
		return PepDecisionResult.builder()
			.decisionId("dec-" + System.nanoTime())
			.subjectKind(SubjectKind.CALLER)
			.decision(AuthorizationDecision.builder()
				.allowed(allowed)
				.reasonCode(allowed ? DecisionReasonCode.POLICY_ALLOWED : DecisionReasonCode.POLICY_DENIED)
				.obligations(List.of())
				.maskFields(List.of())
				.policyHash("hash-1")
				.evaluatedAt(Instant.now())
				.build())
			.effectiveMode(mode)
			.build();
	}

}
