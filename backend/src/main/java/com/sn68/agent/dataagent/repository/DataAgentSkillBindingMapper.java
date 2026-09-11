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
package com.sn68.agent.dataagent.repository;

import com.sn68.agent.dataagent.entity.DataAgentSkillBinding;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * DataAgent技能绑定Mapper服务契约。
 */
@Repository
public interface DataAgentSkillBindingMapper extends SuperMapper<DataAgentSkillBinding> {

	/**
	 * 查询 Agent 绑定的全部 Skill（不区分启用状态），按优先级降序、Skill ID 升序；逻辑删除自动过滤。
	 */
	default List<DataAgentSkillBinding> findByAgentId(Long agentId) {
		return selectList(Wraps.<DataAgentSkillBinding>lbQ()
			.eq(DataAgentSkillBinding::getAgentId, agentId)
			.orderByDesc(DataAgentSkillBinding::getPriority)
			.orderByAsc(DataAgentSkillBinding::getSkillId));
	}

	/**
	 * 查询 Agent 在指定租户下已启用的 Skill 绑定（tenantId 显式过滤，防止跨租户读取）。
	 */
	default List<DataAgentSkillBinding> findEnabledByAgentId(Long agentId, String tenantId) {
		return selectList(Wraps.<DataAgentSkillBinding>lbQ()
			.eq(DataAgentSkillBinding::getAgentId, agentId)
			.eq(DataAgentSkillBinding::getTenantId, tenantId)
			.eq(DataAgentSkillBinding::getEnabled, true)
			.orderByDesc(DataAgentSkillBinding::getPriority)
			.orderByAsc(DataAgentSkillBinding::getSkillId)
			.orderByAsc(DataAgentSkillBinding::getId));
	}

	/**
	 * 查询全部已启用绑定（跨租户跨 Agent，供路由候选预热使用），按租户、Skill、固定版本排序。
	 */
	default List<DataAgentSkillBinding> findAllEnabled() {
		return selectList(Wraps.<DataAgentSkillBinding>lbQ()
			.eq(DataAgentSkillBinding::getEnabled, true)
			.orderByAsc(DataAgentSkillBinding::getTenantId)
			.orderByAsc(DataAgentSkillBinding::getSkillId)
			.orderByAsc(DataAgentSkillBinding::getPinnedSkillVersionId));
	}

	/**
	 * 按 Agent ID 逻辑删除其全部 Skill 绑定（Agent 删除时的级联清理）。
	 */
	default int deleteByAgentId(Long agentId) {
		return delete(Wraps.<DataAgentSkillBinding>lbQ().eq(DataAgentSkillBinding::getAgentId, agentId));
	}

}
