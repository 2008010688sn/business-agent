/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.entity.DataChatSession;
import com.sn68.agent.dataagent.entity.DataChatTurn;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun;
import com.sn68.agent.dataagent.evaluation.enums.AgentEvaluationErrorDict;
import com.sn68.agent.dataagent.repository.DataChatTurnMapper;
import com.sn68.agent.dataagent.service.agent.AgentInvocationService;
import com.sn68.agent.dataagent.service.chat.DataChatSessionService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.DataPermission;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * DataAgent 真实运行链路评估适配器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataAgentEvalAdapter implements AgentEvalAdapter {

	public static final String ADAPTER_CODE = "DATA_AGENT";

	public static final String SUBJECT_TYPE = "DATA_AGENT";

	private final AgentInvocationService invocationService;

	private final DataChatSessionService chatSessionService;

	private final DataChatTurnMapper turnMapper;

	private final ObjectMapper objectMapper;

	@Override
	public String adapterCode() {
		return ADAPTER_CODE;
	}

	@Override
	public boolean supports(String subjectType) {
		return SUBJECT_TYPE.equalsIgnoreCase(subjectType);
	}

	@Override
	public EvalInvocationResult invoke(EvalInvocationPrepared prepared) {
		Long agentId = parseAgentId(prepared.subject().getSubjectId());
		DataChatSession session = chatSessionService.createSession(agentId, "评估-" + prepared.evalCase().getCaseName(),
				resolveUserId(prepared.run()));
		String runtimeRequestId = "eval-" + prepared.run().getId() + "-" + prepared.evalCase().getId() + "-"
				+ UUID.randomUUID();
		AgentRequest request = AgentRequest.builder()
			.agentId(String.valueOf(agentId))
			.threadId(String.valueOf(session.getId()))
			.runtimeRequestId(runtimeRequestId)
			.rootRuntimeRequestId(runtimeRequestId)
			.query(prepared.evalCase().getUserInput())
			.requestSource("EVAL")
			// DRY_RUN 意图随 AgentRequest 显式透传：即便运行时内部切线程丢失 ThreadLocal，
			// CapabilityGateway 仍能凭该字段失败关闭地拦截写能力（方案第十四章）。
			.executionIntent(prepared.run().getExecutionIntent())
			.executionScopeKey(prepared.executionScopeKey())
			.evalSystemInstructionOverride(EvalRunHarness.overlayInstruction(prepared.run(), objectMapper))
			.isolatedMemory(true)
			.runtimeTimeout(prepared.runtimeTimeout())
			.dataPermissionSnapshot(resolveDataPermission(prepared.run()))
			.userIdSnapshot(prepared.run().getUserIdSnapshot())
			.tenantIdSnapshot(prepared.run().getTenantId())
			.tenantCodeSnapshot(prepared.run().getTenantCode())
			.clientIdSnapshot(prepared.run().getClientIdSnapshot())
			.teamIdsSnapshot(resolveTeamIds(prepared.run()))
			.build();
		long start = System.nanoTime();
		try {
			String answer = invocationService.invoke(request);
			DataChatTurn turn = turnMapper.findBySessionIdAndRuntimeRequestId(session.getId(), runtimeRequestId);
			return new EvalInvocationResult(true, answer, session.getId(), request.getThreadId(), runtimeRequestId,
					resolveDurationMs(start, turn), turn == null ? null : turn.getPromptTokens(),
					turn == null ? null : turn.getCompletionTokens(), turn == null ? null : turn.getTotalTokens(),
					turn == null ? 0 : turn.getToolCount(), turn == null ? 0 : turn.getToolFailCount(), null);
		}
		catch (RuntimeException ex) {
			log.warn("Agent evaluation case invocation failed. runId={}, sessionId={}, runtimeRequestId={}",
					prepared.run() == null ? null : prepared.run().getId(), session.getId(), runtimeRequestId, ex);
			DataChatTurn turn = turnMapper.findBySessionIdAndRuntimeRequestId(session.getId(), runtimeRequestId);
			return new EvalInvocationResult(false, null, session.getId(), request.getThreadId(), runtimeRequestId,
					resolveDurationMs(start, turn), turn == null ? null : turn.getPromptTokens(),
					turn == null ? null : turn.getCompletionTokens(), turn == null ? null : turn.getTotalTokens(),
					turn == null ? 0 : turn.getToolCount(), turn == null ? 0 : turn.getToolFailCount(),
					ex.getMessage());
		}
	}

	private Long parseAgentId(String subjectId) {
		if (!StringUtils.hasText(subjectId)) {
			throw badRequest(AgentEvaluationErrorDict.REQUEST_INVALID);
		}
		try {
			return Long.valueOf(subjectId.trim());
		}
		catch (NumberFormatException ex) {
			throw badRequest(AgentEvaluationErrorDict.REQUEST_INVALID);
		}
	}

	private Long resolveDurationMs(long start, DataChatTurn turn) {
		if (turn != null && turn.getDurationMs() != null) {
			return turn.getDurationMs();
		}
		return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
	}

	private Long resolveUserId(DataAgentEvalRun run) {
		String userId = run.getUserIdSnapshot();
		if (!StringUtils.hasText(userId)) {
			return null;
		}
		try {
			return Long.valueOf(userId.trim());
		}
		catch (NumberFormatException ex) {
			log.warn("Evaluation run user snapshot is not numeric, treating it as absent. runId={}", run.getId(), ex);
			return null;
		}
	}

	private DataPermission resolveDataPermission(DataAgentEvalRun run) {
		if (StringUtils.hasText(run.getDataPermissionSnapshotJson())) {
			try {
				return objectMapper.readValue(run.getDataPermissionSnapshotJson(), DataPermission.class);
			}
			catch (Exception ex) {
				// The eval run would otherwise execute under a different data scope than the one it recorded.
				log.warn("Failed to restore the evaluation data permission snapshot, running without it. runId={}",
						run.getId(), ex);
				return null;
			}
		}
		return null;
	}

	private List<String> resolveTeamIds(DataAgentEvalRun run) {
		if (StringUtils.hasText(run.getTeamIdsJson())) {
			try {
				return objectMapper.readValue(run.getTeamIdsJson(), new TypeReference<List<String>>() {
				});
			}
			catch (Exception ex) {
				log.warn("Failed to restore the evaluation team snapshot, running with no teams. runId={}", run.getId(),
						ex);
				return List.of();
			}
		}
		return List.of();
	}

	private CheckedException badRequest(AgentEvaluationErrorDict dict) {
		return CheckedException.badRequest(dict.getValue(), dict.getLabel());
	}

}
