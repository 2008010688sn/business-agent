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

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.employee.dto.EmployeeReleaseCreateReq;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeRelease;
import com.sn68.agent.framework.db.mybatisplus.page.PageRequest;

/**
 * 数字员工发布生命周期服务（DRAFT → SEALED → PUBLISHED → RETIRED）。
 *
 * <p>Seal：冻结能力清单进 snapshot + 计算 spec_hash（CAS）；
 * Publish：SEALED → PUBLISHED（CAS）；Retire：被员工 Deployment 或任务定义引用时拒绝（CAS）。</p>
 */
public interface EmployeeReleaseLifecycleService {

	/**
	 * 从员工当前草稿（或指定 base Release）创建 DRAFT Release。
	 * @return 新建的 Release ID
	 */
	Long createDraft(Long employeeId, EmployeeReleaseCreateReq request);

	/**
	 * Seal：以 Seal 时刻的员工草稿 + 启用中能力清单重新装配快照并冻结（DRAFT → SEALED，CAS）。
	 */
	void seal(Long releaseId);

	/**
	 * Publish（SEALED → PUBLISHED，CAS）。
	 */
	void publish(Long releaseId);

	/**
	 * Retire（PUBLISHED → RETIRED，CAS；仍被部署或任务引用时拒绝）。
	 */
	void retire(Long releaseId);

	/**
	 * 分页查询当前租户（可按员工过滤）的 Release。
	 */
	IPage<DigitalEmployeeRelease> queryPage(Long employeeId, String status, PageRequest request);

	/**
	 * 查询 Release 详情。
	 */
	DigitalEmployeeRelease getDetail(Long releaseId);

	/**
	 * 校验 Release 存在且属于指定员工，且状态为 SEALED 或 PUBLISHED。
	 * 非任务绑定场景（如基于已封版本创建草稿）可复用。
	 * @throws com.sn68.agent.framework.commons.exception.CheckedException 校验失败
	 */
	void requireReleaseOwnedByEmployee(Long employeeId, Long releaseId);

	/**
	 * 校验 Release 存在、属于指定员工，且状态必须为 PUBLISHED（任务定义绑定专用）。
	 * @throws com.sn68.agent.framework.commons.exception.CheckedException 校验失败
	 */
	void requirePublishedReleaseOwnedByEmployee(Long employeeId, Long releaseId);

}
