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

import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeModelConfig;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * 数字员工可用模型配置 Mapper。查询显式带 tenant_id。
 */
@Repository
public interface DigitalEmployeeModelConfigMapper extends SuperMapper<DigitalEmployeeModelConfig> {

	default List<DigitalEmployeeModelConfig> findByEmployeeId(Long employeeId, String tenantId) {
		requireTenantId(tenantId);
		return selectList(Wraps.<DigitalEmployeeModelConfig>lbQ()
			.eq(DigitalEmployeeModelConfig::getTenantId, tenantId)
			.eq(DigitalEmployeeModelConfig::getEmployeeId, employeeId)
			.orderByDesc(DigitalEmployeeModelConfig::getIsDefault)
			.orderByAsc(DigitalEmployeeModelConfig::getId));
	}

	default List<DigitalEmployeeModelConfig> findEnabledByEmployeeId(Long employeeId, String tenantId) {
		requireTenantId(tenantId);
		return selectList(Wraps.<DigitalEmployeeModelConfig>lbQ()
			.eq(DigitalEmployeeModelConfig::getTenantId, tenantId)
			.eq(DigitalEmployeeModelConfig::getEmployeeId, employeeId)
			.eq(DigitalEmployeeModelConfig::getEnabled, true)
			.orderByDesc(DigitalEmployeeModelConfig::getIsDefault)
			.orderByAsc(DigitalEmployeeModelConfig::getId));
	}

	/**
	 * 批量统计员工模型配置数量，供 Gallery 列表使用；一次查询覆盖当前页，避免逐员工 N+1。
	 */
	default Map<Long, Long> countByEmployeeIds(Collection<Long> employeeIds, String tenantId) {
		requireTenantId(tenantId);
		if (employeeIds == null || employeeIds.isEmpty()) {
			return Map.of();
		}
		return selectList(Wraps.<DigitalEmployeeModelConfig>lbQ()
			.eq(DigitalEmployeeModelConfig::getDeleted, false)
			.eq(DigitalEmployeeModelConfig::getTenantId, tenantId)
			.in(DigitalEmployeeModelConfig::getEmployeeId, employeeIds))
			.stream()
			.collect(Collectors.groupingBy(DigitalEmployeeModelConfig::getEmployeeId, Collectors.counting()));
	}

	default DigitalEmployeeModelConfig findDefaultByEmployeeId(Long employeeId, String tenantId) {
		requireTenantId(tenantId);
		return selectOne(Wraps.<DigitalEmployeeModelConfig>lbQ()
			.eq(DigitalEmployeeModelConfig::getTenantId, tenantId)
			.eq(DigitalEmployeeModelConfig::getEmployeeId, employeeId)
			.eq(DigitalEmployeeModelConfig::getEnabled, true)
			.eq(DigitalEmployeeModelConfig::getIsDefault, true)
			.last(" limit 1"));
	}

	default int softDeleteByEmployeeId(Long employeeId, String tenantId) {
		requireTenantId(tenantId);
		return update(null, Wraps.<DigitalEmployeeModelConfig>lbU()
			.eq(DigitalEmployeeModelConfig::getTenantId, tenantId)
			.eq(DigitalEmployeeModelConfig::getEmployeeId, employeeId)
			.set(DigitalEmployeeModelConfig::getDeleted, true)
			.set(DigitalEmployeeModelConfig::getLastModifyTime, Instant.now()));
	}

	private static void requireTenantId(String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.forbidden("缺少租户上下文，无法访问数字员工模型配置");
		}
	}

}
