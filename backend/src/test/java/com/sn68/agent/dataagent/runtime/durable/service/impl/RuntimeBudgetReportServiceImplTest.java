/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeBudgetAggregateRow;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeBudgetOwnerRowResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeBudgetReportResp;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeBudgetLedgerMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RuntimeBudgetReportServiceImplTest {

	private static final String TENANT = "7";

	private AgentRuntimeBudgetLedgerMapper mapper;

	private RuntimeBudgetReportServiceImpl service;

	@BeforeEach
	void setUp() {
		mapper = mock(AgentRuntimeBudgetLedgerMapper.class);
		service = new RuntimeBudgetReportServiceImpl(mapper);
	}

	@Test
	void reportGroupsOwnerRowsAndKeepsTypeTotals() {
		Instant from = Instant.parse("2026-08-01T00:00:00Z");
		Instant to = Instant.parse("2026-08-08T00:00:00Z");
		when(mapper.aggregateByType(eq(TENANT), eq(from), eq(to), isNull())).thenReturn(List.of(typeRow("TOOL_CALL", "12", 3),
				typeRow("DURATION_MS", "4000", 3)));
		when(mapper.aggregateByOwner(eq(TENANT), eq(from), eq(to), isNull())).thenReturn(List.of(
				ownerRow(88L, "DIGITAL_EMPLOYEE", 88L, "TOOL_CALL", "10", 2),
				ownerRow(88L, "DIGITAL_EMPLOYEE", 88L, "DURATION_MS", "3000", 2),
				ownerRow(null, "CALLER", 9L, "TOOL_CALL", "2", 1)));

		RuntimeBudgetReportResp report = service.report(TENANT, from, to, null);

		assertEquals(2, report.totals().size());
		assertEquals("TOOL_CALL", report.totals().get(0).budgetType());
		assertEquals(new BigDecimal("12"), report.totals().get(0).amount());
		assertEquals(2, report.owners().size());
		RuntimeBudgetOwnerRowResp employee = report.owners().get(0);
		assertEquals(88L, employee.digitalEmployeeId());
		assertEquals(2L, employee.runCount());
		assertEquals(2, employee.amounts().size());
	}

	@Test
	void reportRejectsInvertedRangeAndTooWideWindow() {
		Instant from = Instant.parse("2026-08-08T00:00:00Z");
		Instant to = Instant.parse("2026-08-01T00:00:00Z");
		CheckedException inverted = assertThrows(CheckedException.class, () -> service.report(TENANT, from, to, null));
		assertTrue(inverted.getMessage().contains("开始时间"));

		CheckedException wide = assertThrows(CheckedException.class,
				() -> service.report(TENANT, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-08-01T00:00:00Z"),
						null));
		assertTrue(wide.getMessage().contains("93"));
	}

	@Test
	void reportRequiresTenant() {
		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.report(null, Instant.now().minusSeconds(60), Instant.now(), null));
		assertTrue(ex.getMessage().contains("租户"));
	}

	@Test
	void reportPassesDigitalEmployeeFilter() {
		Instant from = Instant.parse("2026-08-01T00:00:00Z");
		Instant to = Instant.parse("2026-08-02T00:00:00Z");
		when(mapper.aggregateByType(any(), any(), any(), eq(88L))).thenReturn(List.of());
		when(mapper.aggregateByOwner(any(), any(), any(), eq(88L))).thenReturn(List.of());

		service.report(TENANT, from, to, 88L);

		verify(mapper).aggregateByType(TENANT, from, to, 88L);
		verify(mapper).aggregateByOwner(TENANT, from, to, 88L);
	}

	private RuntimeBudgetAggregateRow typeRow(String type, String amount, long runCount) {
		RuntimeBudgetAggregateRow row = new RuntimeBudgetAggregateRow();
		row.setBudgetType(type);
		row.setAmount(new BigDecimal(amount));
		row.setRunCount(runCount);
		return row;
	}

	private RuntimeBudgetAggregateRow ownerRow(Long employeeId, String ownerType, Long ownerId, String type,
			String amount, long runCount) {
		RuntimeBudgetAggregateRow row = typeRow(type, amount, runCount);
		row.setDigitalEmployeeId(employeeId);
		row.setOwnerType(ownerType);
		row.setOwnerId(ownerId);
		return row;
	}

}
