/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.authorization.pep.PepAuthorizationProperties;
import com.sn68.agent.dataagent.dto.tool.ToolPermissionResult;
import com.sn68.agent.dataagent.properties.ToolCenterProperties;
import com.sn68.agent.framework.commons.entity.enums.UserType;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import com.sn68.agent.framework.security.domain.UserInfoDetails;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolPermissionServiceImplTest {

	private final AuthenticationContext authenticationContext = mock(AuthenticationContext.class);

	private final PepAuthorizationProperties pepProperties = new PepAuthorizationProperties();

	private final ToolPermissionServiceImpl service =
			new ToolPermissionServiceImpl(authenticationContext, new ToolCenterProperties(), pepProperties);

	@Test
	void distinguishesMissingContextFromMissingFunctionPermission() {
		when(authenticationContext.anonymous()).thenReturn(true);

		ToolPermissionResult missing = service.canAccess(List.of("demand:create"), null);

		assertFalse(missing.allowed());
		assertEquals(ToolPermissionResult.AUTH_CONTEXT_MISSING, missing.reasonCode());

		when(authenticationContext.anonymous()).thenReturn(false);
		when(authenticationContext.getContext()).thenReturn(new UserInfoDetails());
		when(authenticationContext.funcPermissionList()).thenReturn(List.of("demand:view"));

		ToolPermissionResult denied = service.canAccess(List.of("demand:create"), "没有下单权限");

		assertFalse(denied.allowed());
		assertEquals("没有下单权限", denied.denyMessage());
		assertEquals(ToolPermissionResult.FUNCTION_PERMISSION_DENIED, denied.reasonCode());
	}

	@Test
	void reportsAuthenticationContextReadFailure() {
		when(authenticationContext.anonymous()).thenThrow(new IllegalStateException("broken context"));

		ToolPermissionResult result = service.canAccess(List.of("demand:create"), null);

		assertFalse(result.allowed());
		assertEquals(ToolPermissionResult.AUTH_CONTEXT_ERROR, result.reasonCode());
	}

	@Test
	void shadowAdminStillBypassesFunctionPermission() {
		when(authenticationContext.anonymous()).thenReturn(false);
		when(authenticationContext.getContext()).thenReturn(new UserInfoDetails());
		when(authenticationContext.userType()).thenReturn(UserType.TENANT_ADMIN);
		when(authenticationContext.tenantId()).thenReturn("7");
		when(authenticationContext.userId()).thenReturn("admin-1");
		when(authenticationContext.funcPermissionList()).thenReturn(List.of());

		ToolPermissionResult result = service.canAccess(List.of("demand:create"), null);

		assertTrue(result.allowed());
	}

	@Test
	void enforceAdminDoesNotBypassFunctionPermission() {
		pepProperties.getEnforceTenantIds().add("999");
		when(authenticationContext.anonymous()).thenReturn(false);
		when(authenticationContext.getContext()).thenReturn(new UserInfoDetails());
		when(authenticationContext.userType()).thenReturn(UserType.TENANT_ADMIN);
		when(authenticationContext.tenantId()).thenReturn("999");
		when(authenticationContext.userId()).thenReturn("admin-1");
		when(authenticationContext.funcPermissionList()).thenReturn(List.of("demand:view"));

		ToolPermissionResult result = service.canAccess(List.of("demand:create"), "没有下单权限");

		assertFalse(result.allowed());
		assertEquals(ToolPermissionResult.FUNCTION_PERMISSION_DENIED, result.reasonCode());
	}

	@Test
	void servicePrincipalNeverUsesAdminBypass() {
		when(authenticationContext.anonymous()).thenReturn(false);
		when(authenticationContext.getContext()).thenReturn(new UserInfoDetails());
		when(authenticationContext.userType()).thenReturn(UserType.TENANT_ADMIN);
		when(authenticationContext.tenantId()).thenReturn("7");
		when(authenticationContext.userId()).thenReturn("sp_employee");
		when(authenticationContext.funcPermissionList()).thenReturn(List.of());

		ToolPermissionResult result = service.canAccess(List.of("demand:create"), null);

		assertFalse(result.allowed());
		assertEquals(ToolPermissionResult.FUNCTION_PERMISSION_DENIED, result.reasonCode());
	}

}
