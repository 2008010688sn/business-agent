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
import com.sn68.agent.dataagent.constant.AgentSessionConstant;
import com.sn68.agent.dataagent.entity.DataChatMessage;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Data会话消息Mapper服务契约。
 */
@Repository
public interface DataChatMessageMapper extends SuperMapper<DataChatMessage> {

	String MESSAGE_TYPE_THINKING = "thinking";

	String THINKING_HTML_BLOCK = "agent-thinking-block";

	String THINKING_HTML_GROUP = "agent-thinking-group";

	/**
	 * Query message list by session ID
	 */
	default List<DataChatMessage> selectBySessionId(Long sessionId) {
		return selectList(new LambdaQueryWrapper<DataChatMessage>().eq(DataChatMessage::getSessionId, sessionId)
			.orderByAsc(DataChatMessage::getCreateTime));
	}

	/**
	 * 查询会话内对用户可见的消息：过滤记忆文本、答案解释、AgentScope 状态消息与思考过程消息。
	 */
	default List<DataChatMessage> selectVisibleBySessionId(Long sessionId) {
		return selectBySessionId(sessionId).stream().filter(DataChatMessageMapper::isVisibleMessage).toList();
	}

	/**
	 * 查询会话内的思考过程消息（messageType=thinking 或内容含思考 HTML 标记），按创建时间升序。
	 */
	default List<DataChatMessage> selectThinkingBySessionId(Long sessionId) {
		return selectBySessionId(sessionId).stream()
			.filter(message -> isThinkingMessage(normalizeMessageType(message.getMessageType()), message.getContent()))
			.toList();
	}

	/**
	 * 按消息类型（大小写不敏感）查询会话消息，按创建时间倒序；类型匹配在内存过滤以兼容历史大小写混杂数据。
	 */
	default List<DataChatMessage> selectBySessionIdAndMessageType(Long sessionId, String messageType) {
		String normalizedMessageType = normalizeMessageType(messageType);
		return selectList(new LambdaQueryWrapper<DataChatMessage>().eq(DataChatMessage::getSessionId, sessionId)
			.orderByDesc(DataChatMessage::getCreateTime)
			.orderByDesc(DataChatMessage::getId))
			.stream()
			.filter(message -> normalizedMessageType.equals(normalizeMessageType(message.getMessageType())))
			.toList();
	}

	/**
	 * 按消息类型查询会话消息，按创建时间升序（用于状态类消息按写入顺序回放）。
	 */
	default List<DataChatMessage> selectStateBySessionIdAndMessageType(Long sessionId, String messageType) {
		String normalizedMessageType = normalizeMessageType(messageType);
		return selectList(new LambdaQueryWrapper<DataChatMessage>().eq(DataChatMessage::getSessionId, sessionId)
			.orderByAsc(DataChatMessage::getCreateTime)
			.orderByAsc(DataChatMessage::getId))
			.stream()
			.filter(message -> normalizedMessageType.equals(normalizeMessageType(message.getMessageType())))
			.toList();
	}

	/**
	 * 查询存在 AgentScope 状态消息的会话 ID 去重集合（仅取 sessionId/messageType 两列，供迁移清理任务扫描）。
	 */
	default List<Long> selectSessionIdsWithAgentScopeState() {
		return selectList(Wraps.<DataChatMessage>lbQ().select(DataChatMessage::getSessionId,
				DataChatMessage::getMessageType)
			.orderByAsc(DataChatMessage::getSessionId))
			.stream()
			.filter(DataChatMessageMapper::isAgentScopeStateMessage)
			.map(DataChatMessage::getSessionId)
			.filter(Objects::nonNull)
			.distinct()
			.toList();
	}

	/**
	 * 统计会话内 AgentScope 状态消息条数（内存过滤，前缀匹配 messageType）。
	 */
	default int countAgentScopeStateBySessionId(Long sessionId) {
		return Math.toIntExact(selectBySessionId(sessionId).stream()
			.filter(DataChatMessageMapper::isAgentScopeStateMessage)
			.count());
	}

	/**
	 * Query message count by session ID
	 */
	default int countBySessionId(Long sessionId) {
		return Math.toIntExact(selectCount(new LambdaQueryWrapper<DataChatMessage>()
			.eq(DataChatMessage::getSessionId, sessionId)));
	}

	/**
	 * Query message list by session ID and role
	 */
	default List<DataChatMessage> selectBySessionIdAndRole(Long sessionId, String role) {
		return selectList(new LambdaQueryWrapper<DataChatMessage>().eq(DataChatMessage::getSessionId, sessionId)
			.eq(DataChatMessage::getRole, role)
			.orderByAsc(DataChatMessage::getCreateTime));
	}

	/**
	 * 按消息类型逻辑删除会话消息（先查 ID 再批量删，类型匹配大小写不敏感）。
	 */
	default int deleteBySessionIdAndMessageType(Long sessionId, String messageType) {
		List<Long> ids = selectBySessionIdAndMessageType(sessionId, messageType).stream()
			.map(DataChatMessage::getId)
			.filter(Objects::nonNull)
			.toList();
		return ids.isEmpty() ? 0 : deleteBatchIds(ids);
	}

	/**
	 * 逻辑删除会话内全部 AgentScope 状态消息（会话重置时调用）。
	 */
	default int deleteAgentScopeStateBySessionId(Long sessionId) {
		List<Long> ids = selectBySessionId(sessionId).stream()
			.filter(DataChatMessageMapper::isAgentScopeStateMessage)
			.map(DataChatMessage::getId)
			.filter(Objects::nonNull)
			.toList();
		return ids.isEmpty() ? 0 : deleteBatchIds(ids);
	}

	/**
	 * 全库逻辑删除 AgentScope 状态消息（跨会话，仅供一次性迁移清理任务使用）。
	 */
	default int deleteAllAgentScopeStateMessages() {
		List<Long> ids = selectList(Wraps.<DataChatMessage>lbQ().select(DataChatMessage::getId,
				DataChatMessage::getMessageType))
			.stream()
			.filter(DataChatMessageMapper::isAgentScopeStateMessage)
			.map(DataChatMessage::getId)
			.filter(Objects::nonNull)
			.toList();
		return ids.isEmpty() ? 0 : deleteBatchIds(ids);
	}

	/**
	 * 用户可见判定：排除记忆文本、答案解释、AgentScope 状态前缀与思考消息。
	 */
	private static boolean isVisibleMessage(DataChatMessage message) {
		String messageType = normalizeMessageType(message.getMessageType());
		return !AgentSessionConstant.MESSAGE_TYPE_MEMORY_TEXT.equals(messageType)
				&& !AgentSessionConstant.MESSAGE_TYPE_ANSWER_EXPLAIN.equals(messageType)
				&& !messageType.startsWith(AgentSessionConstant.AGENTSCOPE_STATE_MESSAGE_TYPE_PREFIX)
				&& !isThinkingMessage(messageType, message.getContent());
	}

	/**
	 * 是否为 AgentScope 运行时状态消息（messageType 前缀匹配）。
	 */
	private static boolean isAgentScopeStateMessage(DataChatMessage message) {
		return normalizeMessageType(message.getMessageType())
			.startsWith(AgentSessionConstant.AGENTSCOPE_STATE_MESSAGE_TYPE_PREFIX);
	}

	/**
	 * 消息类型归一化：null 转空串、去空白并小写，兼容历史大小写混杂数据。
	 */
	private static String normalizeMessageType(String messageType) {
		return messageType == null ? "" : messageType.trim().toLowerCase(Locale.ROOT);
	}

	/**
	 * 是否为思考过程消息：类型为 thinking，或正文包含思考块 HTML 标记（历史数据兼容）。
	 */
	private static boolean isThinkingMessage(String messageType, String content) {
		return MESSAGE_TYPE_THINKING.equals(messageType) || containsThinkingHtml(content);
	}

	/**
	 * 正文是否包含思考块/思考分组的 HTML class 标记（大小写不敏感）。
	 */
	private static boolean containsThinkingHtml(String content) {
		if (content == null) {
			return false;
		}
		String normalizedContent = content.toLowerCase(Locale.ROOT);
		return normalizedContent.contains(THINKING_HTML_BLOCK) || normalizedContent.contains(THINKING_HTML_GROUP);
	}

}
