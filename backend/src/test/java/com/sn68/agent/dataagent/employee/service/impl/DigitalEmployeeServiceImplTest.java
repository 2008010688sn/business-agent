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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationGrantCreateReq;
import com.sn68.agent.dataagent.authorization.model.AuthorizationGrantPermission;
import com.sn68.agent.dataagent.authorization.model.AuthorizationGrantSubjectType;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.dataagent.authorization.service.AgentAuthorizationPapService;
import com.sn68.agent.dataagent.employee.config.DigitalEmployeeProperties;
import com.sn68.agent.dataagent.employee.dto.DigitalEmployeeCreateReq;
import com.sn68.agent.dataagent.employee.dto.DigitalEmployeeModifyReq;
import com.sn68.agent.dataagent.employee.dto.DigitalEmployeeResp;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeCapability;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeDeployment;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeRelease;
import com.sn68.agent.dataagent.employee.enums.DeploymentEnvironmentDict;
import com.sn68.agent.dataagent.employee.enums.DeploymentStatusDict;
import com.sn68.agent.dataagent.employee.enums.EmployeeReleaseStatusDict;
import com.sn68.agent.dataagent.employee.enums.EmployeeStatusDict;
import com.sn68.agent.dataagent.employee.enums.PrincipalProvisionStatusDict;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeCapabilityMapper;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeDeploymentMapper;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeModelConfigMapper;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeReleaseMapper;
import com.sn68.agent.dataagent.employee.service.EmployeeDeploymentService;
import com.sn68.agent.dataagent.employee.service.EmployeeJobTemplateService;
import com.sn68.agent.dataagent.employee.service.EmployeeReleaseLifecycleService;
import com.sn68.agent.dataagent.employee.service.PrincipalProvisioningService;
import com.sn68.agent.dataagent.employee.service.PrincipalProvisioningService.ProvisionOutcome;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Principal 开通 HTTP 层：READY 成功；SKIPPED / FAILED 转为 CheckedException（避免 Controller 返回 String 触发 500）。
 */
class DigitalEmployeeServiceImplTest {

	private static final String TENANT_ID = "7";

	private static final Long EMPLOYEE_ID = 9L;

	private static final String CREATOR_USER_ID = "user-3";

	private static final Long STATE_VERSION = 1L;

	private final DigitalEmployeeMapper employeeMapper = mock(DigitalEmployeeMapper.class);

	private final PrincipalProvisioningService provisioningService = mock(PrincipalProvisioningService.class);

	private final DigitalEmployeeCapabilityMapper capabilityMapper = mock(DigitalEmployeeCapabilityMapper.class);

	private final DigitalEmployeeModelConfigMapper modelConfigMapper = mock(DigitalEmployeeModelConfigMapper.class);

	private final DigitalEmployeeReleaseMapper releaseMapper = mock(DigitalEmployeeReleaseMapper.class);

	private final DigitalEmployeeDeploymentMapper deploymentMapper = mock(DigitalEmployeeDeploymentMapper.class);

	private final EmployeeReleaseLifecycleService releaseLifecycleService = mock(EmployeeReleaseLifecycleService.class);

	private final EmployeeDeploymentService deploymentService = mock(EmployeeDeploymentService.class);

	private final AuthenticationContext authenticationContext = mock(AuthenticationContext.class);

	private final AgentAuthorizationPapService papService = mock(AgentAuthorizationPapService.class);

	private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

	private final DigitalEmployeeProperties properties = new DigitalEmployeeProperties();

	private final DigitalEmployeeServiceImpl service = new DigitalEmployeeServiceImpl(employeeMapper, releaseMapper,
			deploymentMapper, capabilityMapper, modelConfigMapper, mock(DataAgentSkillVersionMapper.class),
			provisioningService, releaseLifecycleService, deploymentService, properties, authenticationContext,
			new ObjectMapper(), new EmployeeJobTemplateService(), papService, transactionTemplate);

	@BeforeAll
	static void initTableInfo() {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
				DigitalEmployee.class);
	}

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		when(authenticationContext.tenantId()).thenReturn(TENANT_ID);
		when(employeeMapper.findByIdAndTenantId(EMPLOYEE_ID, TENANT_ID)).thenReturn(employee());
		doAnswer(invocation -> {
			invocation.getArgument(0, Consumer.class).accept(mock(TransactionStatus.class));
			return null;
		}).when(transactionTemplate).executeWithoutResult(any());
	}

	@Test
	@DisplayName("rollout skip：抛业务异常，不伪装成功")
	void skippedThrowsBadRequest() {
		when(provisioningService.provision(any())).thenReturn(
				new ProvisionOutcome(ProvisionOutcome.STATUS_SKIPPED, null, "数字员工灰度未开启"));

		CheckedException ex = assertThrows(CheckedException.class, () -> service.provisionPrincipal(EMPLOYEE_ID));
		assertTrue(ex.getMessage().contains("灰度未开启"), ex.getMessage());
	}

	@Test
	@DisplayName("开通 FAILED：抛业务异常")
	void failedThrowsBadRequest() {
		when(provisioningService.provision(any())).thenReturn(
				new ProvisionOutcome(ProvisionOutcome.STATUS_FAILED, null, "IAM 开通失败"));

		CheckedException ex = assertThrows(CheckedException.class, () -> service.provisionPrincipal(EMPLOYEE_ID));
		assertTrue(ex.getMessage().contains("IAM 开通失败"), ex.getMessage());
	}

	@Test
	@DisplayName("开通 READY：不抛异常")
	void readyDoesNotThrow() {
		when(provisioningService.provision(any())).thenReturn(
				new ProvisionOutcome(ProvisionOutcome.STATUS_READY, "sp_1", "已开通"));

		assertDoesNotThrow(() -> service.provisionPrincipal(EMPLOYEE_ID));
	}

	@Test
	@DisplayName("发布当前配置：按创建草稿、封版、发布、激活顺序执行")
	void publishCurrentConfigRunsReleaseLifecycleAndActivation() {
		when(releaseLifecycleService.createDraft(eq(EMPLOYEE_ID), any())).thenReturn(101L);
		when(deploymentService.findCurrent(eq(EMPLOYEE_ID), any())).thenReturn(null);

		assertDoesNotThrow(() -> service.publishCurrentConfig(EMPLOYEE_ID));

		InOrder order = inOrder(releaseLifecycleService, deploymentService);
		order.verify(releaseLifecycleService).createDraft(eq(EMPLOYEE_ID), any());
		order.verify(releaseLifecycleService).seal(101L);
		order.verify(releaseLifecycleService).publish(101L);
		order.verify(deploymentService).activate(eq(EMPLOYEE_ID), any());
	}

	@Test
	@DisplayName("发布当前配置：封版失败时不继续发布或激活")
	void publishCurrentConfigStopsWhenSealFails() {
		when(releaseLifecycleService.createDraft(eq(EMPLOYEE_ID), any())).thenReturn(102L);
		doThrow(CheckedException.badRequest("封版失败")).when(releaseLifecycleService).seal(102L);

		CheckedException ex = assertThrows(CheckedException.class, () -> service.publishCurrentConfig(EMPLOYEE_ID));

		assertTrue(ex.getMessage().contains("封版失败"), ex.getMessage());
		verify(releaseLifecycleService, never()).publish(102L);
		verify(deploymentService, never()).activate(eq(EMPLOYEE_ID), any());
	}

	@Test
	@DisplayName("详情：返回模型和能力统计")
	void detailIncludesModelAndCapabilityCounts() {
		when(modelConfigMapper.countByEmployeeIds(List.of(EMPLOYEE_ID), TENANT_ID))
			.thenReturn(Map.of(EMPLOYEE_ID, 2L));
		when(capabilityMapper.countByEmployeeIds(List.of(EMPLOYEE_ID), TENANT_ID))
			.thenReturn(Map.of(EMPLOYEE_ID, 3L));

		var response = service.getDetail(EMPLOYEE_ID);

		assertEquals(2L, response.getModelCount());
		assertEquals(3L, response.getCapabilityCount());
	}

	@Test
	@DisplayName("能力启停：校验归属后更新 enabled")
	void updateCapabilityEnabledUpdatesOwnedBinding() {
		DigitalEmployeeCapability capability = DigitalEmployeeCapability.builder()
			.id(12L)
			.employeeId(EMPLOYEE_ID)
			.tenantId(TENANT_ID)
			.enabled(true)
			.build();
		when(capabilityMapper.findByIdAndEmployeeId(12L, EMPLOYEE_ID, TENANT_ID)).thenReturn(capability);
		when(capabilityMapper.updateEnabledById(12L, EMPLOYEE_ID, TENANT_ID, false)).thenReturn(1);

		assertDoesNotThrow(() -> service.updateCapabilityEnabled(EMPLOYEE_ID, 12L, false));

		verify(capabilityMapper).updateEnabledById(12L, EMPLOYEE_ID, TENANT_ID, false);
	}

	@Test
	@DisplayName("能力启停：封存员工拒绝修改")
	void updateCapabilityEnabledRejectsArchivedEmployee() {
		DigitalEmployee archived = employee();
		archived.setStatus(EmployeeStatusDict.ARCHIVED.getValue());
		when(employeeMapper.findByIdAndTenantId(EMPLOYEE_ID, TENANT_ID)).thenReturn(archived);

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.updateCapabilityEnabled(EMPLOYEE_ID, 12L, true));

		assertTrue(ex.getMessage().contains("封存"), ex.getMessage());
		verify(capabilityMapper, never()).findByIdAndEmployeeId(any(), any(), any());
	}

	@Test
	@DisplayName("创建：OPS_ANALYST 预填岗位与提示词，不绑定技能")
	void createAppliesOpsAnalystTemplateWithoutBindingSkills() {
		when(authenticationContext.userId()).thenReturn(CREATOR_USER_ID);
		DigitalEmployeeCreateReq request = new DigitalEmployeeCreateReq();
		request.setEmployeeName("运营小助");
		request.setTemplateCode("OPS_ANALYST");
		when(employeeMapper.findByCodeAndTenantId(any(), eq(TENANT_ID))).thenReturn(null);
		when(employeeMapper.insert(any(DigitalEmployee.class))).thenAnswer(invocation -> {
			DigitalEmployee employee = invocation.getArgument(0);
			employee.setId(EMPLOYEE_ID);
			return 1;
		});

		DigitalEmployeeResp created = service.create(request);

		assertEquals("运营分析", created.getJobTitle());
		assertEquals("ASSISTED", created.getAutonomyLevel());
		assertEquals(EmployeeJobTemplateService.TEMPLATE_OPS_ANALYST, created.getJobTemplateCode());
		assertTrue(created.getSystemInstruction().contains("运营分析"));
		verify(capabilityMapper, never()).insert(any(DigitalEmployeeCapability.class));
		ArgumentCaptor<AuthorizationGrantCreateReq> grantCaptor = ArgumentCaptor.captor();
		InOrder order = inOrder(employeeMapper, papService);
		order.verify(employeeMapper).insert(any(DigitalEmployee.class));
		order.verify(papService).createGrant(grantCaptor.capture(), eq(TENANT_ID));
		AuthorizationGrantCreateReq grant = grantCaptor.getValue();
		assertEquals(AuthorizationOwnerType.DIGITAL_EMPLOYEE, grant.getOwnerType());
		assertEquals(EMPLOYEE_ID, grant.getOwnerId());
		assertEquals(AuthorizationGrantSubjectType.USER, grant.getSubjectType());
		assertEquals(CREATOR_USER_ID, grant.getSubjectId());
		assertEquals(AuthorizationGrantPermission.USE, grant.getPermission());
	}

	@Test
	@DisplayName("创建：创建人 ID 为空则跳过 USE Grant")
	void createSkipsGrantWhenCreatorIdEmpty() {
		when(authenticationContext.userId()).thenReturn(null);
		DigitalEmployeeCreateReq request = new DigitalEmployeeCreateReq();
		request.setEmployeeName("运营小助");
		when(employeeMapper.findByCodeAndTenantId(any(), eq(TENANT_ID))).thenReturn(null);
		when(employeeMapper.insert(any(DigitalEmployee.class))).thenAnswer(invocation -> {
			DigitalEmployee employee = invocation.getArgument(0);
			employee.setId(EMPLOYEE_ID);
			return 1;
		});

		assertDoesNotThrow(() -> service.create(request));

		verify(employeeMapper).insert(any(DigitalEmployee.class));
		verify(papService, never()).createGrant(any(), any());
	}

	@Test
	@DisplayName("创建：Grant 失败可见，不静默")
	void createGrantFailureIsVisible() {
		when(authenticationContext.userId()).thenReturn(CREATOR_USER_ID);
		DigitalEmployeeCreateReq request = new DigitalEmployeeCreateReq();
		request.setEmployeeName("运营小助");
		when(employeeMapper.findByCodeAndTenantId(any(), eq(TENANT_ID))).thenReturn(null);
		when(employeeMapper.insert(any(DigitalEmployee.class))).thenAnswer(invocation -> {
			DigitalEmployee employee = invocation.getArgument(0);
			employee.setId(EMPLOYEE_ID);
			return 1;
		});
		when(papService.createGrant(any(), eq(TENANT_ID))).thenThrow(CheckedException.badRequest("授权失败"));

		CheckedException ex = assertThrows(CheckedException.class, () -> service.create(request));
		assertTrue(ex.getMessage().contains("授权失败"), ex.getMessage());
	}

	@Test
	@DisplayName("创建：未知模板拒绝")
	void createRejectsUnknownTemplate() {
		DigitalEmployeeCreateReq request = new DigitalEmployeeCreateReq();
		request.setEmployeeName("客服");
		request.setTemplateCode("CS_BOT");

		CheckedException ex = assertThrows(CheckedException.class, () -> service.create(request));
		assertTrue(ex.getMessage().contains("未知岗位模板"), ex.getMessage());
		verify(employeeMapper, never()).insert(any(DigitalEmployee.class));
		verify(papService, never()).createGrant(any(), any());
	}

	@Test
	@DisplayName("能力启停：非本员工绑定拒绝")
	void updateCapabilityEnabledRejectsUnownedBinding() {
		when(capabilityMapper.findByIdAndEmployeeId(12L, EMPLOYEE_ID, TENANT_ID)).thenReturn(null);

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.updateCapabilityEnabled(EMPLOYEE_ID, 12L, true));

		assertTrue(ex.getMessage().contains("不属于"), ex.getMessage());
		verify(capabilityMapper, never()).updateEnabledById(eq(12L), eq(EMPLOYEE_ID), eq(TENANT_ID), eq(true));
	}

	@Test
	@DisplayName("启用：Feign provision 在 CAS 短事务之前")
	void enableProvisionsThenCas() {
		properties.getRollout().setEnabled(true);
		stubPublishedProduction();
		when(provisioningService.provision(any())).thenReturn(
				new ProvisionOutcome(ProvisionOutcome.STATUS_READY, "sp_1", "开通成功"));
		when(employeeMapper.casUpdateStatus(eq(EMPLOYEE_ID), eq(TENANT_ID), eq(EmployeeStatusDict.DRAFT.getValue()),
				eq(EmployeeStatusDict.ENABLED.getValue()), eq(STATE_VERSION))).thenReturn(1);

		assertDoesNotThrow(() -> service.enable(EMPLOYEE_ID, STATE_VERSION));

		InOrder order = inOrder(provisioningService, transactionTemplate, employeeMapper);
		order.verify(provisioningService).provision(any());
		order.verify(transactionTemplate).executeWithoutResult(any());
		verify(employeeMapper).casUpdateStatus(eq(EMPLOYEE_ID), eq(TENANT_ID), eq(EmployeeStatusDict.DRAFT.getValue()),
				eq(EmployeeStatusDict.ENABLED.getValue()), eq(STATE_VERSION));
	}

	@Test
	@DisplayName("启用：provision 失败不走 CAS")
	void enableDoesNotCasWhenProvisionFails() {
		properties.getRollout().setEnabled(true);
		stubPublishedProduction();
		when(provisioningService.provision(any())).thenReturn(
				new ProvisionOutcome(ProvisionOutcome.STATUS_FAILED, null, "IAM 开通失败"));

		CheckedException ex = assertThrows(CheckedException.class, () -> service.enable(EMPLOYEE_ID, STATE_VERSION));
		assertTrue(ex.getMessage().contains("Principal 未就绪"), ex.getMessage());
		verify(transactionTemplate, never()).executeWithoutResult(any());
		verify(employeeMapper, never()).casUpdateStatus(any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("修改：只更新草稿字段，不碰 status/stateVersion/principal")
	void modifyDoesNotTouchStatusOrPrincipal() {
		DigitalEmployee existing = employee();
		existing.setStatus(EmployeeStatusDict.ENABLED.getValue());
		existing.setStateVersion(5L);
		existing.setPrincipalStatus(PrincipalProvisionStatusDict.READY.getValue());
		existing.setIamPrincipalId("sp_1");
		existing.setDraftRevision(2);
		when(employeeMapper.findByIdAndTenantId(EMPLOYEE_ID, TENANT_ID)).thenReturn(existing);
		when(employeeMapper.update(isNull(), any())).thenReturn(1);
		DigitalEmployeeModifyReq request = new DigitalEmployeeModifyReq();
		request.setEmployeeName("新名称");

		assertDoesNotThrow(() -> service.modify(EMPLOYEE_ID, request));

		verify(employeeMapper, never()).updateById(any(DigitalEmployee.class));
		ArgumentCaptor<LambdaUpdateWrapper<DigitalEmployee>> captor = ArgumentCaptor.captor();
		verify(employeeMapper).update(isNull(), captor.capture());
		String sqlSet = captor.getValue().getSqlSet();
		assertTrue(sqlSet.contains("employee_name") || sqlSet.contains("employeeName"), sqlSet);
		assertTrue(sqlSet.contains("draft_revision") || sqlSet.contains("draftRevision"), sqlSet);
		assertFalse(sqlSet.matches("(?s).*\\bstatus\\s*=.*"), sqlSet);
		assertFalse(sqlSet.contains("state_version") || sqlSet.contains("stateVersion"), sqlSet);
		assertFalse(sqlSet.contains("principal_status") || sqlSet.contains("principalStatus"), sqlSet);
		assertFalse(sqlSet.contains("iam_principal_id") || sqlSet.contains("iamPrincipalId"), sqlSet);
		assertFalse(sqlSet.contains("tenant_id") || sqlSet.contains("tenantId"), sqlSet);
	}

	private void stubPublishedProduction() {
		DigitalEmployeeDeployment deployment = DigitalEmployeeDeployment.builder()
			.employeeId(EMPLOYEE_ID)
			.tenantId(TENANT_ID)
			.environment(DeploymentEnvironmentDict.PRODUCTION.getValue())
			.status(DeploymentStatusDict.ACTIVE.getValue())
			.activeReleaseId(88L)
			.build();
		when(deploymentMapper.findByEmployeeAndEnvironment(EMPLOYEE_ID,
				DeploymentEnvironmentDict.PRODUCTION.getValue(), TENANT_ID)).thenReturn(deployment);
		DigitalEmployeeRelease release = DigitalEmployeeRelease.builder()
			.id(88L)
			.tenantId(TENANT_ID)
			.status(EmployeeReleaseStatusDict.PUBLISHED.getValue())
			.build();
		when(releaseMapper.findByIdAndTenantId(88L, TENANT_ID)).thenReturn(release);
	}

	private DigitalEmployee employee() {
		return DigitalEmployee.builder()
			.id(EMPLOYEE_ID)
			.tenantId(TENANT_ID)
			.employeeName("测试员工")
			.status(EmployeeStatusDict.DRAFT.getValue())
			.stateVersion(STATE_VERSION)
			.principalStatus(PrincipalProvisionStatusDict.PENDING.getValue())
			.draftRevision(0)
			.build();
	}

}
