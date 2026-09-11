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

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.constant.AgentSessionConstant;
import com.sn68.agent.dataagent.dto.agent.OrchestrationTraceResp;
import com.sn68.agent.dataagent.dto.chat.DataChatTurnDetailResp;
import com.sn68.agent.dataagent.dto.chat.DataChatTurnPageQueryReq;
import com.sn68.agent.dataagent.dto.chat.DataChatUserSummaryResp;
import com.sn68.agent.dataagent.dto.chat.DataChatUserSummaryQueryReq;
import com.sn68.agent.dataagent.dto.chat.SessionCallChainResp;
import com.sn68.agent.dataagent.entity.AgentOrchestrationRun;
import com.sn68.agent.dataagent.entity.DataChatMessage;
import com.sn68.agent.dataagent.entity.DataChatSession;
import com.sn68.agent.dataagent.entity.DataChatTurn;
import com.sn68.agent.dataagent.enums.AgentRequestSourceDict;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.AnswerTraceExplainView;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.ToolStepView;
import com.sn68.agent.dataagent.observability.SessionTraceStore;
import com.sn68.agent.dataagent.repository.AgentOrchestrationRunMapper;
import com.sn68.agent.dataagent.repository.AgentOrchestrationStepMapper;
import com.sn68.agent.dataagent.repository.DataChatMessageMapper;
import com.sn68.agent.dataagent.repository.DataChatSessionMapper;
import com.sn68.agent.dataagent.repository.DataChatTurnMapper;
import com.sn68.agent.dataagent.service.permission.DataAgentThinkingPermissionService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 数据问答轮次管理实现：维护单轮问答的持久化、回放与状态更新。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataChatTurnServiceImpl implements DataChatTurnService {

	private static final String INVALID_ROUTE_REASON_CODE = "INVALID_ROUTE_REASON_CODE";

	private static final Pattern ROUTE_REASON_CODE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

	private final DataChatTurnMapper turnMapper;

	private final DataChatSessionMapper sessionMapper;

	private final DataChatMessageMapper messageMapper;

	private final AgentOrchestrationRunMapper orchestrationRunMapper;

	private final AgentOrchestrationStepMapper orchestrationStepMapper;

	private final AnswerTraceExplainStore answerTraceExplainStore;

	private final SessionTraceStore sessionTraceStore;

	private final DataAgentThinkingPermissionService thinkingPermissionService;

	private final ObjectMapper objectMapper;

	@Override
	public void startTurn(AgentRequest request) {
		if (request == null || !StringUtils.hasText(request.getThreadId())
				|| !StringUtils.hasText(request.getRuntimeRequestId())) {
			return;
		}
		Long sessionId = parseLongOrNull(request.getThreadId());
		Long agentId = parseLongOrNull(request.getAgentId());
		if (sessionId == null || agentId == null) {
			return;
		}
		DataChatTurn existing = turnMapper.findBySessionIdAndRuntimeRequestId(sessionId, request.getRuntimeRequestId());
		if (existing != null) {
			return;
		}
		DataChatSession session = sessionMapper.selectBySessionId(sessionId);
		Instant now = Instant.now();
		DataChatTurn turn = DataChatTurn.builder()
			.sessionId(sessionId)
			.threadId(request.getThreadId())
			.runtimeRequestId(request.getRuntimeRequestId())
			.requestSource(resolveRequestSource(request))
			.provider(trimToNull(request.getProvider()))
			.connectorCode(trimToNull(request.getConnectorCode()))
			.externalConversationId(trimToNull(request.getExternalConversationId()))
			.externalUserId(trimToNull(request.getExternalUserId()))
			.agentId(agentId)
			.userId(resolveUserId(request, session))
			.question(request.getQuery())
			.status(STATUS_RUNNING)
			.startedAt(now)
			.toolCount(0)
			.toolFailCount(0)
			.hasDatasource(false)
			.hasSql(false)
			.build();
		if (session != null) {
			turn.setCreateBy(session.getCreateBy());
			turn.setCreateName(session.getCreateName());
		}
		turnMapper.insert(turn);
	}

	@Override
	public void completeTurn(AgentRequest request, String answer, long durationMs, int toolCount, int toolFailCount,
			DataChatMessage answerExplainMessage) {
		if (request == null) {
			return;
		}
		Long sessionId = parseLongOrNull(request.getThreadId());
		if (sessionId == null || !StringUtils.hasText(request.getRuntimeRequestId())) {
			return;
		}
		ensureTurnExists(request);
		DataChatTurn turn = turnMapper.findBySessionIdAndRuntimeRequestId(sessionId, request.getRuntimeRequestId());
		if (turn == null) {
			return;
		}
		Instant finishedAt = Instant.now();
		JsonNode explain = readExplain(answerExplainMessage).orElse(null);
		AgentOrchestrationRun run = findOrchestrationRun(request);
		turn.setAnswer(answer);
		turn.setStatus(STATUS_SUCCESS);
		turn.setErrorMessage(null);
		turn.setFinishedAt(finishedAt);
		turn.setDurationMs(resolveDurationMs(turn.getStartedAt(), finishedAt, durationMs));
		turn.setToolCount(resolveToolCount(toolCount, explain));
		turn.setToolFailCount(toolFailCount);
		turn.setHasDatasource(hasText(explain, "datasource"));
		turn.setHasSql(hasText(explain, "sql"));
		turn.setAnswerExplainMessageId(answerExplainMessage == null ? null : answerExplainMessage.getId());
		turn.setOrchestrationRunId(run == null ? null : run.getId());
		applyRouteDiagnostics(turn, request);
		turnMapper.updateById(turn);
	}

	@Override
	public void waitForClarification(AgentRequest request, String answer, DataChatMessage answerExplainMessage) {
		waitForInteraction(request, answer, answerExplainMessage, STATUS_WAITING_CLARIFICATION);
	}

	private void waitForInteraction(AgentRequest request, String answer, DataChatMessage answerExplainMessage,
			String status) {
		if (request == null) {
			return;
		}
		Long sessionId = parseLongOrNull(request.getThreadId());
		if (sessionId == null || !StringUtils.hasText(request.getRuntimeRequestId())) {
			return;
		}
		ensureTurnExists(request);
		DataChatTurn turn = turnMapper.findBySessionIdAndRuntimeRequestId(sessionId, request.getRuntimeRequestId());
		if (turn == null) {
			return;
		}
		Instant now = Instant.now();
		turn.setAnswer(answer);
		turn.setStatus(status);
		turn.setErrorMessage(null);
		turn.setFinishedAt(now);
		turn.setDurationMs(resolveDurationMs(turn.getStartedAt(), now, 0L));
		turn.setAnswerExplainMessageId(answerExplainMessage == null ? null : answerExplainMessage.getId());
		applyRouteDiagnostics(turn, request);
		turnMapper.updateById(turn);
	}

	@Override
	public void failTurn(AgentRequest request, Throwable error) {
		updateTerminalStatus(request, STATUS_FAILED, error == null ? null : error.getMessage());
	}

	@Override
	public void completeFailedTurn(AgentRequest request, String answer, Throwable error,
			DataChatMessage answerExplainMessage) {
		if (request == null) {
			return;
		}
		Long sessionId = parseLongOrNull(request.getThreadId());
		if (sessionId == null || !StringUtils.hasText(request.getRuntimeRequestId())) {
			return;
		}
		ensureTurnExists(request);
		DataChatTurn turn = turnMapper.findBySessionIdAndRuntimeRequestId(sessionId, request.getRuntimeRequestId());
		if (turn == null) {
			return;
		}
		Instant finishedAt = Instant.now();
		turn.setAnswer(answer);
		turn.setStatus(STATUS_FAILED);
		turn.setErrorMessage(error == null ? answer : error.getMessage());
		turn.setFinishedAt(finishedAt);
		turn.setDurationMs(resolveDurationMs(turn.getStartedAt(), finishedAt, 0L));
		turn.setAnswerExplainMessageId(answerExplainMessage == null ? null : answerExplainMessage.getId());
		applyRouteDiagnostics(turn, request);
		turnMapper.updateById(turn);
	}

	@Override
	public void cancelTurn(AgentRequest request) {
		updateTerminalStatus(request, STATUS_CANCELLED, null);
	}

	@Override
	public IPage<DataChatTurn> queryTurns(DataChatTurnPageQueryReq request) {
		DataChatTurnPageQueryReq pageRequest = request == null ? new DataChatTurnPageQueryReq() : request;
		return turnMapper.selectDiagnosticsPage(pageRequest.buildPage(), pageRequest);
	}

	@Override
	public DataChatTurnDetailResp getTurnDetail(Long sessionId, String runtimeRequestId) {
		if (sessionId == null || !StringUtils.hasText(runtimeRequestId)) {
			throw CheckedException.badRequest("sessionId and runtimeRequestId cannot be empty");
		}
		DataChatTurn turn = turnMapper.findBySessionIdAndRuntimeRequestId(sessionId, runtimeRequestId);
		DataChatSession session = sessionMapper.selectBySessionId(sessionId);
		if (turn == null && session == null) {
			throw CheckedException.notFound("Chat turn does not exist");
		}
		Long agentId = resolveDetailAgentId(turn, session);
		boolean canViewThinking = thinkingPermissionService.canViewThinking();
		boolean canViewAnswerSource = thinkingPermissionService.canViewAnswerSource();
		boolean canViewCallChain = thinkingPermissionService.canViewCallChain();
		boolean canViewAnswerExplain = thinkingPermissionService.canViewAnswerExplain();
		AnswerTraceExplainView answerExplain = null;
		SessionCallChainResp callChain = null;
		AnswerTraceExplainView rawAnswerExplain = (canViewAnswerExplain || canViewCallChain)
				? loadAnswerExplainForDiagnostics(sessionId, runtimeRequestId, turn) : null;
		if (canViewAnswerExplain) {
			answerExplain = filterAnswerExplainForDiagnostics(rawAnswerExplain, canViewThinking, canViewAnswerSource,
					canViewCallChain);
		}
		if (canViewCallChain) {
			callChain = buildCallChainForDiagnostics(sessionId, agentId, runtimeRequestId, turn, rawAnswerExplain,
					canViewAnswerSource);
		}
		List<DataChatMessage> thinkingMessages = canViewThinking ? findThinkingMessages(sessionId, runtimeRequestId)
				: List.of();
		boolean available = !thinkingMessages.isEmpty() || answerExplain != null || callChain != null;
		return DataChatTurnDetailResp.builder()
			.turn(turn)
			.session(session)
			.messages(messageMapper.selectVisibleBySessionId(sessionId))
			.thinkingMessages(thinkingMessages)
			.answerExplain(answerExplain)
			.callChain(callChain)
			.canViewThinking(canViewThinking)
			.canViewAnswerSource(canViewAnswerSource)
			.canViewCallChain(canViewCallChain)
			.diagnosticsAvailable(available)
			.diagnosticsMessage(available ? null : "No diagnostics snapshot is available for this turn")
			.build();
	}

	private Long resolveDetailAgentId(DataChatTurn turn, DataChatSession session) {
		if (turn != null && turn.getAgentId() != null) {
			return turn.getAgentId();
		}
		return session == null ? null : session.getAgentId();
	}

	private AnswerTraceExplainView loadAnswerExplainForDiagnostics(Long sessionId, String runtimeRequestId,
			DataChatTurn turn) {
		String sessionKey = String.valueOf(sessionId);
		return loadPersistedAnswerExplainViewByMessageId(turn)
			.or(() -> loadPersistedAnswerExplainView(sessionId, runtimeRequestId))
			.or(() -> loadNearestPersistedAnswerExplainView(sessionId, turn))
			.or(() -> answerTraceExplainStore.getExplain(sessionKey, runtimeRequestId))
			.orElse(null);
	}

	private Optional<AnswerTraceExplainView> loadPersistedAnswerExplainViewByMessageId(DataChatTurn turn) {
		if (turn == null || turn.getAnswerExplainMessageId() == null) {
			return Optional.empty();
		}
		DataChatMessage snapshot = messageMapper.selectById(turn.getAnswerExplainMessageId());
		if (snapshot == null || !AgentSessionConstant.MESSAGE_TYPE_ANSWER_EXPLAIN
			.equalsIgnoreCase(snapshot.getMessageType())) {
			return Optional.empty();
		}
		return readExplain(snapshot).flatMap(explainNode -> toAnswerExplainView(explainNode, snapshot.getSessionId(),
				turn.getRuntimeRequestId(), snapshot.getId()));
	}

	private Optional<AnswerTraceExplainView> loadPersistedAnswerExplainView(Long sessionId, String runtimeRequestId) {
		if (sessionId == null || !StringUtils.hasText(runtimeRequestId)) {
			return Optional.empty();
		}
		List<DataChatMessage> snapshots = messageMapper.selectBySessionIdAndMessageType(sessionId,
				AgentSessionConstant.MESSAGE_TYPE_ANSWER_EXPLAIN);
		for (DataChatMessage snapshot : snapshots) {
			Optional<JsonNode> explainNode = readExplain(snapshot);
			if (explainNode.isEmpty() || !answerExplainMatchesRequest(snapshot, explainNode.get(), runtimeRequestId)) {
				continue;
			}
			return toAnswerExplainView(explainNode.get(), sessionId, runtimeRequestId, snapshot.getId());
		}
		return Optional.empty();
	}

	private Optional<AnswerTraceExplainView> loadNearestPersistedAnswerExplainView(Long sessionId, DataChatTurn turn) {
		if (sessionId == null || turn == null) {
			return Optional.empty();
		}
		List<DataChatMessage> snapshots = messageMapper.selectBySessionIdAndMessageType(sessionId,
				AgentSessionConstant.MESSAGE_TYPE_ANSWER_EXPLAIN);
		DataChatMessage nearest = snapshots.stream()
			.filter(snapshot -> snapshot != null && snapshot.getCreateTime() != null)
			.filter(snapshot -> snapshotWithinTurnWindow(snapshot, turn))
			.min(Comparator.comparing(snapshot -> distanceFromTurn(snapshot, turn)))
			.orElse(null);
		return nearest == null ? Optional.empty()
				: readExplain(nearest).flatMap(explainNode -> toAnswerExplainView(explainNode, sessionId,
						turn.getRuntimeRequestId(), nearest.getId()));
	}

	private boolean snapshotWithinTurnWindow(DataChatMessage snapshot, DataChatTurn turn) {
		if (snapshot.getCreateTime() == null) {
			return false;
		}
		Instant startedAt = turn.getStartedAt();
		Instant finishedAt = turn.getFinishedAt();
		if (startedAt != null && snapshot.getCreateTime().isBefore(startedAt.minus(Duration.ofMinutes(2)))) {
			return false;
		}
		if (finishedAt != null && snapshot.getCreateTime().isAfter(finishedAt.plus(Duration.ofMinutes(2)))) {
			return false;
		}
		return startedAt != null || finishedAt != null;
	}

	private Duration distanceFromTurn(DataChatMessage snapshot, DataChatTurn turn) {
		Instant reference = turn.getFinishedAt() != null ? turn.getFinishedAt() : turn.getStartedAt();
		if (reference == null || snapshot.getCreateTime() == null) {
			return Duration.ofDays(3650);
		}
		return Duration.between(reference, snapshot.getCreateTime()).abs();
	}

	private Optional<AnswerTraceExplainView> toAnswerExplainView(JsonNode explainNode, Long sessionId,
			String runtimeRequestId, Long messageId) {
		try {
			AnswerTraceExplainView view = objectMapper.treeToValue(explainNode, AnswerTraceExplainView.class);
			if (!StringUtils.hasText(view.getSessionId()) && sessionId != null) {
				view.setSessionId(String.valueOf(sessionId));
			}
			if (!StringUtils.hasText(view.getRuntimeRequestId()) && StringUtils.hasText(runtimeRequestId)) {
				view.setRuntimeRequestId(runtimeRequestId);
			}
			return Optional.of(view);
		}
		catch (Exception ex) {
			log.warn("Failed to convert diagnostics answer explain. sessionId={}, runtimeRequestId={}, messageId={}",
					sessionId, runtimeRequestId, messageId, ex);
			return Optional.empty();
		}
	}

	private boolean answerExplainMatchesRequest(DataChatMessage snapshot, JsonNode explainNode, String runtimeRequestId) {
		if (runtimeRequestId.equals(explainNode.path("runtimeRequestId").asText(null))) {
			return true;
		}
		return readMetadata(snapshot)
			.map(metadata -> runtimeRequestId.equals(metadata.path("runtimeRequestId").asText(null)))
			.orElse(false);
	}

	private SessionCallChainResp buildCallChainForDiagnostics(Long sessionId, Long agentId, String runtimeRequestId,
			DataChatTurn turn, AnswerTraceExplainView rawAnswerExplain, boolean canViewAnswerSource) {
		String sessionKey = String.valueOf(sessionId);
		SessionTraceStore.TraceView trace = sessionTraceStore.getTrace(sessionKey, runtimeRequestId).orElse(null);
		OrchestrationTraceResp orchestration = loadOrchestrationTrace(agentId, runtimeRequestId, turn).orElse(null);
		List<ToolStepView> toolSteps = filterToolStepsForDiagnostics(rawAnswerExplain, canViewAnswerSource);
		if (trace == null && orchestration == null && toolSteps.isEmpty()) {
			return null;
		}
		return SessionCallChainResp.builder()
			.sessionId(sessionKey)
			.agentId(agentId == null ? null : String.valueOf(agentId))
			.runtimeRequestId(runtimeRequestId)
			.trace(trace)
			.orchestration(orchestration)
			.answerExplain(filterAnswerExplainForDiagnostics(rawAnswerExplain, false, canViewAnswerSource, true))
			.toolSteps(toolSteps)
			.build();
	}

	private Optional<OrchestrationTraceResp> loadOrchestrationTrace(Long agentId, String runtimeRequestId,
			DataChatTurn turn) {
		AgentOrchestrationRun run = null;
		if (turn != null && turn.getOrchestrationRunId() != null) {
			run = orchestrationRunMapper.selectById(turn.getOrchestrationRunId());
		}
		if (run == null && agentId != null && StringUtils.hasText(runtimeRequestId)) {
			run = orchestrationRunMapper.findByRuntimeRequestIdAndAgentId(runtimeRequestId, agentId);
		}
		if (run == null) {
			return Optional.empty();
		}
		return Optional.of(OrchestrationTraceResp.builder()
			.run(run)
			.steps(orchestrationStepMapper.findByRunId(run.getId()))
			.build());
	}

	private AnswerTraceExplainView filterAnswerExplainForDiagnostics(AnswerTraceExplainView explain,
			boolean canViewThinking, boolean canViewAnswerSource, boolean canViewCallChain) {
		if (explain == null || (!canViewThinking && !canViewAnswerSource && !canViewCallChain)) {
			return null;
		}
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
			.toolSteps(canViewCallChain ? filterToolStepsForDiagnostics(explain, canViewAnswerSource) : List.of())
			.reportDataSnapshots(canViewAnswerSource ? safeList(explain.getReportDataSnapshots()) : List.of())
			.clarify(canViewThinking && explain.getClarify() != null ? explain.getClarify() : Map.of())
			.warnings(canViewThinking ? safeList(explain.getWarnings()) : List.of())
			.updatedAt(explain.getUpdatedAt())
			.build();
	}

	private List<ToolStepView> filterToolStepsForDiagnostics(AnswerTraceExplainView explain, boolean canViewAnswerSource) {
		if (explain == null || explain.getToolSteps() == null) {
			return List.of();
		}
		return explain.getToolSteps().stream().map(step -> filterToolStepForDiagnostics(step, canViewAnswerSource)).toList();
	}

	private ToolStepView filterToolStepForDiagnostics(ToolStepView step, boolean canViewAnswerSource) {
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

	@Override
	public List<DataChatTurn> listSessionTurns(Long sessionId) {
		if (sessionId == null) {
			throw CheckedException.badRequest("sessionId cannot be null");
		}
		return turnMapper.findBySessionId(sessionId);
	}

	@Override
	public List<DataChatUserSummaryResp> summarizeUsers(DataChatUserSummaryQueryReq request) {
		DataChatUserSummaryQueryReq query = request == null ? new DataChatUserSummaryQueryReq() : request;
		LambdaQueryWrapper<DataChatTurn> wrapper = new LambdaQueryWrapper<DataChatTurn>()
			.eq(DataChatTurn::getDeleted, false);
		if (query.getAgentId() != null) {
			wrapper.eq(DataChatTurn::getAgentId, query.getAgentId());
		}
		if (query.getStartTime() != null) {
			wrapper.ge(DataChatTurn::getStartedAt, query.getStartTime());
		}
		if (query.getEndTime() != null) {
			wrapper.le(DataChatTurn::getStartedAt, query.getEndTime());
		}
		List<DataChatTurn> turns = turnMapper.selectList(wrapper);
		Map<String, List<DataChatTurn>> grouped = new LinkedHashMap<>();
		for (DataChatTurn turn : turns) {
			String key = (turn.getUserId() == null ? "" : turn.getUserId()) + "|"
					+ (turn.getCreateBy() == null ? "" : turn.getCreateBy());
			grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(turn);
		}
		return grouped.values()
			.stream()
			.map(this::toUserSummary)
			.sorted(Comparator.comparing(DataChatUserSummaryResp::getTurnCount, Comparator.nullsLast(Long::compareTo))
				.reversed())
			.toList();
	}

	@Override
	public Instant turnStartedAt(AgentRequest request) {
		if (request == null) {
			return null;
		}
		Long sessionId = parseLongOrNull(request.getThreadId());
		if (sessionId == null || !StringUtils.hasText(request.getRuntimeRequestId())) {
			return null;
		}
		DataChatTurn turn = turnMapper.findBySessionIdAndRuntimeRequestId(sessionId, request.getRuntimeRequestId());
		return turn == null ? null : turn.getStartedAt();
	}

	private void updateTerminalStatus(AgentRequest request, String status, String errorMessage) {
		if (request == null) {
			return;
		}
		Long sessionId = parseLongOrNull(request.getThreadId());
		if (sessionId == null || !StringUtils.hasText(request.getRuntimeRequestId())) {
			return;
		}
		ensureTurnExists(request);
		DataChatTurn turn = turnMapper.findBySessionIdAndRuntimeRequestId(sessionId, request.getRuntimeRequestId());
		if (turn == null) {
			return;
		}
		Instant finishedAt = Instant.now();
		long durationMs = resolveDurationMs(turn.getStartedAt(), finishedAt, 0L);
		var route = request.getRouteResult();
		turnMapper.updateStatus(sessionId, request.getRuntimeRequestId(), status, errorMessage, finishedAt, durationMs,
				normalizeRouteReasonCode(request), route == null ? null : route.degradeMode().name());
	}

	private void ensureTurnExists(AgentRequest request) {
		Long sessionId = parseLongOrNull(request.getThreadId());
		if (sessionId == null || !StringUtils.hasText(request.getRuntimeRequestId())) {
			return;
		}
		if (turnMapper.findBySessionIdAndRuntimeRequestId(sessionId, request.getRuntimeRequestId()) == null) {
			startTurn(request);
		}
	}

	private Optional<JsonNode> readExplain(DataChatMessage message) {
		if (message == null || !StringUtils.hasText(message.getContent())) {
			return Optional.empty();
		}
		try {
			return Optional.of(objectMapper.readTree(message.getContent()));
		}
		catch (Exception ex) {
			log.warn("Failed to parse answer explain snapshot. messageId={}", message.getId(), ex);
			return Optional.empty();
		}
	}

	private List<DataChatMessage> findThinkingMessages(Long sessionId, String runtimeRequestId) {
		return messageMapper.selectThinkingBySessionId(sessionId)
			.stream()
			.filter(message -> runtimeRequestIdMatches(message, runtimeRequestId))
			.toList();
	}

	private boolean runtimeRequestIdMatches(DataChatMessage message, String runtimeRequestId) {
		if (!StringUtils.hasText(runtimeRequestId)) {
			return false;
		}
		return readMetadata(message).map(metadata -> runtimeRequestId.equals(metadata.path("runtimeRequestId").asText(null)))
			.orElse(false);
	}

	private Optional<JsonNode> readMetadata(DataChatMessage message) {
		if (message == null || !StringUtils.hasText(message.getMetadata())) {
			return Optional.empty();
		}
		try {
			return Optional.of(objectMapper.readTree(message.getMetadata()));
		}
		catch (Exception ex) {
			log.warn("Failed to parse chat message metadata. messageId={}", message.getId(), ex);
			return Optional.empty();
		}
	}

	private DataChatUserSummaryResp toUserSummary(List<DataChatTurn> turns) {
		DataChatTurn first = turns.stream().filter(Objects::nonNull).findFirst().orElse(null);
		long failed = turns.stream().filter(turn -> STATUS_FAILED.equals(turn.getStatus())).count();
		double avgDuration = turns.stream()
			.map(DataChatTurn::getDurationMs)
			.filter(Objects::nonNull)
			.mapToLong(Long::longValue)
			.average()
			.orElse(0D);
		long sessionCount = turns.stream().map(DataChatTurn::getSessionId).filter(Objects::nonNull).distinct().count();
		return DataChatUserSummaryResp.builder()
			.userId(first == null ? null : first.getUserId())
			.createBy(first == null ? null : first.getCreateBy())
			.createName(first == null ? null : first.getCreateName())
			.sessionCount(sessionCount)
			.turnCount((long) turns.size())
			.failedTurnCount(failed)
			.averageDurationMs(avgDuration)
			.build();
	}

	private AgentOrchestrationRun findOrchestrationRun(AgentRequest request) {
		Long agentId = parseLongOrNull(request.getAgentId());
		if (agentId == null || !StringUtils.hasText(request.getRuntimeRequestId())) {
			return null;
		}
		return orchestrationRunMapper.findByRuntimeRequestIdAndAgentId(request.getRuntimeRequestId(), agentId);
	}

	private void applyRouteDiagnostics(DataChatTurn turn, AgentRequest request) {
		var route = request.getRouteResult();
		var selection = route != null && route.selections().size() == 1 ? route.selections().get(0) : null;
		turn.setRouteTargetType(selection == null || selection.target() == null ? null
				: selection.target().targetType().name());
		turn.setRouteTargetId(selection == null || selection.target() == null ? null
				: selection.target().targetId());
		turn.setRouteTargetVersionId(selection == null || selection.target() == null ? null
				: selection.target().targetVersionId());
		turn.setRouteArtifactId(selection == null ? null : selection.routeArtifactId());
		turn.setRouteProfileId(selection == null ? null : selection.routeProfileId());
		turn.setRouteDecision(trimToNull(request.getRouteDecision()));
		turn.setRouteReasonCode(normalizeRouteReasonCode(request));
		turn.setRouteDegradeMode(route == null ? null : route.degradeMode().name());
		turn.setRouteSelectedCount(route == null ? 0 : route.selections().size());
		turn.setRouteDurationMs(request.getRouteDurationMs());
		turn.setKnowledgeDurationMs(request.getKnowledgeDurationMs());
		turn.setFlowDurationMs(request.getFlowDurationMs());
		turn.setReactDurationMs(request.getReactDurationMs());
	}

	private String normalizeRouteReasonCode(AgentRequest request) {
		String reasonCode = trimToNull(request.getRouteReasonCode());
		if (reasonCode == null || ROUTE_REASON_CODE_PATTERN.matcher(reasonCode).matches()) {
			return reasonCode;
		}
		log.warn(
				"Invalid route reason code; storing fallback. agentId={}, runtimeRequestId={}, length={}",
				request.getAgentId(), request.getRuntimeRequestId(), reasonCode.length());
		return INVALID_ROUTE_REASON_CODE;
	}

	private Long resolveUserId(AgentRequest request, DataChatSession session) {
		Long requestUserId = parseLongOrNull(request.getUserIdSnapshot());
		if (requestUserId != null) {
			return requestUserId;
		}
		return session == null ? null : session.getUserId();
	}

	private String resolveRequestSource(AgentRequest request) {
		String requestSource = trimToNull(request.getRequestSource());
		return requestSource == null ? AgentRequestSourceDict.WEB.getValue() : requestSource.toUpperCase();
	}

	private String trimToNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	private int resolveToolCount(int fallback, JsonNode explain) {
		if (fallback > 0) {
			return fallback;
		}
		return explain != null && explain.path("toolSteps").isArray() ? explain.path("toolSteps").size() : 0;
	}

	private long resolveDurationMs(Instant startedAt, Instant finishedAt, long fallback) {
		if (fallback > 0) {
			return fallback;
		}
		if (startedAt == null || finishedAt == null) {
			return 0L;
		}
		return Math.max(0L, Duration.between(startedAt, finishedAt).toMillis());
	}

	private boolean hasText(JsonNode node, String fieldName) {
		return node != null && StringUtils.hasText(node.path(fieldName).asText(null));
	}

	private Long parseLongOrNull(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		try {
			return Long.valueOf(value.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

}
