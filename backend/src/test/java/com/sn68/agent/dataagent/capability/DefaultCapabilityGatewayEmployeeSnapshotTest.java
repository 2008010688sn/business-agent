/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.authorization.pep.PepAuthorizationProperties;
import com.sn68.agent.dataagent.authorization.pep.PepInvocationInspector;
import com.sn68.agent.dataagent.employee.dto.EmployeeReleaseSnapshot;
import com.sn68.agent.dataagent.employee.service.EmployeeReleaseSnapshotResolver;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceVersionMapper;
import com.sn68.agent.dataagent.runtime.durable.service.AgentApprovalService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeBudgetService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeInvocationService;
import com.sn68.agent.dataagent.service.tokenusage.AgentUsageLimitService;
import com.sn68.agent.dataagent.tool.ToolPermissionService;
import com.sn68.agent.dataagent.tool.ToolTransportInvoker;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-F：数字员工未钉死 Release 时能力网关失败关闭，禁止 resolveActive(PRODUCTION)。
 */
class DefaultCapabilityGatewayEmployeeSnapshotTest {

	private EmployeeReleaseSnapshotResolver employeeSnapshotResolver;

	private DefaultCapabilityGateway gateway;

	@BeforeEach
	void setUp() {
		employeeSnapshotResolver = mock(EmployeeReleaseSnapshotResolver.class);
		PepInvocationInspector pepInvocationInspector = mock(PepInvocationInspector.class);
		when(pepInvocationInspector.applyOutputObligations(any(), any(), any(), any()))
			.thenAnswer(invocation -> invocation.getArgument(2));
		gateway = new DefaultCapabilityGateway(mock(CapabilitySourceGuard.class),
				mock(AgentExecutionResourceVersionMapper.class), mock(ToolPermissionService.class),
				mock(AgentUsageLimitService.class), mock(ToolTransportInvoker.class),
				mock(AuthenticationContext.class), new ObjectMapper(), mock(RuntimeInvocationService.class),
				mock(AgentApprovalService.class), mock(RuntimeBudgetService.class), pepInvocationInspector,
				employeeSnapshotResolver, new PepAuthorizationProperties());
	}

	@Test
	void digitalEmployeeWithoutReleaseIdFailsClosedAndDoesNotResolveActive() {
		InvocationRequest request = InvocationRequest.builder()
			.tenantId("7")
			.ownerType("DIGITAL_EMPLOYEE")
			.ownerId(9L)
			.capabilityKind(CapabilityKind.TOOL)
			.capabilityCode("sql.execute")
			.arguments(Map.of())
			.source("AGENT_SCOPE")
			.build();

		CheckedException ex = assertThrows(CheckedException.class, () -> gateway.invoke(request));

		assertTrue(ex.getMessage().contains("未钉死 Release"), ex.getMessage());
		assertTrue(ex.getMessage().contains("禁止回落生产部署"), ex.getMessage());
		verify(employeeSnapshotResolver, never()).resolveActive(anyString(), any(), anyString());
		verify(employeeSnapshotResolver, never()).resolveById(anyString(), any(), any());
	}

	@Test
	void digitalEmployeeWithReleaseIdPinsSnapshotAndSkipsResolveActive() {
		EmployeeReleaseSnapshot snapshot = new EmployeeReleaseSnapshot(12L, 9L, 1, "1", "hash", "E-001", "员工", "岗",
				"提示", "你好", 1L, null, null, "GUIDED", Map.of(), List.of(), List.of(), null);
		when(employeeSnapshotResolver.resolveById("7", 9L, 12L)).thenReturn(snapshot);
		InvocationRequest request = InvocationRequest.builder()
			.tenantId("7")
			.ownerType("DIGITAL_EMPLOYEE")
			.ownerId(9L)
			.releaseId(12L)
			.capabilityKind(CapabilityKind.TOOL)
			.capabilityCode("sql.execute")
			.arguments(Map.of())
			.source("AGENT_SCOPE")
			.build();

		try {
			gateway.invoke(request);
		}
		catch (RuntimeException ignored) {
			// 后续检查链是否放行与本断言无关；只证明钉死路径走 resolveById、不回落生产。
		}

		verify(employeeSnapshotResolver).resolveById(eq("7"), eq(9L), eq(12L));
		verify(employeeSnapshotResolver, never()).resolveActive(anyString(), any(), anyString());
	}

}
