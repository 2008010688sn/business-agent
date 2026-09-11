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

import com.sn68.agent.dataagent.entity.AgentScopeSessionState;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Repository;

/**
 * AgentScope会话StateMapper服务契约。
 */
@Repository
public interface AgentScopeSessionStateMapper extends SuperMapper<AgentScopeSessionState> {

	/**
	 * 查询会话内指定模块的状态分片，按序号、ID 升序（供状态重组拼接）。
	 */
	default List<AgentScopeSessionState> selectBySessionIdAndModuleName(Long sessionId, String moduleName) {
		return selectList(activeWrapper().eq(AgentScopeSessionState::getSessionId, sessionId)
			.eq(AgentScopeSessionState::getModuleName, moduleName)
			.orderByAsc(AgentScopeSessionState::getSequence)
			.orderByAsc(AgentScopeSessionState::getId));
	}

	/**
	 * 逻辑删除会话内指定模块的全部状态分片（模块状态重写前清理旧分片）。
	 */
	default int deleteBySessionIdAndModuleName(Long sessionId, String moduleName) {
		return delete(activeWrapper().eq(AgentScopeSessionState::getSessionId, sessionId)
			.eq(AgentScopeSessionState::getModuleName, moduleName));
	}

	/**
	 * 统计会话的状态分片条数（判断会话是否已有持久化状态）。
	 */
	default int countBySessionId(Long sessionId) {
		return Math.toIntExact(selectCount(activeWrapper().eq(AgentScopeSessionState::getSessionId, sessionId)));
	}

	/**
	 * 逻辑删除会话的全部状态分片（会话重置/删除时调用）。
	 */
	default int deleteBySessionId(Long sessionId) {
		List<Long> ids = selectList(activeWrapper().eq(AgentScopeSessionState::getSessionId, sessionId)).stream()
			.map(AgentScopeSessionState::getId)
			.filter(Objects::nonNull)
			.toList();
		return ids.isEmpty() ? 0 : deleteBatchIds(ids);
	}

	/**
	 * 全库逻辑删除全部会话状态分片（仅供一次性迁移清理任务使用）。
	 */
	default int deleteAllStates() {
		List<Long> ids = selectList(activeWrapper()).stream()
			.map(AgentScopeSessionState::getId)
			.filter(Objects::nonNull)
			.toList();
		return ids.isEmpty() ? 0 : deleteBatchIds(ids);
	}

	/**
	 * 查询存在状态分片的会话 ID 去重集合（仅取 sessionId 列，供巡检/迁移扫描）。
	 */
	default List<Long> selectSessionIdsWithState() {
		return selectList(activeWrapper().select(AgentScopeSessionState::getSessionId)
			.orderByAsc(AgentScopeSessionState::getSessionId))
			.stream()
			.map(AgentScopeSessionState::getSessionId)
			.filter(Objects::nonNull)
			.distinct()
			.toList();
	}

	/**
	 * 逻辑删除过滤由 {@code @TableLogic} 自动追加，此处不再手写 {@code deleted = false}。
	 */
	private LbqWrapper<AgentScopeSessionState> activeWrapper() {
		return Wraps.<AgentScopeSessionState>lbQ();
	}

}
