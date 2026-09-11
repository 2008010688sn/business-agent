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
package com.sn68.agent.dataagent.employee.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sn68.agent.dataagent.employee.dto.DigitalEmployeeCreateReq;
import com.sn68.agent.dataagent.employee.dto.EmployeeJobTemplateResp;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmployeeJobTemplateServiceTest {

	private final EmployeeJobTemplateService service = new EmployeeJobTemplateService();

	@Test
	@DisplayName("清单仅含 OPS_ANALYST，且不带 SkillVersion ID")
	void listContainsOpsAnalystWithoutSkillVersionIds() {
		assertEquals(1, service.list().size());
		EmployeeJobTemplateResp template = service.list().get(0);
		assertEquals(EmployeeJobTemplateService.TEMPLATE_OPS_ANALYST, template.getTemplateCode());
		assertEquals("运营分析", template.getJobTitle());
		assertEquals("ASSISTED", template.getAutonomyLevel());
		assertTrue(template.getRecommendedSkills().stream().noneMatch(item -> item.getSkillCode() == null));
		assertTrue(template.getRecommendedTask().getTriggerHint().contains("发布生产"));
	}

	@Test
	@DisplayName("未知模板失败关闭")
	void unknownTemplateRejected() {
		CheckedException ex = assertThrows(CheckedException.class, () -> service.require("CS_BOT"));
		assertTrue(ex.getMessage().contains("未知岗位模板"), ex.getMessage());
	}

	@Test
	@DisplayName("空白字段按模板预填，已填岗位不被覆盖")
	void applyDefaultsFillsBlankFieldsOnly() {
		DigitalEmployeeCreateReq request = new DigitalEmployeeCreateReq();
		request.setEmployeeName("运营小助");
		request.setJobTitle("自定义岗");
		request.setTemplateCode("OPS_ANALYST");

		service.applyDefaults(request);

		assertEquals("自定义岗", request.getJobTitle());
		assertEquals("ASSISTED", request.getAutonomyLevel());
		assertTrue(request.getSystemInstruction().contains("运营分析"));
		assertEquals(EmployeeJobTemplateService.TEMPLATE_OPS_ANALYST,
				request.getExecutionPolicy().get(EmployeeJobTemplateService.JOB_TEMPLATE_CODE_KEY));
	}

	@Test
	@DisplayName("无模板编码时不改请求")
	void blankTemplateCodeIsNoop() {
		DigitalEmployeeCreateReq request = new DigitalEmployeeCreateReq();
		request.setEmployeeName("空白员工");
		service.applyDefaults(request);
		assertEquals(null, request.getJobTitle());
		assertEquals(null, request.getExecutionPolicy());
	}

	@Test
	@DisplayName("已有运营配置时合并 jobTemplateCode，不覆盖其它键")
	void applyDefaultsMergesExecutionPolicy() {
		DigitalEmployeeCreateReq request = new DigitalEmployeeCreateReq();
		request.setEmployeeName("运营小助");
		request.setTemplateCode("ops_analyst");
		request.setExecutionPolicy(Map.of("budgetWarn", true));

		service.applyDefaults(request);

		assertEquals(true, request.getExecutionPolicy().get("budgetWarn"));
		assertEquals(EmployeeJobTemplateService.TEMPLATE_OPS_ANALYST,
				request.getExecutionPolicy().get(EmployeeJobTemplateService.JOB_TEMPLATE_CODE_KEY));
	}

}
