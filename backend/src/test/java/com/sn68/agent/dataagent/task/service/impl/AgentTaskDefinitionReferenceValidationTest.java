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
package com.sn68.agent.dataagent.task.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.task.dto.AgentTaskDefinitionModifyReq;
import com.sn68.agent.dataagent.task.dto.AgentTaskDefinitionSaveReq;
import com.sn68.agent.dataagent.task.entity.AgentTaskDefinition;
import com.sn68.agent.dataagent.task.entity.AgentTaskVersion;
import com.sn68.agent.dataagent.task.repository.AgentTaskDefinitionMapper;
import com.sn68.agent.dataagent.task.repository.AgentTaskTriggerMapper;
import com.sn68.agent.dataagent.task.repository.AgentTaskVersionMapper;
import com.sn68.agent.dataagent.task.service.EmployeeReleaseReferenceChecker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 任务定义数字员工引用校验：digitalEmployeeId / employeeReleaseId 归属与 PUBLISHED 状态
 * 交由 {@link EmployeeReleaseReferenceChecker}（真实实现 EmployeeReleaseReferenceCheckerImpl）。
 *
 * <p>回归背景：不存在的 employeeReleaseId 或仅 SEALED 的版本不得创建/改绑任务；校验失败不得落库。</p>
 */
class AgentTaskDefinitionReferenceValidationTest {

	private static final String CURRENT_TENANT = "7";

	private static final Long DIGITAL_EMPLOYEE_ID = 9L;

	private static final Long EMPLOYEE_RELEASE_ID = 1L;

	private static final Long UNKNOWN_ID = 999999999999999999L;

	private final AgentTaskDefinitionMapper definitionMapper = mock(AgentTaskDefinitionMapper.class);

	private final AgentTaskVersionMapper versionMapper = mock(AgentTaskVersionMapper.class);

	private final AgentTaskTriggerMapper triggerMapper = mock(AgentTaskTriggerMapper.class);

	private final EmployeeReleaseReferenceChecker referenceChecker = mock(EmployeeReleaseReferenceChecker.class);

	private final DigitalEmployeeMapper digitalEmployeeMapper = mock(DigitalEmployeeMapper.class);

	private final AuthenticationContext authenticationContext = mock(AuthenticationContext.class);

	private final AgentTaskDefinitionServiceImpl service = new AgentTaskDefinitionServiceImpl(versionMapper,
			triggerMapper, referenceChecker, digitalEmployeeMapper, authenticationContext, new ObjectMapper());

	@BeforeEach
	void setUp() {
		// baseMapper 由 Spring 注入到 ServiceImpl，纯单测手动塞入
		ReflectionTestUtils.setField(service, "baseMapper", definitionMapper);
		when(authenticationContext.tenantId()).thenReturn(CURRENT_TENANT);
		when(digitalEmployeeMapper.findByIdAndTenantId(DIGITAL_EMPLOYEE_ID, CURRENT_TENANT))
			.thenReturn(DigitalEmployee.builder()
				.id(DIGITAL_EMPLOYEE_ID)
				.tenantId(CURRENT_TENANT)
				.iamPrincipalId("sp_abc")
				.build());
	}

	@Test
	void createRejectsWhenEmployeeReferenceCheckFails() {
		doThrow(CheckedException.badRequest("数字员工绑定不存在或不属于当前租户: " + UNKNOWN_ID)).when(referenceChecker)
			.validateReleaseOwner(DIGITAL_EMPLOYEE_ID, UNKNOWN_ID);

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.create(saveReq(DIGITAL_EMPLOYEE_ID, UNKNOWN_ID)));

		assertTrue(ex.getMessage().contains("数字员工绑定不存在"), "提示应指明是哪个引用无效: " + ex.getMessage());
		assertTrue(ex.getMessage().contains(String.valueOf(UNKNOWN_ID)), "提示应带上无效ID便于排查");
		verify(definitionMapper, never()).insert(any(AgentTaskDefinition.class));
		verify(versionMapper, never()).insert(any(AgentTaskVersion.class));
	}

	@Test
	void createRejectsWhenReleaseIsSealed() {
		doThrow(CheckedException.badRequest("任务只能绑定已发布的员工版本，当前状态: SEALED")).when(referenceChecker)
			.validateReleaseOwner(DIGITAL_EMPLOYEE_ID, EMPLOYEE_RELEASE_ID);

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.create(saveReq(DIGITAL_EMPLOYEE_ID, EMPLOYEE_RELEASE_ID)));

		assertTrue(ex.getMessage().contains("已发布"), ex.getMessage());
		assertTrue(ex.getMessage().contains("SEALED"), ex.getMessage());
		verify(definitionMapper, never()).insert(any(AgentTaskDefinition.class));
		verify(versionMapper, never()).insert(any(AgentTaskVersion.class));
	}

	@Test
	void createPersistsDigitalEmployeeAndReleaseWhenCheckPasses() {
		doNothing().when(referenceChecker).validateReleaseOwner(DIGITAL_EMPLOYEE_ID, EMPLOYEE_RELEASE_ID);

		service.create(saveReq(DIGITAL_EMPLOYEE_ID, EMPLOYEE_RELEASE_ID));

		ArgumentCaptor<AgentTaskDefinition> captor = ArgumentCaptor.forClass(AgentTaskDefinition.class);
		verify(definitionMapper).insert(captor.capture());
		AgentTaskDefinition saved = captor.getValue();
		assertEquals(CURRENT_TENANT, saved.getTenantId());
		assertEquals(DIGITAL_EMPLOYEE_ID, saved.getDigitalEmployeeId());
		assertEquals(EMPLOYEE_RELEASE_ID, saved.getEmployeeReleaseId());
		assertEquals("sp_abc", saved.getServicePrincipal());
		verify(referenceChecker).validateReleaseOwner(DIGITAL_EMPLOYEE_ID, EMPLOYEE_RELEASE_ID);
	}

	@Test
	void modifyRejectsSwitchingToReleaseThatFailsReferenceCheck() {
		when(definitionMapper.findByTenantAndId(CURRENT_TENANT, 1L)).thenReturn(definition());
		doThrow(CheckedException.badRequest("数字员工域未就绪，任务创建暂不可用")).when(referenceChecker)
			.validateReleaseOwner(DIGITAL_EMPLOYEE_ID, UNKNOWN_ID);

		AgentTaskDefinitionModifyReq request = new AgentTaskDefinitionModifyReq();
		request.setEmployeeReleaseId(UNKNOWN_ID);

		CheckedException ex = assertThrows(CheckedException.class, () -> service.modify(1L, request));

		assertTrue(ex.getMessage().contains("数字员工域未就绪"), ex.getMessage());
		verify(definitionMapper, never()).updateById(any(AgentTaskDefinition.class));
	}

	/** digitalEmployeeId 创建后固定：不切换 Release 的修改不触发引用校验，存量任务仍可维护业务属性。 */
	@Test
	void modifyWithoutReleaseSwitchSkipsReferenceCheck() {
		when(definitionMapper.findByTenantAndId(CURRENT_TENANT, 1L)).thenReturn(definition());

		AgentTaskDefinitionModifyReq request = new AgentTaskDefinitionModifyReq();
		request.setTaskName("日报生成 v2");

		service.modify(1L, request);

		verify(definitionMapper).updateById(any(AgentTaskDefinition.class));
		verify(referenceChecker, never()).validateReleaseOwner(any(), any());
	}

	private AgentTaskDefinitionSaveReq saveReq(Long digitalEmployeeId, Long employeeReleaseId) {
		AgentTaskDefinitionSaveReq request = new AgentTaskDefinitionSaveReq();
		request.setDigitalEmployeeId(digitalEmployeeId);
		request.setEmployeeReleaseId(employeeReleaseId);
		request.setTaskName("日报生成");
		return request;
	}

	private AgentTaskDefinition definition() {
		AgentTaskDefinition definition = AgentTaskDefinition.builder()
			.tenantId(CURRENT_TENANT)
			.digitalEmployeeId(DIGITAL_EMPLOYEE_ID)
			.employeeReleaseId(EMPLOYEE_RELEASE_ID)
			.taskName("日报生成")
			.build();
		definition.setId(1L);
		return definition;
	}

}
