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
package com.sn68.agent.dataagent.employee.repository;

import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeDeployment;
import com.sn68.agent.dataagent.employee.enums.DeploymentStatusDict;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.time.Instant;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * 数字员工部署 Mapper（每租户每员工每环境至多一行，CAS 条件更新防并发覆盖）。
 * 本表无 deleted 列（主文档 4.2），所有方法显式携带 tenant_id 条件作为租户防线。
 */
@Repository
public interface DigitalEmployeeDeploymentMapper extends SuperMapper<DigitalEmployeeDeployment> {

	/**
	 * 查询员工在指定环境的部署行（无则返回 null，由服务层决定是否初始化）。
	 */
	default DigitalEmployeeDeployment findByEmployeeAndEnvironment(Long employeeId, String environment,
			String tenantId) {
		requireTenantId(tenantId);
		return selectOne(Wraps.<DigitalEmployeeDeployment>lbQ()
			.eq(DigitalEmployeeDeployment::getTenantId, tenantId)
			.eq(DigitalEmployeeDeployment::getEmployeeId, employeeId)
			.eq(DigitalEmployeeDeployment::getEnvironment, environment)
			.last(" limit 1"));
	}

	/**
	 * 查询员工全部环境的部署行（详情页展示）。
	 */
	default java.util.List<DigitalEmployeeDeployment> findByEmployee(Long employeeId, String tenantId) {
		requireTenantId(tenantId);
		return selectList(Wraps.<DigitalEmployeeDeployment>lbQ()
			.eq(DigitalEmployeeDeployment::getTenantId, tenantId)
			.eq(DigitalEmployeeDeployment::getEmployeeId, employeeId)
			.orderByDesc(DigitalEmployeeDeployment::getDeploymentVersion));
	}

	/**
	 * CAS 激活/回滚：命中当前 deployment_version 才切换 active_release_id 并递增版本号，
	 * 影响 0 行即并发部署冲突（防并发重复发布/部署互相覆盖）。
	 */
	default int casActivate(Long id, String tenantId, Integer expectVersion, Long targetReleaseId,
			Long previousReleaseId, String deployedBy) {
		requireTenantId(tenantId);
		return update(null, Wraps.<DigitalEmployeeDeployment>lbU()
			.eq(DigitalEmployeeDeployment::getId, id)
			.eq(DigitalEmployeeDeployment::getTenantId, tenantId)
			.eq(DigitalEmployeeDeployment::getDeploymentVersion, expectVersion)
			.set(DigitalEmployeeDeployment::getActiveReleaseId, targetReleaseId)
			.set(DigitalEmployeeDeployment::getPreviousReleaseId, previousReleaseId)
			.setSql("deployment_version = deployment_version + 1")
			.set(DigitalEmployeeDeployment::getStatus, DeploymentStatusDict.ACTIVE.getValue())
			.set(DigitalEmployeeDeployment::getDeployedBy, deployedBy)
			.set(DigitalEmployeeDeployment::getDeployedAt, Instant.now()));
	}

	/**
	 * 统计激活某 Release 的部署行数（Release 退役/删除拦截；主键引用、跨租户计数）。
	 */
	default long countByActiveReleaseId(Long releaseId) {
		if (releaseId == null) {
			return 0L;
		}
		return selectCount(Wraps.<DigitalEmployeeDeployment>lbQ()
			.eq(DigitalEmployeeDeployment::getActiveReleaseId, releaseId));
	}

	private static void requireTenantId(String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.forbidden("缺少租户上下文，无法访问数字员工部署");
		}
	}

}
