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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeDigest;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeDigestMapper;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRun;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeRunFeedbackMapper;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeRunMapper;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DigitalEmployeeDigestServiceImplTest {

	private DigitalEmployeeMapper employeeMapper;

	private DigitalEmployeeDigestMapper digestMapper;

	private AgentRuntimeRunMapper runMapper;

	private AgentRuntimeRunFeedbackMapper feedbackMapper;

	private DigitalEmployeeDigestServiceImpl service;

	@BeforeEach
	void setUp() {
		employeeMapper = mock(DigitalEmployeeMapper.class);
		digestMapper = mock(DigitalEmployeeDigestMapper.class);
		runMapper = mock(AgentRuntimeRunMapper.class);
		feedbackMapper = mock(AgentRuntimeRunFeedbackMapper.class);
		service = new DigitalEmployeeDigestServiceImpl(employeeMapper, digestMapper, runMapper, feedbackMapper,
				mock(AuthenticationContext.class));
	}

	@Test
	void writesSummaryForEnabledEmployeeWithManager() {
		LocalDate date = LocalDate.of(2026, 8, 24);
		DigitalEmployee employee = DigitalEmployee.builder()
			.id(9L)
			.tenantId("7")
			.managerUserId("88")
			.build();
		when(employeeMapper.listEnabledWithManager()).thenReturn(List.of(employee));
		when(digestMapper.findByTenantEmployeeDate("7", 9L, date)).thenReturn(null);
		AgentRuntimeRun ok = AgentRuntimeRun.builder().id(1L).state("SUCCEEDED").build();
		AgentRuntimeRun failed = AgentRuntimeRun.builder().id(2L).state("FAILED").build();
		AgentRuntimeRun waiting = AgentRuntimeRun.builder().id(3L).state("WAITING_APPROVAL").build();
		when(runMapper.listByEmployeeCreatedBetween(eq("7"), eq(9L), any(), any()))
			.thenReturn(List.of(ok, failed, waiting));
		when(feedbackMapper.countDownByRunIds(eq("7"), any())).thenReturn(1L);
		when(digestMapper.insert(any(DigitalEmployeeDigest.class))).thenReturn(1);

		int written = service.generateForDate(date);

		assertEquals(1, written);
		ArgumentCaptor<DigitalEmployeeDigest> captor = ArgumentCaptor.forClass(DigitalEmployeeDigest.class);
		verify(digestMapper).insert(captor.capture());
		DigitalEmployeeDigest row = captor.getValue();
		assertEquals(3, row.getRunTotal());
		assertEquals(1, row.getRunFailed());
		assertEquals(1, row.getWaitingApproval());
		assertEquals(1, row.getFeedbackDown());
		assertTrue(row.getSummary().contains("差评 1"));
	}

	@Test
	void skipsWhenDigestAlreadyExists() {
		LocalDate date = LocalDate.of(2026, 8, 24);
		DigitalEmployee employee = DigitalEmployee.builder().id(9L).tenantId("7").managerUserId("88").build();
		when(employeeMapper.listEnabledWithManager()).thenReturn(List.of(employee));
		when(digestMapper.findByTenantEmployeeDate("7", 9L, date)).thenReturn(DigitalEmployeeDigest.builder().id(1L).build());

		assertEquals(0, service.generateForDate(date));
		verify(digestMapper, never()).insert(any(DigitalEmployeeDigest.class));
	}

}
