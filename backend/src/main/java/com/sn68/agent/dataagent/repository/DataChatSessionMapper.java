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
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.constant.AgentSessionConstant;
import com.sn68.agent.dataagent.dto.chat.ChatSessionPageQueryReq;
import com.sn68.agent.dataagent.entity.DataChatSession;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;

/**
 * Data会话会话Mapper服务契约。
 */
@Repository
public interface DataChatSessionMapper extends SuperMapper<DataChatSession> {

	/**
	 * Query session list by agent ID
	 */
	default List<DataChatSession> selectByAgentId(Long agentId, String channelType) {
		return selectList(baseQuery(agentId, channelType)
			.orderByDesc(DataChatSession::getIsPinned)
			.orderByDesc(DataChatSession::getLastModifyTime));
	}

	/**
	 * Page query session list by agent ID
	 */
	default IPage<DataChatSession> selectPageByAgentId(IPage<DataChatSession> page, Long agentId,
			ChatSessionPageQueryReq request) {
		ChatSessionPageQueryReq query = request == null ? new ChatSessionPageQueryReq() : request;
		LambdaQueryWrapper<DataChatSession> wrapper = baseQuery(agentId, query.getChannelType());
		if (StringUtils.hasText(query.getProvider())) {
			wrapper.eq(DataChatSession::getProvider, query.getProvider().trim().toUpperCase());
		}
		if (StringUtils.hasText(query.getConnectorCode())) {
			wrapper.eq(DataChatSession::getConnectorCode, query.getConnectorCode().trim());
		}
		return selectPage(page, wrapper
			.orderByDesc(DataChatSession::getIsPinned)
			.orderByDesc(DataChatSession::getLastModifyTime));
	}

	/**
	 * 会话查询公共条件：按 Agent 过滤并排除已删除状态（status!=DELETED），渠道类型可选（大写归一化）。
	 */
	private static LambdaQueryWrapper<DataChatSession> baseQuery(Long agentId, String channelType) {
		LambdaQueryWrapper<DataChatSession> wrapper = new LambdaQueryWrapper<DataChatSession>()
			.eq(DataChatSession::getAgentId, agentId)
			.ne(DataChatSession::getStatus, AgentSessionConstant.SESSION_STATUS_DELETED);
		if (StringUtils.hasText(channelType)) {
			wrapper.eq(DataChatSession::getChannelType, channelType.trim().toUpperCase());
		}
		return wrapper;
	}

	/**
	 * Query session details by session ID
	 */
	default DataChatSession selectBySessionId(Long sessionId) {
		return selectOne(new LambdaQueryWrapper<DataChatSession>().eq(DataChatSession::getId, sessionId)
			.ne(DataChatSession::getStatus, AgentSessionConstant.SESSION_STATUS_DELETED)
			.last(" limit 1"));
	}

	/**
	 * Soft delete all sessions for an agent
	 */
	default int softDeleteByAgentId(Long agentId, Instant updateTime) {
		return update(null, Wraps.<DataChatSession>lbU().eq(DataChatSession::getAgentId, agentId)
			.set(DataChatSession::getStatus, AgentSessionConstant.SESSION_STATUS_DELETED)
			.set(DataChatSession::getLastModifyTime, updateTime));
	}

	/**
	 * Update session time
	 */
	default int updateSessionTime(Long sessionId, Instant updateTime) {
		return update(null, Wraps.<DataChatSession>lbU().eq(DataChatSession::getId, sessionId)
			.set(DataChatSession::getLastModifyTime, updateTime));
	}

	/**
	 * Update session pinned status
	 */
	default int updatePinStatus(Long sessionId, boolean isPinned, Instant updateTime) {
		return update(null, Wraps.<DataChatSession>lbU().eq(DataChatSession::getId, sessionId)
			.set(DataChatSession::getIsPinned, isPinned)
			.set(DataChatSession::getLastModifyTime, updateTime));
	}

	/**
	 * Update session title
	 */
	default int updateTitle(Long sessionId, String title, Instant updateTime) {
		return update(null, Wraps.<DataChatSession>lbU().eq(DataChatSession::getId, sessionId)
			.set(DataChatSession::getTitle, title)
			.set(DataChatSession::getLastModifyTime, updateTime));
	}

	/**
	 * Soft delete session
	 */
	default int softDeleteById(Long sessionId, Instant updateTime) {
		return update(null, Wraps.<DataChatSession>lbU().eq(DataChatSession::getId, sessionId)
			.set(DataChatSession::getStatus, AgentSessionConstant.SESSION_STATUS_DELETED)
			.set(DataChatSession::getLastModifyTime, updateTime));
	}

}
