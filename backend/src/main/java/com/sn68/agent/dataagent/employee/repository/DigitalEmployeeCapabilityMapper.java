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

import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeCapability;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * 数字员工能力绑定 Mapper（草稿态；Seal 冻结进 Release snapshot，运行时不读本表）。
 * 框架租户拦截器为白名单制，本 Mapper 全部方法显式携带 tenant_id 条件作为租户防线。
 */
@Repository
public interface DigitalEmployeeCapabilityMapper extends SuperMapper<DigitalEmployeeCapability> {

	/**
	 * 查询员工全部启用中的能力绑定（Seal 冻结清单的来源）。
	 */
	default List<DigitalEmployeeCapability> findEnabledByEmployeeId(Long employeeId, String tenantId) {
		requireTenantId(tenantId);
		return selectList(Wraps.<DigitalEmployeeCapability>lbQ()
			.eq(DigitalEmployeeCapability::getDeleted, false)
			.eq(DigitalEmployeeCapability::getTenantId, tenantId)
			.eq(DigitalEmployeeCapability::getEmployeeId, employeeId)
			.eq(DigitalEmployeeCapability::getEnabled, true)
			.orderByAsc(DigitalEmployeeCapability::getId));
	}

	/**
	 * 查询员工全部能力绑定（含停用，详情页展示）。
	 */
	default List<DigitalEmployeeCapability> findByEmployeeId(Long employeeId, String tenantId) {
		requireTenantId(tenantId);
		return selectList(Wraps.<DigitalEmployeeCapability>lbQ()
			.eq(DigitalEmployeeCapability::getDeleted, false)
			.eq(DigitalEmployeeCapability::getTenantId, tenantId)
			.eq(DigitalEmployeeCapability::getEmployeeId, employeeId)
			.orderByAsc(DigitalEmployeeCapability::getId));
	}

	/**
	 * 幂等查找既有的 (employee, skillVersion) 绑定（市场安装幂等落点）。
	 */
	default DigitalEmployeeCapability findByEmployeeAndSkillVersion(Long employeeId, Long skillVersionId,
			String tenantId) {
		requireTenantId(tenantId);
		return selectOne(Wraps.<DigitalEmployeeCapability>lbQ()
			.eq(DigitalEmployeeCapability::getDeleted, false)
			.eq(DigitalEmployeeCapability::getTenantId, tenantId)
			.eq(DigitalEmployeeCapability::getEmployeeId, employeeId)
			.eq(DigitalEmployeeCapability::getSkillVersionId, skillVersionId)
			.last(" limit 1"));
	}

	/**
	 * 按租户 + 主键 + 员工查询单条绑定（解绑/启停校验归属）。
	 */
	default DigitalEmployeeCapability findByIdAndEmployeeId(Long id, Long employeeId, String tenantId) {
		requireTenantId(tenantId);
		return selectOne(Wraps.<DigitalEmployeeCapability>lbQ()
			.eq(DigitalEmployeeCapability::getDeleted, false)
			.eq(DigitalEmployeeCapability::getId, id)
			.eq(DigitalEmployeeCapability::getTenantId, tenantId)
			.eq(DigitalEmployeeCapability::getEmployeeId, employeeId)
			.last(" limit 1"));
	}

	/**
	 * 重新启用既有绑定（市场安装幂等路径：软删恢复 + enabled=true）。
	 */
	default int reEnable(Long id, String tenantId) {
		requireTenantId(tenantId);
		return update(null, Wraps.<DigitalEmployeeCapability>lbU()
			.eq(DigitalEmployeeCapability::getId, id)
			.eq(DigitalEmployeeCapability::getTenantId, tenantId)
			.set(DigitalEmployeeCapability::getEnabled, true));
	}

	/**
	 * 批量统计员工能力绑定数量，供 Gallery 列表使用；一次查询覆盖当前页，避免逐员工 N+1。
	 */
	default Map<Long, Long> countByEmployeeIds(Collection<Long> employeeIds, String tenantId) {
		requireTenantId(tenantId);
		if (employeeIds == null || employeeIds.isEmpty()) {
			return Map.of();
		}
		return selectList(Wraps.<DigitalEmployeeCapability>lbQ()
			.eq(DigitalEmployeeCapability::getDeleted, false)
			.eq(DigitalEmployeeCapability::getTenantId, tenantId)
			.in(DigitalEmployeeCapability::getEmployeeId, employeeIds))
			.stream()
			.collect(Collectors.groupingBy(DigitalEmployeeCapability::getEmployeeId, Collectors.counting()));
	}

	/**
	 * 按租户、员工与绑定主键更新能力启用状态。
	 *
	 * <p>三重归属条件与查询侧保持一致，避免仅凭能力主键误更新其他员工或租户的历史绑定。</p>
	 */
	default int updateEnabledById(Long id, Long employeeId, String tenantId, Boolean enabled) {
		requireTenantId(tenantId);
		return update(null, Wraps.<DigitalEmployeeCapability>lbU()
			.eq(DigitalEmployeeCapability::getId, id)
			.eq(DigitalEmployeeCapability::getEmployeeId, employeeId)
			.eq(DigitalEmployeeCapability::getTenantId, tenantId)
			.eq(DigitalEmployeeCapability::getDeleted, false)
			.set(DigitalEmployeeCapability::getEnabled, enabled)
			.set(DigitalEmployeeCapability::getLastModifyTime, Instant.now()));
	}

	/**
	 * 原子地创建或恢复启用能力绑定。
	 *
	 * <p>能力表的部分唯一索引是市场安装幂等性的最终防线。先查再插在并发安装下仍可能
	 * 撞唯一键，因此这里使用 PostgreSQL upsert；仅更新启用状态，不覆盖历史绑定的审计字段。</p>
	 */
	@Insert("""
			INSERT INTO digital_employee_capability
				(tenant_id, employee_id, skill_version_id, enabled, create_time, last_modify_time, deleted)
			VALUES (#{tenantId}, #{employeeId}, #{skillVersionId}, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE)
			ON CONFLICT (tenant_id, employee_id, skill_version_id) WHERE deleted = FALSE
			DO UPDATE SET enabled = TRUE, last_modify_time = CURRENT_TIMESTAMP
			""")
	int upsertEnabled(@Param("tenantId") String tenantId, @Param("employeeId") Long employeeId,
			@Param("skillVersionId") Long skillVersionId);

	private static void requireTenantId(String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.forbidden("缺少租户上下文，无法访问数字员工能力绑定");
		}
	}

}
