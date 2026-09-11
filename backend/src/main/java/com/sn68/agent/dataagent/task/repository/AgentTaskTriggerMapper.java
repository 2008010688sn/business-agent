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
import com.sn68.agent.dataagent.task.entity.AgentTaskTrigger;
import com.sn68.agent.dataagent.task.enums.TaskConstants;
import com.sn68.agent.dataagent.task.enums.TaskTriggerType;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Agent任务触发器Mapper服务契约。
 */
@Repository
public interface AgentTaskTriggerMapper extends SuperMapper<AgentTaskTrigger> {

	/**
	 * 查询任务定义下的全部触发器。
	 */
	default List<AgentTaskTrigger> findByDefinition(String tenantId, Long definitionId) {
		requireTenantId(tenantId);
		if (definitionId == null) {
			return List.of();
		}
		return selectList(new LambdaQueryWrapper<AgentTaskTrigger>()
			.eq(AgentTaskTrigger::getTenantId, tenantId.trim())
			.eq(AgentTaskTrigger::getDefinitionId, definitionId)
			.orderByAsc(AgentTaskTrigger::getId));
	}

	/**
	 * 按租户 + 主键查询触发器。
	 */
	default AgentTaskTrigger findByTenantAndId(String tenantId, Long id) {
		requireTenantId(tenantId);
		if (id == null) {
			return null;
		}
		return selectOne(new LambdaQueryWrapper<AgentTaskTrigger>()
			.eq(AgentTaskTrigger::getTenantId, tenantId.trim())
			.eq(AgentTaskTrigger::getId, id)
			.last(" limit 1"));
	}

	/**
	 * 系统级：扫描到期的启用 SCHEDULE 触发器（跨租户）。
	 *
	 * <p>定时调度是无租户上下文的系统级路径，任务归属租户由触发器行上的 tenant_id 提供，
	 * 创建 task_run 时按行内租户落库，不依赖当前登录上下文。
	 */
	default List<AgentTaskTrigger> findDueScheduleTriggers(Instant now, int limit) {
		return selectList(new LambdaQueryWrapper<AgentTaskTrigger>()
			.eq(AgentTaskTrigger::getTriggerType, TaskTriggerType.SCHEDULE.getValue())
			.eq(AgentTaskTrigger::getStatus, TaskConstants.STATUS_ENABLED)
			.isNotNull(AgentTaskTrigger::getNextFireTime)
			.le(AgentTaskTrigger::getNextFireTime, now)
			.orderByAsc(AgentTaskTrigger::getNextFireTime)
			.last(" limit " + Math.max(1, limit)));
	}

	/**
	 * 系统级：查询订阅指定事件主题的启用 EVENT 触发器（跨租户，MQ 消费路径）。
	 *
	 * <p>eventTopic 存于 JSONB 配置内，这里用 JSONB 提取表达式过滤；
	 * PostgreSQL 专用写法，与模块现有 JsonbMapTypeHandler 的存储格式一致。
	 */
	default List<AgentTaskTrigger> findEnabledEventTriggersByTopic(String eventTopic) {
		if (!StringUtils.hasText(eventTopic)) {
			return List.of();
		}
		return selectList(new LambdaQueryWrapper<AgentTaskTrigger>()
			.eq(AgentTaskTrigger::getTriggerType, TaskTriggerType.EVENT.getValue())
			.eq(AgentTaskTrigger::getStatus, TaskConstants.STATUS_ENABLED)
			.apply("trigger_config ->> 'eventTopic' = {0}", eventTopic.trim())
			.orderByAsc(AgentTaskTrigger::getId));
	}

	private static void requireTenantId(String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.forbidden("缺少租户上下文，无法访问任务触发器");
		}
	}

}
