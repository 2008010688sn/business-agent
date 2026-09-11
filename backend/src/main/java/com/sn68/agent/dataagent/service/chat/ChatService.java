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
import com.sn68.agent.dataagent.dto.ChatMessageReq;
import com.sn68.agent.dataagent.dto.chat.AnswerExplainQueryReq;
import com.sn68.agent.dataagent.dto.chat.ChatReportDownloadReq;
import com.sn68.agent.dataagent.dto.chat.ChatReportGenerateReq;
import com.sn68.agent.dataagent.dto.chat.ChatReportGenerateResp;
import com.sn68.agent.dataagent.dto.chat.DataChatMessageVO;
import com.sn68.agent.dataagent.dto.chat.ChatSessionPageQueryReq;
import com.sn68.agent.dataagent.dto.chat.CreateChatSessionReq;
import com.sn68.agent.dataagent.dto.chat.SessionCallChainResp;
import com.sn68.agent.dataagent.dto.chat.SessionContextCompressionReq;
import com.sn68.agent.dataagent.dto.chat.SessionContextUsageReq;
import com.sn68.agent.dataagent.dto.chat.SessionPinReq;
import com.sn68.agent.dataagent.dto.chat.SessionRenameReq;
import com.sn68.agent.dataagent.agentscope.vo.AgentResponse;
import com.sn68.agent.dataagent.entity.DataChatMessage;
import com.sn68.agent.dataagent.entity.DataChatSession;
import com.sn68.agent.dataagent.vo.SessionContextCompressionVO;
import com.sn68.agent.dataagent.vo.SessionContextUsageVO;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * DataAgent 会话、消息、上下文和报告能力的服务契约。
 */
public interface ChatService {

	/**
	 * 查询指定 Agent 的会话清单。
	 */
	List<DataChatSession> getAgentSessions(Long agentId);

	/**
	 * 分页查询指定 Agent 的会话列表。
	 */
	IPage<DataChatSession> queryAgentSessions(Long agentId, ChatSessionPageQueryReq request);

	/**
	 * 创建指定 Agent 的会话。
	 */
	DataChatSession createSession(Long agentId, CreateChatSessionReq request);

	/**
	 * 清空指定 Agent 的会话记录。
	 */
	void clearAgentSessions(Long agentId);

	/**
	 * 查询会话消息实体清单。
	 */
	List<DataChatMessage> getSessionMessages(Long sessionId, Long agentId);

	/**
	 * 查询会话消息视图，补充运行请求和耗时信息。
	 */
	List<DataChatMessageVO> getSessionMessageViews(Long sessionId, Long agentId);

	/**
	 * 查询会话上下文用量。
	 */
	SessionContextUsageVO getSessionContextUsage(Long sessionId, SessionContextUsageReq request);

	/**
	 * 手动压缩会话上下文。
	 */
	SessionContextCompressionVO compressSessionContext(Long sessionId, SessionContextCompressionReq request);

	/**
	 * 查询会话最近一次运行 Trace。
	 */
	Object getLatestSessionTrace(Long sessionId, Long agentId);

	/**
	 * 查询会话调用链详情。
	 */
	SessionCallChainResp getSessionCallChain(Long sessionId, Long agentId, String runtimeRequestId);

	/**
	 * 查询会话最近一次答案解释。
	 */
	Object getLatestAnswerExplain(Long sessionId, Long agentId);

	/**
	 * 按运行请求查询答案解释。
	 */
	Object getAnswerExplain(Long sessionId, String runtimeRequestId, Long agentId);

	/**
	 * 按请求对象查询答案解释。
	 */
	Object getAnswerExplain(AnswerExplainQueryReq request);

	/**
	 * 根据答案解释生成分析报告。
	 */
	ChatReportGenerateResp generateReport(ChatReportGenerateReq request);

	/**
	 * 根据答案解释流式生成分析报告。
	 */
	Flux<ServerSentEvent<AgentResponse>> streamGenerateReport(ChatReportGenerateReq request);

	/**
	 * 保存会话消息。
	 */
	DataChatMessage saveMessage(Long sessionId, ChatMessageReq request);

	/**
	 * 修改会话置顶状态。
	 */
	void pinSession(Long sessionId, SessionPinReq request);

	/**
	 * 修改会话标题。
	 */
	void renameSession(Long sessionId, SessionRenameReq request);

	/**
	 * 删除指定 Agent 下的会话。
	 */
	void deleteSession(Long sessionId, Long agentId);

	/**
	 * 下载指定会话的 HTML 报告。
	 */
	byte[] downloadHtmlReport(Long sessionId, ChatReportDownloadReq request, HttpServletResponse response);

	/**
	 * 按请求对象下载 HTML 报告。
	 */
	byte[] downloadHtmlReport(ChatReportDownloadReq request, HttpServletResponse response);

}
