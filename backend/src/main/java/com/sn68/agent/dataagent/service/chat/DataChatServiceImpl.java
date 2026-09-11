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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sn68.agent.dataagent.agentscope.runtime.ContextCompressionService;
import com.sn68.agent.dataagent.agentscope.runtime.ContextCompressionService.CompressionResult;
import com.sn68.agent.dataagent.agentscope.service.AgentScopeModelFactory;
import com.sn68.agent.dataagent.agentscope.session.AgentRuntimeRegistry;
import com.sn68.agent.dataagent.agentscope.session.AgentScopeNativeSessionService;
import com.sn68.agent.dataagent.agentscope.v2.V2AgentStateStore;
import com.sn68.agent.dataagent.agentscope.v2.V2RuntimeSnapshot;
import com.sn68.agent.dataagent.agentscope.vo.AgentResponse;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.sn68.agent.dataagent.dto.ChatMessageReq;
import com.sn68.agent.dataagent.dto.chat.AnswerExplainQueryReq;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
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
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.entity.AgentOrchestrationRun;
import com.sn68.agent.dataagent.entity.DataChatMessage;
import com.sn68.agent.dataagent.entity.DataChatSession;
import com.sn68.agent.dataagent.entity.DataChatTurn;
import com.sn68.agent.dataagent.enums.ChatSessionChannelDict;
import com.sn68.agent.dataagent.enums.SessionContextCompressionStatus;
import com.sn68.agent.dataagent.dto.agent.OrchestrationTraceResp;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.AnswerTraceExplainView;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.ToolStepView;
import com.sn68.agent.dataagent.observability.SessionTraceStore;
import com.sn68.agent.dataagent.repository.AgentOrchestrationRunMapper;
import com.sn68.agent.dataagent.repository.AgentOrchestrationStepMapper;
import com.sn68.agent.dataagent.repository.DataChatTurnMapper;
import com.sn68.agent.dataagent.service.agent.AgentModelConfigService;
import com.sn68.agent.dataagent.service.agent.DataAgentService;
import com.sn68.agent.dataagent.service.aimodelconfig.DynamicModelFactory;
import com.sn68.agent.dataagent.service.file.FilePreviewResp;
import com.sn68.agent.dataagent.service.file.LocalFileService;
import com.sn68.agent.dataagent.service.permission.DataAgentThinkingPermissionService;
import com.sn68.agent.dataagent.service.report.AnalysisReportService;
import com.sn68.agent.dataagent.util.ReportTemplateUtil;
import com.sn68.agent.dataagent.vo.SessionContextCompressionVO;
import com.sn68.agent.dataagent.vo.SessionContextUsageVO;
import com.sn68.agent.dataagent.enums.TextType;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

/**
 * DataAgent 会话应用服务，串联消息、上下文压缩、调用链和报告生成能力。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataChatServiceImpl implements ChatService {

	private static final String ANSWER_EXPLAIN_MESSAGE_TYPE = "answer-explain";

	private static final String STREAM_EVENT_MESSAGE = "message";

	private static final String STREAM_EVENT_COMPLETE = "complete";

	private static final String REPORT_NODE_NAME = "ReportGeneratorNode";

	private static final String COMPAT_USER_ROLE = "user";

	private static final String CLIENT_REQUEST_ID_METADATA_KEY = "clientRequestId";

	private static final long DEFAULT_CONTEXT_WINDOW_TOKENS = 32768L;

	private static final Encoding TOKEN_ENCODING = Encodings.newDefaultEncodingRegistry()
		.getEncoding(EncodingType.CL100K_BASE);

	private final DataChatSessionService chatSessionService;

	private final ChatMessageService chatMessageService;

	private final AgentModelConfigService agentModelConfigService;

	private final DataAgentService agentService;

	private final AgentOrchestrationRunMapper orchestrationRunMapper;

	private final AgentOrchestrationStepMapper orchestrationStepMapper;

	private final DataChatTurnMapper chatTurnMapper;

	private final SessionTitleService sessionTitleService;

	private final ReportTemplateUtil reportTemplateUtil;

	private final SessionTraceStore sessionTraceStore;

	private final AnswerTraceExplainStore answerTraceExplainStore;

	private final AnalysisReportService analysisReportService;

	private final LocalFileService localFileService;

	private final ObjectMapper objectMapper;

	private final DataAgentThinkingPermissionService thinkingPermissionService;

	private final AgentRuntimeRegistry runtimeRegistry;

	private final AgentScopeNativeSessionService nativeSessionService;

	private final ContextCompressionService contextCompressionService;

	private final DynamicModelFactory dynamicModelFactory;

	private final AgentScopeModelFactory agentScopeModelFactory;

	private final AuthenticationContext authenticationContext;

	private final ObjectProvider<V2AgentStateStore> v2AgentStateStoreProvider;

	private final ConcurrentHashMap<Long, ReentrantLock> contextCompressionLocks = new ConcurrentHashMap<>();

	@Override
	public List<DataChatSession> getAgentSessions(Long agentId) {
		return chatSessionService.findByAgentIdAndUserId(agentId, requireCurrentUserId());
	}

	@Override
	public IPage<DataChatSession> queryAgentSessions(Long agentId, ChatSessionPageQueryReq request) {
		Long userId = requireCurrentUserId();
		ChatSessionPageQueryReq pageRequest = request == null ? new ChatSessionPageQueryReq() : request;
		rejectEmployeeChannelQuery(pageRequest.getChannelType());
		if (StringUtils.hasText(pageRequest.getChannelType())
				&& !ChatSessionChannelDict.WEB.getValue().equalsIgnoreCase(pageRequest.getChannelType())) {
			thinkingPermissionService.requireCanViewAnyDiagnostics();
		}
		return chatSessionService.queryByAgentIdAndUserId(agentId, userId, pageRequest);
	}

	@Override
	public DataChatSession createSession(Long agentId, CreateChatSessionReq request) {
		String title = request == null ? null : request.getTitle();
		return chatSessionService.createSession(agentId, title, requireCurrentUserId(),
				ChatSessionChannelDict.WEB.getValue(), null, null, null, null);
	}

	@Override
	public void clearAgentSessions(Long agentId) {
		chatSessionService.clearSessionsByAgentId(agentId);
	}

	@Override
	public List<DataChatMessage> getSessionMessages(Long sessionId, Long agentId) {
		requireAgentHttpSession(sessionId, agentId);
		return enrichMessageAttachmentPreviews(chatMessageService.findVisibleBySessionId(sessionId, agentId));
	}

	@Override
	public List<DataChatMessageVO> getSessionMessageViews(Long sessionId, Long agentId) {
		List<DataChatMessage> messages = getSessionMessages(sessionId, agentId);
		Map<String, DataChatTurn> turnsByRuntimeRequestId = new java.util.LinkedHashMap<>();
		for (DataChatTurn turn : chatTurnMapper.findBySessionId(sessionId)) {
			if (turn != null && StringUtils.hasText(turn.getRuntimeRequestId())) {
				turnsByRuntimeRequestId.putIfAbsent(turn.getRuntimeRequestId(), turn);
			}
		}
		return messages.stream().map(message -> toMessageView(message, turnsByRuntimeRequestId)).toList();
	}

	private DataChatMessageVO toMessageView(DataChatMessage message, Map<String, DataChatTurn> turnsByRuntimeRequestId) {
		DataChatMessageVO view = DataChatMessageVO.from(message);
		String runtimeRequestId = resolveMessageRuntimeRequestId(message);
		view.setRuntimeRequestId(runtimeRequestId);
		DataChatTurn turn = StringUtils.hasText(runtimeRequestId) ? turnsByRuntimeRequestId.get(runtimeRequestId) : null;
		if (turn != null) {
			view.setDurationMs(turn.getDurationMs());
			view.setTurnStatus(turn.getStatus());
		}
		return view;
	}

	private String resolveMessageRuntimeRequestId(DataChatMessage message) {
		if (message == null || !StringUtils.hasText(message.getMetadata())) {
			return null;
		}
		try {
			JsonNode metadata = objectMapper.readTree(message.getMetadata());
			String runtimeRequestId = metadata.path("runtimeRequestId").asText(null);
			return StringUtils.hasText(runtimeRequestId) ? runtimeRequestId : null;
		}
		catch (Exception ex) {
			log.warn("Failed to parse chat message metadata. messageId={}", message.getId(), ex);
			return null;
		}
	}

	@Override
	public SessionContextUsageVO getSessionContextUsage(Long sessionId, SessionContextUsageReq request) {
		Long agentId = request == null ? null : request.getAgentId();
		DataChatSession session = requireAgentHttpSession(sessionId, agentId);
		ModelConfigDTO modelConfig = resolveChatModelConfig(agentId,
				request == null ? null : request.getChatModelConfigId());
		Optional<SessionContextUsageVO> as2Usage = loadAs2ContextUsage(session, modelConfig);
		if (as2Usage.isPresent()) {
			return as2Usage.get();
		}
		List<DataChatMessage> messages = chatMessageService.findVisibleBySessionId(sessionId, agentId);
		long usedTokens = messages.stream().mapToLong(this::countMessageTokens).sum();
		return buildContextUsage(usedTokens, resolveContextWindowTokens(modelConfig), messages.size(), modelConfig,
				true);
	}

	@Override
	public SessionContextCompressionVO compressSessionContext(Long sessionId,
			SessionContextCompressionReq request) {
		long startNs = System.nanoTime();
		Long agentId = request == null ? null : request.getAgentId();
		DataChatSession session = requireAgentHttpSession(sessionId, agentId);
		SessionContextUsageVO displayUsage = getSessionContextUsage(sessionId, toUsageRequest(request));
		String threadId = String.valueOf(sessionId);
		if (runtimeRegistry.hasActiveRequest(threadId)) {
			return compressionResponse(SessionContextCompressionStatus.BUSY, false, 0L, 0L, 0, 0, displayUsage,
					"当前会话正在运行，请稍后再压缩");
		}
		ReentrantLock lock = contextCompressionLocks.computeIfAbsent(sessionId, key -> new ReentrantLock());
		if (!lock.tryLock()) {
			return compressionResponse(SessionContextCompressionStatus.BUSY, false, 0L, 0L, 0, 0, displayUsage,
					"当前会话正在运行，请稍后再压缩");
		}
		try {
			if (runtimeRegistry.hasActiveRequest(threadId)) {
				return compressionResponse(SessionContextCompressionStatus.BUSY, false, 0L, 0L, 0, 0, displayUsage,
						"当前会话正在运行，请稍后再压缩");
			}
			if (!contextCompressionService.isManualContextEnabled()) {
				return compressionResponse(SessionContextCompressionStatus.DISABLED, false, 0L, 0L, 0, 0, displayUsage,
						"手动上下文压缩未启用");
			}
			Memory memory = as2Memory(session);
			List<Msg> beforeMessages = memory == null ? List.of() : memory.getMessages();
			if (beforeMessages == null || beforeMessages.isEmpty()) {
				return compressionResponse(SessionContextCompressionStatus.NO_COMPRESSIBLE_CONTENT, false, 0L, 0L, 0, 0,
						displayUsage, "当前无需压缩");
			}
			Msg pinnedSystem = firstSystemMessage(beforeMessages);
			ModelConfigDTO modelConfig = resolveChatModelConfig(agentId,
					request == null ? null : request.getChatModelConfigId());
			Model model = agentScopeModelFactory.create(dynamicModelFactory.createChatModel(modelConfig),
					modelConfig == null ? null : modelConfig.getModelName(), Map.of());
			CompressionResult result = contextCompressionService.compressMemoryManually(memory, modelConfig, model);
			if (result.compressed() && result.memory() != null) {
				saveAs2Memory(session, pinFirstSystem(result.memory(), pinnedSystem));
			}
			log.info(
					"Manual context compression finished. sessionId={}, agentId={}, chatModelConfigId={}, status={}, compressed={}, beforeTokens={}, afterTokens={}, beforeMessages={}, afterMessages={}, elapsedMs={}",
					sessionId, agentId, modelConfig == null ? null : modelConfig.getId(), result.status(),
					result.compressed(), result.beforeRuntimeTokens(), result.afterRuntimeTokens(),
					result.beforeRuntimeMessageCount(), result.afterRuntimeMessageCount(), elapsedMs(startNs));
			SessionContextUsageVO runtimeUsage = buildContextUsage(result.afterRuntimeTokens(),
					resolveContextWindowTokens(modelConfig), result.afterRuntimeMessageCount(), modelConfig, false);
			return compressionResponse(result.status(), result.compressed(), result.beforeRuntimeTokens(),
					result.afterRuntimeTokens(), result.beforeRuntimeMessageCount(), result.afterRuntimeMessageCount(),
					runtimeUsage, result.message());
		}
		catch (RuntimeException ex) {
			log.warn("Manual context compression failed. sessionId={}, agentId={}, elapsedMs={}", sessionId, agentId,
					elapsedMs(startNs), ex);
			return compressionResponse(SessionContextCompressionStatus.FAILED, false, 0L, 0L, 0, 0, displayUsage,
					"上下文压缩失败");
		}
		finally {
			lock.unlock();
		}
	}

	@Override
	public Object getLatestSessionTrace(Long sessionId, Long agentId) {
		thinkingPermissionService.requireCanViewCallChain();
		requireAgentHttpSession(sessionId, agentId);
		return sessionTraceStore.getLatestTrace(String.valueOf(sessionId))
			.orElseThrow(() -> CheckedException.notFound("会话 Trace 不存在"));
	}

	@Override
	public SessionCallChainResp getSessionCallChain(Long sessionId, Long agentId, String runtimeRequestId) {
		thinkingPermissionService.requireCanViewCallChain();
		requireAgentHttpSession(sessionId, agentId);
		String sessionKey = String.valueOf(sessionId);
		SessionTraceStore.TraceView trace = sessionTraceStore.getTrace(sessionKey, runtimeRequestId).orElse(null);
		String resolvedRuntimeRequestId = StringUtils.hasText(runtimeRequestId) ? runtimeRequestId
				: trace == null ? null : trace.runtimeRequestId();
		AnswerTraceExplainView answerExplain = loadAnswerExplainView(sessionId, resolvedRuntimeRequestId, agentId)
			.orElse(null);
		if (!StringUtils.hasText(resolvedRuntimeRequestId) && answerExplain != null) {
			resolvedRuntimeRequestId = answerExplain.getRuntimeRequestId();
		}
		OrchestrationTraceResp orchestration = loadOrchestrationTrace(agentId, resolvedRuntimeRequestId).orElse(null);
		if (trace == null && orchestration == null && answerExplain == null) {
			throw CheckedException.notFound("会话调用链不存在");
		}
		return SessionCallChainResp.builder()
			.sessionId(sessionKey)
			.agentId(String.valueOf(agentId))
			.runtimeRequestId(resolvedRuntimeRequestId)
			.trace(trace)
			.orchestration(orchestration)
			.answerExplain(filterAnswerExplainForCurrentUser(answerExplain))
			.toolSteps(filterToolStepsForCurrentUser(answerExplain))
			.build();
	}

	@Override
	public Object getLatestAnswerExplain(Long sessionId, Long agentId) {
		thinkingPermissionService.requireCanViewAnswerExplain();
		requireAgentHttpSession(sessionId, agentId);
		return answerTraceExplainStore.getLatestExplain(String.valueOf(sessionId))
			.or(() -> loadLatestPersistedAnswerExplainView(sessionId, agentId))
			.map(this::filterAnswerExplainForCurrentUser)
			.map(Object.class::cast)
			.orElseThrow(() -> CheckedException.notFound("答案解释不存在"));
	}

	@Override
	public Object getAnswerExplain(Long sessionId, String runtimeRequestId, Long agentId) {
		thinkingPermissionService.requireCanViewAnswerExplain();
		requireAgentHttpSession(sessionId, agentId);
		return answerTraceExplainStore.getExplain(String.valueOf(sessionId), runtimeRequestId)
			.or(() -> loadPersistedAnswerExplainView(sessionId, runtimeRequestId, agentId))
			.map(this::filterAnswerExplainForCurrentUser)
			.map(Object.class::cast)
			.orElseThrow(() -> CheckedException.notFound("答案解释不存在"));
	}

	@Override
	public Object getAnswerExplain(AnswerExplainQueryReq request) {
		if (request == null) {
			throw CheckedException.badRequest("请求不能为空");
		}
		return getAnswerExplain(request.getSessionId(), request.getRuntimeRequestId(), request.getAgentId());
	}

	@Override
	public ChatReportGenerateResp generateReport(ChatReportGenerateReq request) {
		if (request == null) {
			throw CheckedException.badRequest("请求不能为空");
		}
		thinkingPermissionService.requireCanViewAnswerExplain();
		requireAgentHttpSession(request.getSessionId(), request.getAgentId());
		boolean professional = ChatReportGenerateReq.REPORT_LEVEL_PROFESSIONAL.equals(request.getReportLevel());
		return answerTraceExplainStore.getExplain(String.valueOf(request.getSessionId()), request.getRuntimeRequestId())
			.or(() -> loadPersistedAnswerExplainView(request.getSessionId(), request.getRuntimeRequestId(),
					request.getAgentId()))
			.map(explain -> professional ? analysisReportService.generateProfessionalReportResponse(explain)
					: analysisReportService.generateReportResponse(explain))
			.orElseThrow(() -> CheckedException.notFound("答案解释不存在"));
	}

	@Override
	public Flux<ServerSentEvent<AgentResponse>> streamGenerateReport(ChatReportGenerateReq request) {
		if (request == null) {
			throw CheckedException.badRequest("请求不能为空");
		}
		thinkingPermissionService.requireCanViewAnswerExplain();
		requireAgentHttpSession(request.getSessionId(), request.getAgentId());
		AnswerTraceExplainView explain = answerTraceExplainStore
			.getExplain(String.valueOf(request.getSessionId()), request.getRuntimeRequestId())
			.or(() -> loadPersistedAnswerExplainView(request.getSessionId(), request.getRuntimeRequestId(),
					request.getAgentId()))
			.orElseThrow(() -> CheckedException.notFound("答案解释不存在"));
		if (ChatReportGenerateReq.REPORT_LEVEL_PROFESSIONAL.equals(request.getReportLevel())) {
			return streamProfessionalReport(request, explain);
		}
		return analysisReportService.generateReportFlux(explain)
			.map(chunk -> ServerSentEvent.builder(reportChunk(request, chunk)).event(STREAM_EVENT_MESSAGE).build())
			.concatWithValues(ServerSentEvent.builder(AgentResponse.complete(String.valueOf(request.getAgentId()),
					String.valueOf(request.getSessionId()))).event(STREAM_EVENT_COMPLETE).build());
	}

	private Flux<ServerSentEvent<AgentResponse>> streamProfessionalReport(ChatReportGenerateReq request,
			AnswerTraceExplainView explain) {
		ChatReportGenerateResp response = analysisReportService.generateProfessionalReportResponse(explain);
		AgentResponse chunk = AgentResponse.builder()
			.agentId(String.valueOf(request.getAgentId()))
			.threadId(String.valueOf(request.getSessionId()))
			.nodeName(REPORT_NODE_NAME)
			.textType(TextType.MARK_DOWN)
			.text(response.getContent())
			.metadata(Map.of("reportLevel", StringUtils.hasText(response.getReportLevel()) ? response.getReportLevel()
					: ChatReportGenerateReq.REPORT_LEVEL_STANDARD,
					"degraded", Boolean.TRUE.equals(response.getDegraded())))
			.build();
		return Flux.just(ServerSentEvent.builder(chunk).event(STREAM_EVENT_MESSAGE).build())
			.concatWithValues(ServerSentEvent.builder(AgentResponse.complete(String.valueOf(request.getAgentId()),
					String.valueOf(request.getSessionId()))).event(STREAM_EVENT_COMPLETE).build());
	}

	private AgentResponse reportChunk(ChatReportGenerateReq request, String chunk) {
		return AgentResponse.builder()
			.agentId(String.valueOf(request.getAgentId()))
			.threadId(String.valueOf(request.getSessionId()))
			.nodeName(REPORT_NODE_NAME)
			.textType(TextType.MARK_DOWN)
			.text(chunk)
			.build();
	}

	@Override
	public DataChatMessage saveMessage(Long sessionId, ChatMessageReq request) {
		try {
			if (request == null) {
				throw CheckedException.badRequest("消息不能为空");
			}
			requireAgentHttpSession(sessionId, request.getAgentId());
			String role = requireCompatUserRole(request.getRole());
			String clientRequestId = trimToNull(request.getClientRequestId());
			if (clientRequestId != null) {
				DataChatMessage existing = findExistingUserMessageByClientRequestId(sessionId, clientRequestId);
				if (existing != null) {
					return existing;
				}
			}
			DataChatMessage message = DataChatMessage.builder()
				.sessionId(sessionId)
				.role(role)
				.content(request.getContent())
				.messageType(request.getMessageType())
				.metadata(attachClientRequestId(request.getMetadata(), clientRequestId))
				.build();
			DataChatMessage savedMessage = chatMessageService.saveMessage(message, request.getAgentId());
			chatSessionService.updateSessionTime(sessionId, request.getAgentId());
			if (shouldGenerateTitle(request, savedMessage)) {
				sessionTitleService.scheduleTitleGeneration(sessionId, message.getContent());
			}
			return savedMessage;
		}
		catch (Exception e) {
			log.error("Save message error for session {}: {}", sessionId, e.getMessage(), e);
			if (e instanceof CheckedException checkedException) {
				throw checkedException;
			}
			throw CheckedException.fail("保存消息失败");
		}
	}

	@Override
	public void pinSession(Long sessionId, SessionPinReq request) {
		if (request == null || request.getIsPinned() == null) {
			throw CheckedException.badRequest("置顶状态不能为空");
		}
		requireAgentHttpSession(sessionId, request.getAgentId());
		chatSessionService.pinSession(sessionId, request.getIsPinned(), request.getAgentId());
	}

	@Override
	public void renameSession(Long sessionId, SessionRenameReq request) {
		if (request == null || !StringUtils.hasText(request.getTitle())) {
			throw CheckedException.badRequest("会话标题不能为空");
		}
		requireAgentHttpSession(sessionId, request.getAgentId());
		chatSessionService.renameSession(sessionId, request.getTitle().trim(), request.getAgentId());
	}

	@Override
	public void deleteSession(Long sessionId, Long agentId) {
		requireAgentHttpSession(sessionId, agentId);
		chatSessionService.deleteSession(sessionId, agentId);
	}

	@Override
	public byte[] downloadHtmlReport(Long sessionId, ChatReportDownloadReq request, HttpServletResponse response) {
		try {
			if (request == null || !StringUtils.hasText(request.getContent())) {
				throw CheckedException.badRequest("报告内容不能为空");
			}
			requireAgentHttpSession(sessionId, request.getAgentId());
			String htmlContent = reportTemplateUtil.getHeader() + request.getContent() + reportTemplateUtil.getFooter();
			String timestamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
				.withZone(ZoneId.systemDefault())
				.format(Instant.now());
			String filename = "report_" + timestamp + ".html";
			response.setContentType(new MediaType("text", "html", StandardCharsets.UTF_8).toString());
			response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
			return htmlContent.getBytes(StandardCharsets.UTF_8);
		}
		catch (Exception e) {
			log.error("Download HTML report error for session {}: {}", sessionId, e.getMessage(), e);
			if (e instanceof CheckedException checkedException) {
				throw checkedException;
			}
			throw CheckedException.fail("下载 HTML 报告失败");
		}
	}

	@Override
	public byte[] downloadHtmlReport(ChatReportDownloadReq request, HttpServletResponse response) {
		if (request == null || request.getSessionId() == null) {
			throw CheckedException.badRequest("sessionId不能为空");
		}
		return downloadHtmlReport(request.getSessionId(), request, response);
	}

	private DataChatSession requireAgentHttpSession(Long sessionId, Long agentId) {
		DataChatSession session = chatSessionService.requireSessionForAgent(sessionId, agentId);
		if (isEmployeeChannel(session)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found");
		}
		return session;
	}

	private void rejectEmployeeChannelQuery(String channelType) {
		if (ChatSessionChannelDict.EMPLOYEE.getValue().equalsIgnoreCase(channelType)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found");
		}
	}

	private Long requireCurrentUserId() {
		String userId;
		try {
			userId = authenticationContext.userId();
		}
		catch (Exception ex) {
			throw CheckedException.forbidden("无法解析当前登录用户，禁止查看会话");
		}
		if (!StringUtils.hasText(userId)) {
			throw CheckedException.forbidden("当前登录用户为空，禁止查看会话");
		}
		try {
			return Long.valueOf(userId.trim());
		}
		catch (NumberFormatException ex) {
			throw CheckedException.forbidden("当前登录用户ID不合法: " + userId);
		}
	}

	private boolean isEmployeeChannel(DataChatSession session) {
		return session != null
				&& ChatSessionChannelDict.EMPLOYEE.getValue().equalsIgnoreCase(session.getChannelType());
	}

	private List<DataChatMessage> enrichMessageAttachmentPreviews(List<DataChatMessage> messages) {
		if (messages == null || messages.isEmpty()) {
			return messages;
		}
		Set<String> storageKeys = new LinkedHashSet<>();
		for (DataChatMessage message : messages) {
			collectAttachmentStorageKeys(message, storageKeys);
		}
		Map<String, FilePreviewResp> previewMap = Map.of();
		if (!storageKeys.isEmpty()) {
			previewMap = localFileService.findDisplayPreviews(storageKeys);
			if (previewMap == null) {
				previewMap = Map.of();
			}
		}
		for (DataChatMessage message : messages) {
			enrichMessageAttachmentPreviews(message, previewMap);
		}
		return messages;
	}

	private void collectAttachmentStorageKeys(DataChatMessage message, Set<String> storageKeys) {
		if (message == null || !StringUtils.hasText(message.getMetadata())) {
			return;
		}
		try {
			JsonNode attachments = objectMapper.readTree(message.getMetadata()).path("attachments");
			if (!attachments.isArray()) {
				return;
			}
			for (JsonNode attachment : attachments) {
				String storageKey = attachment.path("storageKey").asText(null);
				if (StringUtils.hasText(storageKey)) {
					storageKeys.add(storageKey.trim());
				}
			}
		}
		catch (Exception ex) {
			log.warn("Failed to parse message attachments. messageId={}", message.getId(), ex);
		}
	}

	private void enrichMessageAttachmentPreviews(DataChatMessage message, Map<String, FilePreviewResp> previewMap) {
		if (message == null || !StringUtils.hasText(message.getMetadata())) {
			return;
		}
		try {
			JsonNode metadata = objectMapper.readTree(message.getMetadata());
			JsonNode attachments = metadata.path("attachments");
			if (!metadata.isObject() || !attachments.isArray()) {
				return;
			}
			boolean changed = false;
			for (JsonNode attachment : attachments) {
				if (!attachment.isObject()) {
					continue;
				}
				ObjectNode attachmentNode = (ObjectNode) attachment;
				attachmentNode.remove(List.of("url", "previewUrl", "data"));
				changed = true;
				String storageKey = attachment.path("storageKey").asText(null);
				FilePreviewResp preview = StringUtils.hasText(storageKey) ? previewMap.get(storageKey.trim()) : null;
				if (preview != null && StringUtils.hasText(preview.getPreviewUrl())) {
					attachmentNode.put("url", preview.getPreviewUrl());
					attachmentNode.put("previewUrl", preview.getPreviewUrl());
				}
				if (preview != null && !StringUtils.hasText(attachment.path("fileName").asText(null))
						&& StringUtils.hasText(preview.getOriginalName())) {
					attachmentNode.put("fileName", preview.getOriginalName());
				}
			}
			if (changed) {
				message.setMetadata(objectMapper.writeValueAsString(metadata));
			}
		}
		catch (Exception ex) {
			log.warn("Failed to enrich message attachment previews. messageId={}", message.getId(), ex);
		}
	}

	private Optional<JsonNode> loadLatestPersistedAnswerExplain(Long sessionId, Long agentId) {
		List<DataChatMessage> snapshots = chatMessageService.findBySessionIdAndMessageType(sessionId,
				ANSWER_EXPLAIN_MESSAGE_TYPE, agentId);
		JsonNode latestExplainNode = null;
		long latestUpdatedAt = Long.MIN_VALUE;
		for (DataChatMessage snapshot : snapshots) {
			if (snapshot == null || !StringUtils.hasText(snapshot.getContent())) {
				continue;
			}
			try {
				JsonNode explainNode = objectMapper.readTree(snapshot.getContent());
				long updatedAt = explainNode.path("updatedAt").asLong(Long.MIN_VALUE);
				if (latestExplainNode == null || updatedAt >= latestUpdatedAt) {
					latestExplainNode = explainNode;
					latestUpdatedAt = updatedAt;
				}
			}
			catch (Exception ex) {
				log.warn("Failed to parse persisted answer explain snapshot. sessionId={}, messageId={}", sessionId,
						snapshot.getId(), ex);
			}
		}
		return Optional.ofNullable(latestExplainNode);
	}

	private Optional<JsonNode> loadPersistedAnswerExplain(Long sessionId, String runtimeRequestId, Long agentId) {
		if (sessionId == null || !StringUtils.hasText(runtimeRequestId)) {
			return Optional.empty();
		}
		List<DataChatMessage> snapshots = chatMessageService.findBySessionIdAndMessageType(sessionId,
				ANSWER_EXPLAIN_MESSAGE_TYPE, agentId);
		for (DataChatMessage snapshot : snapshots) {
			if (snapshot == null || !StringUtils.hasText(snapshot.getContent())) {
				continue;
			}
			try {
				JsonNode explainNode = objectMapper.readTree(snapshot.getContent());
				if (runtimeRequestId.equals(explainNode.path("runtimeRequestId").asText())) {
					return Optional.of(explainNode);
				}
			}
			catch (Exception ex) {
				log.warn("Failed to parse persisted answer explain snapshot. sessionId={}, messageId={}", sessionId,
						snapshot.getId(), ex);
			}
		}
		return Optional.empty();
	}

	private Optional<AnswerTraceExplainView> loadPersistedAnswerExplainView(Long sessionId, String runtimeRequestId,
			Long agentId) {
		return loadPersistedAnswerExplain(sessionId, runtimeRequestId, agentId).flatMap(explainNode -> {
			try {
				return Optional.of(objectMapper.treeToValue(explainNode, AnswerTraceExplainView.class));
			}
			catch (Exception ex) {
				log.warn("Failed to convert persisted answer explain snapshot. sessionId={}, runtimeRequestId={}",
						sessionId, runtimeRequestId, ex);
				return Optional.empty();
			}
		});
	}

	private Optional<AnswerTraceExplainView> loadAnswerExplainView(Long sessionId, String runtimeRequestId,
			Long agentId) {
		String sessionKey = String.valueOf(sessionId);
		if (StringUtils.hasText(runtimeRequestId)) {
			return answerTraceExplainStore.getExplain(sessionKey, runtimeRequestId)
				.or(() -> loadPersistedAnswerExplainView(sessionId, runtimeRequestId, agentId));
		}
		return answerTraceExplainStore.getLatestExplain(sessionKey)
			.or(() -> loadLatestPersistedAnswerExplainView(sessionId, agentId));
	}

	private Optional<AnswerTraceExplainView> loadLatestPersistedAnswerExplainView(Long sessionId, Long agentId) {
		return loadLatestPersistedAnswerExplain(sessionId, agentId).flatMap(explainNode -> {
			try {
				return Optional.of(objectMapper.treeToValue(explainNode, AnswerTraceExplainView.class));
			}
			catch (Exception ex) {
				log.warn("Failed to convert latest persisted answer explain snapshot. sessionId={}", sessionId, ex);
				return Optional.empty();
			}
		});
	}

	private Optional<OrchestrationTraceResp> loadOrchestrationTrace(Long agentId, String runtimeRequestId) {
		if (agentId == null || !StringUtils.hasText(runtimeRequestId)) {
			return Optional.empty();
		}
		AgentOrchestrationRun run = orchestrationRunMapper.findByRuntimeRequestIdAndAgentId(runtimeRequestId, agentId);
		if (run == null) {
			return Optional.empty();
		}
		return Optional.of(OrchestrationTraceResp.builder()
			.run(run)
			.steps(orchestrationStepMapper.findByRunId(run.getId()))
			.build());
	}

	private AnswerTraceExplainView filterAnswerExplainForCurrentUser(AnswerTraceExplainView explain) {
		if (explain == null || !thinkingPermissionService.canViewAnswerExplain()) {
			return null;
		}
		boolean canViewThinking = thinkingPermissionService.canViewThinking();
		boolean canViewAnswerSource = thinkingPermissionService.canViewAnswerSource();
		boolean canViewCallChain = thinkingPermissionService.canViewCallChain();
		return AnswerTraceExplainView.builder()
			.sessionId(explain.getSessionId())
			.runtimeRequestId(explain.getRuntimeRequestId())
			.agentId(explain.getAgentId())
			.question(explain.getQuestion())
			.answer(explain.getAnswer())
			.datasource(canViewAnswerSource ? explain.getDatasource() : null)
			.sql(canViewAnswerSource ? explain.getSql() : null)
			.decisionReason(canViewAnswerSource ? explain.getDecisionReason() : null)
			.resultScope(canViewAnswerSource ? explain.getResultScope() : null)
			.usedTables(canViewAnswerSource ? safeList(explain.getUsedTables()) : List.of())
			.usedColumns(canViewAnswerSource ? safeList(explain.getUsedColumns()) : List.of())
			.relationEvidence(canViewAnswerSource ? safeList(explain.getRelationEvidence()) : List.of())
			.toolDecisionReasons(canViewAnswerSource ? safeList(explain.getToolDecisionReasons()) : List.of())
			.resultScopeDetails(canViewAnswerSource ? safeList(explain.getResultScopeDetails()) : List.of())
			.semanticHits(canViewAnswerSource ? safeList(explain.getSemanticHits()) : List.of())
			.knowledgeHits(canViewAnswerSource ? safeList(explain.getKnowledgeHits()) : List.of())
			.toolSteps(canViewCallChain ? filterToolStepsForCurrentUser(explain) : List.of())
			.reportDataSnapshots(canViewAnswerSource ? safeList(explain.getReportDataSnapshots()) : List.of())
			.clarify(canViewThinking && explain.getClarify() != null ? explain.getClarify() : Map.of())
			.warnings(canViewThinking ? safeList(explain.getWarnings()) : List.of())
			.updatedAt(explain.getUpdatedAt())
			.build();
	}

	private List<ToolStepView> filterToolStepsForCurrentUser(AnswerTraceExplainView explain) {
		if (explain == null || explain.getToolSteps() == null || !thinkingPermissionService.canViewCallChain()) {
			return List.of();
		}
		boolean canViewAnswerSource = thinkingPermissionService.canViewAnswerSource();
		return explain.getToolSteps().stream().map(step -> filterToolStep(step, canViewAnswerSource)).toList();
	}

	private ToolStepView filterToolStep(ToolStepView step, boolean canViewAnswerSource) {
		if (step == null || canViewAnswerSource) {
			return step;
		}
		return ToolStepView.builder()
			.sequenceNo(step.getSequenceNo())
			.stepType(step.getStepType())
			.toolName(step.getToolName())
			.title(step.getTitle())
			.status(step.getStatus())
			.startEpochMs(step.getStartEpochMs())
			.endEpochMs(step.getEndEpochMs())
			.durationMs(step.getDurationMs())
			.errorCode(step.getErrorCode())
			.timestampEpochMs(step.getTimestampEpochMs())
			.build();
	}

	private <T> List<T> safeList(List<T> values) {
		return values == null ? List.of() : values;
	}

	private SessionContextUsageReq toUsageRequest(SessionContextCompressionReq request) {
		SessionContextUsageReq usageRequest = new SessionContextUsageReq();
		if (request != null) {
			usageRequest.setAgentId(request.getAgentId());
			usageRequest.setChatModelConfigId(request.getChatModelConfigId());
		}
		return usageRequest;
	}

	private Optional<SessionContextUsageVO> loadAs2ContextUsage(DataChatSession session, ModelConfigDTO modelConfig) {
		try {
			Memory memory = as2Memory(session);
			List<Msg> messages = memory == null ? List.of() : memory.getMessages();
			if (messages == null || messages.isEmpty()) {
				return Optional.empty();
			}
			long usedTokens = contextCompressionService.countTokens(messages);
			return Optional.of(buildContextUsage(usedTokens, resolveContextWindowTokens(modelConfig), messages.size(),
					modelConfig, false));
		}
		catch (RuntimeException ex) {
			log.warn("Failed to load as2 context usage. sessionId={}", session == null ? null : session.getId(), ex);
			return Optional.empty();
		}
	}

	/**
	 * Load as2: short memory for this chat session. Missing store / unbindable tenant-user /
	 * empty payload returns empty memory; never bind {@code _}.
	 */
	Memory as2Memory(DataChatSession session) {
		InMemoryMemory empty = new InMemoryMemory();
		V2AgentStateStore store = v2AgentStateStoreProvider == null ? null
				: v2AgentStateStoreProvider.getIfAvailable();
		V2RuntimeSnapshot snapshot = as2Snapshot(session);
		if (store == null || snapshot == null) {
			return empty;
		}
		try {
			AgentStateStore bound = store.bind(snapshot);
			if (bound == null) {
				return empty;
			}
			InMemoryMemory memory = new InMemoryMemory();
			memory.loadFrom(bound, snapshot.userId(), snapshot.sessionId());
			return memory;
		}
		catch (RuntimeException ex) {
			log.warn("as2: load session memory skipped. sessionId={}", session.getId(), ex);
			return empty;
		}
	}

	private V2RuntimeSnapshot as2Snapshot(DataChatSession session) {
		if (session == null || session.getId() == null || session.getUserId() == null
				|| !StringUtils.hasText(session.getTenantId())) {
			return null;
		}
		String tenantId = session.getTenantId().trim();
		String userId = String.valueOf(session.getUserId()).trim();
		if (!StringUtils.hasText(tenantId) || "_".equals(tenantId) || !StringUtils.hasText(userId)
				|| "_".equals(userId)) {
			return null;
		}
		return new V2RuntimeSnapshot(tenantId, tenantId, userId, null, null, null, List.of(),
				String.valueOf(session.getId()), null);
	}

	private void saveAs2Memory(DataChatSession session, Memory memory) {
		V2AgentStateStore store = v2AgentStateStoreProvider == null ? null
				: v2AgentStateStoreProvider.getIfAvailable();
		V2RuntimeSnapshot snapshot = as2Snapshot(session);
		if (store == null || snapshot == null || memory == null) {
			throw CheckedException.fail("as2: 会话状态不可写");
		}
		toInMemoryMemory(memory).saveTo(store.bind(snapshot), snapshot.userId(), snapshot.sessionId());
	}

	private static InMemoryMemory pinFirstSystem(Memory source, Msg pinnedSystem) {
		InMemoryMemory memory = new InMemoryMemory();
		List<Msg> messages = source == null || source.getMessages() == null ? List.of() : source.getMessages();
		boolean firstMatches = !messages.isEmpty() && sameSystemBlock(messages.get(0), pinnedSystem);
		if (pinnedSystem != null && !firstMatches) {
			memory.addMessage(pinnedSystem);
		}
		for (Msg msg : messages) {
			if (msg != null) {
				memory.addMessage(msg);
			}
		}
		return memory;
	}

	private static Msg firstSystemMessage(List<Msg> messages) {
		if (messages == null || messages.isEmpty() || messages.get(0) == null) {
			return null;
		}
		return messages.get(0).getRole() == MsgRole.SYSTEM ? messages.get(0) : null;
	}

	private static boolean sameSystemBlock(Msg left, Msg right) {
		if (left == null || right == null) {
			return false;
		}
		if (left == right) {
			return true;
		}
		return left.getRole() == MsgRole.SYSTEM && right.getRole() == MsgRole.SYSTEM
				&& Objects.equals(left.getTextContent(), right.getTextContent());
	}

	private static InMemoryMemory toInMemoryMemory(Memory memory) {
		if (memory instanceof InMemoryMemory inMemory) {
			return inMemory;
		}
		InMemoryMemory copy = new InMemoryMemory();
		List<Msg> messages = memory == null || memory.getMessages() == null ? List.of() : memory.getMessages();
		for (Msg msg : messages) {
			if (msg != null) {
				copy.addMessage(msg);
			}
		}
		return copy;
	}

	private SessionContextCompressionVO compressionResponse(SessionContextCompressionStatus status, boolean compressed,
			long beforeRuntimeTokens, long afterRuntimeTokens, int beforeRuntimeMessageCount, int afterRuntimeMessageCount,
			SessionContextUsageVO displayUsage, String message) {
		return SessionContextCompressionVO.builder()
			.status(status)
			.compressed(compressed)
			.beforeRuntimeTokens(beforeRuntimeTokens)
			.afterRuntimeTokens(afterRuntimeTokens)
			.beforeRuntimeMessageCount(beforeRuntimeMessageCount)
			.afterRuntimeMessageCount(afterRuntimeMessageCount)
			.displayContextUsage(displayUsage)
			.estimated(true)
			.message(message)
			.build();
	}

	private SessionContextUsageVO buildContextUsage(long usedTokens, long limitTokens, int messageCount,
			ModelConfigDTO modelConfig, boolean estimated) {
		double usageRatio = limitTokens > 0 ? Math.min(1D, usedTokens / (double) limitTokens) : 0D;
		return SessionContextUsageVO.builder()
			.usedTokens(usedTokens)
			.limitTokens(limitTokens)
			.usageRatio(usageRatio)
			.messageCount(messageCount)
			.estimated(estimated)
			.modelName(modelConfig == null ? null : modelConfig.getModelName())
			.chatModelConfigId(modelConfig == null ? null : modelConfig.getId())
			.build();
	}

	private long elapsedMs(long startNs) {
		return (System.nanoTime() - startNs) / 1_000_000L;
	}

	private ModelConfigDTO resolveChatModelConfig(Long agentId, Long chatModelConfigId) {
		DataAgent dataAgent = agentId == null ? null : agentService.requireAgent(agentId);
		return agentModelConfigService.resolveChatModelConfig(dataAgent, chatModelConfigId);
	}

	private long resolveContextWindowTokens(ModelConfigDTO modelConfig) {
		if (modelConfig == null || modelConfig.getContextWindowTokens() == null
				|| modelConfig.getContextWindowTokens() <= 0) {
			return DEFAULT_CONTEXT_WINDOW_TOKENS;
		}
		return modelConfig.getContextWindowTokens();
	}

	private long countMessageTokens(DataChatMessage message) {
		if (message == null) {
			return 0L;
		}
		StringBuilder tokenText = new StringBuilder();
		appendTokenText(tokenText, message.getRole());
		appendTokenText(tokenText, message.getMessageType());
		appendTokenText(tokenText, message.getContent());
		if (tokenText.length() == 0) {
			return 0L;
		}
		return TOKEN_ENCODING.countTokens(tokenText.toString());
	}

	private void appendTokenText(StringBuilder builder, String value) {
		if (!StringUtils.hasText(value)) {
			return;
		}
		if (builder.length() > 0) {
			builder.append('\n');
		}
		builder.append(value);
	}

	private boolean shouldGenerateTitle(ChatMessageReq request, DataChatMessage savedMessage) {
		if (request == null || savedMessage == null) {
			return false;
		}
		if (request.isTitleNeeded()) {
			return true;
		}
		if (!COMPAT_USER_ROLE.equalsIgnoreCase(savedMessage.getRole())) {
			return false;
		}
		List<DataChatMessage> sessionMessages = chatMessageService.findBySessionId(savedMessage.getSessionId());
		return sessionMessages.size() == 1;
	}

	private String requireCompatUserRole(String role) {
		if (!StringUtils.hasText(role) || COMPAT_USER_ROLE.equalsIgnoreCase(role.trim())) {
			return COMPAT_USER_ROLE;
		}
		throw CheckedException.badRequest("客户端仅允许保存用户消息");
	}

	private DataChatMessage findExistingUserMessageByClientRequestId(Long sessionId, String clientRequestId) {
		List<DataChatMessage> messages = chatMessageService.findBySessionId(sessionId);
		if (messages == null || messages.isEmpty()) {
			return null;
		}
		for (DataChatMessage message : messages) {
			if (message == null || !COMPAT_USER_ROLE.equalsIgnoreCase(message.getRole())) {
				continue;
			}
			if (metadataHasClientRequestId(message.getMetadata(), clientRequestId)) {
				return message;
			}
		}
		return null;
	}

	private boolean metadataHasClientRequestId(String metadata, String clientRequestId) {
		if (!StringUtils.hasText(metadata) || !StringUtils.hasText(clientRequestId)) {
			return false;
		}
		try {
			JsonNode node = objectMapper.readTree(metadata);
			if (node == null || !node.isObject()) {
				return false;
			}
			JsonNode stored = node.get(CLIENT_REQUEST_ID_METADATA_KEY);
			return stored != null && !stored.isNull() && clientRequestId.equals(stored.asText());
		}
		catch (Exception ex) {
			log.warn("Failed to parse clientRequestId from chat message metadata");
			return false;
		}
	}

	private String attachClientRequestId(String metadata, String clientRequestId) {
		if (!StringUtils.hasText(clientRequestId)) {
			return metadata;
		}
		if (!StringUtils.hasText(metadata)) {
			return writeClientRequestIdMetadata(null, clientRequestId);
		}
		try {
			JsonNode node = objectMapper.readTree(metadata);
			if (node != null && node.isObject()) {
				return writeClientRequestIdMetadata((ObjectNode) node, clientRequestId);
			}
		}
		catch (Exception ex) {
			log.warn("Failed to merge clientRequestId into chat message metadata");
		}
		return writeClientRequestIdMetadata(null, clientRequestId);
	}

	private String writeClientRequestIdMetadata(ObjectNode metadata, String clientRequestId) {
		try {
			ObjectNode node = metadata == null ? objectMapper.createObjectNode() : metadata;
			node.put(CLIENT_REQUEST_ID_METADATA_KEY, clientRequestId);
			return objectMapper.writeValueAsString(node);
		}
		catch (Exception ex) {
			throw CheckedException.fail("保存消息失败");
		}
	}

	private static String trimToNull(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		return value.trim();
	}

}
