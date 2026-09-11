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
package com.sn68.agent.dataagent.service.chat;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.dto.chat.DataChatTurnDetailResp;
import com.sn68.agent.dataagent.dto.chat.DataChatTurnPageQueryReq;
import com.sn68.agent.dataagent.dto.chat.DataChatUserSummaryResp;
import com.sn68.agent.dataagent.dto.chat.DataChatUserSummaryQueryReq;
import com.sn68.agent.dataagent.entity.DataChatMessage;
import com.sn68.agent.dataagent.entity.DataChatTurn;
import java.time.Instant;
import java.util.List;

/**
 * Data会话Turn服务契约。
 */
public interface DataChatTurnService {

	String STATUS_RUNNING = "running";

	String STATUS_SUCCESS = "success";

	String STATUS_FAILED = "failed";

	String STATUS_CANCELLED = "cancelled";

	String STATUS_WAITING_CLARIFICATION = "waiting_clarification";

	/**
	 * 处理Data会话Turn。
	 */
	void startTurn(AgentRequest request);

	/**
	 * 完成一轮问答：持久化答案、耗时与工具调用统计，并落解释消息。
	 */
	void completeTurn(AgentRequest request, String answer, long durationMs, int toolCount, int toolFailCount,
			DataChatMessage answerExplainMessage);

	/** Persist a public clarification/confirmation response without marking execution successful. */
	void waitForClarification(AgentRequest request, String answer, DataChatMessage answerExplainMessage);

	/**
	 * 处理Data会话Turn。
	 */
	void failTurn(AgentRequest request, Throwable error);

	/**
	 * 保存已返回给用户的业务失败文本，并将 Turn 标记为失败。
	 */
	void completeFailedTurn(AgentRequest request, String answer, Throwable error, DataChatMessage answerExplainMessage);

	/**
	 * 处理Data会话Turn。
	 */
	void cancelTurn(AgentRequest request);

	/**
	 * 查询Data会话Turn。
	 */
	IPage<DataChatTurn> queryTurns(DataChatTurnPageQueryReq request);

	/**
	 * 查询Data会话Turn。
	 */
	DataChatTurnDetailResp getTurnDetail(Long sessionId, String runtimeRequestId);

	/**
	 * 查询Data会话Turn。
	 */
	List<DataChatTurn> listSessionTurns(Long sessionId);

	/**
	 * 处理Data会话Turn。
	 */
	List<DataChatUserSummaryResp> summarizeUsers(DataChatUserSummaryQueryReq request);

	/**
	 * 处理Data会话Turn。
	 */
	Instant turnStartedAt(AgentRequest request);

}
