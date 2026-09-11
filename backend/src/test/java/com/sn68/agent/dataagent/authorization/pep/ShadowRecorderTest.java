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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.authorization.observability.AuthorizationMetrics;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationDecision;
import com.sn68.agent.dataagent.authorization.pdp.DecisionReasonCode;
import com.sn68.agent.dataagent.authorization.pdp.SubjectKind;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeEventType;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeEventMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 授权决策影子记录器聚焦单测（PR-3c 交付物 2：审计写入 quiet 失败不破坏主流程）。
 *
 * @author James (PR-3c PEP 内核扩展)
 */
class ShadowRecorderTest {

	private AgentRuntimeEventMapper eventMapper;

	private ShadowRecorder recorder;

	@BeforeEach
	void setUp() {
		eventMapper = mock(AgentRuntimeEventMapper.class);
		recorder = new ShadowRecorder(eventMapper, new ObjectMapper(), AuthorizationMetrics.noop());
	}

	@Test
	void recordPersistsAuditColumnsAndDetailedLog() {
		PepDecisionResult result = PepDecisionResult.builder()
			.decisionId("dec-1")
			.subjectKind(SubjectKind.CALLER)
			.decision(AuthorizationDecision.builder()
				.allowed(false)
				.reasonCode(DecisionReasonCode.MISSING_POLICY)
				.obligations(List.of())
				.maskFields(List.of())
				.policyHash(null)
				.evaluatedAt(Instant.now())
				.build())
			.effectiveMode(PepAuthorizationMode.SHADOW)
			.build();

		recorder.recordShadowDecision(result, "HOOK_EXECUTE", Boolean.TRUE, 1L, 100L, "step-1");

		verify(eventMapper).appendAuthorizationDecision(eq("1"), eq(100L), eq("authz-decision-dec-1"),
				eq(RuntimeEventType.AUTHORIZATION_DECISION.getValue()), eq("step-1"), contains("dec-1"),
				eq("dec-1"), eq(null), eq(SubjectKind.CALLER.getCode()),
				eq(DecisionReasonCode.MISSING_POLICY.name()), any(Instant.class));
	}

	@Test
	void recordSwallowsMapperFailureQuietly() {
		when(eventMapper.appendAuthorizationDecision(any(), any(), anyString(), anyString(), any(), any(),
				anyString(), any(), anyString(), anyString(), any()))
			.thenThrow(new IllegalStateException("db down"));
		PepDecisionResult result = resultOf(PepAuthorizationMode.SHADOW, true, "hash-1");

		assertDoesNotThrow(() -> recorder.recordShadowDecision(result, "MEMORY_READ", Boolean.TRUE, 1L, 100L,
				null));
	}

	@Test
	void missingRunIdDegradesToStructuredLogWithoutInsert() {
		PepDecisionResult result = resultOf(PepAuthorizationMode.SHADOW, true, "hash-1");

		recorder.recordShadowDecision(result, "MEMORY_WRITE", Boolean.FALSE, null, null, null);

		verify(eventMapper, never()).appendAuthorizationDecision(any(), any(), anyString(), anyString(), any(),
				any(), anyString(), any(), anyString(), anyString(), any());
	}

	@Test
	void nullTenantFallsBackToZero() {
		PepDecisionResult result = resultOf(PepAuthorizationMode.ENFORCE, true, "hash-1");

		recorder.recordShadowDecision(result, "TASK_START", Boolean.TRUE, null, 200L, null);

		verify(eventMapper).appendAuthorizationDecision(eq("0"), eq(200L), anyString(), anyString(), any(),
				any(), anyString(), anyString(), any(), any(), any(Instant.class));
	}

	@Test
	void nullResultIsIgnored() {
		recorder.recordShadowDecision(null, "HOOK_EXECUTE", Boolean.TRUE, 1L, 100L, null);
		verify(eventMapper, never()).appendAuthorizationDecision(any(), any(), anyString(), anyString(), any(),
				any(), anyString(), any(), any(), any(), any());
	}

	@Test
	void detailedLogCarriesComparisonFields() throws Exception {
		PepDecisionResult result = resultOf(PepAuthorizationMode.SHADOW, false, "hash-9");

		recorder.recordShadowDecision(result, "HOOK_EXECUTE", Boolean.TRUE, 1L, 100L, "s");

		ArgumentCaptor<String> logCaptor = ArgumentCaptor.forClass(String.class);
		verify(eventMapper).appendAuthorizationDecision(any(), any(), anyString(), anyString(), any(),
				logCaptor.capture(), anyString(), anyString(), any(), any(), any(Instant.class));
		com.fasterxml.jackson.databind.JsonNode node = new ObjectMapper().readTree(logCaptor.getValue());
		assertTrue(node.get("decisionId").asText().startsWith("dec-"));
		assertTrue(node.has("scene"));
		assertTrue(node.has("originalDecision"));
		assertTrue(node.has("shadowDecision"));
		assertTrue(node.has("comparisonStatus"));
		assertTrue(node.has("mode"));
	}

	@Test
	void recordObligationSkippedPersistsIndependentEventRow() {
		PepDecisionResult result = resultOf(PepAuthorizationMode.SHADOW, true, "hash-1");

		recorder.recordObligationSkipped(result, "OUTPUT_OBLIGATION", "MASK_FIELDS", "UNPARSEABLE_OUTPUT", 1L,
				100L, "step-1");

		// 独立幂等键前缀（不与主决策影子行 authz-decision- 冲突）+ 审计四列同口径落库
		verify(eventMapper).appendAuthorizationDecision(eq("1"), eq(100L), contains("authz-obligation-skipped-dec-"),
				eq(RuntimeEventType.AUTHORIZATION_DECISION.getValue()), eq("step-1"), contains("obligationSkipped"),
				anyString(), eq("hash-1"), any(), anyString(), any(Instant.class));
	}

	@Test
	void obligationSkippedLogCarriesSkipFieldsWithoutComparison() throws Exception {
		PepDecisionResult result = resultOf(PepAuthorizationMode.SHADOW, true, "hash-1");

		recorder.recordObligationSkipped(result, "OUTPUT_OBLIGATION", "FILTER_FIELDS", "UNPARSEABLE_OUTPUT", 1L,
				100L, null);

		// 明细只携带跳过定位字段，不携带比对字段（义务跳过不是 allow/deny 比对事件，不参与差异率分母）
		ArgumentCaptor<String> logCaptor = ArgumentCaptor.forClass(String.class);
		verify(eventMapper).appendAuthorizationDecision(any(), any(), anyString(), anyString(), any(),
				logCaptor.capture(), anyString(), any(), any(), any(), any(Instant.class));
		com.fasterxml.jackson.databind.JsonNode node = new ObjectMapper().readTree(logCaptor.getValue());
		assertTrue(node.get("obligationSkipped").asBoolean());
		assertEquals("FILTER_FIELDS", node.get("skippedObligation").asText());
		assertEquals("UNPARSEABLE_OUTPUT", node.get("skipReason").asText());
		assertTrue(node.get("comparisonStatus") == null && node.get("originalDecision") == null);
	}

	@Test
	void obligationSkippedWithoutRunIdDegradesToLog() {
		PepDecisionResult result = resultOf(PepAuthorizationMode.SHADOW, true, "hash-1");

		recorder.recordObligationSkipped(result, "OUTPUT_OBLIGATION", "MASK_FIELDS", "UNPARSEABLE_OUTPUT", 1L,
				null, null);

		verify(eventMapper, never()).appendAuthorizationDecision(any(), any(), anyString(), anyString(), any(),
				any(), anyString(), any(), any(), any(), any());
	}

	@Test
	void obligationSkippedNullResultAndMapperFailureQuiet() {
		recorder.recordObligationSkipped(null, "OUTPUT_OBLIGATION", "MASK_FIELDS", "UNPARSEABLE_OUTPUT", 1L, 100L,
				null);
		verify(eventMapper, never()).appendAuthorizationDecision(any(), any(), anyString(), anyString(), any(),
				any(), anyString(), any(), any(), any(), any());

		when(eventMapper.appendAuthorizationDecision(any(), any(), anyString(), anyString(), any(), any(),
				anyString(), any(), anyString(), anyString(), any()))
			.thenThrow(new IllegalStateException("db down"));
		PepDecisionResult result = resultOf(PepAuthorizationMode.SHADOW, true, "hash-1");
		assertDoesNotThrow(() -> recorder.recordObligationSkipped(result, "OUTPUT_OBLIGATION", "MASK_FIELDS",
				"UNPARSEABLE_OUTPUT", 1L, 100L, null));
	}

	private PepDecisionResult resultOf(PepAuthorizationMode mode, boolean allowed, String policyHash) {
		return PepDecisionResult.builder()
			.decisionId("dec-" + System.nanoTime())
			.subjectKind(SubjectKind.DIGITAL_EMPLOYEE)
			.decision(AuthorizationDecision.builder()
				.allowed(allowed)
				.reasonCode(allowed ? DecisionReasonCode.POLICY_ALLOWED : DecisionReasonCode.POLICY_DENIED)
				.obligations(List.of())
				.maskFields(List.of())
				.policyHash(policyHash)
				.evaluatedAt(Instant.now())
				.build())
			.effectiveMode(mode)
			.build();
	}

}
