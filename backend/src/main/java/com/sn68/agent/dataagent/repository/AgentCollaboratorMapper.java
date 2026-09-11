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

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sn68.agent.dataagent.entity.AgentCollaborator;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * Agent协作者Mapper服务契约。
 */
@Repository
public interface AgentCollaboratorMapper extends SuperMapper<AgentCollaborator> {

	/**
	 * 查询主 Agent 下全部协作者（不区分启用状态），按优先级降序、ID 升序；逻辑删除自动过滤。
	 */
	default List<AgentCollaborator> findByAgentId(Long agentId) {
		return selectList(activeWrapper().eq(AgentCollaborator::getAgentId, agentId)
			.orderByDesc(AgentCollaborator::getPriority)
			.orderByAsc(AgentCollaborator::getId));
	}

	/**
	 * 查询主 Agent 下已启用的协作者，按优先级降序、ID 升序。
	 */
	default List<AgentCollaborator> findEnabledByAgentId(Long agentId) {
		return selectList(activeWrapper().eq(AgentCollaborator::getAgentId, agentId)
			.eq(AgentCollaborator::getEnabled, true)
			.orderByDesc(AgentCollaborator::getPriority)
			.orderByAsc(AgentCollaborator::getId));
	}

	/**
	 * 查询全部已启用的协作关系（跨 Agent，供编排预热/巡检使用），按主 Agent、优先级排序。
	 */
	default List<AgentCollaborator> findAllEnabled() {
		return selectList(activeWrapper()
			.eq(AgentCollaborator::getEnabled, true)
			.orderByAsc(AgentCollaborator::getAgentId)
			.orderByDesc(AgentCollaborator::getPriority)
			.orderByAsc(AgentCollaborator::getId));
	}

	/**
	 * 反向查询：某 Agent 作为协作者被哪些主 Agent 启用引用（删除/下线前的影响面检查）。
	 */
	default List<AgentCollaborator> findEnabledByCollaboratorAgentId(Long collaboratorAgentId) {
		return selectList(activeWrapper()
			.eq(AgentCollaborator::getCollaboratorAgentId, collaboratorAgentId)
			.eq(AgentCollaborator::getEnabled, true)
			.orderByAsc(AgentCollaborator::getAgentId)
			.orderByDesc(AgentCollaborator::getPriority)
			.orderByAsc(AgentCollaborator::getId));
	}

	/**
	 * 按主键查询协作者并校验其归属的主 Agent，防止越权操作他人 Agent 的协作关系。
	 */
	default AgentCollaborator findByIdAndAgentId(Long id, Long agentId) {
		return selectOne(activeWrapper().eq(AgentCollaborator::getId, id)
			.eq(AgentCollaborator::getAgentId, agentId)
			.last(" limit 1"));
	}

	/**
	 * 查询主 Agent 与协作 Agent 之间未删除的绑定关系（不区分启用状态），用于判重。
	 */
	default AgentCollaborator findActive(Long agentId, Long collaboratorAgentId) {
		return selectOne(activeWrapper().eq(AgentCollaborator::getAgentId, agentId)
			.eq(AgentCollaborator::getCollaboratorAgentId, collaboratorAgentId)
			.last(" limit 1"));
	}

	/**
	 * 逻辑删除协作者（显式置 deleted=true 并刷新修改时间），同时校验主 Agent 归属。
	 */
	default int softDelete(Long id, Long agentId) {
		return update(null, Wraps.<AgentCollaborator>lbU().eq(AgentCollaborator::getId, id)
			.eq(AgentCollaborator::getAgentId, agentId)
			.set(AgentCollaborator::getDeleted, true)
			.set(AgentCollaborator::getLastModifyTime, Instant.now()));
	}

	/**
	 * 逻辑删除过滤由 {@code @TableLogic} 自动追加，此处不再手写 {@code deleted = false}。
	 */
	private LambdaQueryWrapper<AgentCollaborator> activeWrapper() {
		return new LambdaQueryWrapper<AgentCollaborator>();
	}

}
