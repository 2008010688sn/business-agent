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
package com.sn68.agent.dataagent.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.agentscope.vo.AgentResponse;
import com.sn68.agent.dataagent.dto.ChatMessageReq;
import com.sn68.agent.dataagent.dto.agent.AgentIdReq;
import com.sn68.agent.dataagent.dto.chat.AnswerExplainQueryReq;
import com.sn68.agent.dataagent.dto.chat.ChatReportDownloadReq;
import com.sn68.agent.dataagent.dto.chat.ChatReportGenerateReq;
import com.sn68.agent.dataagent.dto.chat.ChatReportGenerateResp;
import com.sn68.agent.dataagent.dto.chat.ChatSessionPageQueryReq;
import com.sn68.agent.dataagent.dto.chat.CreateChatSessionReq;
import com.sn68.agent.dataagent.dto.chat.DataChatMessageVO;
import com.sn68.agent.dataagent.dto.chat.SessionAgentReq;
import com.sn68.agent.dataagent.dto.chat.SessionCallChainResp;
import com.sn68.agent.dataagent.dto.chat.SessionContextCompressionReq;
import com.sn68.agent.dataagent.dto.chat.SessionContextUsageReq;
import com.sn68.agent.dataagent.dto.chat.SessionPinReq;
import com.sn68.agent.dataagent.dto.chat.SessionRenameReq;
import com.sn68.agent.dataagent.entity.DataChatMessage;
import com.sn68.agent.dataagent.entity.DataChatSession;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.AnswerTraceExplainView;
import com.sn68.agent.dataagent.observability.SessionTraceStore.TraceView;
import com.sn68.agent.dataagent.service.chat.ChatService;
import com.sn68.agent.dataagent.vo.SessionContextCompressionVO;
import com.sn68.agent.dataagent.vo.SessionContextUsageVO;
import com.sn68.agent.framework.commons.annotation.IgnoreGlobalResponse;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import com.sn68.agent.framework.commons.exception.CheckedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * DataAgent 会话接口，保留历史路径契约并补充会话诊断、报告与消息操作。
 */
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
@Tag(name = "DataAgent 会话", description = "管理 DataAgent 会话、消息、上下文、调用链和报告生成")
public class ChatController {

	private final ChatService chatService;

	@Operation(summary = "会话清单", description = "查询指定 Agent 下的会话清单")
	@PostMapping("/sessions/query")
	public List<DataChatSession> getAgentSessions(@Valid @RequestBody AgentIdReq request) {
		return chatService.getAgentSessions(request.agentId());
	}

	@Operation(summary = "会话分页", description = "分页查询指定 Agent 下的会话列表")
	@PostMapping("/sessions/page")
	public IPage<DataChatSession> queryAgentSessions(
			@Valid @RequestBody(required = false) ChatSessionPageQueryReq request) {
		// 请求体可缺省，缺省时无从取得 agentId，与 @NotNull 校验保持同一拒绝语义
		if (request == null) {
			throw CheckedException.badRequest("agentId不能为空");
		}
		return chatService.queryAgentSessions(request.getAgentId(), request);
	}

	@Operation(summary = "创建会话", description = "为指定 Agent 创建新的对话会话")
	@AccessLog(module = "DataAgent 会话", description = "创建会话")
	@PostMapping("/sessions/create")
	public DataChatSession createSession(@Valid @RequestBody(required = false) CreateChatSessionReq request) {
		// 请求体可缺省，缺省时无从取得 agentId，与 @NotNull 校验保持同一拒绝语义
		if (request == null) {
			throw CheckedException.badRequest("agentId不能为空");
		}
		return chatService.createSession(request.getAgentId(), request);
	}

	@Operation(summary = "清空会话", description = "清空指定 Agent 下当前用户的会话记录")
	@AccessLog(module = "DataAgent 会话", description = "清空会话")
	@DeleteMapping("/sessions/clear")
	public void clearAgentSessions(@Valid @RequestBody AgentIdReq request) {
		chatService.clearAgentSessions(request.agentId());
	}

	@Operation(summary = "消息清单", description = "查询指定会话下的消息清单和运行摘要视图")
	@PostMapping("/sessions/messages/query")
	public List<DataChatMessageVO> getSessionMessages(@Valid @RequestBody SessionAgentReq request) {
		return chatService.getSessionMessageViews(request.getSessionId(), request.getAgentId());
	}

	@Operation(summary = "上下文用量", description = "查询指定会话的上下文窗口与消息用量")
	@PostMapping("/sessions/context/query")
	public SessionContextUsageVO getSessionContextUsage(@Valid @RequestBody SessionContextUsageReq request) {
		return chatService.getSessionContextUsage(request.getSessionId(), request);
	}

	@Operation(summary = "压缩上下文", description = "手动触发指定会话的上下文压缩")
	@PostMapping("/sessions/context-compress")
	public SessionContextCompressionVO compressSessionContext(
			@Valid @RequestBody SessionContextCompressionReq request) {
		return chatService.compressSessionContext(request.getSessionId(), request);
	}

	// 以下诊断端点：ChatService 接口仍以 Object 声明返回值，这里收窄到运行时真实类型，
	// 让 Swagger 能产出 schema；序列化对象本身不变，出参 JSON 与收窄前一致。
	@Operation(summary = "最新 Trace", description = "查询指定会话最近一次运行 Trace")
	@PostMapping("/sessions/trace/query")
	public TraceView getLatestSessionTrace(@Valid @RequestBody SessionAgentReq request) {
		return (TraceView) chatService.getLatestSessionTrace(request.getSessionId(), request.getAgentId());
	}

	@Operation(summary = "调用链", description = "查询指定会话和运行请求的调用链详情")
	@PostMapping("/sessions/call-chain/query")
	public SessionCallChainResp getSessionCallChain(@Valid @RequestBody SessionAgentReq request) {
		return chatService.getSessionCallChain(request.getSessionId(), request.getAgentId(),
				request.getRuntimeRequestId());
	}

	@Operation(summary = "最新答案解释", description = "查询指定会话最近一次答案解释")
	@PostMapping("/sessions/answers/latest/explain/query")
	public AnswerTraceExplainView getLatestAnswerExplain(@Valid @RequestBody SessionAgentReq request) {
		return (AnswerTraceExplainView) chatService.getLatestAnswerExplain(request.getSessionId(),
				request.getAgentId());
	}

	@Operation(summary = "答案解释", description = "按会话和运行请求查询答案解释")
	@PostMapping("/sessions/answers/explain/query")
	public AnswerTraceExplainView getAnswerExplain(
			@RequestBody @jakarta.validation.Valid AnswerExplainQueryReq request) {
		return (AnswerTraceExplainView) chatService.getAnswerExplain(request);
	}

	@Operation(summary = "生成报告", description = "根据答案解释生成分析报告")
	@PostMapping("/sessions/reports/generate")
	public ChatReportGenerateResp generateReport(
			@RequestBody @jakarta.validation.Valid ChatReportGenerateReq request) {
		return chatService.generateReport(request);
	}

	@Operation(summary = "流式生成报告", description = "根据答案解释流式生成 Markdown 分析报告")
	@IgnoreGlobalResponse(description = "流式生成 Markdown 报告")
	@PostMapping(value = "/sessions/reports/generate/stream", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<AgentResponse>> streamGenerateReport(
			@RequestBody @jakarta.validation.Valid ChatReportGenerateReq request) {
		return chatService.streamGenerateReport(request);
	}

	@Operation(summary = "保存消息", description = "保存指定会话下的单条聊天消息")
	@PostMapping("/sessions/messages")
	public DataChatMessage saveMessage(@Valid @RequestBody ChatMessageReq request) {
		return chatService.saveMessage(request.getSessionId(), request);
	}

	@Operation(summary = "置顶会话", description = "修改指定会话的置顶状态")
	@AccessLog(module = "DataAgent 会话", description = "置顶会话")
	@PutMapping("/sessions/pin")
	public void pinSession(@Valid @RequestBody SessionPinReq request) {
		chatService.pinSession(request.getSessionId(), request);
	}

	@Operation(summary = "重命名会话", description = "修改指定会话的标题")
	@AccessLog(module = "DataAgent 会话", description = "重命名会话")
	@PutMapping("/sessions/rename")
	public void renameSession(@Valid @RequestBody SessionRenameReq request) {
		chatService.renameSession(request.getSessionId(), request);
	}

	@Operation(summary = "删除会话", description = "删除指定 Agent 下的会话")
	@AccessLog(module = "DataAgent 会话", description = "删除会话")
	@DeleteMapping("/sessions")
	public void deleteSession(@Valid @RequestBody SessionAgentReq request) {
		chatService.deleteSession(request.getSessionId(), request.getAgentId());
	}

	@Operation(summary = "下载 HTML 报告", description = "将报告内容转换为 HTML 文件并下载")
	@IgnoreGlobalResponse(description = "下载 HTML 报告")
	@PostMapping("/sessions/reports/html")
	public byte[] convertAndDownloadHtml(@RequestBody ChatReportDownloadReq request, HttpServletResponse response) {
		return chatService.downloadHtmlReport(request, response);
	}

}
