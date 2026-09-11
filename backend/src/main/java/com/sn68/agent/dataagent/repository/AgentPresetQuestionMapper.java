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
import com.sn68.agent.dataagent.entity.AgentPresetQuestion;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * AgentPresetQuestionMapper服务契约。
 */
@Repository
public interface AgentPresetQuestionMapper extends SuperMapper<AgentPresetQuestion> {

	/**
	 * 查询 Agent 已启用的预设问题，按排序号、ID 升序。
	 */
	default List<AgentPresetQuestion> selectByAgentId(Long agentId) {
		return selectList(new LambdaQueryWrapper<AgentPresetQuestion>().eq(AgentPresetQuestion::getAgentId, agentId)
			.eq(AgentPresetQuestion::getIsActive, true)
			.orderByAsc(AgentPresetQuestion::getSortOrder, AgentPresetQuestion::getId));
	}

	/**
	 * 查询 Agent 全部预设问题（含停用），按排序号、ID 升序（管理页展示）。
	 */
	default List<AgentPresetQuestion> selectAllByAgentId(Long agentId) {
		return selectList(new LambdaQueryWrapper<AgentPresetQuestion>().eq(AgentPresetQuestion::getAgentId, agentId)
			.orderByAsc(AgentPresetQuestion::getSortOrder, AgentPresetQuestion::getId));
	}

	/**
	 * 按主键整行更新预设问题（updateById 的语义别名）。
	 */
	default int update(AgentPresetQuestion question) {
		return updateById(question);
	}

	/**
	 * 按 Agent ID 逻辑删除其全部预设问题（Agent 删除时的级联清理）。
	 */
	default int deleteByAgentId(Long agentId) {
		return delete(new LambdaQueryWrapper<AgentPresetQuestion>().eq(AgentPresetQuestion::getAgentId, agentId));
	}

}
