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

import com.sn68.agent.dataagent.employee.dto.EmployeeDeploymentActivateReq;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeDeployment;
import java.util.List;

/**
 * 数字员工部署服务（每租户每员工每环境至多一行；CAS 条件更新防并发重复部署/覆盖）。
 */
public interface EmployeeDeploymentService {

	/**
	 * 激活指定 Release 到指定环境（deployment_version CAS；首次部署自动初始化部署行）。
	 */
	void activate(Long employeeId, EmployeeDeploymentActivateReq request);

	/**
	 * 回滚到 previousReleaseId 指向的版本（CAS；无历史版本时拒绝）。
	 */
	void rollback(Long employeeId, String environment, Integer expectVersion);

	/**
	 * 查询员工在指定环境的当前部署（未初始化时返回 null）。
	 */
	DigitalEmployeeDeployment findCurrent(Long employeeId, String environment);

	/**
	 * 查询员工全部环境的部署行（详情页展示）。
	 */
	List<DigitalEmployeeDeployment> findByEmployee(Long employeeId);

}
