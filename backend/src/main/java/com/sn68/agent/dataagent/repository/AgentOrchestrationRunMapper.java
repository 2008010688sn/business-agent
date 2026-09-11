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
import com.sn68.agent.dataagent.entity.AgentOrchestrationRun;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * Agent编排RunMapper服务契约。
 */
@Repository
public interface AgentOrchestrationRunMapper extends SuperMapper<AgentOrchestrationRun> {

	/**
	 * 查询 Agent 的全部编排运行记录，按开始时间、ID 倒序。
	 */
	default List<AgentOrchestrationRun> findByAgentId(Long agentId) {
		return selectList(new LambdaQueryWrapper<AgentOrchestrationRun>()
			.eq(AgentOrchestrationRun::getAgentId, agentId)
			.orderByDesc(AgentOrchestrationRun::getStartedAt)
			.orderByDesc(AgentOrchestrationRun::getId));
	}

	/**
	 * 按主键查询编排运行记录并校验 Agent 归属，防止跨 Agent 读取。
	 */
	default AgentOrchestrationRun findByIdAndAgentId(Long id, Long agentId) {
		return selectOne(new LambdaQueryWrapper<AgentOrchestrationRun>()
			.eq(AgentOrchestrationRun::getId, id)
			.eq(AgentOrchestrationRun::getAgentId, agentId)
			.last(" limit 1"));
	}

	/**
	 * 按运行请求 ID + Agent 查询编排运行记录（幂等定位一次编排执行）。
	 */
	default AgentOrchestrationRun findByRuntimeRequestIdAndAgentId(String runtimeRequestId, Long agentId) {
		return selectOne(new LambdaQueryWrapper<AgentOrchestrationRun>()
			.eq(AgentOrchestrationRun::getRuntimeRequestId, runtimeRequestId)
			.eq(AgentOrchestrationRun::getAgentId, agentId)
			.last(" limit 1"));
	}

}
