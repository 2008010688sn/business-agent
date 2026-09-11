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

import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.enums.PrincipalProvisionStatusDict;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.employee.service.EmployeePrincipalService;
import com.sn68.agent.dataagent.iam.LocalPrincipalStore;
import com.sn68.agent.dataagent.iam.dto.ServicePrincipalAuthSnapshotResp;
import com.sn68.agent.dataagent.iam.dto.ServicePrincipalRoleResp;
import com.sn68.agent.dataagent.iam.dto.ServicePrincipalRolesReplaceReq;
import com.sn68.agent.dataagent.iam.dto.ServicePrincipalStatusReq;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 数字员工 Principal Facade：校验员工归属当前租户后读写本地 Principal 存储。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmployeePrincipalServiceImpl implements EmployeePrincipalService {

	private static final String STATUS_DISABLED = "DISABLED";

	private final DigitalEmployeeMapper employeeMapper;

	private final LocalPrincipalStore principalStore;

	private final AuthenticationContext authenticationContext;

	@Override
	public List<ServicePrincipalRoleResp> listRoles(Long employeeId) {
		DigitalEmployee employee = requireEmployee(employeeId);
		if (!StringUtils.hasText(employee.getIamPrincipalId())) {
			return List.of();
		}
		return principalStore.listRoles(employee.getIamPrincipalId());
	}

	@Override
	public void replaceRoles(Long employeeId, ServicePrincipalRolesReplaceReq req) {
		DigitalEmployee employee = requireReadyPrincipal(employeeId);
		principalStore.replaceRoles(employee.getIamPrincipalId(),
				req == null ? List.of() : req.getRoleIds());
		refreshPrincipalRevision(employee);
	}

	@Override
	public void updateStatus(Long employeeId, ServicePrincipalStatusReq req) {
		DigitalEmployee employee = requireReadyPrincipal(employeeId);
		if (req == null || !StringUtils.hasText(req.getStatus())) {
			throw CheckedException.badRequest("Principal 状态不能为空");
		}
		principalStore.updateStatus(employee.getIamPrincipalId(), req.getStatus());
		String localStatus = STATUS_DISABLED.equals(req.getStatus())
				? PrincipalProvisionStatusDict.DISABLED.getValue()
				: PrincipalProvisionStatusDict.READY.getValue();
		employeeMapper.updatePrincipalLocalStatus(employee.getId(), employee.getTenantId(), localStatus);
		refreshPrincipalRevision(employee);
	}

	@Override
	public ServicePrincipalAuthSnapshotResp previewAuth(Long employeeId) {
		DigitalEmployee employee = requireReadyPrincipal(employeeId);
		ServicePrincipalAuthSnapshotResp snapshot = principalStore.snapshot(employee.getIamPrincipalId());
		if (snapshot != null && snapshot.getAuthRevision() != null) {
			employeeMapper.updatePrincipalRevision(employee.getId(), employee.getTenantId(),
					snapshot.getAuthRevision());
		}
		return snapshot;
	}

	private void refreshPrincipalRevision(DigitalEmployee employee) {
		ServicePrincipalAuthSnapshotResp snapshot = principalStore.snapshot(employee.getIamPrincipalId());
		if (snapshot != null && snapshot.getAuthRevision() != null) {
			employeeMapper.updatePrincipalRevision(employee.getId(), employee.getTenantId(),
					snapshot.getAuthRevision());
		}
	}

	private DigitalEmployee requireReadyPrincipal(Long employeeId) {
		DigitalEmployee employee = requireEmployee(employeeId);
		if (!StringUtils.hasText(employee.getIamPrincipalId())) {
			throw CheckedException.badRequest("Principal 尚未开通，请先触发开通");
		}
		return employee;
	}

	private DigitalEmployee requireEmployee(Long employeeId) {
		if (employeeId == null) {
			throw CheckedException.badRequest("数字员工ID不能为空");
		}
		DigitalEmployee employee = employeeMapper.findByIdAndTenantId(employeeId, currentTenantId());
		if (employee == null) {
			throw CheckedException.notFound("数字员工不存在: " + employeeId);
		}
		return employee;
	}

	private String currentTenantId() {
		String tenantId;
		try {
			tenantId = authenticationContext.tenantId();
		}
		catch (Exception ex) {
			throw CheckedException.forbidden("无法解析当前登录租户，禁止访问数字员工 Principal");
		}
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.forbidden("当前登录租户为空，禁止访问数字员工 Principal");
		}
		return tenantId.trim();
	}

}
