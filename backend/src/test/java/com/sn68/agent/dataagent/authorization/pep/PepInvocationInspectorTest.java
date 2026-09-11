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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.authorization.model.AuthorizationAction;
import com.sn68.agent.dataagent.authorization.model.AuthorizationObligation;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationDecision;
import com.sn68.agent.dataagent.authorization.pdp.DecisionReasonCode;
import com.sn68.agent.dataagent.authorization.pdp.SubjectKind;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeInvocation;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeInvocationState;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeInvocationMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 能力网关 PEP 检查员聚焦单测（PR-3c：Hook 线三段时序 + 输出义务 + 主体推导 + 审计 quiet；
 * PR-4：ENFORCE deny 拦截点 enforceDenyDecision 接线语义）。
 *
 * @author James (PR-3c PEP 内核扩展、PR-4 ENFORCE 接线)
 */
class PepInvocationInspectorTest {

	private RuntimePolicyEvaluator runtimePolicyEvaluator;

	private ShadowRecorder shadowRecorder;

	private AgentRuntimeInvocationMapper invocationMapper;

	private PepInvocationInspector inspector;

	@BeforeEach
	void setUp() {
		runtimePolicyEvaluator = mock(RuntimePolicyEvaluator.class);
		shadowRecorder = mock(ShadowRecorder.class);
		invocationMapper = mock(AgentRuntimeInvocationMapper.class);
		PepAuthorizationProperties properties = new PepAuthorizationProperties();
		// 租户 999 灰度 ENFORCE（其余租户默认 SHADOW）
		properties.getEnforceTenantIds().add("999");
		inspector = new PepInvocationInspector(runtimePolicyEvaluator, new InvocationSubjectGuard(),
				shadowRecorder, new OutputObligationApplier(), properties, invocationMapper,
				new ObjectMapper());
	}

	@Test
	void shadowAuthorizeEvaluatesWithCallerSubjectAndReturnsResult() {
		when(runtimePolicyEvaluator.evaluate(any())).thenReturn(resultOf(PepAuthorizationMode.SHADOW, true));

		PepDecisionResult result = inspector.authorizeBeforeExecute(callerInput("1"));

		assertEquals(SubjectKind.CALLER, result.getSubjectKind());
		ArgumentCaptor<PepDecisionContext> captor = ArgumentCaptor.forClass(PepDecisionContext.class);
		verify(runtimePolicyEvaluator).evaluate(captor.capture());
		assertEquals(AuthorizationAction.EXECUTE, captor.getValue().getAction());
		assertEquals("tool-1", captor.getValue().getCapabilityCode());
		assertEquals(SubjectKind.CALLER, captor.getValue().getSubjectKind());
		assertEquals("user-1", captor.getValue().getSubjectId());
	}

	@Test
	void employeeOwnerWithoutUserIdDerivesEmployeeSubjectFromOwner() {
		when(runtimePolicyEvaluator.evaluate(any())).thenReturn(resultOf(PepAuthorizationMode.SHADOW, true));

		inspector.authorizeBeforeExecute(new PepInvocationInspector.HookDecisionInput("1", 100L, "s",
				"DIGITAL_EMPLOYEE", 20L, "tool-1", null, null, null));

		ArgumentCaptor<PepDecisionContext> captor = ArgumentCaptor.forClass(PepDecisionContext.class);
		verify(runtimePolicyEvaluator).evaluate(captor.capture());
		assertEquals(SubjectKind.DIGITAL_EMPLOYEE, captor.getValue().getSubjectKind());
		assertEquals("20", captor.getValue().getSubjectId());
	}

	@Test
	void enforceMissingSubjectFailsClosed() {
		// ENFORCE + 空主体（userId/owner 均缺失，推导后 subjectId 为空）：失败关闭
		CheckedException ex = assertThrows(CheckedException.class,
				() -> inspector.authorizeBeforeExecute(new PepInvocationInspector.HookDecisionInput("999", 100L, "s",
						null, null, "tool-1", null, null, null)));
		assertTrue(ex.getMessage().contains("BUSINESS_DENIED"));
		verify(runtimePolicyEvaluator, never()).evaluate(any());
	}

	@Test
	void enforcePdpDenyDecisionRejectedAfterPr4Wiring() {
		// PR-4 接线后语义：authorizeBeforeExecute 仍照常返回 deny（PR-3a 冻结评估行为），
		// 紧随其后的 enforceDenyDecision 在 ENFORCE 下强制拦截
		when(runtimePolicyEvaluator.evaluate(any())).thenReturn(resultOf(PepAuthorizationMode.ENFORCE, false));
		PepDecisionResult result = inspector.authorizeBeforeExecute(completeEmployeeInput("999"));

		assertEquals(DecisionReasonCode.POLICY_DENIED, result.reasonCode());
		CheckedException ex = assertThrows(CheckedException.class,
				() -> inspector.enforceDenyDecision(result, completeEmployeeInput("999")));
		assertTrue(ex.getMessage().contains("BUSINESS_DENIED"));
		assertTrue(ex.getMessage().contains("POLICY_DENIED"));
		// 拦截即最终结论：先落影子留痕 original=FALSE（MATCHED 口径不虚增差异率）
		verify(shadowRecorder).recordShadowDecision(result, PepInvocationInspector.SCENE_HOOK_EXECUTE,
				Boolean.FALSE, 999L, 100L, "s");
	}

	@Test
	void shadowPdpDenyNotRejectedAndShadowObservationDeferred() {
		// SHADOW：deny 不拦截（现网行为不变）；enforce 点不落影子，
		// 比对现测延迟到 recordAfterInvocationOpened（original=TRUE → MISMATCHED）
		when(runtimePolicyEvaluator.evaluate(any())).thenReturn(resultOf(PepAuthorizationMode.SHADOW, false));
		PepDecisionResult result = inspector.authorizeBeforeExecute(callerInput("1"));

		inspector.enforceDenyDecision(result, callerInput("1"));

		verify(shadowRecorder, never()).recordShadowDecision(any(), anyString(), any(), any(), any(), any());
	}

	@Test
	void enforceAllowAndNullResultSkipEnforcePoint() {
		// ENFORCE allow 与 SHADOW 评估失败（null）都不触发拦截点
		inspector.enforceDenyDecision(resultOf(PepAuthorizationMode.ENFORCE, true), completeEmployeeInput("999"));
		inspector.enforceDenyDecision(null, completeEmployeeInput("999"));

		verify(shadowRecorder, never()).recordShadowDecision(any(), anyString(), any(), any(), any(), any());
	}

	@Test
	void shadowEvaluationFailureQuietlyReturnsNull() {
		when(runtimePolicyEvaluator.evaluate(any())).thenThrow(new IllegalStateException("pdp down"));

		assertNull(inspector.authorizeBeforeExecute(callerInput("1")));
	}

	@Test
	void recordAfterInvocationWritesShadowAndAttachesAuditColumns() {
		PepDecisionResult result = resultOf(PepAuthorizationMode.SHADOW, true);

		inspector.recordAfterInvocationOpened(result, callerInput("1"), 555L);

		verify(shadowRecorder).recordShadowDecision(result, PepInvocationInspector.SCENE_HOOK_EXECUTE,
				Boolean.TRUE, 1L, 100L, "s");
		verify(invocationMapper).attachDecision(555L, result.getDecisionId(), "hash-1",
				SubjectKind.CALLER.getCode(), DecisionReasonCode.POLICY_ALLOWED.name());
	}

	@Test
	void recordAfterInvocationSwallowsAuditFailureQuietly() {
		doThrow(new IllegalStateException("db down")).when(invocationMapper)
			.attachDecision(anyLong(), anyString(), anyString(), anyString(), anyString());
		PepDecisionResult result = resultOf(PepAuthorizationMode.SHADOW, true);

		inspector.recordAfterInvocationOpened(result, callerInput("1"), 555L);

		verify(shadowRecorder).recordShadowDecision(any(), anyString(), any(), any(), any(), any());
	}

	@Test
	void recordAfterInvocationSkipsNullResultAndNullInvocation() {
		inspector.recordAfterInvocationOpened(null, callerInput("1"), 555L);

		verify(shadowRecorder, never()).recordShadowDecision(any(), anyString(), any(), any(), any(), any());
		inspector.recordAfterInvocationOpened(resultOf(PepAuthorizationMode.SHADOW, true), callerInput("1"),
				null);
		verify(invocationMapper, never()).attachDecision(anyLong(), anyString(), any(), any(), any());
	}

	@Test
	void shadowOutputPreviewKeepsPayloadUntouched() {
		PepDecisionResult result = resultOfWithObligations(PepAuthorizationMode.SHADOW);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("idCard", "310101199001011234");

		Object output = inspector.applyOutputObligations(result, "[\"idCard\"]", payload, callerInput("1"));

		assertSame(payload, output);
		assertEquals("310101199001011234", payload.get("idCard"));
	}

	@Test
	void enforceOutputObligationMasksPayload() {
		PepDecisionResult result = resultOfWithObligations(PepAuthorizationMode.ENFORCE);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("idCard", "310101199001011234");
		payload.put("name", "王五");

		Object output = inspector.applyOutputObligations(result, "[\"idCard\"]", payload, callerInput("1"));

		@SuppressWarnings("unchecked")
		Map<String, Object> masked = (Map<String, Object>) output;
		assertEquals("****", masked.get("idCard"));
		assertEquals("王五", masked.get("name"));
		// 原 payload 不被修改
		assertEquals("310101199001011234", payload.get("idCard"));
	}

	@Test
	void nonMapDataAndNullResultPassThrough() {
		assertEquals("plain", inspector.applyOutputObligations(null, null, "plain", callerInput("1")));
		// 非 String 输出（List 等内存对象）无从按 JSON 字段定位：无默认集时 preview 为空直接透传
		PepDecisionResult result = resultOfWithObligations(PepAuthorizationMode.ENFORCE);
		List<String> data = List.of("a");
		assertSame(data, inspector.applyOutputObligations(result, null, data, callerInput("1")));
	}

	@Test
	void invalidSensitiveFieldsJsonDegradesToEmptyDefaults() {
		PepDecisionResult result = resultOfWithObligations(PepAuthorizationMode.ENFORCE);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("idCard", "310101");

		@SuppressWarnings("unchecked")
		Map<String, Object> masked = (Map<String, Object>) inspector.applyOutputObligations(result,
				"{broken", payload, callerInput("1"));

		// 决策未带 maskFields 且默认集解析失败 → 无可脱敏字段，原值保留
		assertEquals("310101", masked.get("idCard"));
	}

	@Test
	void enforceStringJsonOutputMasksAndReserializes() throws Exception {
		// 评审高-1：AgentScope 进程内工具经网关返回 String（JSON 文本），ENFORCE 应用义务后重新序列化
		PepDecisionResult result = resultOfWithObligations(PepAuthorizationMode.ENFORCE);
		String json = "{\"idCard\":\"310101199001011234\",\"name\":\"王五\"}";

		Object output = inspector.applyOutputObligations(result, "[\"idCard\"]", json, callerInput("1"));

		assertTrue(output instanceof String);
		JsonNode node = new ObjectMapper().readTree((String) output);
		assertEquals("****", node.get("idCard").asText());
		assertEquals("王五", node.get("name").asText());
	}

	@Test
	void shadowStringJsonOutputOnlyPreviewsWithoutModification() {
		// SHADOW：String JSON 输出只预览不修改（现网行为不变）
		PepDecisionResult result = resultOfWithObligations(PepAuthorizationMode.SHADOW);
		String json = "{\"idCard\":\"310101199001011234\"}";

		Object output = inspector.applyOutputObligations(result, "[\"idCard\"]", json, callerInput("1"));

		assertSame(json, output);
	}

	@Test
	void enforceStringJsonArrayOutputMasksEachElement() throws Exception {
		// 数组形态：逐元素应用义务（元素为对象才处理）
		PepDecisionResult result = resultOfWithObligations(PepAuthorizationMode.ENFORCE);
		String json = "[{\"idCard\":\"310101199001011234\"},{\"idCard\":\"110101199001015678\"}]";

		Object output = inspector.applyOutputObligations(result, "[\"idCard\"]", json, callerInput("1"));

		JsonNode node = new ObjectMapper().readTree((String) output);
		assertTrue(node.isArray());
		assertEquals(2, node.size());
		assertEquals("****", node.get(0).get("idCard").asText());
		assertEquals("****", node.get(1).get("idCard").asText());
	}

	@Test
	void enforceStringJsonOutputWithFilterRemovesFields() throws Exception {
		PepDecisionResult result = resultOfWithFilterObligations(PepAuthorizationMode.ENFORCE);
		String json = "{\"idCard\":\"310101199001011234\",\"name\":\"王五\"}";

		Object output = inspector.applyOutputObligations(result, "[\"idCard\"]", json, callerInput("1"));

		JsonNode node = new ObjectMapper().readTree((String) output);
		assertFalse(node.has("idCard"));
		assertEquals("王五", node.get("name").asText());
	}

	@Test
	void decisionLevelMaskFieldsApplyWithoutCatalogDefaults() throws Exception {
		// 进程内能力无目录版本（version==null → sensitiveFieldsJson=null）：决策级 maskFields 依然生效
		PepDecisionResult result = resultOfWithDecisionMaskFields(PepAuthorizationMode.ENFORCE);
		String json = "{\"idCard\":\"310101199001011234\"}";

		Object output = inspector.applyOutputObligations(result, null, json, callerInput("1"));

		JsonNode node = new ObjectMapper().readTree((String) output);
		assertEquals("****", node.get("idCard").asText());
	}

	@Test
	void unparseableStringOutputWithMaskMarksSkippedAndPassesThrough() {
		// 解析失败 + MASK：落 OBLIGATION_SKIPPED 标记并告警后透传（不 fail-closed）
		PepDecisionResult result = resultOfWithObligations(PepAuthorizationMode.ENFORCE);

		Object output = inspector.applyOutputObligations(result, "[\"idCard\"]", "plain text", callerInput("1"));

		assertEquals("plain text", output);
		verify(shadowRecorder).recordObligationSkipped(result, PepInvocationInspector.SCENE_OUTPUT_OBLIGATION,
				AuthorizationObligation.MASK_FIELDS.name(), "UNPARSEABLE_OUTPUT", 1L, 100L, "s");
	}

	@Test
	void unparseableStringOutputWithFilterFailsClosedOnEnforce() {
		// 解析失败 + FILTER + ENFORCE：无法证明输出不含过滤字段 → 保守 fail-closed 拒绝
		PepDecisionResult result = resultOfWithFilterObligations(PepAuthorizationMode.ENFORCE);

		CheckedException ex = assertThrows(CheckedException.class, () -> inspector.applyOutputObligations(result,
				"[\"idCard\"]", "plain text", callerInput("1")));

		assertTrue(ex.getMessage().contains("BUSINESS_DENIED"));
		verify(shadowRecorder).recordObligationSkipped(result, PepInvocationInspector.SCENE_OUTPUT_OBLIGATION,
				AuthorizationObligation.FILTER_FIELDS.name(), "UNPARSEABLE_OUTPUT", 1L, 100L, "s");
	}

	@Test
	void unparseableStringOutputWithFilterShadowOnlyMarksSkipped() {
		// 解析失败 + FILTER + SHADOW：仅落标记不拦截（现网行为不变）
		PepDecisionResult result = resultOfWithFilterObligations(PepAuthorizationMode.SHADOW);

		Object output = inspector.applyOutputObligations(result, "[\"idCard\"]", "plain text", callerInput("1"));

		assertEquals("plain text", output);
		verify(shadowRecorder).recordObligationSkipped(result, PepInvocationInspector.SCENE_OUTPUT_OBLIGATION,
				AuthorizationObligation.FILTER_FIELDS.name(), "UNPARSEABLE_OUTPUT", 1L, 100L, "s");
	}

	@Test
	void unparseableStringOutputWithNullInputIsSafe() {
		// input 可空（决策输入缺失时义务跳过标记降级为无定位信息，不 NPE）
		PepDecisionResult result = resultOfWithObligations(PepAuthorizationMode.ENFORCE);

		Object output = inspector.applyOutputObligations(result, "[\"idCard\"]", "plain text", null);

		assertEquals("plain text", output);
		verify(shadowRecorder).recordObligationSkipped(eq(result), eq(PepInvocationInspector.SCENE_OUTPUT_OBLIGATION),
				eq(AuthorizationObligation.MASK_FIELDS.name()), eq("UNPARSEABLE_OUTPUT"), isNull(), isNull(),
				isNull());
	}

	@Test
	void enforceDenialPersistsTerminalInvocationWithAuditColumns() {
		// 评审高-2：ENFORCE 拒绝补终态 invocation 留痕（被拦截调用不会走到 openInvocation，审计列无从回填）
		when(runtimePolicyEvaluator.evaluate(any())).thenReturn(resultOf(PepAuthorizationMode.ENFORCE, false));
		PepDecisionResult result = inspector.authorizeBeforeExecute(completeEmployeeInput("999"));

		assertThrows(CheckedException.class,
				() -> inspector.enforceDenyDecision(result, completeEmployeeInput("999")));

		ArgumentCaptor<AgentRuntimeInvocation> captor = ArgumentCaptor.forClass(AgentRuntimeInvocation.class);
		verify(invocationMapper).insert(captor.capture());
		AgentRuntimeInvocation denial = captor.getValue();
		assertEquals(999L, denial.getTenantId());
		assertEquals(100L, denial.getRunId());
		assertEquals("authz-deny-" + result.getDecisionId(), denial.getIdempotencyKey());
		assertEquals("tool-1", denial.getCapabilityHandle());
		assertEquals(RuntimeInvocationState.FAILED.getValue(), denial.getState());
		assertEquals(result.getDecisionId(), denial.getDecisionId());
		assertEquals("hash-1", denial.getPolicyHash());
		assertEquals(SubjectKind.CALLER.getCode(), denial.getSubjectKind());
		assertEquals(DecisionReasonCode.POLICY_DENIED.name(), denial.getReasonCode());
	}

	@Test
	void enforceDenialWithoutRunIdSkipsInvocationInsert() {
		// 无 runId 时 invocation 无处挂靠：留痕由影子事件/结构化日志承载，不落库
		PepDecisionResult result = resultOf(PepAuthorizationMode.ENFORCE, false);
		PepInvocationInspector.HookDecisionInput noRunInput = new PepInvocationInspector.HookDecisionInput("999",
				null, "s", "DIGITAL_EMPLOYEE", 20L, "tool-1", null, null, "user-9");

		assertThrows(CheckedException.class, () -> inspector.enforceDenyDecision(result, noRunInput));

		verify(invocationMapper, never()).insert(any(AgentRuntimeInvocation.class));
	}

	@Test
	void enforceDenialInsertFailureKeepsDenialQuiet() {
		// 留痕落库失败 quiet 吞掉：拒绝本身必须照常抛出
		when(invocationMapper.insert(any(AgentRuntimeInvocation.class)))
				.thenThrow(new IllegalStateException("db down"));
		PepDecisionResult result = resultOf(PepAuthorizationMode.ENFORCE, false);

		CheckedException ex = assertThrows(CheckedException.class,
				() -> inspector.enforceDenyDecision(result, completeEmployeeInput("999")));

		assertTrue(ex.getMessage().contains("BUSINESS_DENIED"));
	}

	@Test
	void employeeOwnerWithRealUserDerivesCallerSubject() {
		// 评审低-1：真人发起的 employeeOwned 调用主体是 CALLER（kind 与 id 语义对齐，PDP 双锚）
		when(runtimePolicyEvaluator.evaluate(any())).thenReturn(resultOf(PepAuthorizationMode.SHADOW, true));

		inspector.authorizeBeforeExecute(completeEmployeeInput("1"));

		ArgumentCaptor<PepDecisionContext> captor = ArgumentCaptor.forClass(PepDecisionContext.class);
		verify(runtimePolicyEvaluator).evaluate(captor.capture());
		assertEquals(SubjectKind.CALLER, captor.getValue().getSubjectKind());
		assertEquals("user-9", captor.getValue().getSubjectId());
	}

	private PepInvocationInspector.HookDecisionInput callerInput(String tenantId) {
		return new PepInvocationInspector.HookDecisionInput(tenantId, 100L, "s", null, null, "tool-1", null,
				null, "user-1");
	}

	private PepInvocationInspector.HookDecisionInput completeEmployeeInput(String tenantId) {
		return new PepInvocationInspector.HookDecisionInput(tenantId, 100L, "s", "DIGITAL_EMPLOYEE", 20L,
				"tool-1", null, null, "user-9");
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

	private PepDecisionResult resultOfWithObligations(PepAuthorizationMode mode) {
		return PepDecisionResult.builder()
			.decisionId("dec-" + System.nanoTime())
			.subjectKind(SubjectKind.CALLER)
			.decision(AuthorizationDecision.builder()
				.allowed(true)
				.reasonCode(DecisionReasonCode.POLICY_ALLOWED)
				.obligations(List.of(AuthorizationObligation.MASK_FIELDS))
				.maskFields(List.of())
				.policyHash("hash-1")
				.evaluatedAt(Instant.now())
				.build())
			.effectiveMode(mode)
			.build();
	}

	private PepDecisionResult resultOfWithFilterObligations(PepAuthorizationMode mode) {
		return PepDecisionResult.builder()
			.decisionId("dec-" + System.nanoTime())
			.subjectKind(SubjectKind.CALLER)
			.decision(AuthorizationDecision.builder()
				.allowed(true)
				.reasonCode(DecisionReasonCode.POLICY_ALLOWED)
				.obligations(List.of(AuthorizationObligation.FILTER_FIELDS))
				.maskFields(List.of())
				.policyHash("hash-1")
				.evaluatedAt(Instant.now())
				.build())
			.effectiveMode(mode)
			.build();
	}

	private PepDecisionResult resultOfWithDecisionMaskFields(PepAuthorizationMode mode) {
		return PepDecisionResult.builder()
			.decisionId("dec-" + System.nanoTime())
			.subjectKind(SubjectKind.CALLER)
			.decision(AuthorizationDecision.builder()
				.allowed(true)
				.reasonCode(DecisionReasonCode.POLICY_ALLOWED)
				.obligations(List.of(AuthorizationObligation.MASK_FIELDS))
				.maskFields(List.of("idCard"))
				.policyHash("hash-1")
				.evaluatedAt(Instant.now())
				.build())
			.effectiveMode(mode)
			.build();
	}

}
