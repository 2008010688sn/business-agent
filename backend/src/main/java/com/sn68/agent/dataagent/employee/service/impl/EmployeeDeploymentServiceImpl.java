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

import com.sn68.agent.dataagent.employee.dto.EmployeeDeploymentActivateReq;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeDeployment;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeRelease;
import com.sn68.agent.dataagent.employee.enums.DeploymentEnvironmentDict;
import com.sn68.agent.dataagent.employee.enums.DeploymentStatusDict;
import com.sn68.agent.dataagent.employee.enums.EmployeeReleaseStatusDict;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeDeploymentMapper;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeReleaseMapper;
import com.sn68.agent.dataagent.employee.service.EmployeeDeploymentService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * 数字员工部署实现（CAS：deployment_version 条件更新，0 行即并发部署冲突；
 * 首次部署在 REQUIRES_NEW 子事务插入 INACTIVE 行后立即 CAS 激活，唯一索引兜底并发初始化）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmployeeDeploymentServiceImpl implements EmployeeDeploymentService {

	private final DigitalEmployeeMapper employeeMapper;

	private final DigitalEmployeeReleaseMapper releaseMapper;

	private final DigitalEmployeeDeploymentMapper deploymentMapper;

	private final AuthenticationContext authenticationContext;

	private final TransactionTemplate transactionTemplate;

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void activate(Long employeeId, EmployeeDeploymentActivateReq request) {
		if (request == null || request.getReleaseId() == null || request.getExpectVersion() == null) {
			throw CheckedException.badRequest("部署请求参数不完整（releaseId/expectVersion 必填）");
		}
		String tenantId = currentTenantId();
		String environment = requireEnvironment(request.getEnvironment());
		requireEmployee(employeeId, tenantId);
		DigitalEmployeeRelease release = requirePublishedRelease(request.getReleaseId(), tenantId);
		if (!employeeId.equals(release.getEmployeeId())) {
			throw CheckedException.badRequest("发布版本不属于该数字员工. releaseId=" + release.getId());
		}

		DigitalEmployeeDeployment deployment = deploymentMapper.findByEmployeeAndEnvironment(employeeId, environment,
				tenantId);
		if (deployment == null) {
			deployment = insertInitialDeployment(employeeId, environment, tenantId);
		}
		casSwitch(deployment, request.getReleaseId(), request.getExpectVersion(), tenantId);
		log.info("数字员工部署已激活. employeeId={}, environment={}, releaseId={}, deploymentVersion={}->{}", employeeId,
				environment, request.getReleaseId(), request.getExpectVersion(),
				request.getExpectVersion() + 1);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void rollback(Long employeeId, String environment, Integer expectVersion) {
		String tenantId = currentTenantId();
		String env = requireEnvironment(environment);
		if (expectVersion == null) {
			throw CheckedException.badRequest("expectVersion 不能为空（CAS 防并发回滚覆盖）");
		}
		requireEmployee(employeeId, tenantId);
		DigitalEmployeeDeployment deployment = deploymentMapper.findByEmployeeAndEnvironment(employeeId, env, tenantId);
		if (deployment == null || deployment.getPreviousReleaseId() == null) {
			throw CheckedException.badRequest("无可回滚的历史版本. employeeId=" + employeeId + ", environment=" + env);
		}
		DigitalEmployeeRelease previous = requirePublishedRelease(deployment.getPreviousReleaseId(), tenantId);
		if (!employeeId.equals(previous.getEmployeeId())) {
			throw CheckedException.badRequest("历史发布版本不属于该数字员工. releaseId=" + previous.getId());
		}
		casSwitch(deployment, deployment.getPreviousReleaseId(), expectVersion, tenantId);
		log.info("数字员工部署已回滚. employeeId={}, environment={}, backToReleaseId={}", employeeId, env,
				deployment.getPreviousReleaseId());
	}

	@Override
	public DigitalEmployeeDeployment findCurrent(Long employeeId, String environment) {
		String env = requireEnvironment(environment);
		return deploymentMapper.findByEmployeeAndEnvironment(employeeId, env, currentTenantId());
	}

	@Override
	public java.util.List<DigitalEmployeeDeployment> findByEmployee(Long employeeId) {
		return deploymentMapper.findByEmployee(employeeId, currentTenantId());
	}

	private void casSwitch(DigitalEmployeeDeployment deployment, Long targetReleaseId, Integer expectVersion,
			String tenantId) {
		if (!expectVersion.equals(deployment.getDeploymentVersion())) {
			throw CheckedException.badRequest("部署版本已变更（并发部署冲突），请刷新后重试. expectVersion=" + expectVersion
					+ ", currentVersion=" + deployment.getDeploymentVersion());
		}
		int updated = deploymentMapper.casActivate(deployment.getId(), tenantId, expectVersion, targetReleaseId,
				deployment.getActiveReleaseId(), currentUserIdOrNull());
		if (updated == 0) {
			throw CheckedException.badRequest("部署版本已变更（并发部署冲突），请刷新后重试. deploymentId=" + deployment.getId());
		}
	}

	private DigitalEmployeeDeployment insertInitialDeployment(Long employeeId, String environment, String tenantId) {
		DigitalEmployeeDeployment initial = DigitalEmployeeDeployment.builder()
			.tenantId(tenantId)
			.employeeId(employeeId)
			.environment(environment)
			.deploymentVersion(0)
			.status(DeploymentStatusDict.INACTIVE.getValue())
			.build();
		try {
			insertInactiveInNewTransaction(initial);
			return initial;
		}
		catch (DuplicateKeyException ex) {
			// 子事务已因唯一冲突回滚，外层 activate 事务仍可用；读回既有行继续 CAS。
			DigitalEmployeeDeployment existing = deploymentMapper.findByEmployeeAndEnvironment(employeeId, environment,
					tenantId);
			if (existing == null) {
				throw CheckedException.badRequest("部署行初始化冲突且读回失败，请重试. employeeId=" + employeeId);
			}
			return existing;
		}
	}

	/**
	 * 首次 INACTIVE 行必须在独立事务提交。PostgreSQL 唯一冲突会 abort 当前事务，
	 * 若与 {@link #activate} 同事务则后续 find/CAS 全部失败。
	 */
	private void insertInactiveInNewTransaction(DigitalEmployeeDeployment initial) {
		TransactionTemplate nested = new TransactionTemplate(transactionTemplate.getTransactionManager());
		nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		nested.executeWithoutResult(status -> deploymentMapper.insert(initial));
	}

	private DigitalEmployeeRelease requirePublishedRelease(Long releaseId, String tenantId) {
		if (releaseId == null) {
			throw CheckedException.badRequest("发布版本ID不能为空");
		}
		DigitalEmployeeRelease release = releaseMapper.findByIdAndTenantId(releaseId, tenantId);
		if (release == null) {
			throw CheckedException.notFound("发布版本不存在: " + releaseId);
		}
		if (!EmployeeReleaseStatusDict.PUBLISHED.getValue().equals(release.getStatus())) {
			throw CheckedException.badRequest("仅已发布的版本可部署，当前状态: " + release.getStatus());
		}
		return release;
	}

	private DigitalEmployee requireEmployee(Long employeeId, String tenantId) {
		if (employeeId == null) {
			throw CheckedException.badRequest("数字员工ID不能为空");
		}
		DigitalEmployee employee = employeeMapper.findByIdAndTenantId(employeeId, tenantId);
		if (employee == null) {
			throw CheckedException.notFound("数字员工不存在: " + employeeId);
		}
		return employee;
	}

	private String requireEnvironment(String environment) {
		if (!StringUtils.hasText(environment)) {
			throw CheckedException.badRequest("部署环境不能为空");
		}
		String env = environment.trim();
		if (DeploymentEnvironmentDict.of(env) == null) {
			throw CheckedException.badRequest("部署环境不合法（SANDBOX/PRODUCTION）: " + env);
		}
		return env;
	}

	private String currentUserIdOrNull() {
		try {
			return authenticationContext.userId();
		}
		catch (Exception ex) {
			return null;
		}
	}

	private String currentTenantId() {
		String tenantId;
		try {
			tenantId = authenticationContext.tenantId();
		}
		catch (Exception ex) {
			throw CheckedException.forbidden("无法解析当前登录租户，禁止访问数字员工部署管理");
		}
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.forbidden("当前登录租户为空，禁止访问数字员工部署管理");
		}
		return tenantId.trim();
	}

}
