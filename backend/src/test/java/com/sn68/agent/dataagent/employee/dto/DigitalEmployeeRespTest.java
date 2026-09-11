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
package com.sn68.agent.dataagent.employee.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import org.junit.jupiter.api.Test;

/**
 * 数字员工响应 DTO：rollout 开关与档案字段投影。
 */
class DigitalEmployeeRespTest {

	@Test
	void fromCopiesArchiveAndRolloutFlag() {
		DigitalEmployee employee = DigitalEmployee.builder()
			.employeeCode("DE-1")
			.employeeName("财务助手")
			.status("DRAFT")
			.iamPrincipalId("sp_1")
			.build();
		employee.setId(99L);

		DigitalEmployeeResp resp = DigitalEmployeeResp.from(employee, true);

		assertEquals(99L, resp.getId());
		assertEquals("财务助手", resp.getEmployeeName());
		assertEquals("sp_1", resp.getIamPrincipalId());
		assertTrue(resp.getRolloutEnabled());
	}

}
