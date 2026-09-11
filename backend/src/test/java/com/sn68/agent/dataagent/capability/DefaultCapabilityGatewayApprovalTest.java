/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.capability;

import cn.hutool.crypto.SecureUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.authorization.pep.PepAuthorizationProperties;
import com.sn68.agent.dataagent.authorization.pep.PepInvocationInspector;
import com.sn68.agent.dataagent.employee.service.EmployeeReleaseSnapshotResolver;
import com.sn68.agent.dataagent.entity.AgentExecutionResource;
import com.sn68.agent.dataagent.entity.AgentExecutionResourceVersion;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceVersionMapper;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeApproval;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeInvocation;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeInvocationState;
import com.sn68.agent.dataagent.runtime.durable.service.AgentApprovalService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeBudgetService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeInvocationService;
import com.sn68.agent.dataagent.service.tokenusage.AgentUsageLimitService;
import com.sn68.agent.dataagent.service.tokenusage.AgentUsageReservation;
import com.sn68.agent.dataagent.tool.ToolPermissionService;
import com.sn68.agent.dataagent.tool.ToolTransportInvoker;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 能力网关「风险与审批」「幂等与调用记录」「预算」检查测试：仅技能打开管理端审批时失败关闭并生成审批请求；
 * 命中已通过审批一次性消费后放行；批复按（运行主体 owner + 来源实例）作用域匹配；租户解析失败失败关闭；
 * 写能力幂等键命中已成功记录时拒绝重放；预算超限拒绝执行。
 */
class DefaultCapabilityGatewayApprovalTest {

	private static final String CAPABILITY = "crm:update";

	private static final String TENANT = "7";

	private static final long OWNER_ID = 3L;

	private static final long RUN_ID = 55L;

	private CapabilitySourceGuard sourceGuard;

	private AgentExecutionResourceVersionMapper resourceVersionMapper;

	private AgentUsageLimitService usageLimitService;

	private ToolTransportInvoker toolTransportInvoker;

	private AuthenticationContext authenticationContext;

	private AgentApprovalService approvalService;

	private RuntimeBudgetService runtimeBudgetService;

	private RuntimeInvocationService runtimeInvocationService;

	private DefaultCapabilityGateway gateway;

	@BeforeEach
	void setUp() {
		sourceGuard = mock(CapabilitySourceGuard.class);
		resourceVersionMapper = mock(AgentExecutionResourceVersionMapper.class);
		usageLimitService = mock(AgentUsageLimitService.class);
		toolTransportInvoker = mock(ToolTransportInvoker.class);
		authenticationContext = mock(AuthenticationContext.class);
		approvalService = mock(AgentApprovalService.class);
		runtimeBudgetService = mock(RuntimeBudgetService.class);
		runtimeInvocationService = mock(RuntimeInvocationService.class);
		PepInvocationInspector pepInvocationInspector = mock(PepInvocationInspector.class);
		// PEP 影子组件 mock：透传输出义务语义，保持既有 envelope 状态/数据断言不变（PR-3c 接线兼容）。
		when(pepInvocationInspector.applyOutputObligations(any(), any(), any(), any()))
				.thenAnswer(invocation -> invocation.getArgument(2));
		gateway = new DefaultCapabilityGateway(sourceGuard, resourceVersionMapper, mock(ToolPermissionService.class),
				usageLimitService, toolTransportInvoker, authenticationContext, new ObjectMapper(),
				runtimeInvocationService, approvalService, runtimeBudgetService,
				pepInvocationInspector, mock(EmployeeReleaseSnapshotResolver.class),
				new PepAuthorizationProperties());
		when(authenticationContext.anonymous()).thenReturn(true);
		when(usageLimitService.preCheckAndReserve(any(), anyLong())).thenReturn(AgentUsageReservation.empty());
		AgentExecutionResource resource = AgentExecutionResource.builder().resourceKey(CAPABILITY).build();
		when(sourceGuard.requireDeclaredResource(any(InvocationRequest.class))).thenReturn(resource);
	}

	@Test
	void writeWithoutManagerApprovalFlagSkipsPapAndExecutes() {
		when(resourceVersionMapper.findLatestPublished(CAPABILITY)).thenReturn(version("WRITE", false));
		when(toolTransportInvoker.invoke(eq(CAPABILITY), anyMap())).thenReturn(Map.of("ok", true));

		ResultEnvelope envelope = gateway.invoke(request());

		assertEquals(ResultEnvelope.STATUS_SUCCESS, envelope.status());
		verify(approvalService, never()).createPending(any());
		verify(toolTransportInvoker).invoke(eq(CAPABILITY), anyMap());
	}

	@Test
	void managerApprovalWithoutTicketCreatesPendingAndThrowsDedicatedException() {
		when(resourceVersionMapper.findLatestPublished(CAPABILITY)).thenReturn(version("WRITE", false));
		AgentRuntimeApproval pending = new AgentRuntimeApproval();
		pending.setId(42L);
		when(approvalService.createPending(any())).thenReturn(pending);

		CapabilityApprovalRequiredException ex = assertThrows(CapabilityApprovalRequiredException.class,
				() -> gateway.invoke(approvalRequest().toBuilder().runId(RUN_ID).build(), () -> "should-not-run"));

		assertEquals(42L, ex.getApprovalId());
		assertTrue(ex.getMessage().contains("approvalId=42"));
		verify(approvalService).createPending(any());
		verify(toolTransportInvoker, never()).invoke(anyString(), anyMap());
	}

	@Test
	void inProcessReadOnlyToolWithoutCatalogVersionSkipsApproval() {
		gateway.invoke(InvocationRequest.builder()
			.tenantId(String.valueOf(TENANT))
			.capabilityKind(CapabilityKind.TOOL)
			.capabilityCode("datasource_skill_search")
			.arguments(Map.of())
			.source(CapabilityGatewayToolCallback.SOURCE_AGENT_SCOPE)
			.build(), () -> "rows");

		verify(approvalService, never()).createPending(any());
		verify(approvalService, never()).findConsumableApproved(anyString(), nullable(String.class), nullable(Long.class),
				nullable(Long.class), anyString(), anyString());
	}

	@Test
	void highRiskWithoutApprovalCreatesPendingAndFailsClosed() {
		when(resourceVersionMapper.findLatestPublished(CAPABILITY)).thenReturn(version("WRITE", false));
		AgentRuntimeApproval pending = new AgentRuntimeApproval();
		pending.setId(99L);
		when(approvalService.createPending(any())).thenReturn(pending);

		CapabilityApprovalRequiredException ex = assertThrows(CapabilityApprovalRequiredException.class,
				() -> gateway.invoke(approvalRequest()));

		assertEquals(99L, ex.getApprovalId());
		verify(toolTransportInvoker, never()).invoke(anyString(), anyMap());
		ArgumentCaptor<AgentApprovalService.ApprovalCreateRequest> captor = ArgumentCaptor
			.forClass(AgentApprovalService.ApprovalCreateRequest.class);
		verify(approvalService).createPending(captor.capture());
		assertEquals(TENANT, captor.getValue().tenantId());
		assertEquals(CAPABILITY, captor.getValue().capabilityCode());
		assertEquals(expectedParamsHash(), captor.getValue().paramsHash());
		assertEquals("flow-1", captor.getValue().sourceRefId());
	}

	@Test
	void highRiskWithApprovedApprovalConsumesAndExecutes() {
		when(resourceVersionMapper.findLatestPublished(CAPABILITY)).thenReturn(version("WRITE", false));
		when(approvalService.consumeMatching(eq(TENANT), eq(7L), nullable(String.class), nullable(Long.class),
				eq("flow-1"), eq(CAPABILITY), eq(expectedParamsHash()))).thenReturn(true);
		when(toolTransportInvoker.invoke(eq(CAPABILITY), anyMap())).thenReturn(Map.of("ok", true));

		ResultEnvelope envelope = gateway.invoke(approvalRequest().toBuilder().approvalId(7L).build());

		assertEquals(ResultEnvelope.STATUS_SUCCESS, envelope.status());
		verify(approvalService).consumeMatching(eq(TENANT), eq(7L), nullable(String.class), nullable(Long.class),
				eq("flow-1"), eq(CAPABILITY), eq(expectedParamsHash()));
		verify(approvalService, never()).createPending(any());
		verify(toolTransportInvoker).invoke(eq(CAPABILITY), anyMap());
	}

	@Test
	void approvalLookupCarriesOwnerAndSourceRef() {
		when(resourceVersionMapper.findLatestPublished(CAPABILITY)).thenReturn(version("WRITE", false));
		AgentRuntimeApproval pending = new AgentRuntimeApproval();
		pending.setId(1L);
		when(approvalService.createPending(any())).thenReturn(pending);

		assertThrows(CheckedException.class,
				() -> gateway.invoke(approvalRequest().toBuilder().ownerType("CALLER").ownerId(OWNER_ID).build()));

		ArgumentCaptor<AgentApprovalService.ApprovalCreateRequest> captor = ArgumentCaptor
			.forClass(AgentApprovalService.ApprovalCreateRequest.class);
		verify(approvalService).createPending(captor.capture());
		assertEquals("CALLER", captor.getValue().ownerType());
		assertEquals(OWNER_ID, captor.getValue().ownerId());
		assertEquals("flow-1", captor.getValue().sourceRefId());
	}

	@Test
	void highRiskWithoutResolvableTenantIsRejectedInsteadOfTenantZero() {
		when(resourceVersionMapper.findLatestPublished(CAPABILITY)).thenReturn(version("WRITE", false));

		CheckedException ex = assertThrows(CheckedException.class,
				() -> gateway.invoke(approvalRequest().toBuilder().tenantId(null).build()));

		assertTrue(ex.getMessage().contains("无法解析当前租户"));
		verify(approvalService, never()).createPending(any());
		verify(toolTransportInvoker, never()).invoke(anyString(), anyMap());
	}

	@Test
	void confirmRequiredWithoutManagerApprovalFlagExecutes() {
		when(resourceVersionMapper.findLatestPublished(CAPABILITY)).thenReturn(version("READ", true));
		when(toolTransportInvoker.invoke(eq(CAPABILITY), anyMap())).thenReturn(Map.of("ok", true));

		ResultEnvelope envelope = gateway.invoke(request());

		assertEquals(ResultEnvelope.STATUS_SUCCESS, envelope.status());
		verify(approvalService, never()).createPending(any());
		verify(toolTransportInvoker).invoke(eq(CAPABILITY), anyMap());
	}

	@Test
	void readOnlyCapabilitySkipsApprovalAndExecutes() {
		when(resourceVersionMapper.findLatestPublished(CAPABILITY)).thenReturn(version("READ", false));
		when(toolTransportInvoker.invoke(eq(CAPABILITY), anyMap())).thenReturn(Map.of("ok", true));

		ResultEnvelope envelope = gateway.invoke(request());

		assertEquals(ResultEnvelope.STATUS_SUCCESS, envelope.status());
		verify(approvalService, never()).findConsumableApproved(anyString(), nullable(String.class), nullable(Long.class),
				nullable(Long.class), anyString(), anyString());
		verify(approvalService, never()).createPending(any());
	}

	/** 集成缺口 2：写能力的幂等键命中已 SUCCESS 的调用记录时必须拒绝，否则副作用会真的执行第二次。 */
	@Test
	void writeCapabilityReplayOnSucceededIdempotencyKeyIsRejected() {
		when(resourceVersionMapper.findLatestPublished(CAPABILITY)).thenReturn(version("WRITE", false));
		AgentRuntimeApproval approved = new AgentRuntimeApproval();
		approved.setId(7L);
		when(approvalService.findConsumableApproved(anyString(), nullable(String.class), nullable(Long.class),
				nullable(Long.class), anyString(), anyString())).thenReturn(approved);
		when(approvalService.consume(7L)).thenReturn(true);
		when(runtimeInvocationService.open(any())).thenReturn(invocation(RuntimeInvocationState.SUCCESS));

		CheckedException ex = assertThrows(CheckedException.class,
				() -> gateway.invoke(request().toBuilder().runId(RUN_ID).build()));

		assertTrue(ex.getMessage().contains("禁止重放"));
		verify(toolTransportInvoker, never()).invoke(anyString(), anyMap());
		verify(runtimeInvocationService, never()).markSuccess(anyLong(), nullable(String.class),
				nullable(String.class));
	}

	/** 只读能力重复调用无副作用：命中终态记录仍照常执行，仅不再改写该记录。 */
	@Test
	void readCapabilityReplayOnSucceededIdempotencyKeyStillExecutes() {
		when(resourceVersionMapper.findLatestPublished(CAPABILITY)).thenReturn(version("READ", false));
		when(runtimeInvocationService.open(any())).thenReturn(invocation(RuntimeInvocationState.SUCCESS));
		when(toolTransportInvoker.invoke(eq(CAPABILITY), anyMap())).thenReturn(Map.of("ok", true));

		ResultEnvelope envelope = gateway.invoke(request().toBuilder().runId(RUN_ID).build());

		assertEquals(ResultEnvelope.STATUS_SUCCESS, envelope.status());
		verify(toolTransportInvoker).invoke(eq(CAPABILITY), anyMap());
		verify(runtimeInvocationService, never()).markSuccess(anyLong(), nullable(String.class),
				nullable(String.class));
	}

	/** 外部端明确失败已证明副作用未生效，同幂等键允许重试（步骤级重试依赖该语义）。 */
	@Test
	void failedInvocationReplayStillExecutes() {
		when(resourceVersionMapper.findLatestPublished(CAPABILITY)).thenReturn(version("READ", false));
		when(runtimeInvocationService.open(any())).thenReturn(invocation(RuntimeInvocationState.FAILED));
		when(toolTransportInvoker.invoke(eq(CAPABILITY), anyMap())).thenReturn(Map.of("ok", true));

		gateway.invoke(request().toBuilder().runId(RUN_ID).build());

		verify(toolTransportInvoker).invoke(eq(CAPABILITY), anyMap());
	}

	/** 带 runId 的调用记录必须落真实租户，解析不出租户即失败关闭，不落 tenant 0 的孤儿审计行。 */
	@Test
	void invocationRecordRequiresResolvableTenant() {
		when(resourceVersionMapper.findLatestPublished(CAPABILITY)).thenReturn(version("READ", false));

		CheckedException ex = assertThrows(CheckedException.class,
				() -> gateway.invoke(request().toBuilder().tenantId(null).runId(RUN_ID).build()));

		assertTrue(ex.getMessage().contains("无法解析当前租户"));
		verify(runtimeInvocationService, never()).open(any());
		verify(toolTransportInvoker, never()).invoke(anyString(), anyMap());
	}

	@Test
	void budgetOverrunRejectsBeforeExecution() {
		when(resourceVersionMapper.findLatestPublished(CAPABILITY)).thenReturn(version("READ", false));
		doThrow(CheckedException.fail("限流与预算检查拒绝：运行的能力调用次数已达预算上限"))
			.when(runtimeBudgetService).enforceCapabilityCallBudget(RUN_ID);

		CheckedException ex = assertThrows(CheckedException.class,
				() -> gateway.invoke(request().toBuilder().runId(RUN_ID).build()));

		assertTrue(ex.getMessage().contains("预算上限"));
		verify(toolTransportInvoker, never()).invoke(anyString(), anyMap());
	}

	@Test
	void successfulInvocationRecordsBudgetLedger() {
		when(resourceVersionMapper.findLatestPublished(CAPABILITY)).thenReturn(version("READ", false));
		when(toolTransportInvoker.invoke(eq(CAPABILITY), anyMap())).thenReturn(Map.of("ok", true));

		gateway.invoke(request());

		verify(runtimeBudgetService).recordCapabilityCall(eq(TENANT), any(), any(), any(), anyLong(), eq(CAPABILITY));
	}

	private AgentRuntimeInvocation invocation(RuntimeInvocationState state) {
		AgentRuntimeInvocation invocation = new AgentRuntimeInvocation();
		invocation.setId(4321L);
		invocation.setRunId(RUN_ID);
		invocation.setState(state.getValue());
		return invocation;
	}

	private InvocationRequest request() {
		return InvocationRequest.builder()
			.tenantId(String.valueOf(TENANT))
			.capabilityKind(CapabilityKind.TOOL)
			.capabilityCode(CAPABILITY)
			.arguments(Map.of("name", "foo"))
			.source("TEST")
			.build();
	}

	private InvocationRequest approvalRequest() {
		return request().toBuilder().requireManagerApproval(true).sourceRefId("flow-1").confirmed(true).build();
	}

	private String expectedParamsHash() {
		return SecureUtil.sha256("{\"name\":\"foo\"}").substring(0, 16);
	}

	private AgentExecutionResourceVersion version(String accessMode, boolean confirmRequired) {
		return AgentExecutionResourceVersion.builder()
			.resourceKey(CAPABILITY)
			.accessMode(accessMode)
			.confirmRequired(confirmRequired)
			.build();
	}

}
