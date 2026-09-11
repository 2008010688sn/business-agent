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

import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeDigest;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.time.LocalDate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * 员工每日汇总 Mapper。
 */
@Repository
public interface DigitalEmployeeDigestMapper extends SuperMapper<DigitalEmployeeDigest> {

	default DigitalEmployeeDigest findByTenantEmployeeDate(String tenantId, Long employeeId, LocalDate digestDate) {
		if (!StringUtils.hasText(tenantId) || employeeId == null || digestDate == null) {
			return null;
		}
		return selectOne(Wraps.<DigitalEmployeeDigest>lbQ()
			.eq(DigitalEmployeeDigest::getTenantId, tenantId.trim())
			.eq(DigitalEmployeeDigest::getEmployeeId, employeeId)
			.eq(DigitalEmployeeDigest::getDigestDate, digestDate)
			.last(" limit 1"));
	}

	default DigitalEmployeeDigest findLatest(String tenantId, Long employeeId) {
		if (!StringUtils.hasText(tenantId) || employeeId == null) {
			return null;
		}
		return selectOne(Wraps.<DigitalEmployeeDigest>lbQ()
			.eq(DigitalEmployeeDigest::getTenantId, tenantId.trim())
			.eq(DigitalEmployeeDigest::getEmployeeId, employeeId)
			.orderByDesc(DigitalEmployeeDigest::getDigestDate)
			.last(" limit 1"));
	}

}
