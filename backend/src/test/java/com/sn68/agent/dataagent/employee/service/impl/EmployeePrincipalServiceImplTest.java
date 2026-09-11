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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.iam.LocalPrincipalStore;
import com.sn68.agent.dataagent.iam.dto.ServicePrincipalRolesReplaceReq;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmployeePrincipalServiceImplTest {

	private static final String TENANT_ID = "7";

	private static final Long EMPLOYEE_ID = 9L;

	private final DigitalEmployeeMapper employeeMapper = mock(DigitalEmployeeMapper.class);

	private final LocalPrincipalStore store = new LocalPrincipalStore();

	private final AuthenticationContext authenticationContext = mock(AuthenticationContext.class);

	private final EmployeePrincipalServiceImpl service = new EmployeePrincipalServiceImpl(employeeMapper, store,
			authenticationContext);

	private DigitalEmployee employee(String principalId) {
		return DigitalEmployee.builder().id(EMPLOYEE_ID).tenantId(TENANT_ID).iamPrincipalId(principalId).build();
	}

	@Test
	@DisplayName("无 Principal 时角色回显为空列表")
	void listRolesWithoutPrincipalReturnsEmpty() {
		when(authenticationContext.tenantId()).thenReturn(TENANT_ID);
		when(employeeMapper.findByIdAndTenantId(EMPLOYEE_ID, TENANT_ID)).thenReturn(employee(null));

		assertTrue(service.listRoles(EMPLOYEE_ID).isEmpty());
	}

	@Test
	@DisplayName("未开通 Principal 时替换角色失败关闭")
	void replaceRolesWithoutPrincipalFailsClosed() {
		when(authenticationContext.tenantId()).thenReturn(TENANT_ID);
		when(employeeMapper.findByIdAndTenantId(EMPLOYEE_ID, TENANT_ID)).thenReturn(employee(null));

		assertThrows(CheckedException.class,
				() -> service.replaceRoles(EMPLOYEE_ID, new ServicePrincipalRolesReplaceReq()));
	}

}
