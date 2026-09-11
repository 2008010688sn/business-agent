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
package com.sn68.agent.dataagent.task.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sn68.agent.dataagent.task.entity.AgentTaskVersion;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Agent任务版本Mapper服务契约。版本记录不可变，只提供插入与查询。
 */
@Repository
public interface AgentTaskVersionMapper extends SuperMapper<AgentTaskVersion> {

	/**
	 * 查询任务定义的最新版本。
	 */
	default AgentTaskVersion findLatestByDefinition(String tenantId, Long definitionId) {
		requireTenantId(tenantId);
		if (definitionId == null) {
			return null;
		}
		return selectOne(new LambdaQueryWrapper<AgentTaskVersion>()
			.eq(AgentTaskVersion::getTenantId, tenantId.trim())
			.eq(AgentTaskVersion::getDefinitionId, definitionId)
			.orderByDesc(AgentTaskVersion::getVersionNo)
			.last(" limit 1"));
	}

	/**
	 * 查询任务定义的全部版本（新到旧）。
	 */
	default List<AgentTaskVersion> findByDefinition(String tenantId, Long definitionId) {
		requireTenantId(tenantId);
		if (definitionId == null) {
			return List.of();
		}
		return selectList(new LambdaQueryWrapper<AgentTaskVersion>()
			.eq(AgentTaskVersion::getTenantId, tenantId.trim())
			.eq(AgentTaskVersion::getDefinitionId, definitionId)
			.orderByDesc(AgentTaskVersion::getVersionNo));
	}

	/**
	 * 按租户 + 主键查询任务版本。
	 */
	default AgentTaskVersion findByTenantAndId(String tenantId, Long id) {
		requireTenantId(tenantId);
		if (id == null) {
			return null;
		}
		return selectOne(new LambdaQueryWrapper<AgentTaskVersion>()
			.eq(AgentTaskVersion::getTenantId, tenantId.trim())
			.eq(AgentTaskVersion::getId, id)
			.last(" limit 1"));
	}

	private static void requireTenantId(String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.forbidden("缺少租户上下文，无法访问任务版本");
		}
	}

}
