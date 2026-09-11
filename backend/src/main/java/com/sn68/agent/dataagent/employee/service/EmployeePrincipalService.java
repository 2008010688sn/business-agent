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

import com.sn68.agent.dataagent.iam.dto.ServicePrincipalAuthSnapshotResp;
import com.sn68.agent.dataagent.iam.dto.ServicePrincipalRoleResp;
import com.sn68.agent.dataagent.iam.dto.ServicePrincipalRolesReplaceReq;
import com.sn68.agent.dataagent.iam.dto.ServicePrincipalStatusReq;
import java.util.List;

/**
 * 数字员工 Principal 管理 Facade：standalone 走本地 Principal 存储。
 */
public interface EmployeePrincipalService {

	List<ServicePrincipalRoleResp> listRoles(Long employeeId);

	void replaceRoles(Long employeeId, ServicePrincipalRolesReplaceReq req);

	void updateStatus(Long employeeId, ServicePrincipalStatusReq req);

	ServicePrincipalAuthSnapshotResp previewAuth(Long employeeId);

}
