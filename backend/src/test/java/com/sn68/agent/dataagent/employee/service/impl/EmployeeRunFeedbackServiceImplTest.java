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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.employee.dto.EmployeeRunFeedbackReq;
import com.sn68.agent.dataagent.employee.dto.EmployeeRunFeedbackResp;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunDetailResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunResp;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRunFeedback;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeRunFeedbackMapper;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeRunService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmployeeRunFeedbackServiceImplTest {

	private DigitalEmployeeMapper employeeMapper;

	private RuntimeRunService runtimeRunService;

	private AgentRuntimeRunFeedbackMapper feedbackMapper;

	private AuthenticationContext authenticationContext;

	private EmployeeRunFeedbackServiceImpl service;

	@BeforeEach
	void setUp() {
		employeeMapper = mock(DigitalEmployeeMapper.class);
		runtimeRunService = mock(RuntimeRunService.class);
		feedbackMapper = mock(AgentRuntimeRunFeedbackMapper.class);
		authenticationContext = mock(AuthenticationContext.class);
		service = new EmployeeRunFeedbackServiceImpl(employeeMapper, runtimeRunService, feedbackMapper,
				authenticationContext);
		when(authenticationContext.tenantId()).thenReturn("7");
		when(authenticationContext.userId()).thenReturn("3");
		when(employeeMapper.findByIdAndTenantId(9L, "7"))
			.thenReturn(DigitalEmployee.builder().id(9L).tenantId("7").build());
		RuntimeRunResp run = new RuntimeRunResp(101L, "DIGITAL_EMPLOYEE", 9L, 9L, null, 9L, "c", null, null, "CHAT",
				"CHAT", "q", "SUCCEEDED", 1L, 0L, null, null, null, null, null, null, "3", null, null,
				"DIGITAL_EMPLOYEE", null);
		when(runtimeRunService.detail("7", 101L))
			.thenReturn(new RuntimeRunDetailResp(run, "ok", null, null, List.of(), 0L));
	}

	@Test
	void insertsNewFeedback() {
		when(feedbackMapper.findByTenantRunUser("7", 101L, "3")).thenReturn(null);
		when(feedbackMapper.insert(any(AgentRuntimeRunFeedback.class))).thenReturn(1);
		EmployeeRunFeedbackReq req = new EmployeeRunFeedbackReq();
		req.setRating("down");
		req.setComment("应列出库存");

		EmployeeRunFeedbackResp resp = service.save(9L, 101L, req);

		assertEquals("DOWN", resp.rating());
		assertEquals("应列出库存", resp.comment());
	}

	@Test
	void rejectsUnknownRating() {
		EmployeeRunFeedbackReq req = new EmployeeRunFeedbackReq();
		req.setRating("OK");
		assertThrows(CheckedException.class, () -> service.save(9L, 101L, req));
	}

}
