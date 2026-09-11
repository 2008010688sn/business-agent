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

import com.sn68.agent.dataagent.employee.dto.EmployeeDeploymentActivateReq;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeDeployment;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeRelease;
import com.sn68.agent.dataagent.employee.enums.DeploymentStatusDict;
import com.sn68.agent.dataagent.employee.enums.EmployeeReleaseStatusDict;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeDeploymentMapper;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeReleaseMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 部署服务测试：deployment_version CAS 防并发重复部署、首次部署 REQUIRES_NEW 初始化与并发初始化兜底。
 */
class EmployeeDeploymentServiceImplTest {

	private static final String TENANT_ID = "7";

	private static final Long EMPLOYEE_ID = 9L;

	private static final Long RELEASE_ID = 3L;

	private final DigitalEmployeeMapper employeeMapper = mock(DigitalEmployeeMapper.class);

	private final DigitalEmployeeReleaseMapper releaseMapper = mock(DigitalEmployeeReleaseMapper.class);

	private final DigitalEmployeeDeploymentMapper deploymentMapper = mock(DigitalEmployeeDeploymentMapper.class);

	private final AuthenticationContext authenticationContext = mock(AuthenticationContext.class);

	private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

	private EmployeeDeploymentServiceImpl service;

	@BeforeEach
	void setUp() {
		when(transactionManager.getTransaction(any(TransactionDefinition.class)))
			.thenReturn(mock(TransactionStatus.class));
		service = new EmployeeDeploymentServiceImpl(employeeMapper, releaseMapper, deploymentMapper,
				authenticationContext, new TransactionTemplate(transactionManager));
		when(authenticationContext.tenantId()).thenReturn(TENANT_ID);
		when(authenticationContext.userId()).thenReturn("3");
		when(employeeMapper.findByIdAndTenantId(EMPLOYEE_ID, TENANT_ID)).thenReturn(DigitalEmployee.builder()
			.id(EMPLOYEE_ID)
			.tenantId(TENANT_ID)
			.build());
		when(releaseMapper.findByIdAndTenantId(RELEASE_ID, TENANT_ID)).thenReturn(DigitalEmployeeRelease.builder()
			.id(RELEASE_ID)
			.tenantId(TENANT_ID)
			.employeeId(EMPLOYEE_ID)
			.status(EmployeeReleaseStatusDict.PUBLISHED.getValue())
			.build());
	}

	private EmployeeDeploymentActivateReq activateReq(int expectVersion) {
		EmployeeDeploymentActivateReq req = new EmployeeDeploymentActivateReq();
		req.setReleaseId(RELEASE_ID);
		req.setEnvironment("PRODUCTION");
		req.setExpectVersion(expectVersion);
		return req;
	}

	@Test
	@DisplayName("CAS 命中 0 行（并发部署）显式抛并发冲突，不静默覆盖")
	void activateFailsWhenConcurrentCasReturnsZero() {
		when(deploymentMapper.findByEmployeeAndEnvironment(EMPLOYEE_ID, "PRODUCTION", TENANT_ID))
			.thenReturn(DigitalEmployeeDeployment.builder()
				.id(1L)
				.tenantId(TENANT_ID)
				.employeeId(EMPLOYEE_ID)
				.environment("PRODUCTION")
				.deploymentVersion(5)
				.activeReleaseId(2L)
				.build());
		when(deploymentMapper.casActivate(eq(1L), eq(TENANT_ID), eq(5), eq(RELEASE_ID), eq(2L), anyString()))
			.thenReturn(0);

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.activate(EMPLOYEE_ID, activateReq(5)));

		assertTrue(ex.getMessage().contains("并发"), ex.getMessage());
	}

	@Test
	@DisplayName("expectVersion 与当前部署版本不一致：直接拒绝，不发起 CAS")
	void activateRejectsVersionMismatchBeforeCas() {
		when(deploymentMapper.findByEmployeeAndEnvironment(EMPLOYEE_ID, "PRODUCTION", TENANT_ID))
			.thenReturn(DigitalEmployeeDeployment.builder()
				.id(1L)
				.tenantId(TENANT_ID)
				.employeeId(EMPLOYEE_ID)
				.environment("PRODUCTION")
				.deploymentVersion(7)
				.activeReleaseId(2L)
				.build());

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.activate(EMPLOYEE_ID, activateReq(5)));

		assertTrue(ex.getMessage().contains("并发部署冲突"), ex.getMessage());
		verify(deploymentMapper, never()).casActivate(anyLong(), anyString(), any(), anyLong(), any(), any());
	}

	@Test
	@DisplayName("首次部署：REQUIRES_NEW 插入 INACTIVE 行并立即 CAS 激活")
	void activateInsertsInitialDeploymentWhenAbsent() {
		when(deploymentMapper.findByEmployeeAndEnvironment(EMPLOYEE_ID, "PRODUCTION", TENANT_ID)).thenReturn(null);
		when(deploymentMapper.insert(any(DigitalEmployeeDeployment.class))).thenAnswer(invocation -> {
			DigitalEmployeeDeployment deployment = invocation.getArgument(0);
			deployment.setId(100L);
			return 1;
		});
		when(deploymentMapper.casActivate(eq(100L), eq(TENANT_ID), eq(0), eq(RELEASE_ID), any(), anyString()))
			.thenReturn(1);

		assertDoesNotThrow(() -> service.activate(EMPLOYEE_ID, activateReq(0)));

		verify(deploymentMapper).insert(any(DigitalEmployeeDeployment.class));
		verify(deploymentMapper).casActivate(eq(100L), eq(TENANT_ID), eq(0), eq(RELEASE_ID), any(), anyString());
		verifyRequiresNewInitialInsert();
		verify(transactionManager).commit(any(TransactionStatus.class));
	}

	@Test
	@DisplayName("并发初始化：唯一冲突在 REQUIRES_NEW 子事务回滚，外层仍可读回既有行并 CAS")
	void concurrentInitializationReadsBackExistingRow() {
		DigitalEmployeeDeployment existing = DigitalEmployeeDeployment.builder()
			.id(200L)
			.tenantId(TENANT_ID)
			.employeeId(EMPLOYEE_ID)
			.environment("PRODUCTION")
			.deploymentVersion(2)
			.activeReleaseId(1L)
			.status(DeploymentStatusDict.ACTIVE.getValue())
			.build();
		when(deploymentMapper.findByEmployeeAndEnvironment(EMPLOYEE_ID, "PRODUCTION", TENANT_ID))
			.thenReturn(null)
			.thenReturn(existing);
		when(deploymentMapper.insert(any(DigitalEmployeeDeployment.class)))
			.thenThrow(new DuplicateKeyException("uk_deploy_env_tenant_emp"));
		when(deploymentMapper.casActivate(eq(200L), eq(TENANT_ID), eq(2), eq(RELEASE_ID), eq(1L), anyString()))
			.thenReturn(1);

		assertDoesNotThrow(() -> service.activate(EMPLOYEE_ID, activateReq(2)));

		verifyRequiresNewInitialInsert();
		verify(transactionManager).rollback(any(TransactionStatus.class));
		verify(transactionManager, never()).commit(any(TransactionStatus.class));
		verify(deploymentMapper).casActivate(eq(200L), eq(TENANT_ID), eq(2), eq(RELEASE_ID), eq(1L), anyString());
	}

	@Test
	@DisplayName("并发初始化：子事务冲突回滚后读回仍为空则失败关闭，不伪装成功")
	void concurrentInitializationFailsWhenExistingRowMissing() {
		when(deploymentMapper.findByEmployeeAndEnvironment(EMPLOYEE_ID, "PRODUCTION", TENANT_ID)).thenReturn(null);
		when(deploymentMapper.insert(any(DigitalEmployeeDeployment.class)))
			.thenThrow(new DuplicateKeyException("uk_deploy_env_tenant_emp"));

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.activate(EMPLOYEE_ID, activateReq(0)));

		assertTrue(ex.getMessage().contains("读回失败"), ex.getMessage());
		verify(transactionManager).rollback(any(TransactionStatus.class));
		verify(deploymentMapper, never()).casActivate(anyLong(), anyString(), any(), anyLong(), any(), any());
	}

	@Test
	@DisplayName("仅 PUBLISHED 版本可部署：SEALED 版本激活被拒绝")
	void activateRejectsNonPublishedRelease() {
		when(deploymentMapper.findByEmployeeAndEnvironment(EMPLOYEE_ID, "PRODUCTION", TENANT_ID)).thenReturn(null);
		when(releaseMapper.findByIdAndTenantId(RELEASE_ID, TENANT_ID)).thenReturn(DigitalEmployeeRelease.builder()
			.id(RELEASE_ID)
			.tenantId(TENANT_ID)
			.employeeId(EMPLOYEE_ID)
			.status(EmployeeReleaseStatusDict.SEALED.getValue())
			.build());

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.activate(EMPLOYEE_ID, activateReq(0)));

		assertTrue(ex.getMessage().contains("仅已发布"), ex.getMessage());
	}

	private void verifyRequiresNewInitialInsert() {
		ArgumentCaptor<TransactionDefinition> defCaptor = ArgumentCaptor.forClass(TransactionDefinition.class);
		verify(transactionManager).getTransaction(defCaptor.capture());
		assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, defCaptor.getValue().getPropagationBehavior());
	}

}
