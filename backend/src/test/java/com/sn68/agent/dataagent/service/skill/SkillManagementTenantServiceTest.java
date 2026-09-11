/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sn68.agent.framework.commons.entity.enums.UserType;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkillManagementTenantServiceTest {

	private final AuthenticationContext authenticationContext = org.mockito.Mockito.mock(AuthenticationContext.class);

	private final SkillManagementTenantService service = new SkillManagementTenantService(authenticationContext);

	@Test
	void platformAdministratorUsesCurrentTenant() {
		org.mockito.Mockito.when(authenticationContext.userType()).thenReturn(UserType.PLATFORM_ADMIN);
		org.mockito.Mockito.when(authenticationContext.tenantId()).thenReturn("7");

		assertEquals("7", service.effectiveTenantId());
	}

	@Test
	void platformSkillPermissionDoesNotRewriteTenant() {
		org.mockito.Mockito.when(authenticationContext.userType()).thenReturn(UserType.TENANT_ADMIN);
		org.mockito.Mockito.when(authenticationContext.funcPermissionList()).thenReturn(List.of("ai-agent:skill:platform"));
		org.mockito.Mockito.when(authenticationContext.tenantId()).thenReturn("7");

		assertEquals("7", service.effectiveTenantId());
	}

	@Test
	void tenantAdministratorManagesItsSessionTenant() {
		org.mockito.Mockito.when(authenticationContext.userType()).thenReturn(UserType.TENANT_ADMIN);
		org.mockito.Mockito.when(authenticationContext.funcPermissionList()).thenReturn(List.of());
		org.mockito.Mockito.when(authenticationContext.tenantId()).thenReturn("tenant-2");

		assertEquals("tenant-2", service.effectiveTenantId());
	}

	@Test
	void missingTenantHasNoManagementScope() {
		org.mockito.Mockito.when(authenticationContext.userType()).thenReturn(UserType.TENANT_ADMIN);
		org.mockito.Mockito.when(authenticationContext.funcPermissionList()).thenReturn(List.of());
		org.mockito.Mockito.when(authenticationContext.tenantId()).thenReturn(null);

		assertEquals(null, service.effectiveTenantId());
	}

}
