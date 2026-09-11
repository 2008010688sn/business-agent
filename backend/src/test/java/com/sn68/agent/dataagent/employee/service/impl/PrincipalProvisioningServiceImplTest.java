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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sn68.agent.dataagent.employee.config.DigitalEmployeeProperties;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.enums.PrincipalProvisionStatusDict;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.employee.service.PrincipalProvisioningService.ProvisionOutcome;
import com.sn68.agent.dataagent.iam.LocalPrincipalStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PrincipalProvisioningServiceImplTest {

	private static final String TENANT_ID = "7";

	private static final Long EMPLOYEE_ID = 9L;

	private final LocalPrincipalStore store = new LocalPrincipalStore();

	private final DigitalEmployeeMapper employeeMapper = mock(DigitalEmployeeMapper.class);

	private final DigitalEmployeeProperties properties = new DigitalEmployeeProperties();

	private final PrincipalProvisioningServiceImpl service = new PrincipalProvisioningServiceImpl(store, employeeMapper,
			properties);

	private DigitalEmployee employee() {
		return DigitalEmployee.builder()
			.id(EMPLOYEE_ID)
			.tenantId(TENANT_ID)
			.employeeName("测试员工")
			.principalStatus(PrincipalProvisionStatusDict.PENDING.getValue())
			.build();
	}

	@Test
	@DisplayName("rollout=false：零开通调用，返回 SKIPPED")
	void rolloutDisabledSkipsIamEntirely() {
		DigitalEmployee employee = employee();

		ProvisionOutcome outcome = service.provision(employee);

		assertEquals(ProvisionOutcome.STATUS_SKIPPED, outcome.status());
		assertTrue(outcome.message().startsWith("数字员工灰度未开启"), outcome.message());
		verifyNoInteractions(employeeMapper);
	}

	@Test
	@DisplayName("Principal 已 READY：幂等直接返回")
	void alreadyReadyIsIdempotent() {
		properties.getRollout().setEnabled(true);
		DigitalEmployee employee = employee();
		employee.setPrincipalStatus(PrincipalProvisionStatusDict.READY.getValue());
		employee.setIamPrincipalId("sp_123");

		ProvisionOutcome outcome = service.provision(employee);

		assertEquals(ProvisionOutcome.STATUS_READY, outcome.status());
		assertEquals("sp_123", outcome.principalId());
		verifyNoInteractions(employeeMapper);
	}

	@Test
	@DisplayName("开通成功：本地 Principal 回写 READY")
	void provisionSuccessWritesReady() {
		properties.getRollout().setEnabled(true);
		DigitalEmployee employee = employee();

		ProvisionOutcome outcome = service.provision(employee);

		assertEquals(ProvisionOutcome.STATUS_READY, outcome.status());
		assertEquals("sp_9", outcome.principalId());
		verify(employeeMapper).casUpdateProvision(eq(EMPLOYEE_ID), eq(TENANT_ID),
				eq(PrincipalProvisionStatusDict.PENDING.getValue()), eq("sp_9"),
				eq(PrincipalProvisionStatusDict.READY.getValue()), eq(1L));
	}

}
