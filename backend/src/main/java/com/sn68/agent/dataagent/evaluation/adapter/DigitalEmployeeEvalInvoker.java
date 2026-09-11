/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.adapter;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.context.DataAgentAsyncContextBridge;
import com.sn68.agent.dataagent.context.DataAgentOutboundContext;
import com.sn68.agent.dataagent.employee.auth.EmployeeAuthTokenContext;
import com.sn68.agent.dataagent.employee.auth.EmployeeExecutionContextClient;
import com.sn68.agent.dataagent.employee.config.DigitalEmployeeProperties;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeDeployment;
import com.sn68.agent.dataagent.employee.enums.DeploymentEnvironmentDict;
import com.sn68.agent.dataagent.employee.enums.EmployeeStatusDict;
import com.sn68.agent.dataagent.employee.enums.PrincipalProvisionStatusDict;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.employee.service.EmployeeDeploymentService;
import com.sn68.agent.dataagent.entity.DataChatSession;
import com.sn68.agent.dataagent.entity.DataChatTurn;
import com.sn68.agent.dataagent.enums.ChatSessionChannelDict;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun;
import com.sn68.agent.dataagent.repository.DataChatTurnMapper;
import com.sn68.agent.dataagent.service.agent.AgentInvocationService;
import com.sn68.agent.dataagent.service.chat.DataChatSessionService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 数字员工评估真跑：只钉 SANDBOX 部署指针，没有 SANDBOX 就失败关闭，绝不回落到 PRODUCTION。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DigitalEmployeeEvalInvoker {

	static final String REQUEST_SOURCE = "EVAL";

	static final String OWNER_TYPE = "DIGITAL_EMPLOYEE";

	private final DigitalEmployeeMapper employeeMapper;

	private final EmployeeDeploymentService deploymentService;

	private final AgentInvocationService invocationService;

	private final DataChatSessionService chatSessionService;

	private final DataChatTurnMapper turnMapper;

	private final EmployeeExecutionContextClient executionContextClient;

	private final DataAgentAsyncContextBridge asyncContextBridge;

	private final DigitalEmployeeProperties properties;

	public EvalInvocationResult invoke(EvalInvocationPrepared prepared, Long employeeId, String overlayInstruction) {
		if (prepared == null || prepared.run() == null || employeeId == null) {
			return failure("评估真跑参数不完整");
		}
		DataAgentEvalRun run = prepared.run();
		String tenantId = run.getTenantId();
		DigitalEmployee employee = employeeMapper.findByIdAndTenantId(employeeId, tenantId);
		if (employee == null) {
			return failure("数字员工不存在或无权访问: " + employeeId);
		}
		DigitalEmployeeDeployment sandbox = deploymentService.findCurrent(employeeId,
				DeploymentEnvironmentDict.SANDBOX.getValue());
		if (sandbox == null || sandbox.getActiveReleaseId() == null) {
			return failure("员工无 SANDBOX 部署，拒绝回落到 PRODUCTION, employeeId=" + employeeId);
		}
		Long releaseId = sandbox.getActiveReleaseId();
		DataChatSession session = chatSessionService.createSession(employeeId,
				"评估-" + (prepared.evalCase() == null ? employeeId : prepared.evalCase().getCaseName()),
				resolveUserId(run), ChatSessionChannelDict.EVALUATION.getValue(), null, null, null,
				"eval-employee:" + employeeId);
		String runtimeRequestId = "eval-" + run.getId() + "-"
				+ (prepared.evalCase() == null ? "case" : prepared.evalCase().getId()) + "-" + UUID.randomUUID();
		boolean principalMode = isPrincipalMode(employee);
		AgentRequest request = AgentRequest.builder()
			.agentId(String.valueOf(employeeId))
			.threadId(String.valueOf(session.getId()))
			.runtimeRequestId(runtimeRequestId)
			.rootRuntimeRequestId(runtimeRequestId)
			.query(prepared.evalCase() == null ? "" : prepared.evalCase().getUserInput())
			.requestSource(REQUEST_SOURCE)
			.executionIntent(run.getExecutionIntent())
			.executionScopeKey(prepared.executionScopeKey())
			.isolatedMemory(true)
			.ownerType(OWNER_TYPE)
			.ownerId(employeeId)
			.releaseId(releaseId)
			.evalSystemInstructionOverride(overlayInstruction)
			.runtimeTimeout(prepared.runtimeTimeout())
			.userIdSnapshot(run.getUserIdSnapshot())
			.tenantIdSnapshot(tenantId)
			.tenantCodeSnapshot(run.getTenantCode())
			.pinnedSkillVersionIds(principalMode ? null : List.of())
			.build();
		long start = System.nanoTime();
		try {
			String answer = execute(principalMode, employee, tenantId, request);
			DataChatTurn turn = turnMapper.findBySessionIdAndRuntimeRequestId(session.getId(), runtimeRequestId);
			return new EvalInvocationResult(true, answer, session.getId(), request.getThreadId(), runtimeRequestId,
					durationMs(start, turn), turn == null ? null : turn.getPromptTokens(),
					turn == null ? null : turn.getCompletionTokens(), turn == null ? null : turn.getTotalTokens(),
					turn == null ? 0 : turn.getToolCount(), turn == null ? 0 : turn.getToolFailCount(), null);
		}
		catch (RuntimeException ex) {
			log.warn("Digital employee eval invoke failed. employeeId={}, releaseId={}, evalRunId={}", employeeId,
					releaseId, run.getId(), ex);
			DataChatTurn turn = turnMapper.findBySessionIdAndRuntimeRequestId(session.getId(), runtimeRequestId);
			return new EvalInvocationResult(false, null, session.getId(), request.getThreadId(), runtimeRequestId,
					durationMs(start, turn), turn == null ? null : turn.getPromptTokens(),
					turn == null ? null : turn.getCompletionTokens(), turn == null ? null : turn.getTotalTokens(),
					turn == null ? 0 : turn.getToolCount(), turn == null ? 0 : turn.getToolFailCount(),
					ex.getMessage());
		}
	}

	private String execute(boolean principalMode, DigitalEmployee employee, String tenantId, AgentRequest request) {
		if (!principalMode) {
			return invocationService.invoke(request);
		}
		EmployeeAuthTokenContext tokenContext = executionContextClient.issueContext(tenantId,
				employee.getIamPrincipalId(), employee.getEmployeeName());
		DataAgentOutboundContext.Snapshot outbound = DataAgentOutboundContext.withPrincipalToken(
				DataAgentOutboundContext.get(), tokenContext.tokenValue(), tenantId);
		DataAgentAsyncContextBridge.Snapshot snapshot = asyncContextBridge.snapshotForDelegatedToken(
				tokenContext.tokenValue(), outbound);
		return asyncContextBridge.supplyWith(snapshot, (Supplier<String>) () -> invocationService.invoke(request));
	}

	private boolean isPrincipalMode(DigitalEmployee employee) {
		return properties.getRollout().isEnabled()
				&& EmployeeStatusDict.ENABLED.getValue().equals(employee.getStatus())
				&& PrincipalProvisionStatusDict.READY.getValue().equals(employee.getPrincipalStatus())
				&& StringUtils.hasText(employee.getIamPrincipalId());
	}

	private Long resolveUserId(DataAgentEvalRun run) {
		if (run == null || !StringUtils.hasText(run.getUserIdSnapshot())) {
			return null;
		}
		try {
			return Long.valueOf(run.getUserIdSnapshot().trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private long durationMs(long start, DataChatTurn turn) {
		if (turn != null && turn.getDurationMs() != null) {
			return turn.getDurationMs();
		}
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
	}

	private EvalInvocationResult failure(String message) {
		return new EvalInvocationResult(false, null, null, null, null, null, null, null, null, 0, 0, message);
	}

}
