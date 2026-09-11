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
import com.sn68.agent.dataagent.entity.AgentModelConfig;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * Agent模型配置Mapper服务契约。
 */
@Repository
public interface AgentModelConfigMapper extends SuperMapper<AgentModelConfig> {

	/**
	 * 查询 Agent 绑定的全部模型配置（不区分启用状态），默认模型优先、ID 升序。
	 */
	default List<AgentModelConfig> findByAgentId(Long agentId) {
		return selectList(activeWrapper().eq(AgentModelConfig::getAgentId, agentId)
			.orderByDesc(AgentModelConfig::getIsDefault)
			.orderByAsc(AgentModelConfig::getId));
	}

	/**
	 * 查询 Agent 已启用的模型配置，默认模型优先、ID 升序。
	 */
	default List<AgentModelConfig> findEnabledByAgentId(Long agentId) {
		return selectList(activeWrapper().eq(AgentModelConfig::getAgentId, agentId)
			.eq(AgentModelConfig::getEnabled, true)
			.orderByDesc(AgentModelConfig::getIsDefault)
			.orderByAsc(AgentModelConfig::getId));
	}

	/**
	 * 查询 Agent 与指定模型配置的绑定记录（判重/校验绑定关系用）。
	 */
	default AgentModelConfig findByAgentIdAndModelConfigId(Long agentId, Long modelConfigId) {
		return selectOne(activeWrapper().eq(AgentModelConfig::getAgentId, agentId)
			.eq(AgentModelConfig::getModelConfigId, modelConfigId)
			.last(" limit 1"));
	}

	/**
	 * 查询 Agent 已启用的默认模型绑定（isDefault=true 且 enabled=true），不存在返回 null。
	 */
	default AgentModelConfig findDefaultByAgentId(Long agentId) {
		return selectOne(activeWrapper().eq(AgentModelConfig::getAgentId, agentId)
			.eq(AgentModelConfig::getEnabled, true)
			.eq(AgentModelConfig::getIsDefault, true)
			.last(" limit 1"));
	}

	/**
	 * 清除 Agent 全部模型绑定的默认标记（切换默认模型前调用，保证默认唯一）。
	 */
	default void clearDefault(Long agentId) {
		update(null, Wraps.<AgentModelConfig>lbU().eq(AgentModelConfig::getAgentId, agentId)
			.set(AgentModelConfig::getIsDefault, false)
			.set(AgentModelConfig::getLastModifyTime, Instant.now()));
	}

	/**
	 * 按 Agent ID 逻辑删除其全部模型绑定（Agent 删除时的级联清理，显式置 deleted=true）。
	 */
	default int softDeleteByAgentId(Long agentId) {
		return update(null, Wraps.<AgentModelConfig>lbU().eq(AgentModelConfig::getAgentId, agentId)
			.set(AgentModelConfig::getDeleted, true)
			.set(AgentModelConfig::getLastModifyTime, Instant.now()));
	}

	/**
	 * 逻辑删除过滤由 {@code @TableLogic} 自动追加，此处不再手写 {@code deleted = false}。
	 */
	private LambdaQueryWrapper<AgentModelConfig> activeWrapper() {
		return new LambdaQueryWrapper<AgentModelConfig>();
	}

}
