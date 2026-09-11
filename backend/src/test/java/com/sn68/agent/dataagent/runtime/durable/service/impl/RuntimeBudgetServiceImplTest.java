/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeBudgetLedger;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimePlan;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeBudgetLedgerMapper;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimePlanMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预算服务测试：默认上限与计划声明上限的超限拒绝、调用完成后的计数与耗时记账。
 */
class RuntimeBudgetServiceImplTest {

	private AgentRuntimeBudgetLedgerMapper ledgerMapper;

	private AgentRuntimePlanMapper planMapper;

	private RuntimeBudgetServiceImpl service;

	@BeforeEach
	void setUp() {
		ledgerMapper = mock(AgentRuntimeBudgetLedgerMapper.class);
		planMapper = mock(AgentRuntimePlanMapper.class);
		service = new RuntimeBudgetServiceImpl(ledgerMapper, planMapper, new ObjectMapper());
	}

	@Test
	void underDefaultLimitPasses() {
		when(planMapper.findActiveByRunId(88L)).thenReturn(null);
		when(ledgerMapper.sumAmount(88L, "TOOL_CALL")).thenReturn(BigDecimal
			.valueOf(RuntimeBudgetServiceImpl.DEFAULT_MAX_CAPABILITY_CALLS_PER_RUN - 1));

		assertDoesNotThrow(() -> service.enforceCapabilityCallBudget(88L));
	}

	@Test
	void reachingDefaultLimitRejects() {
		when(planMapper.findActiveByRunId(88L)).thenReturn(null);
		when(ledgerMapper.sumAmount(88L, "TOOL_CALL")).thenReturn(BigDecimal
			.valueOf(RuntimeBudgetServiceImpl.DEFAULT_MAX_CAPABILITY_CALLS_PER_RUN));

		CheckedException ex = assertThrows(CheckedException.class, () -> service.enforceCapabilityCallBudget(88L));
		assertTrue(ex.getMessage().contains("预算上限"));
	}

	@Test
	void planDeclaredLimitOverridesDefault() {
		AgentRuntimePlan plan = AgentRuntimePlan.builder()
			.policySnapshot("{\"maxCapabilityCalls\":3}")
			.build();
		when(planMapper.findActiveByRunId(88L)).thenReturn(plan);
		when(ledgerMapper.sumAmount(88L, "TOOL_CALL")).thenReturn(BigDecimal.valueOf(3));

		assertThrows(CheckedException.class, () -> service.enforceCapabilityCallBudget(88L));

		when(ledgerMapper.sumAmount(88L, "TOOL_CALL")).thenReturn(BigDecimal.valueOf(2));
		assertDoesNotThrow(() -> service.enforceCapabilityCallBudget(88L));
	}

	@Test
	void corruptPolicySnapshotFallsBackToDefaultLimit() {
		AgentRuntimePlan plan = AgentRuntimePlan.builder().policySnapshot("not-json").build();
		when(planMapper.findActiveByRunId(88L)).thenReturn(plan);
		when(ledgerMapper.sumAmount(88L, "TOOL_CALL")).thenReturn(BigDecimal
			.valueOf(RuntimeBudgetServiceImpl.DEFAULT_MAX_CAPABILITY_CALLS_PER_RUN));

		assertThrows(CheckedException.class, () -> service.enforceCapabilityCallBudget(88L));
	}

	@Test
	void nullRunIdSkipsEnforcement() {
		assertDoesNotThrow(() -> service.enforceCapabilityCallBudget(null));
		verify(ledgerMapper, never()).sumAmount(null, "TOOL_CALL");
	}

	@Test
	void recordCapabilityCallWritesCallCountAndDuration() {
		service.recordCapabilityCall("7", 88L, "step-1", 66L, 1234L, "crm:update");

		ArgumentCaptor<AgentRuntimeBudgetLedger> captor = ArgumentCaptor.forClass(AgentRuntimeBudgetLedger.class);
		verify(ledgerMapper, times(2)).insert(captor.capture());
		List<AgentRuntimeBudgetLedger> rows = captor.getAllValues();
		assertEquals("TOOL_CALL", rows.get(0).getBudgetType());
		assertEquals(0, BigDecimal.ONE.compareTo(rows.get(0).getAmount()));
		assertEquals("DURATION_MS", rows.get(1).getBudgetType());
		assertEquals(0, BigDecimal.valueOf(1234L).compareTo(rows.get(1).getAmount()));
		assertEquals(88L, rows.get(0).getRunId());
		assertEquals("crm:update", rows.get(0).getRemark());
		assertEquals(66L, rows.get(0).getRefInvocationId());
	}

	@Test
	void recordWithoutRunIdIsSkipped() {
		service.recordCapabilityCall("7", null, null, null, 100L, "crm:update");
		verify(ledgerMapper, never()).insert(org.mockito.ArgumentMatchers.any(AgentRuntimeBudgetLedger.class));
	}

}
