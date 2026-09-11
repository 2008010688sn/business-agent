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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeCapability;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeRelease;
import com.sn68.agent.dataagent.employee.enums.EmployeeReleaseStatusDict;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeCapabilityMapper;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeDeploymentMapper;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeReleaseMapper;
import com.sn68.agent.dataagent.employee.service.EmployeeReleaseSnapshotAssembler;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.task.repository.AgentTaskDefinitionMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Release 生命周期测试：Seal/Publish CAS 0 行并发冲突显式报错；
 * Retire 被任务定义或员工部署引用时 fail-closed 拒绝。
 */
class EmployeeReleaseLifecycleServiceImplTest {

	private static final String TENANT_ID = "7";

	private static final Long EMPLOYEE_ID = 9L;

	private static final Long RELEASE_ID = 1L;

	private final DigitalEmployeeMapper employeeMapper = mock(DigitalEmployeeMapper.class);

	private final DigitalEmployeeReleaseMapper releaseMapper = mock(DigitalEmployeeReleaseMapper.class);

	private final DigitalEmployeeCapabilityMapper capabilityMapper = mock(DigitalEmployeeCapabilityMapper.class);

	private final DataAgentSkillVersionMapper skillVersionMapper = mock(DataAgentSkillVersionMapper.class);

	private final DigitalEmployeeDeploymentMapper deploymentMapper = mock(DigitalEmployeeDeploymentMapper.class);

	private final AgentTaskDefinitionMapper taskDefinitionMapper = mock(AgentTaskDefinitionMapper.class);

	private final EmployeeReleaseSnapshotAssembler assembler = new EmployeeReleaseSnapshotAssembler(
			new com.fasterxml.jackson.databind.ObjectMapper(), skillVersionMapper,
			mock(com.sn68.agent.dataagent.employee.repository.DigitalEmployeeModelConfigMapper.class));

	private final AuthenticationContext authenticationContext = mock(AuthenticationContext.class);

	private EmployeeReleaseLifecycleServiceImpl service;

	@BeforeEach
	void setUp() {
		service = new EmployeeReleaseLifecycleServiceImpl(employeeMapper, releaseMapper, capabilityMapper,
				skillVersionMapper, deploymentMapper, taskDefinitionMapper, assembler, authenticationContext);
		when(authenticationContext.tenantId()).thenReturn(TENANT_ID);
		when(authenticationContext.userId()).thenReturn("3");
		when(employeeMapper.findByIdAndTenantId(EMPLOYEE_ID, TENANT_ID)).thenReturn(DigitalEmployee.builder()
			.id(EMPLOYEE_ID)
			.tenantId(TENANT_ID)
			.build());
		when(capabilityMapper.findEnabledByEmployeeId(EMPLOYEE_ID, TENANT_ID)).thenReturn(List.of());
	}

	private DigitalEmployeeRelease release(String status) {
		return DigitalEmployeeRelease.builder()
			.id(RELEASE_ID)
			.tenantId(TENANT_ID)
			.employeeId(EMPLOYEE_ID)
			.status(status)
			.build();
	}

	@Test
	@DisplayName("Seal：CAS 命中 0 行（并发封版）显式抛并发冲突")
	void sealConflictWhenCasReturnsZero() {
		when(releaseMapper.findByIdAndTenantId(RELEASE_ID, TENANT_ID))
			.thenReturn(release(EmployeeReleaseStatusDict.DRAFT.getValue()));
		when(releaseMapper.casSeal(eq(RELEASE_ID), eq(TENANT_ID), anyString(), anyString(), anyString()))
			.thenReturn(0);

		CheckedException ex = assertThrows(CheckedException.class, () -> service.seal(RELEASE_ID));

		assertTrue(ex.getMessage().contains("并发"), ex.getMessage());
	}

	@Test
	@DisplayName("Seal：非 DRAFT 状态直接拒绝（不发起 CAS）")
	void sealRejectsNonDraftStatus() {
		when(releaseMapper.findByIdAndTenantId(RELEASE_ID, TENANT_ID))
			.thenReturn(release(EmployeeReleaseStatusDict.PUBLISHED.getValue()));

		CheckedException ex = assertThrows(CheckedException.class, () -> service.seal(RELEASE_ID));

		assertTrue(ex.getMessage().contains("仅草稿"), ex.getMessage());
		verify(releaseMapper, never()).casSeal(anyLong(), anyString(), anyString(), anyString(), anyString());
	}

	@Test
	@DisplayName("Publish：CAS 命中 0 行（并发发布）显式抛并发冲突")
	void publishConflictWhenCasReturnsZero() {
		when(releaseMapper.findByIdAndTenantId(RELEASE_ID, TENANT_ID))
			.thenReturn(release(EmployeeReleaseStatusDict.SEALED.getValue()));
		when(releaseMapper.casPublish(RELEASE_ID, TENANT_ID, "3")).thenReturn(0);

		CheckedException ex = assertThrows(CheckedException.class, () -> service.publish(RELEASE_ID));

		assertTrue(ex.getMessage().contains("并发"), ex.getMessage());
	}

	@Test
	@DisplayName("Retire：仍被任务定义引用时拒绝且不发起 CAS")
	void retireRejectedWhileTaskReferences() {
		when(releaseMapper.findByIdAndTenantId(RELEASE_ID, TENANT_ID))
			.thenReturn(release(EmployeeReleaseStatusDict.PUBLISHED.getValue()));
		when(taskDefinitionMapper.countByEmployeeReleaseId(RELEASE_ID)).thenReturn(2L);
		when(deploymentMapper.countByActiveReleaseId(RELEASE_ID)).thenReturn(0L);

		CheckedException ex = assertThrows(CheckedException.class, () -> service.retire(RELEASE_ID));

		assertTrue(ex.getMessage().contains("任务定义"), ex.getMessage());
		verify(releaseMapper, never()).casRetire(anyLong(), anyString());
	}

	@Test
	@DisplayName("Retire：仍被员工部署激活引用时拒绝且不发起 CAS")
	void retireRejectedWhileDeploymentReferences() {
		when(releaseMapper.findByIdAndTenantId(RELEASE_ID, TENANT_ID))
			.thenReturn(release(EmployeeReleaseStatusDict.PUBLISHED.getValue()));
		when(taskDefinitionMapper.countByEmployeeReleaseId(RELEASE_ID)).thenReturn(0L);
		when(deploymentMapper.countByActiveReleaseId(RELEASE_ID)).thenReturn(1L);

		CheckedException ex = assertThrows(CheckedException.class, () -> service.retire(RELEASE_ID));

		assertTrue(ex.getMessage().contains("部署"), ex.getMessage());
		verify(releaseMapper, never()).casRetire(anyLong(), anyString());
	}

	@Test
	@DisplayName("Retire：无引用且 CAS 命中时正常退役")
	void retireSucceedsWithoutReferences() {
		when(releaseMapper.findByIdAndTenantId(RELEASE_ID, TENANT_ID))
			.thenReturn(release(EmployeeReleaseStatusDict.PUBLISHED.getValue()));
		when(taskDefinitionMapper.countByEmployeeReleaseId(RELEASE_ID)).thenReturn(0L);
		when(deploymentMapper.countByActiveReleaseId(RELEASE_ID)).thenReturn(0L);
		when(releaseMapper.casRetire(RELEASE_ID, TENANT_ID)).thenReturn(1);

		assertDoesNotThrow(() -> service.retire(RELEASE_ID));

		verify(releaseMapper).casRetire(RELEASE_ID, TENANT_ID);
	}

	@Test
	@DisplayName("requireReleaseOwnedByEmployee：非 SEALED/PUBLISHED 状态拒绝")
	void requireReleaseOwnedRejectsDraftStatus() {
		when(releaseMapper.findByIdAndTenantId(RELEASE_ID, TENANT_ID))
			.thenReturn(release(EmployeeReleaseStatusDict.DRAFT.getValue()));

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.requireReleaseOwnedByEmployee(EMPLOYEE_ID, RELEASE_ID));

		assertTrue(ex.getMessage().contains("状态不可用"), ex.getMessage());
	}

	@Test
	@DisplayName("requireReleaseOwnedByEmployee：SEALED 仍可通过（非任务绑定场景）")
	void requireReleaseOwnedAllowsSealed() {
		when(releaseMapper.findByIdAndTenantId(RELEASE_ID, TENANT_ID))
			.thenReturn(release(EmployeeReleaseStatusDict.SEALED.getValue()));

		assertDoesNotThrow(() -> service.requireReleaseOwnedByEmployee(EMPLOYEE_ID, RELEASE_ID));
	}

	@Test
	@DisplayName("requirePublishedReleaseOwnedByEmployee：SEALED 拒绝（任务定义必须 PUBLISHED）")
	void requirePublishedReleaseOwnedRejectsSealed() {
		when(releaseMapper.findByIdAndTenantId(RELEASE_ID, TENANT_ID))
			.thenReturn(release(EmployeeReleaseStatusDict.SEALED.getValue()));

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.requirePublishedReleaseOwnedByEmployee(EMPLOYEE_ID, RELEASE_ID));

		assertTrue(ex.getMessage().contains("已发布"), ex.getMessage());
		assertTrue(ex.getMessage().contains("SEALED"), ex.getMessage());
	}

	@Test
	@DisplayName("requirePublishedReleaseOwnedByEmployee：PUBLISHED 通过")
	void requirePublishedReleaseOwnedAllowsPublished() {
		when(releaseMapper.findByIdAndTenantId(RELEASE_ID, TENANT_ID))
			.thenReturn(release(EmployeeReleaseStatusDict.PUBLISHED.getValue()));

		assertDoesNotThrow(() -> service.requirePublishedReleaseOwnedByEmployee(EMPLOYEE_ID, RELEASE_ID));
	}

	@Test
	@DisplayName("requireReleaseOwnedByEmployee：归属不匹配拒绝")
	void requireReleaseOwnedRejectsForeignRelease() {
		when(releaseMapper.findByIdAndTenantId(RELEASE_ID, TENANT_ID)).thenReturn(DigitalEmployeeRelease.builder()
			.id(RELEASE_ID)
			.tenantId(TENANT_ID)
			.employeeId(999L)
			.status(EmployeeReleaseStatusDict.PUBLISHED.getValue())
			.build());

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.requireReleaseOwnedByEmployee(EMPLOYEE_ID, RELEASE_ID));

		assertTrue(ex.getMessage().contains("不属于"), ex.getMessage());
	}

	@Test
	@DisplayName("Seal 成功路径：重新装配快照并计算 spec_hash 后落库")
	void sealSuccessFreezesSnapshotWithSpecHash() {
		when(releaseMapper.findByIdAndTenantId(RELEASE_ID, TENANT_ID))
			.thenReturn(release(EmployeeReleaseStatusDict.DRAFT.getValue()));
		when(releaseMapper.casSeal(eq(RELEASE_ID), eq(TENANT_ID), anyString(), anyString(), anyString()))
			.thenReturn(1);

		assertDoesNotThrow(() -> service.seal(RELEASE_ID));

		verify(releaseMapper).casSeal(eq(RELEASE_ID), eq(TENANT_ID), anyString(), anyString(), eq("3"));
	}

	@Test
	@DisplayName("Seal：跨租户技能版本一律拒绝，市场已审核也不能开口")
	void sealRejectsCrossTenantSkillEvenIfMarketApproved() {
		when(releaseMapper.findByIdAndTenantId(RELEASE_ID, TENANT_ID))
			.thenReturn(release(EmployeeReleaseStatusDict.DRAFT.getValue()));
		when(capabilityMapper.findEnabledByEmployeeId(EMPLOYEE_ID, TENANT_ID)).thenReturn(List.of(
			DigitalEmployeeCapability.builder()
				.id(10L)
				.tenantId(TENANT_ID)
				.employeeId(EMPLOYEE_ID)
				.skillVersionId(900L)
				.enabled(true)
				.build()));
		when(skillVersionMapper.selectById(900L)).thenReturn(DataAgentSkillVersion.builder()
			.id(900L)
			.tenantId("publisher-tenant")
			.status("PUBLISHED")
			.deleted(false)
			.build());

		CheckedException ex = assertThrows(CheckedException.class, () -> service.seal(RELEASE_ID));

		assertTrue(ex.getMessage().contains("不属于当前租户"), ex.getMessage());
		verify(releaseMapper, never()).casSeal(anyLong(), anyString(), anyString(), anyString(), anyString());
	}

	@Test
	@DisplayName("Seal：已撤销技能版本拒绝进入新快照")
	void sealRejectsRevokedSkillVersion() {
		when(releaseMapper.findByIdAndTenantId(RELEASE_ID, TENANT_ID))
			.thenReturn(release(EmployeeReleaseStatusDict.DRAFT.getValue()));
		when(capabilityMapper.findEnabledByEmployeeId(EMPLOYEE_ID, TENANT_ID)).thenReturn(List.of(
			com.sn68.agent.dataagent.employee.entity.DigitalEmployeeCapability.builder()
				.id(12L)
				.tenantId(TENANT_ID)
				.employeeId(EMPLOYEE_ID)
				.skillVersionId(902L)
				.enabled(true)
				.build()));
		when(skillVersionMapper.selectById(902L)).thenReturn(com.sn68.agent.dataagent.entity.DataAgentSkillVersion.builder()
			.id(902L)
			.tenantId(TENANT_ID)
			.status("PUBLISHED")
			.deleted(false)
			.revoked(true)
			.build());

		CheckedException ex = assertThrows(CheckedException.class, () -> service.seal(RELEASE_ID));

		assertTrue(ex.getMessage().contains("不存在或未发布"), ex.getMessage());
		verify(releaseMapper, never()).casSeal(anyLong(), anyString(), anyString(), anyString(), anyString());
	}

}
