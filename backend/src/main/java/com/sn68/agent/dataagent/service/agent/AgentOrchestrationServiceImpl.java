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
package com.sn68.agent.dataagent.service.agent;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.constant.AgentTypeConstant;
import com.sn68.agent.dataagent.constant.OrchestrationStatus;
import com.sn68.agent.dataagent.dto.agent.CollaborateToolReq;
import com.sn68.agent.dataagent.dto.agent.CollaborateToolResult;
import com.sn68.agent.dataagent.dto.agent.OrchestrationRunDetailResp;
import com.sn68.agent.dataagent.dto.agent.OrchestrationTraceResp;
import com.sn68.agent.dataagent.entity.*;
import com.sn68.agent.dataagent.repository.AgentOrchestrationRunMapper;
import com.sn68.agent.dataagent.repository.AgentOrchestrationStepMapper;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.routing.model.DelegationMode;
import com.sn68.agent.dataagent.service.agent.orchestration.OrchestrationRuntimeSupport;
import com.sn68.agent.dataagent.service.permission.DataAgentThinkingPermissionService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Agent 编排管理实现：维护多 Agent 协作编排关系及其运行入口。
 */
@Service
@AllArgsConstructor
@Slf4j
public class AgentOrchestrationServiceImpl implements AgentOrchestrationService {

	private final DataAgentService agentService;

	private final AgentCollaboratorService collaboratorService;

	private final AgentInvocationService invocationService;

	private final AgentOrchestrationPolicyService policyService;

	private final AgentOrchestrationRunMapper runMapper;

	private final AgentOrchestrationStepMapper stepMapper;

	private final DataAgentThinkingPermissionService thinkingPermissionService;

	private final DataAgentProperties dataAgentProperties;

	/**
	 * 内部编排 API，**不是**模型可见工具——从未注册过 {@code ToolCallback}，且工具名不允许点号
	 * （{@code AgentModelToolName} 正则 {@code [A-Za-z0-9_-]{1,64}}），`dataAgent.collaborate` 这个名字
	 * 本就不可能成立；编排智能体的运行时工具集也恒为空（{@code AgentToolPolicyService:51-53}）。
	 * <p>
	 * 生产链路走的是 {@code AiAgentRuntimeServiceImpl.executeLightweightOrchestration}，由路由在 Java 侧
	 * 选定协作者并扇出。本方法有活跃单测覆盖（{@code DataAgentOrchestrationServiceImplTest}）。
	 * <p>
	 * <b>请勿当作死代码删除</b>——已被误判两次，详见 PRD D-3。
	 */
	@Override
	public CollaborateToolResult collaborate(AgentRequest orchestratorRequest, CollaborateToolReq request) {
		if (orchestratorRequest == null) {
			throw CheckedException.badRequest("orchestrator request cannot be null");
		}
		Long orchestratorAgentId = parseRequiredLong(orchestratorRequest.getAgentId(), "agentId");
		DataAgent orchestrator = agentService.requireAgent(orchestratorAgentId);
		if (!AgentTypeConstant.isOrchestrator(orchestrator.getAgentType())) {
			throw CheckedException.badRequest("Lightweight orchestration can only be started by an orchestrator Agent");
		}
		Long collaboratorAgentId = request == null ? null : request.getCollaboratorAgentId();
		AgentCollaborator collaborator = collaboratorService.requireEnabled(orchestratorAgentId, collaboratorAgentId);
		AgentOrchestrationRun run = ensureRun(orchestratorRequest);
		AgentOrchestrationStep step = createStep(run.getId(), collaborator.getCollaboratorAgentId(), request);
		boolean failFast = shouldFailFast(orchestratorAgentId);
		try {
			String answer = invocationService.invoke(buildCollaboratorRequest(orchestratorRequest, collaborator,
					request));
			step.setStatus(OrchestrationStatus.SUCCESS);
			step.setAnswer(answer);
			step.setFinishedAt(Instant.now());
			stepMapper.updateById(step);
			return CollaborateToolResult.builder()
				.collaboratorAgentId(collaborator.getCollaboratorAgentId())
				.status(OrchestrationStatus.SUCCESS)
				.answer(answer)
				.build();
		}
		catch (RuntimeException ex) {
			markStepFailedSafely(step, ex);
			if (failFast) {
				throw ex;
			}
			return CollaborateToolResult.builder()
				.collaboratorAgentId(collaborator.getCollaboratorAgentId())
				.status(OrchestrationStatus.FAILED)
				.errorMessage(ex.getMessage())
				.build();
		}
	}

	@Override
	public List<AgentOrchestrationRun> listRuns(Long agentId) {
		agentService.requireAgent(agentId);
		return runMapper.findByAgentId(agentId);
	}

	@Override
	public OrchestrationRunDetailResp getRun(Long agentId, Long runId) {
		AgentOrchestrationRun run = runMapper.findByIdAndAgentId(runId, agentId);
		if (run == null) {
			throw CheckedException.notFound("Orchestration run does not exist");
		}
		return OrchestrationRunDetailResp.builder().run(run).steps(stepMapper.findByRunId(runId)).build();
	}

	@Override
	public OrchestrationTraceResp getRunTrace(Long agentId, Long runId) {
		thinkingPermissionService.requireCanViewCallChain();
		agentService.requireAgent(agentId);
		AgentOrchestrationRun run = runMapper.findByIdAndAgentId(runId, agentId);
		return buildTrace(run);
	}

	@Override
	public OrchestrationTraceResp getRuntimeTrace(Long agentId, String runtimeRequestId) {
		thinkingPermissionService.requireCanViewCallChain();
		agentService.requireAgent(agentId);
		if (!StringUtils.hasText(runtimeRequestId)) {
			throw CheckedException.badRequest("runtimeRequestId cannot be empty");
		}
		AgentOrchestrationRun run = runMapper.findByRuntimeRequestIdAndAgentId(runtimeRequestId, agentId);
		return buildTrace(run);
	}

	private OrchestrationTraceResp buildTrace(AgentOrchestrationRun run) {
		if (run == null) {
			throw CheckedException.notFound("Orchestration trace does not exist");
		}
		return OrchestrationTraceResp.builder().run(run).steps(stepMapper.findByRunId(run.getId())).build();
	}

	private AgentRequest buildCollaboratorRequest(AgentRequest orchestratorRequest, AgentCollaborator collaborator,
			CollaborateToolReq request) {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String collaboratorAgentId = String.valueOf(collaborator.getCollaboratorAgentId());
		Duration runtimeTimeout = resolveCollaboratorTimeout(orchestratorRequest, collaborator);
		AgentRequest child = AgentRequest.builder()
			.agentId(collaboratorAgentId)
			.threadId(orchestratorRequest.getThreadId() + "-collab-" + collaboratorAgentId + "-" + suffix)
			.runtimeRequestId(orchestratorRequest.getRuntimeRequestId() + "-collab-" + collaboratorAgentId + "-" + suffix)
			.query(OrchestrationRuntimeSupport.appendFusionSummary(buildCollaboratorPrompt(request),
					orchestratorRequest))
			.responseMode("normal")
			.clarifyCheckEnabled(false)
			.humanFeedback(false)
			.dataPermissionSnapshot(orchestratorRequest.getDataPermissionSnapshot())
			.userIdSnapshot(orchestratorRequest.getUserIdSnapshot())
			.userNickNameSnapshot(orchestratorRequest.getUserNickNameSnapshot())
			.parentThreadId(orchestratorRequest.getThreadId())
			.isolatedMemory(true)
			.collaboratorChild(true)
			.collaboratorDelegationMode(DelegationMode.resolve(collaborator.getDelegationMode()).name())
			.runtimeTimeout(runtimeTimeout)
			.build();
		OrchestrationRuntimeSupport.copyCollaboratorVisibility(child, orchestratorRequest,
				request == null ? null : request.getTask());
		return child;
	}

	private Duration resolveCollaboratorTimeout(AgentRequest orchestratorRequest, AgentCollaborator collaborator) {
		DataAgent target = agentService.requireAgent(collaborator.getCollaboratorAgentId());
		Integer configuredSeconds = target.getRuntimeTimeoutSeconds();
		Duration configured = configuredSeconds != null && configuredSeconds > 0
				? Duration.ofSeconds(configuredSeconds) : dataAgentProperties.getRuntime().getTotalTimeout();
		if (orchestratorRequest.getRuntimeDeadline() == null) {
			return configured;
		}
		Duration timeout = orchestratorRequest.getRuntimeDeadline().timeoutFor(configured,
				orchestratorRequest.getRuntimeFinishBuffer());
		if (timeout.isZero() || timeout.isNegative()) {
			throw new IllegalStateException("编排者没有剩余时间启动协作者");
		}
		return timeout;
	}

	private String buildCollaboratorPrompt(CollaborateToolReq request) {
		if (request == null) {
			return "";
		}
		StringBuilder prompt = new StringBuilder();
		if (StringUtils.hasText(request.getTask())) {
			prompt.append("协作任务：").append(request.getTask()).append('\n');
		}
		if (StringUtils.hasText(request.getReason())) {
			prompt.append("调用原因：").append(request.getReason()).append('\n');
		}
		if (StringUtils.hasText(request.getExpectedOutput())) {
			prompt.append("期望输出：").append(request.getExpectedOutput()).append('\n');
		}
		return prompt.toString().trim();
	}

	private AgentOrchestrationRun ensureRun(AgentRequest request) {
		List<AgentOrchestrationRun> runs = runMapper.findByAgentId(parseRequiredLong(request.getAgentId(), "agentId"));
		for (AgentOrchestrationRun run : runs) {
			if (StringUtils.hasText(request.getRuntimeRequestId())
					&& request.getRuntimeRequestId().equals(run.getRuntimeRequestId())) {
				return run;
			}
		}
		return createRun(request);
	}

	private AgentOrchestrationRun createRun(AgentRequest request) {
		AgentOrchestrationRun run = AgentOrchestrationRun.builder()
			.agentId(parseRequiredLong(request.getAgentId(), "agentId"))
			.threadId(request.getThreadId())
			.runtimeRequestId(request.getRuntimeRequestId())
			.query(request.getQuery())
			.status(OrchestrationStatus.RUNNING)
			.startedAt(Instant.now())
			.createTime(Instant.now())
			.lastModifyTime(Instant.now())
			.deleted(false)
			.build();
		runMapper.insert(run);
		return runMapper.selectById(run.getId());
	}

	private AgentOrchestrationStep createStep(Long runId, Long collaboratorAgentId, CollaborateToolReq request) {
		AgentOrchestrationStep step = AgentOrchestrationStep.builder()
			.runId(runId)
			.stepNo(stepMapper.findByRunId(runId).size() + 1)
			.collaboratorAgentId(collaboratorAgentId)
			.task(request == null ? null : request.getTask())
			.reason(request == null ? null : request.getReason())
			.expectedOutput(request == null ? null : request.getExpectedOutput())
			.status(OrchestrationStatus.RUNNING)
			.startedAt(Instant.now())
			.createTime(Instant.now())
			.lastModifyTime(Instant.now())
			.deleted(false)
			.build();
		stepMapper.insert(step);
		return stepMapper.selectById(step.getId());
	}

	private void markRunFailedSafely(AgentOrchestrationRun run, RuntimeException ex) {
		if (run == null) {
			return;
		}
		run.setStatus(OrchestrationStatus.FAILED);
		run.setErrorMessage(ex.getMessage());
		run.setFinishedAt(Instant.now());
		try {
			runMapper.updateById(run);
		}
		catch (RuntimeException updateEx) {
			log.warn("Failed to mark orchestration run as failed. runId={}", run.getId(), updateEx);
		}
	}

	private void markStepFailedSafely(AgentOrchestrationStep step, RuntimeException ex) {
		if (step == null) {
			return;
		}
		step.setStatus(OrchestrationStatus.FAILED);
		step.setErrorMessage(ex.getMessage());
		step.setFinishedAt(Instant.now());
		try {
			stepMapper.updateById(step);
		}
		catch (RuntimeException updateEx) {
			log.warn("Failed to mark orchestration step as failed. stepId={}", step.getId(), updateEx);
		}
	}

	private boolean shouldFailFast(Long agentId) {
		return "fail_fast".equalsIgnoreCase(policyService.getOrCreate(agentId).getFailureStrategy());
	}

	private Long parseRequiredLong(String value, String fieldName) {
		if (!StringUtils.hasText(value)) {
			throw CheckedException.badRequest(fieldName + " cannot be empty");
		}
		try {
			return Long.valueOf(value);
		}
		catch (NumberFormatException ex) {
			throw CheckedException.badRequest(fieldName + " must be numeric");
		}
	}

}
