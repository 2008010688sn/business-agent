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
package com.sn68.agent.dataagent.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.authorization.pep.InvocationSubjectGuard;
import com.sn68.agent.dataagent.authorization.pep.OutputObligationApplier;
import com.sn68.agent.dataagent.authorization.pep.PepAuthorizationMode;
import com.sn68.agent.dataagent.authorization.pep.PepAuthorizationProperties;
import com.sn68.agent.dataagent.authorization.pep.PepDecisionContext;
import com.sn68.agent.dataagent.authorization.pep.PepDecisionResult;
import com.sn68.agent.dataagent.authorization.pep.PepInvocationInspector;
import com.sn68.agent.dataagent.authorization.pep.RuntimePolicyEvaluator;
import com.sn68.agent.dataagent.authorization.pep.ShadowRecorder;
import com.sn68.agent.dataagent.authorization.model.AuthorizationObligation;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationDecision;
import com.sn68.agent.dataagent.authorization.pdp.DecisionReasonCode;
import com.sn68.agent.dataagent.authorization.pdp.SubjectKind;
import com.sn68.agent.dataagent.context.ExecutionIntentContext;
import com.sn68.agent.dataagent.dto.tool.ToolPermissionResult;
import com.sn68.agent.dataagent.employee.service.EmployeeReleaseSnapshotResolver;
import com.sn68.agent.dataagent.entity.AgentExecutionResource;
import com.sn68.agent.dataagent.entity.AgentExecutionResourceVersion;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceVersionMapper;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeInvocationMapper;
import com.sn68.agent.dataagent.runtime.durable.service.AgentApprovalService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeBudgetService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeInvocationService;
import com.sn68.agent.dataagent.service.tokenusage.AgentUsageLimitService;
import com.sn68.agent.dataagent.service.tokenusage.AgentUsageReservation;
import com.sn68.agent.dataagent.tool.ToolPermissionService;
import com.sn68.agent.dataagent.tool.ToolTransportInvoker;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 能力网关 PEP ENFORCE 接线测试（v1.2 清单 PR-4）：
 * <ul>
 * <li>ENFORCE 租户 PDP deny：检查链最前置拦截（BUSINESS_DENIED），不执行、不落调用记录；
 * 拦截在 DRY_RUN 下计入隔离违规（发布门禁可见，对应验收 2.15/2.16）；</li>
 * <li>SHADOW 租户 PDP deny：不拦截照常执行（现网行为不变，验收 1.5），影子记录延迟到
 * openInvocation 之后（original=TRUE → MISMATCHED 供 PR-10 观测）；</li>
 * <li>ENFORCE 输出义务 MASK_FIELDS 真实生效（验收 2.14/2.17 的网关侧口径）。</li>
 * </ul>
 *
 * @author James (PR-4 PEP 接线)
 */
class DefaultCapabilityGatewayPepEnforceTest {

	private static final String READ_CAPABILITY = "crm:query";

	/** ENFORCE 灰度白名单租户。 */
	private static final String ENFORCE_TENANT = "999";

	/** SHADOW 租户（未命中白名单）。 */
	private static final String SHADOW_TENANT = "7";

	private AgentExecutionResourceVersionMapper resourceVersionMapper;

	private ToolTransportInvoker toolTransportInvoker;

	private RuntimeInvocationService runtimeInvocationService;

	private PepInvocationInspector pepInvocationInspector;

	private PepDecisionResult enforceDenyResult;

	private DefaultCapabilityGateway gateway;

	@BeforeEach
	void setUp() {
		CapabilitySourceGuard sourceGuard = mock(CapabilitySourceGuard.class);
		resourceVersionMapper = mock(AgentExecutionResourceVersionMapper.class);
		toolTransportInvoker = mock(ToolTransportInvoker.class);
		AgentApprovalService approvalService = mock(AgentApprovalService.class);
		ToolPermissionService toolPermissionService = mock(ToolPermissionService.class);
		AgentUsageLimitService usageLimitService = mock(AgentUsageLimitService.class);
		AuthenticationContext authenticationContext = mock(AuthenticationContext.class);
		runtimeInvocationService = mock(RuntimeInvocationService.class);
		pepInvocationInspector = mock(PepInvocationInspector.class);
		// 输出线透传语义由具体用例覆写（义务用例改为真 inspector 端到端验证）
		when(pepInvocationInspector.applyOutputObligations(any(), any(), any(), any()))
				.thenAnswer(invocation -> invocation.getArgument(2));
		when(toolPermissionService.canAccess(any(), any())).thenReturn(new ToolPermissionResult(true, null));
		PepAuthorizationProperties pepProperties = new PepAuthorizationProperties();
		pepProperties.getEnforceTenantIds().add(ENFORCE_TENANT);
		gateway = new DefaultCapabilityGateway(sourceGuard, resourceVersionMapper, toolPermissionService,
				usageLimitService, toolTransportInvoker, authenticationContext, new ObjectMapper(),
				runtimeInvocationService, approvalService,
				mock(RuntimeBudgetService.class), pepInvocationInspector,
				mock(EmployeeReleaseSnapshotResolver.class), pepProperties);
		when(authenticationContext.anonymous()).thenReturn(true);
		when(usageLimitService.preCheckAndReserve(any(), anyLong())).thenReturn(AgentUsageReservation.empty());
		when(sourceGuard.requireDeclaredResource(any(InvocationRequest.class))).thenAnswer(invocation -> {
			InvocationRequest request = invocation.getArgument(0);
			return AgentExecutionResource.builder().resourceKey(request.capabilityCode()).build();
		});
		when(resourceVersionMapper.findLatestPublished(READ_CAPABILITY))
				.thenReturn(AgentExecutionResourceVersion.builder()
						.resourceKey(READ_CAPABILITY)
						.accessMode("READ")
						.permissionCode("tool:query")
						.confirmRequired(false)
						.build());
		enforceDenyResult = decisionResult(PepAuthorizationMode.ENFORCE, false, List.of(), List.of());
	}

	@Test
	void enforcePdpDenyBlocksBeforeCheckChainAndInvocation() {
		when(pepInvocationInspector.authorizeBeforeExecute(any())).thenReturn(enforceDenyResult);
		doThrow(CheckedException.fail("授权决策拒绝（BUSINESS_DENIED）：能力执行被授权策略拒绝"))
				.when(pepInvocationInspector).enforceDenyDecision(any(), any());

		CheckedException ex = assertThrows(CheckedException.class,
				() -> gateway.invoke(request(ENFORCE_TENANT)));

		assertTrue(ex.getMessage().contains("BUSINESS_DENIED"));
		// 失败关闭：拦截点先于 Release 固定/授权/资源范围检查生效，更不执行传输层与调用记录
		verify(toolTransportInvoker, never()).invoke(anyString(), anyMap());
		verify(runtimeInvocationService, never()).open(any());
		// 拦截点确实被网关调用（时序上先于后续检查链）
		ArgumentCaptor<PepDecisionResult> resultCaptor = ArgumentCaptor.forClass(PepDecisionResult.class);
		ArgumentCaptor<PepInvocationInspector.HookDecisionInput> inputCaptor = ArgumentCaptor
				.forClass(PepInvocationInspector.HookDecisionInput.class);
		verify(pepInvocationInspector).enforceDenyDecision(resultCaptor.capture(), inputCaptor.capture());
		assertEquals(ENFORCE_TENANT, inputCaptor.getValue().tenantId());
		assertEquals(READ_CAPABILITY, inputCaptor.getValue().capabilityCode());
	}

	@Test
	void enforcePdpDenyCountsAsIsolationViolationUnderDryRun() {
		when(pepInvocationInspector.authorizeBeforeExecute(any())).thenReturn(enforceDenyResult);
		doThrow(CheckedException.fail("授权决策拒绝（BUSINESS_DENIED）：能力执行被授权策略拒绝"))
				.when(pepInvocationInspector).enforceDenyDecision(any(), any());
		ExecutionIntentContext.ViolationCollector collector = new ExecutionIntentContext.ViolationCollector();

		ExecutionIntentContext.supplyDryRun("dry-pep-1", collector,
				() -> assertThrows(CheckedException.class, () -> gateway.invoke(request(ENFORCE_TENANT))));

		// 授权拒绝计入 DRY_RUN 隔离违规：发布门禁可见（审批不能补权限，验收 2.15）
		assertEquals(1, collector.isolationViolationCount());
		assertEquals(0, collector.writeViolationCount());
	}

	@Test
	void shadowPdpDenyKeepsLegacyBehavior() {
		// SHADOW 语义由 inspector 承担（mock 的 void enforceDenyDecision 默认 no-op = 不拦截）；
		// 网关侧固化：deny 决策下检查链照常走完、执行照常发生、影子记录延迟到 openInvocation 之后
		PepDecisionResult shadowDeny = decisionResult(PepAuthorizationMode.SHADOW, false, List.of(), List.of());
		when(pepInvocationInspector.authorizeBeforeExecute(any())).thenReturn(shadowDeny);
		when(toolTransportInvoker.invoke(eq(READ_CAPABILITY), anyMap())).thenReturn(Map.of("rows", 1));

		ResultEnvelope envelope = gateway.invoke(request(SHADOW_TENANT));

		assertEquals(ResultEnvelope.STATUS_SUCCESS, envelope.status());
		verify(toolTransportInvoker).invoke(eq(READ_CAPABILITY), anyMap());
		verify(pepInvocationInspector).recordAfterInvocationOpened(eq(shadowDeny), any(), any());
	}

	@Test
	void enforceOutputObligationsMaskFieldsAppliedEndToEnd() {
		// 真 inspector 端到端：ENFORCE 租户 allow + MASK_FIELDS → 网关输出真实脱敏
		gateway = gatewayWithRealInspector(READ_CAPABILITY);
		when(toolTransportInvoker.invoke(eq(READ_CAPABILITY), anyMap())).thenReturn(Map.of(
				"idCard", "310101199001011234", "name", "王五"));

		ResultEnvelope envelope = gateway.invoke(request(ENFORCE_TENANT));

		@SuppressWarnings("unchecked")
		Map<String, Object> data = (Map<String, Object>) envelope.data();
		assertEquals("****", data.get("idCard"));
		assertEquals("王五", data.get("name"));
	}

	@Test
	void shadowTenantOutputNotMaskedWhileEnforceTenantBlocked() {
		// 灰度白名单边界：同一配置下 SHADOW 租户 deny 不拦截、输出不脱敏（行为不变）
		gateway = gatewayWithRealInspector(READ_CAPABILITY);
		when(toolTransportInvoker.invoke(eq(READ_CAPABILITY), anyMap())).thenReturn(Map.of(
				"idCard", "310101199001011234"));

		ResultEnvelope envelope = gateway.invoke(request(SHADOW_TENANT));

		@SuppressWarnings("unchecked")
		Map<String, Object> data = (Map<String, Object>) envelope.data();
		assertEquals("310101199001011234", data.get("idCard"));
	}

	@Test
	void enforceEmptyPermissionCodeRejected() {
		when(pepInvocationInspector.authorizeBeforeExecute(any()))
				.thenReturn(decisionResult(PepAuthorizationMode.ENFORCE, true, List.of(), List.of()));
		when(resourceVersionMapper.findLatestPublished(READ_CAPABILITY))
				.thenReturn(AgentExecutionResourceVersion.builder()
						.resourceKey(READ_CAPABILITY)
						.accessMode("READ")
						.confirmRequired(false)
						.build());

		CheckedException ex = assertThrows(CheckedException.class, () -> gateway.invoke(request(ENFORCE_TENANT)));

		assertTrue(ex.getMessage().contains("MISSING_PERMISSION_CODE"));
		verify(toolTransportInvoker, never()).invoke(anyString(), anyMap());
	}

	@Test
	void shadowEmptyPermissionCodeStillAllowed() {
		when(pepInvocationInspector.authorizeBeforeExecute(any()))
				.thenReturn(decisionResult(PepAuthorizationMode.SHADOW, true, List.of(), List.of()));
		when(resourceVersionMapper.findLatestPublished(READ_CAPABILITY))
				.thenReturn(AgentExecutionResourceVersion.builder()
						.resourceKey(READ_CAPABILITY)
						.accessMode("READ")
						.confirmRequired(false)
						.build());
		when(toolTransportInvoker.invoke(eq(READ_CAPABILITY), anyMap())).thenReturn(Map.of("rows", 1));

		ResultEnvelope envelope = gateway.invoke(request(SHADOW_TENANT));

		assertEquals(ResultEnvelope.STATUS_SUCCESS, envelope.status());
		verify(toolTransportInvoker).invoke(eq(READ_CAPABILITY), anyMap());
	}

	/**
	 * 构造挂真实 PepInvocationInspector 的网关：ENFORCE 白名单 {@value #ENFORCE_TENANT}，
	 * 策略串联器 mock 按租户生效模式返回 allow + MASK_FIELDS(["idCard"]) 决策
	 * （effectiveMode 跟随灰度白名单，义务生效/不生效由租户隔离验证）。
	 */
	private DefaultCapabilityGateway gatewayWithRealInspector(String capabilityCode) {
		RuntimePolicyEvaluator evaluator = mock(RuntimePolicyEvaluator.class);
		when(evaluator.evaluate(any(PepDecisionContext.class))).thenAnswer(invocation -> {
			PepDecisionContext context = invocation.getArgument(0);
			PepAuthorizationMode mode = ENFORCE_TENANT.equals(context.getTenantId())
					? PepAuthorizationMode.ENFORCE : PepAuthorizationMode.SHADOW;
			return decisionResult(mode, true, List.of(AuthorizationObligation.MASK_FIELDS), List.of("idCard"));
		});
		PepAuthorizationProperties properties = new PepAuthorizationProperties();
		properties.getEnforceTenantIds().add(ENFORCE_TENANT);
		PepInvocationInspector realInspector = new PepInvocationInspector(evaluator,
				new InvocationSubjectGuard(), mock(ShadowRecorder.class), new OutputObligationApplier(),
				properties, mock(AgentRuntimeInvocationMapper.class), new ObjectMapper());
		CapabilitySourceGuard sourceGuard = mock(CapabilitySourceGuard.class);
		when(sourceGuard.requireDeclaredResource(any(InvocationRequest.class))).thenAnswer(invocation -> {
			InvocationRequest request = invocation.getArgument(0);
			return AgentExecutionResource.builder().resourceKey(request.capabilityCode()).build();
		});
		ToolPermissionService permissions = mock(ToolPermissionService.class);
		when(permissions.canAccess(any(), any())).thenReturn(new ToolPermissionResult(true, null));
		return new DefaultCapabilityGateway(sourceGuard, resourceVersionMapper,
				permissions, mock(AgentUsageLimitService.class), toolTransportInvoker,
				mock(AuthenticationContext.class), new ObjectMapper(),
				runtimeInvocationService, mock(AgentApprovalService.class), mock(RuntimeBudgetService.class),
				realInspector, mock(EmployeeReleaseSnapshotResolver.class), properties);
	}

	private InvocationRequest request(String tenantId) {
		Map<String, Object> arguments = new LinkedHashMap<>();
		arguments.put("name", "foo");
		return InvocationRequest.builder()
				.tenantId(tenantId)
				.capabilityKind(CapabilityKind.TOOL)
				.capabilityCode(READ_CAPABILITY)
				.arguments(arguments)
				.source("TEST")
				.userId("user-1")
				.build();
	}

	private PepDecisionResult decisionResult(PepAuthorizationMode mode, boolean allowed,
			List<AuthorizationObligation> obligations, List<String> maskFields) {
		return PepDecisionResult.builder()
				.decisionId("dec-" + System.nanoTime())
				.subjectKind(SubjectKind.CALLER)
				.decision(AuthorizationDecision.builder()
						.allowed(allowed)
						.reasonCode(allowed ? DecisionReasonCode.POLICY_ALLOWED : DecisionReasonCode.POLICY_DENIED)
						.obligations(obligations)
						.maskFields(maskFields)
						.policyHash("hash-1")
						.evaluatedAt(Instant.now())
						.build())
				.effectiveMode(mode)
				.build();
	}

}
