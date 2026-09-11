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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.authorization.dto.AuthorizationSubjectSnapshot;
import com.sn68.agent.dataagent.authorization.model.AuthorizationAction;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationDecision;
import com.sn68.agent.dataagent.authorization.pdp.DecisionReasonCode;
import com.sn68.agent.dataagent.authorization.pdp.SubjectKind;
import com.sn68.agent.dataagent.authorization.service.OwnerAuthorizationQueryGateway;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 任务拉起授权守卫聚焦单测（PR-3c：SHADOW 记录 / ENFORCE fail-closed，original_decision 采样）。
 *
 * @author James (PR-3c PEP 内核扩展)
 */
class TaskAuthorizationGuardTest {

	private RuntimePolicyEvaluator runtimePolicyEvaluator;

	private ShadowRecorder shadowRecorder;

	private OwnerAuthorizationQueryGateway ownerQueryGateway;

	private TaskAuthorizationGuard guard;

	@BeforeEach
	void setUp() {
		runtimePolicyEvaluator = mock(RuntimePolicyEvaluator.class);
		shadowRecorder = mock(ShadowRecorder.class);
		ownerQueryGateway = mock(OwnerAuthorizationQueryGateway.class);
		PepAuthorizationProperties properties = new PepAuthorizationProperties();
		// 租户 999 灰度 ENFORCE（其余租户默认 SHADOW）
		properties.getEnforceTenantIds().add("999");
		guard = new TaskAuthorizationGuard(runtimePolicyEvaluator, new InvocationSubjectGuard(),
				shadowRecorder, ownerQueryGateway, properties);
	}

	@Test
	void shadowDenyRecordsComparisonWithoutBlocking() {
		AuthorizationSubjectSnapshot snapshot = mock(AuthorizationSubjectSnapshot.class);
		PepDecisionResult denied = resultOf(PepAuthorizationMode.SHADOW, false);
		when(runtimePolicyEvaluator.evaluate(any())).thenReturn(denied);
		when(ownerQueryGateway.canUse(AuthorizationOwnerType.DIGITAL_EMPLOYEE, 20L, snapshot))
			.thenReturn(Boolean.TRUE);

		PepDecisionResult result = guard.authorizeTaskStart(completeContext(), snapshot);

		assertEquals(denied, result);
		// original=TRUE vs shadow=FALSE：MISMATCHED 供灰度门禁观测
		verify(shadowRecorder).recordShadowDecision(denied, TaskAuthorizationGuard.SCENE_TASK_START,
				Boolean.TRUE, 1L, null, null);
	}

	@Test
	void enforceMissingSubjectFailsClosed() {
		CheckedException ex = assertThrows(CheckedException.class,
				() -> guard.authorizeTaskStart(emptyContext(), null));

		assertTrue(ex.getMessage().contains("BUSINESS_DENIED"));
		verify(runtimePolicyEvaluator, never()).evaluate(any());
	}

	@Test
	void enforceDenyRejected() {
		PepDecisionResult denied = resultOf(PepAuthorizationMode.ENFORCE, false);
		when(runtimePolicyEvaluator.evaluate(any())).thenReturn(denied);

		CheckedException ex = assertThrows(CheckedException.class,
				() -> guard.authorizeTaskStart(enforceContext(), null));

		assertTrue(ex.getMessage().contains("BUSINESS_DENIED"));
		assertTrue(ex.getMessage().contains(DecisionReasonCode.POLICY_DENIED.name()));
	}

	@Test
	void enforceAllowPassesThrough() {
		PepDecisionResult allowed = resultOf(PepAuthorizationMode.ENFORCE, true);
		when(runtimePolicyEvaluator.evaluate(any())).thenReturn(allowed);

		PepDecisionResult result = guard.authorizeTaskStart(enforceContext(), null);

		assertEquals(allowed, result);
	}

	@Test
	void shadowEvaluationFailureQuietlyReturnsNull() {
		when(runtimePolicyEvaluator.evaluate(any())).thenThrow(new IllegalStateException("pdp down"));

		PepDecisionResult result = guard.authorizeTaskStart(completeContext(), null);

		assertNull(result);
	}

	@Test
	void enforceEvaluationFailureFailsClosed() {
		when(runtimePolicyEvaluator.evaluate(any())).thenThrow(new IllegalStateException("pdp down"));

		assertThrows(IllegalStateException.class, () -> guard.authorizeTaskStart(enforceContext(), null));
	}

	@Test
	void nullSnapshotSkipsOriginalSampling() {
		PepDecisionResult allowed = resultOf(PepAuthorizationMode.SHADOW, true);
		when(runtimePolicyEvaluator.evaluate(any())).thenReturn(allowed);

		guard.authorizeTaskStart(completeContext(), null);

		verify(ownerQueryGateway, never()).canUse(any(), any(), any());
		verify(shadowRecorder).recordShadowDecision(allowed, TaskAuthorizationGuard.SCENE_TASK_START,
				null, 1L, null, null);
	}

	private PepDecisionContext completeContext() {
		return PepDecisionContext.builder()
			.tenantId("1")
			.ownerType(AuthorizationOwnerType.DIGITAL_EMPLOYEE)
			.ownerId(20L)
			.subjectKind(SubjectKind.DIGITAL_EMPLOYEE)
			.subjectId("emp-20")
			.action(AuthorizationAction.START_UNATTENDED)
			.build();
	}

	private PepDecisionContext enforceContext() {
		return PepDecisionContext.builder()
			.tenantId("999")
			.ownerType(AuthorizationOwnerType.DIGITAL_EMPLOYEE)
			.ownerId(20L)
			.subjectKind(SubjectKind.DIGITAL_EMPLOYEE)
			.subjectId("emp-20")
			.action(AuthorizationAction.START_UNATTENDED)
			.build();
	}

	private PepDecisionContext emptyContext() {
		return PepDecisionContext.builder()
			.tenantId("999")
			.ownerType(AuthorizationOwnerType.DIGITAL_EMPLOYEE)
			.ownerId(20L)
			.action(AuthorizationAction.START_UNATTENDED)
			.build();
	}

	private PepDecisionResult resultOf(PepAuthorizationMode mode, boolean allowed) {
		return PepDecisionResult.builder()
			.decisionId("dec-" + System.nanoTime())
			.subjectKind(SubjectKind.DIGITAL_EMPLOYEE)
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
