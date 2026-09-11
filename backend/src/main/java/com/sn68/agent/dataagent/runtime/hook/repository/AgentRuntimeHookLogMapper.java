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
package com.sn68.agent.dataagent.runtime.hook.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.runtime.hook.dto.RuntimeHookLogPageQueryRequest;
import com.sn68.agent.dataagent.runtime.hook.entity.AgentRuntimeHookLog;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Agent 运行时钩子执行日志 Mapper：记录每次钩子触发的入参、结果与状态，供诊断与审计查询。
 */
@Repository
public interface AgentRuntimeHookLogMapper extends SuperMapper<AgentRuntimeHookLog> {

	/**
	 * 查询最近的钩子执行日志（诊断用）：按钩子编码/事件类型/状态/Agent/技能/版本/资源键/运行时请求号
	 * 可选过滤，按 ID 倒序，最多返回 200 条。
	 */
	default List<AgentRuntimeHookLog> findRecent(String hookCode, String eventType, String status, Long agentId,
			String skillCode, Long skillVersionId, String resourceKey, String runtimeRequestId) {
		return selectList(buildQuery(hookCode, eventType, status, agentId, skillCode, skillVersionId, resourceKey,
				runtimeRequestId).orderByDesc(AgentRuntimeHookLog::getId).last("LIMIT 200"));
	}

	/**
	 * 管理端分页查询钩子执行日志：过滤维度同 findRecent，按 ID 倒序。
	 */
	default IPage<AgentRuntimeHookLog> selectLogPage(IPage<AgentRuntimeHookLog> page,
			RuntimeHookLogPageQueryRequest request) {
		RuntimeHookLogPageQueryRequest query = request == null ? new RuntimeHookLogPageQueryRequest() : request;
		return selectPage(page, buildQuery(query.getHookCode(), query.getEventType(), query.getStatus(),
			query.getAgentId(), query.getSkillCode(), query.getSkillVersionId(), query.getResourceKey(),
			query.getRuntimeRequestId()).orderByDesc(AgentRuntimeHookLog::getId));
	}

	private static LbqWrapper<AgentRuntimeHookLog> buildQuery(String hookCode, String eventType, String status,
			Long agentId, String skillCode, Long skillVersionId, String resourceKey, String runtimeRequestId) {
		return Wraps.<AgentRuntimeHookLog>lbQ()
			.eq(AgentRuntimeHookLog::getDeleted, false)
			.eq(AgentRuntimeHookLog::getHookCode, trim(hookCode))
			.eq(AgentRuntimeHookLog::getEventType, trim(eventType))
			.eq(AgentRuntimeHookLog::getStatus, trim(status))
			.eq(AgentRuntimeHookLog::getAgentId, agentId)
			.eq(AgentRuntimeHookLog::getSkillCode, trim(skillCode))
			.eq(AgentRuntimeHookLog::getSkillVersionId, skillVersionId)
			.eq(AgentRuntimeHookLog::getResourceKey, trim(resourceKey))
			.eq(AgentRuntimeHookLog::getRuntimeRequestId, trim(runtimeRequestId));
	}

	private static String trim(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

}
