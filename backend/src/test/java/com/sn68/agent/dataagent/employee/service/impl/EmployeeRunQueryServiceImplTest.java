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
package com.sn68.agent.dataagent.employee.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sn68.agent.dataagent.employee.dto.EmployeeRunDetailResp;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeArtifactResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeBudgetReportResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunDetailResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunPageQueryReq;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunResp;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeBudgetReportService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeRunService;
import com.sn68.agent.dataagent.runtime.durable.support.RuntimeWorkProductAssembler;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EmployeeRunQueryServiceImplTest {

	private static final String TENANT_ID = "7";

	private static final Long EMPLOYEE_ID = 9L;

	private DigitalEmployeeMapper employeeMapper;

	private AuthenticationContext authenticationContext;

	private RuntimeRunService runtimeRunService;

	private RuntimeWorkProductAssembler assembler;

	private RuntimeBudgetReportService budgetReportService;

	private EmployeeRunQueryServiceImpl service;

	@BeforeEach
	void setUp() {
		employeeMapper = mock(DigitalEmployeeMapper.class);
		authenticationContext = mock(AuthenticationContext.class);
		runtimeRunService = mock(RuntimeRunService.class);
		assembler = mock(RuntimeWorkProductAssembler.class);
		budgetReportService = mock(RuntimeBudgetReportService.class);
		service = new EmployeeRunQueryServiceImpl(employeeMapper, authenticationContext, runtimeRunService, assembler,
				mock(com.sn68.agent.dataagent.employee.service.EmployeeRunFeedbackService.class), budgetReportService);
		when(authenticationContext.tenantId()).thenReturn(TENANT_ID);
		when(employeeMapper.findByIdAndTenantId(EMPLOYEE_ID, TENANT_ID))
			.thenReturn(DigitalEmployee.builder().id(EMPLOYEE_ID).tenantId(TENANT_ID).build());
	}

	@Test
	void pageRunsForcesDigitalEmployeeIdAndDropsOwnerFilters() {
		RuntimeRunPageQueryReq request = new RuntimeRunPageQueryReq();
		request.setDigitalEmployeeId(99L);
		request.setOwnerId(88L);
		request.setOwnerType("CALLER");
		request.setAgentId(1L);
		request.setKeyword("库存");
		when(runtimeRunService.page(eq("7"), any(RuntimeRunPageQueryReq.class))).thenReturn(new Page<>());

		service.pageRuns(EMPLOYEE_ID, request);

		ArgumentCaptor<RuntimeRunPageQueryReq> captor = ArgumentCaptor.forClass(RuntimeRunPageQueryReq.class);
		org.mockito.Mockito.verify(runtimeRunService).page(eq("7"), captor.capture());
		RuntimeRunPageQueryReq forwarded = captor.getValue();
		assertEquals(EMPLOYEE_ID, forwarded.getDigitalEmployeeId());
		assertNull(forwarded.getOwnerId());
		assertNull(forwarded.getOwnerType());
		assertNull(forwarded.getAgentId());
		assertEquals("库存", forwarded.getKeyword());
		assertEquals(99L, request.getDigitalEmployeeId(), "不得改写调用方入参对象");
	}

	@Test
	void detailRejectsRunOfAnotherEmployee() {
		RuntimeRunResp run = new RuntimeRunResp(101L, "DIGITAL_EMPLOYEE", 8L, 8L, null, 8L, "c1", null, null, "CHAT",
				"CHAT", "q", "SUCCEEDED", 1L, 0L, null, null, null, null, null, null, "3", null, null,
				"DIGITAL_EMPLOYEE", null);
		when(runtimeRunService.detail("7", 101L))
			.thenReturn(new RuntimeRunDetailResp(run, "别人的回答", null, null, List.of(), 0L));

		assertThrows(CheckedException.class, () -> service.getRunDetail(EMPLOYEE_ID, 101L));
	}

	@Test
	void detailReturnsResolvedAnswerAndArtifacts() {
		RuntimeRunResp run = new RuntimeRunResp(101L, "DIGITAL_EMPLOYEE", EMPLOYEE_ID, EMPLOYEE_ID, null, EMPLOYEE_ID,
				"c1", null, null, "CHAT", "CHAT", "q", "SUCCEEDED", 1L, 0L, null, null, null, null, null, null, "3",
				null, null, "DIGITAL_EMPLOYEE", null);
		when(runtimeRunService.detail("7", 101L))
			.thenReturn(new RuntimeRunDetailResp(run, null, null, null, List.of(), 1L));
		List<RuntimeArtifactResp> artifacts = List.of(new RuntimeArtifactResp(1L, "single-turn",
				"single-turn-answer/v1", "{\"answer\":\"表后的结论\"}", "INTERNAL"));
		when(assembler.listArtifacts(101L)).thenReturn(artifacts);
		when(assembler.resolveFinalAnswer(null, artifacts)).thenReturn("表后的结论");

		EmployeeRunDetailResp detail = service.getRunDetail(EMPLOYEE_ID, 101L);

		assertEquals("表后的结论", detail.finalAnswer());
		assertEquals(1, detail.artifacts().size());
	}

	@Test
	void budgetReportForcesPathEmployeeId() {
		Instant from = Instant.parse("2026-08-01T00:00:00Z");
		Instant to = Instant.parse("2026-08-08T00:00:00Z");
		when(budgetReportService.report("7", from, to, EMPLOYEE_ID))
			.thenReturn(new RuntimeBudgetReportResp(from, to, List.of(), List.of()));

		service.budgetReport(EMPLOYEE_ID, from, to);

		org.mockito.Mockito.verify(budgetReportService).report("7", from, to, EMPLOYEE_ID);
	}

	@Test
	void budgetReportRejectsMissingEmployee() {
		when(employeeMapper.findByIdAndTenantId(88L, TENANT_ID)).thenReturn(null);
		assertThrows(CheckedException.class, () -> service.budgetReport(88L, null, null));
		org.mockito.Mockito.verify(budgetReportService, org.mockito.Mockito.never()).report(eq("7"),
				org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(), eq(88L));
	}

}
