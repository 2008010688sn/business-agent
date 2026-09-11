/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.optimization.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.employee.dto.DigitalEmployeeModifyReq;
import com.sn68.agent.dataagent.employee.dto.DigitalEmployeeResp;
import com.sn68.agent.dataagent.employee.dto.EmployeeDeploymentActivateReq;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeDeployment;
import com.sn68.agent.dataagent.employee.service.DigitalEmployeeService;
import com.sn68.agent.dataagent.employee.service.EmployeeDeploymentService;
import com.sn68.agent.dataagent.employee.service.EmployeeReleaseLifecycleService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmployeeEvolutionApplyAdapterTest {

	private final DigitalEmployeeService employeeService = mock(DigitalEmployeeService.class);

	private final EmployeeReleaseLifecycleService releaseLifecycleService = mock(EmployeeReleaseLifecycleService.class);

	private final EmployeeDeploymentService deploymentService = mock(EmployeeDeploymentService.class);

	private final EmployeeEvolutionApplyAdapter adapter = new EmployeeEvolutionApplyAdapter(employeeService,
			releaseLifecycleService, deploymentService, new ObjectMapper());

	@Test
	void applyToSandboxActivatesSandboxWithoutTouchingProduction() {
		DigitalEmployeeResp employee = new DigitalEmployeeResp();
		employee.setSystemInstruction("旧指令");
		employee.setModelConfigId(8L);
		when(employeeService.getDetail(42L)).thenReturn(employee);
		when(releaseLifecycleService.createDraft(eq(42L), any())).thenReturn(900L);
		DigitalEmployeeDeployment sandboxAfter = new DigitalEmployeeDeployment();
		sandboxAfter.setActiveReleaseId(900L);
		sandboxAfter.setDeploymentVersion(1);
		DigitalEmployeeDeployment production = new DigitalEmployeeDeployment();
		production.setActiveReleaseId(100L);
		production.setDeploymentVersion(3);
		when(deploymentService.findCurrent(42L, "SANDBOX")).thenReturn(null, sandboxAfter);
		when(deploymentService.findCurrent(42L, "PRODUCTION")).thenReturn(production);

		EmployeeEvolutionApplyAdapter.ApplyResult result = adapter.applyToSandbox(42L,
				Map.of("promptSnapshot", "{\"prompt\":\"新指令\"}"));

		assertEquals(900L, result.employeeReleaseId());
		assertEquals(900L, result.sandboxActiveReleaseId());
		assertEquals(100L, result.productionActiveReleaseId());
		assertNotEquals(result.sandboxActiveReleaseId(), result.productionActiveReleaseId());
		ArgumentCaptor<DigitalEmployeeModifyReq> modifyCaptor = ArgumentCaptor.forClass(DigitalEmployeeModifyReq.class);
		verify(employeeService).modify(eq(42L), modifyCaptor.capture());
		assertEquals("新指令", modifyCaptor.getValue().getSystemInstruction());
		verify(releaseLifecycleService).seal(900L);
		verify(releaseLifecycleService).publish(900L);
		ArgumentCaptor<EmployeeDeploymentActivateReq> activateCaptor = ArgumentCaptor
			.forClass(EmployeeDeploymentActivateReq.class);
		verify(deploymentService).activate(eq(42L), activateCaptor.capture());
		assertEquals("SANDBOX", activateCaptor.getValue().getEnvironment());
		assertEquals(900L, activateCaptor.getValue().getReleaseId());
		assertEquals(0, activateCaptor.getValue().getExpectVersion());
		verify(deploymentService, never()).activate(eq(42L),
				org.mockito.ArgumentMatchers.argThat(req -> req != null && "PRODUCTION".equals(req.getEnvironment())));
	}

	@Test
	void promoteToProductionRequiresSandboxPointer() {
		DigitalEmployeeDeployment sandbox = new DigitalEmployeeDeployment();
		sandbox.setActiveReleaseId(800L);
		when(deploymentService.findCurrent(42L, "SANDBOX")).thenReturn(sandbox);

		assertThrows(Exception.class, () -> adapter.promoteToProduction(42L, 900L));
		verify(deploymentService, never()).activate(eq(42L), any());
	}

	@Test
	void promoteToProductionActivatesWhenSandboxMatches() {
		DigitalEmployeeDeployment sandbox = new DigitalEmployeeDeployment();
		sandbox.setActiveReleaseId(900L);
		when(deploymentService.findCurrent(42L, "SANDBOX")).thenReturn(sandbox);
		DigitalEmployeeDeployment production = new DigitalEmployeeDeployment();
		production.setActiveReleaseId(100L);
		production.setDeploymentVersion(3);
		when(deploymentService.findCurrent(42L, "PRODUCTION")).thenReturn(production);

		adapter.promoteToProduction(42L, 900L);

		ArgumentCaptor<EmployeeDeploymentActivateReq> captor = ArgumentCaptor.forClass(EmployeeDeploymentActivateReq.class);
		verify(deploymentService).activate(eq(42L), captor.capture());
		assertEquals("PRODUCTION", captor.getValue().getEnvironment());
		assertEquals(900L, captor.getValue().getReleaseId());
		assertEquals(3, captor.getValue().getExpectVersion());
	}

	@Test
	void rollbackEnvironmentSkipsWhenNoHistory() {
		when(deploymentService.findCurrent(42L, "SANDBOX")).thenReturn(new DigitalEmployeeDeployment());

		assertFalse(adapter.rollbackEnvironment(42L, "SANDBOX"));
		verify(deploymentService, never()).rollback(eq(42L), eq("SANDBOX"), any());
	}

}
