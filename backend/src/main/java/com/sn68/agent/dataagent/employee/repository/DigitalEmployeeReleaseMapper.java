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

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeRelease;
import com.sn68.agent.dataagent.employee.enums.EmployeeReleaseStatusDict;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.time.Instant;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * 数字员工发布 Mapper（DRAFT → SEALED → PUBLISHED → RETIRED，Seal/Publish 均为 CAS 条件更新）。
 * 框架租户拦截器为白名单制；租户内 CRUD 显式带 tenant_id。市场撤销引用扫描为平台级，不按当前租户收敛。
 */
@Repository
public interface DigitalEmployeeReleaseMapper extends SuperMapper<DigitalEmployeeRelease> {

	/**
	 * 按租户 + 主键查询未删除 Release。
	 */
	default DigitalEmployeeRelease findByIdAndTenantId(Long id, String tenantId) {
		requireTenantId(tenantId);
		return selectOne(Wraps.<DigitalEmployeeRelease>lbQ()
			.eq(DigitalEmployeeRelease::getDeleted, false)
			.eq(DigitalEmployeeRelease::getId, id)
			.eq(DigitalEmployeeRelease::getTenantId, tenantId)
			.last(" limit 1"));
	}

	/**
	 * 查询员工当前最大发布序号，用于生成下一个 release_no。
	 */
	default Integer findMaxReleaseNo(Long employeeId, String tenantId) {
		requireTenantId(tenantId);
		DigitalEmployeeRelease latest = selectOne(Wraps.<DigitalEmployeeRelease>lbQ()
			.eq(DigitalEmployeeRelease::getDeleted, false)
			.eq(DigitalEmployeeRelease::getTenantId, tenantId)
			.eq(DigitalEmployeeRelease::getEmployeeId, employeeId)
			.orderByDesc(DigitalEmployeeRelease::getReleaseNo)
			.last(" limit 1"));
		return latest == null ? null : latest.getReleaseNo();
	}

	/**
	 * 按租户 + 员工分页查询 Release。
	 */
	default IPage<DigitalEmployeeRelease> selectReleasePage(IPage<DigitalEmployeeRelease> page, Long employeeId,
			String status, String tenantId) {
		requireTenantId(tenantId);
		return selectPage(page, Wraps.<DigitalEmployeeRelease>lbQ()
			.eq(DigitalEmployeeRelease::getDeleted, false)
			.eq(DigitalEmployeeRelease::getTenantId, tenantId)
			.eq(employeeId != null, DigitalEmployeeRelease::getEmployeeId, employeeId)
			.eq(StringUtils.hasText(status), DigitalEmployeeRelease::getStatus, status)
			.orderByDesc(DigitalEmployeeRelease::getId));
	}

	/**
	 * Seal CAS：仅 DRAFT → SEALED 生效；snapshot/spec_hash/sealed_at/sealed_by 一并落库，
	 * 影响 0 行即已被并发 Seal/发布或状态非法。
	 *
	 * <p>snapshot 使用显式 JSONB type handler。UpdateWrapper.set 只按 String 绑定该动态参数，
	 * PostgreSQL 会将其识别为 varchar，无法赋值给 jsonb。</p>
	 */
	@Update("UPDATE digital_employee_release "
			+ "SET snapshot = #{snapshot,typeHandler=com.sn68.agent.dataagent.repository.typehandler.JsonbStringTypeHandler}, "
			+ "spec_hash = #{specHash}, sealed_at = CURRENT_TIMESTAMP, sealed_by = #{sealedBy}, status = 'SEALED' "
			+ "WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted = false AND status = 'DRAFT'")
	int casSeal(@Param("id") Long id, @Param("tenantId") String tenantId, @Param("snapshot") String snapshot,
			@Param("specHash") String specHash, @Param("sealedBy") String sealedBy);

	/**
	 * Publish CAS：仅 SEALED → PUBLISHED 生效，影响 0 行即状态已变更（并发或非法流转）。
	 */
	default int casPublish(Long id, String tenantId, String publishedBy) {
		requireTenantId(tenantId);
		return update(null, Wraps.<DigitalEmployeeRelease>lbU()
			.eq(DigitalEmployeeRelease::getId, id)
			.eq(DigitalEmployeeRelease::getTenantId, tenantId)
			.eq(DigitalEmployeeRelease::getDeleted, false)
			.eq(DigitalEmployeeRelease::getStatus, EmployeeReleaseStatusDict.SEALED.getValue())
			.set(DigitalEmployeeRelease::getPublishedAt, Instant.now())
			.set(DigitalEmployeeRelease::getPublishedBy, publishedBy)
			.set(DigitalEmployeeRelease::getStatus, EmployeeReleaseStatusDict.PUBLISHED.getValue()));
	}

	/**
	 * Retire CAS：仅 PUBLISHED → RETIRED 生效，影响 0 行即状态已变更。
	 */
	default int casRetire(Long id, String tenantId) {
		requireTenantId(tenantId);
		return update(null, Wraps.<DigitalEmployeeRelease>lbU()
			.eq(DigitalEmployeeRelease::getId, id)
			.eq(DigitalEmployeeRelease::getTenantId, tenantId)
			.eq(DigitalEmployeeRelease::getDeleted, false)
			.eq(DigitalEmployeeRelease::getStatus, EmployeeReleaseStatusDict.PUBLISHED.getValue())
			.set(DigitalEmployeeRelease::getStatus, EmployeeReleaseStatusDict.RETIRED.getValue()));
	}

	/**
	 * 统计员工在某状态（如 PUBLISHED）的 Release 数（回滚目标可用性校验用）。
	 */
	default long countByEmployeeIdAndStatus(Long employeeId, String status, String tenantId) {
		requireTenantId(tenantId);
		return selectCount(Wraps.<DigitalEmployeeRelease>lbQ()
			.eq(DigitalEmployeeRelease::getDeleted, false)
			.eq(DigitalEmployeeRelease::getTenantId, tenantId)
			.eq(DigitalEmployeeRelease::getEmployeeId, employeeId)
			.eq(DigitalEmployeeRelease::getStatus, status));
	}

	/**
	 * 市场撤销门禁：是否仍有 SEALED/PUBLISHED Release 引用指定技能版本。
	 * 扫描草稿能力绑定（该员工已有封/发版本）以及不可变 snapshot 中的 capabilities.skillVersionId。
	 * 命中则调用方应失败关闭，不得改写已封快照。平台级扫描，不按当前租户收敛。
	 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("""
			SELECT EXISTS (
				SELECT 1
				FROM digital_employee_capability c
				INNER JOIN digital_employee_release r
					ON r.employee_id = c.employee_id
					AND r.tenant_id = c.tenant_id
					AND r.deleted = FALSE
					AND r.status IN ('SEALED', 'PUBLISHED')
				WHERE c.deleted = FALSE
					AND c.skill_version_id = #{skillVersionId}
			)
			OR EXISTS (
				SELECT 1
				FROM digital_employee_release r
				WHERE r.deleted = FALSE
					AND r.status IN ('SEALED', 'PUBLISHED')
					AND EXISTS (
						SELECT 1
						FROM jsonb_array_elements(COALESCE(r.snapshot -> 'capabilities', '[]'::jsonb)) cap
						WHERE cap ->> 'skillVersionId' = CAST(#{skillVersionId} AS TEXT)
					)
			)
			""")
	boolean existsSealedOrPublishedReferencingSkillVersion(@Param("skillVersionId") Long skillVersionId);

	private static void requireTenantId(String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.forbidden("缺少租户上下文，无法访问数字员工发布");
		}
	}

}
