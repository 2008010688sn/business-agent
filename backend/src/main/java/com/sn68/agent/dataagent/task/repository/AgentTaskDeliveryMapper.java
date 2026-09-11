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
import com.sn68.agent.dataagent.task.entity.AgentTaskDelivery;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Agent任务投递Mapper服务契约。
 */
@Repository
public interface AgentTaskDeliveryMapper extends SuperMapper<AgentTaskDelivery> {

	/**
	 * 查询任务运行的全部投递记录。
	 */
	default List<AgentTaskDelivery> findByTaskRun(String tenantId, Long taskRunId) {
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.forbidden("缺少租户上下文，无法访问任务投递");
		}
		if (taskRunId == null) {
			return List.of();
		}
		return selectList(new LambdaQueryWrapper<AgentTaskDelivery>()
			.eq(AgentTaskDelivery::getTenantId, tenantId.trim())
			.eq(AgentTaskDelivery::getTaskRunId, taskRunId)
			.orderByAsc(AgentTaskDelivery::getId));
	}

}
