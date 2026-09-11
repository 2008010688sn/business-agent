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
import com.sn68.agent.dataagent.entity.AgentOrchestrationStep;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * Agent编排StepMapper服务契约。
 */
@Repository
public interface AgentOrchestrationStepMapper extends SuperMapper<AgentOrchestrationStep> {

	/**
	 * 查询编排运行的全部步骤，按步骤号、ID 升序（供执行轨迹回放）。
	 */
	default List<AgentOrchestrationStep> findByRunId(Long runId) {
		return selectList(new LambdaQueryWrapper<AgentOrchestrationStep>()
			.eq(AgentOrchestrationStep::getRunId, runId)
			.orderByAsc(AgentOrchestrationStep::getStepNo)
			.orderByAsc(AgentOrchestrationStep::getId));
	}

}
