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

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.employee.dto.DigitalEmployeePageQueryReq;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.enums.EmployeeStatusDict;
import com.sn68.agent.dataagent.employee.enums.PrincipalProvisionStatusDict;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * 数字员工身份 Mapper。
 * 框架租户拦截器为白名单制，本 Mapper 全部方法显式携带 tenant_id 条件作为租户防线。
 */
@Repository
public interface DigitalEmployeeMapper extends SuperMapper<DigitalEmployee> {

	/**
	 * 系统扫描：启用且配置了负责人的员工（每日汇总）。无用户会话，不带租户拦截。
	 */
	default List<DigitalEmployee> listEnabledWithManager() {
		return selectList(Wraps.<DigitalEmployee>lbQ()
			.eq(DigitalEmployee::getDeleted, false)
			.eq(DigitalEmployee::getStatus, EmployeeStatusDict.ENABLED.getValue())
			.isNotNull(DigitalEmployee::getManagerUserId)
			.ne(DigitalEmployee::getManagerUserId, ""));
	}

	/**
	 * 按租户 + 主键查询未删除员工。
	 */
	default DigitalEmployee findByIdAndTenantId(Long id, String tenantId) {
		return selectOne(Wraps.<DigitalEmployee>lbQ()
			.eq(DigitalEmployee::getDeleted, false)
			.eq(DigitalEmployee::getId, id)
			.eq(DigitalEmployee::getTenantId, tenantId)
			.last(" limit 1"));
	}

	/**
	 * 按员工编码查询（租户内唯一约束的读侧校验）。
	 */
	default DigitalEmployee findByCodeAndTenantId(String employeeCode, String tenantId) {
		return selectOne(Wraps.<DigitalEmployee>lbQ()
			.eq(DigitalEmployee::getDeleted, false)
			.eq(DigitalEmployee::getTenantId, tenantId)
			.eq(DigitalEmployee::getEmployeeCode, employeeCode)
			.last(" limit 1"));
	}

	/**
	 * 按租户分页查询员工。
	 */
	default IPage<DigitalEmployee> selectEmployeePage(IPage<DigitalEmployee> page,
			DigitalEmployeePageQueryReq request, String tenantId) {
		DigitalEmployeePageQueryReq query = request == null ? new DigitalEmployeePageQueryReq() : request;
		return selectPage(page, Wraps.<DigitalEmployee>lbQ()
			.eq(DigitalEmployee::getDeleted, false)
			.eq(DigitalEmployee::getTenantId, tenantId)
			.eq(DigitalEmployee::getStatus, trim(query.getStatus()))
			.like(StringUtils.hasText(trim(query.getKeyword())), DigitalEmployee::getEmployeeName,
					trim(query.getKeyword()))
			.orderByDesc(DigitalEmployee::getId));
	}

	/**
	 * CAS 状态迁移（启用/停用/封存）：命中 expectStatus + expectStateVersion 才更新，
	 * 影响 0 行即并发冲突或状态已变更。
	 */
	default int casUpdateStatus(Long id, String tenantId, String expectStatus, String targetStatus,
			Long expectStateVersion) {
		return update(null, Wraps.<DigitalEmployee>lbU()
			.eq(DigitalEmployee::getId, id)
			.eq(DigitalEmployee::getTenantId, tenantId)
			.eq(DigitalEmployee::getDeleted, false)
			.eq(DigitalEmployee::getStatus, expectStatus)
			.eq(DigitalEmployee::getStateVersion, expectStateVersion)
			.set(DigitalEmployee::getStatus, targetStatus)
			.setSql("state_version = state_version + 1"));
	}

	/**
	 * 开通结果回写（iam_principal_id + principal_status + principal_revision），CAS 防重复覆盖：
	 * 仅当本地仍处于 expectStatus 时生效，幂等重试安全。
	 */
	default int casUpdateProvision(Long id, String tenantId, String expectStatus, String iamPrincipalId,
			String targetStatus, Long principalRevision) {
		return update(null, Wraps.<DigitalEmployee>lbU()
			.eq(DigitalEmployee::getId, id)
			.eq(DigitalEmployee::getTenantId, tenantId)
			.eq(DigitalEmployee::getDeleted, false)
			.eq(DigitalEmployee::getPrincipalStatus, expectStatus)
			.set(DigitalEmployee::getIamPrincipalId, iamPrincipalId)
			.set(DigitalEmployee::getPrincipalStatus, targetStatus)
			.set(DigitalEmployee::getPrincipalRevision, principalRevision)
			.set(DigitalEmployee::getLastModifyTime, Instant.now()));
	}

	/**
	 * 统计仍处于待开通（PENDING）状态的员工数（rollout 开关打开后的批量补开通入口用）。
	 */
	default long countPendingProvision(String tenantId) {
		return selectCount(Wraps.<DigitalEmployee>lbQ()
			.eq(DigitalEmployee::getDeleted, false)
			.eq(DigitalEmployee::getTenantId, tenantId)
			.eq(DigitalEmployee::getPrincipalStatus, PrincipalProvisionStatusDict.PENDING.getValue()));
	}

	/**
	 * 统计指定状态下指定 DataAgent 来源的员工数（DataAgent 下线防御校验用）。
	 */
	default long countActiveBySourceAgent(Long sourceAgentId, String tenantId) {
		return selectCount(Wraps.<DigitalEmployee>lbQ()
			.eq(DigitalEmployee::getDeleted, false)
			.eq(DigitalEmployee::getTenantId, tenantId)
			.eq(DigitalEmployee::getSourceAgentId, sourceAgentId)
			.eq(DigitalEmployee::getStatus, EmployeeStatusDict.ENABLED.getValue()));
	}

	/**
	 * 按 Principal ID 查询当前租户员工（权限中心按执行身份反查）。
	 */
	default DigitalEmployee findByIamPrincipalIdAndTenantId(String iamPrincipalId, String tenantId) {
		if (!StringUtils.hasText(iamPrincipalId) || !StringUtils.hasText(tenantId)) {
			return null;
		}
		return selectOne(Wraps.<DigitalEmployee>lbQ()
			.eq(DigitalEmployee::getDeleted, false)
			.eq(DigitalEmployee::getTenantId, tenantId)
			.eq(DigitalEmployee::getIamPrincipalId, iamPrincipalId.trim())
			.last(" limit 1"));
	}

	/**
	 * 回写本地观测到的 auth_revision（角色替换/状态变更后与 IAM 对齐）。
	 */
	default int updatePrincipalRevision(Long id, String tenantId, Long principalRevision) {
		return update(null, Wraps.<DigitalEmployee>lbU()
			.eq(DigitalEmployee::getId, id)
			.eq(DigitalEmployee::getTenantId, tenantId)
			.eq(DigitalEmployee::getDeleted, false)
			.set(DigitalEmployee::getPrincipalRevision, principalRevision)
			.set(DigitalEmployee::getLastModifyTime, Instant.now()));
	}

	/**
	 * 同步本地 principal_status（IAM Principal 停用/启用后与档案对齐，不改员工启用状态）。
	 */
	default int updatePrincipalLocalStatus(Long id, String tenantId, String principalStatus) {
		return update(null, Wraps.<DigitalEmployee>lbU()
			.eq(DigitalEmployee::getId, id)
			.eq(DigitalEmployee::getTenantId, tenantId)
			.eq(DigitalEmployee::getDeleted, false)
			.set(DigitalEmployee::getPrincipalStatus, principalStatus)
			.set(DigitalEmployee::getLastModifyTime, Instant.now()));
	}

	private static String trim(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

}
