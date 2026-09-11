/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.optimization.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.context.DataAgentAsyncContextBridge;
import com.sn68.agent.dataagent.context.ExecutionIntentContext;
import com.sn68.agent.dataagent.entity.DataChatSession;
import com.sn68.agent.dataagent.optimization.entity.DataAgentOptCandidate;
import com.sn68.agent.dataagent.optimization.entity.DataAgentOptExperiment;
import com.sn68.agent.dataagent.optimization.repository.DataAgentOptCandidateMapper;
import com.sn68.agent.dataagent.optimization.repository.DataAgentOptExperimentMapper;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.runtime.hook.dto.RuntimeHookEvent;
import com.sn68.agent.dataagent.service.agent.AgentInvocationService;
import com.sn68.agent.dataagent.service.chat.DataChatSessionService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * DataAgent 只读 Shadow：生产问答成功后，把同一问题异步用候选 prompt 再跑一遍 DRY_RUN。
 * 不切线上流量；数字员工 / 任务 / 评估 / 已是 Shadow 的请求一律跳过。
 */
@Slf4j
@Component
public class DataAgentEvolutionShadowService {

	static final String REQUEST_SOURCE = "SHADOW";

	static final String OWNER_TYPE_DATA_AGENT = "DATA_AGENT";

	static final String OWNER_TYPE_DIGITAL_EMPLOYEE = "DIGITAL_EMPLOYEE";

	private static final Set<String> SKIP_SOURCES = Set.of("EVAL", REQUEST_SOURCE, "RUNTIME_TASK");

	private static final Set<String> ELIGIBLE_CANDIDATE_STATUS = Set.of("draft", "evaluated", "gate_blocked",
			"offline_evaluating");

	private final DataAgentProperties dataAgentProperties;

	private final DataAgentOptExperimentMapper experimentMapper;

	private final DataAgentOptCandidateMapper candidateMapper;

	private final DataChatSessionService chatSessionService;

	private final ObjectProvider<AgentInvocationService> invocationProvider;

	private final DataAgentAsyncContextBridge asyncContextBridge;

	private final ObjectMapper objectMapper;

	private final Executor shadowExecutor;

	@Autowired
	public DataAgentEvolutionShadowService(DataAgentProperties dataAgentProperties,
			DataAgentOptExperimentMapper experimentMapper, DataAgentOptCandidateMapper candidateMapper,
			DataChatSessionService chatSessionService, ObjectProvider<AgentInvocationService> invocationProvider,
			DataAgentAsyncContextBridge asyncContextBridge, ObjectMapper objectMapper) {
		this(dataAgentProperties, experimentMapper, candidateMapper, chatSessionService, invocationProvider,
				asyncContextBridge, objectMapper, command -> {
					Thread thread = new Thread(command, "opt-shadow");
					thread.setDaemon(true);
					thread.start();
				});
	}

	DataAgentEvolutionShadowService(DataAgentProperties dataAgentProperties,
			DataAgentOptExperimentMapper experimentMapper, DataAgentOptCandidateMapper candidateMapper,
			DataChatSessionService chatSessionService, ObjectProvider<AgentInvocationService> invocationProvider,
			DataAgentAsyncContextBridge asyncContextBridge, ObjectMapper objectMapper, Executor shadowExecutor) {
		this.dataAgentProperties = dataAgentProperties;
		this.experimentMapper = experimentMapper;
		this.candidateMapper = candidateMapper;
		this.chatSessionService = chatSessionService;
		this.invocationProvider = invocationProvider;
		this.asyncContextBridge = asyncContextBridge;
		this.objectMapper = objectMapper;
		this.shadowExecutor = shadowExecutor;
	}

	public void scheduleIfEligible(RuntimeHookEvent event) {
		try {
			if (event == null || event.agentId() == null || event.input() == null) {
				return;
			}
			if (!shouldMirror(event)) {
				return;
			}
			DataAgentOptCandidate candidate = findPromptCandidate(text(event.input(), "tenantIdSnapshot"),
					event.agentId());
			String overlay = extractPromptOverlay(candidate);
			if (candidate == null || !StringUtils.hasText(overlay)) {
				return;
			}
			DataAgentAsyncContextBridge.Snapshot snapshot = asyncContextBridge.capture();
			shadowExecutor.execute(() -> runShadow(snapshot, event, candidate, overlay));
		}
		catch (RuntimeException ex) {
			log.warn("调度 DataAgent Shadow 失败, 不影响生产问答. agentId={}, runtimeRequestId={}",
					event == null ? null : event.agentId(), event == null ? null : event.runtimeRequestId(), ex);
		}
	}

	boolean shouldMirror(RuntimeHookEvent event) {
		Map<String, Object> input = event.input();
		String source = text(input, "requestSource");
		if (StringUtils.hasText(source) && SKIP_SOURCES.contains(source.trim().toUpperCase())) {
			return false;
		}
		if (ExecutionIntentContext.INTENT_DRY_RUN.equalsIgnoreCase(text(input, "executionIntent"))) {
			return false;
		}
		if (OWNER_TYPE_DIGITAL_EMPLOYEE.equalsIgnoreCase(text(input, "ownerType"))) {
			log.info("数字员工请求不做 Shadow. agentId={}, ownerId={}", event.agentId(), text(input, "ownerId"));
			return false;
		}
		return shadowEnabledForTenant(text(input, "tenantIdSnapshot"));
	}

	boolean shadowEnabledForTenant(String tenantId) {
		DataAgentProperties.Optimization optimization = dataAgentProperties.getOptimization();
		if (optimization == null || !optimization.isShadowEnabled()) {
			return false;
		}
		List<String> allow = optimization.getShadowTenantIds();
		if (allow == null || allow.isEmpty() || !StringUtils.hasText(tenantId)) {
			return false;
		}
		return allow.stream().anyMatch(id -> tenantId.equals(id == null ? null : id.trim()));
	}

	private DataAgentOptCandidate findPromptCandidate(String tenantId, Long agentId) {
		List<DataAgentOptExperiment> experiments = experimentMapper.findActiveByOwner(tenantId, OWNER_TYPE_DATA_AGENT,
				String.valueOf(agentId));
		for (DataAgentOptExperiment experiment : experiments) {
			List<DataAgentOptCandidate> candidates = candidateMapper.findByExperimentId(experiment.getId());
			for (DataAgentOptCandidate candidate : candidates) {
				if (candidate != null && "PROMPT".equalsIgnoreCase(candidate.getTargetType())
						&& ELIGIBLE_CANDIDATE_STATUS.contains(
								candidate.getStatus() == null ? "" : candidate.getStatus().trim().toLowerCase())
						&& StringUtils.hasText(extractPromptOverlay(candidate))) {
					return candidate;
				}
			}
		}
		return null;
	}

	private void runShadow(DataAgentAsyncContextBridge.Snapshot snapshot, RuntimeHookEvent event,
			DataAgentOptCandidate candidate, String overlay) {
		asyncContextBridge.runWith(snapshot, () -> {
			String scopeKey = "shadow-" + UUID.randomUUID();
			try {
				ExecutionIntentContext.supplyDryRun(scopeKey, new ExecutionIntentContext.ViolationCollector(),
						() -> invokeShadow(event, candidate, overlay, scopeKey));
			}
			catch (RuntimeException ex) {
				log.warn("DataAgent Shadow DRY_RUN 失败, 不影响生产. agentId={}, candidateId={}, sourceRuntimeRequestId={}",
						event.agentId(), candidate.getId(), event.runtimeRequestId(), ex);
			}
		});
	}

	private String invokeShadow(RuntimeHookEvent event, DataAgentOptCandidate candidate, String overlay,
			String scopeKey) {
		AgentInvocationService invocation = invocationProvider.getIfAvailable();
		if (invocation == null) {
			throw new IllegalStateException("AgentInvocationService 不可用，Shadow 失败关闭");
		}
		Long userId = parseLong(text(event.input(), "userIdSnapshot"));
		DataChatSession session = chatSessionService.createSession(event.agentId(), "shadow-" + candidate.getId(),
				userId);
		String runtimeRequestId = "shadow-" + event.runtimeRequestId() + "-" + candidate.getId();
		AgentRequest request = AgentRequest.builder()
			.agentId(String.valueOf(event.agentId()))
			.threadId(String.valueOf(session.getId()))
			.runtimeRequestId(runtimeRequestId)
			.rootRuntimeRequestId(runtimeRequestId)
			.query(text(event.input(), "query"))
			.requestSource(REQUEST_SOURCE)
			.executionIntent(ExecutionIntentContext.INTENT_DRY_RUN)
			.executionScopeKey(scopeKey)
			.evalSystemInstructionOverride(overlay)
			.isolatedMemory(true)
			.tenantIdSnapshot(text(event.input(), "tenantIdSnapshot"))
			.tenantCodeSnapshot(text(event.input(), "tenantCodeSnapshot"))
			.userIdSnapshot(text(event.input(), "userIdSnapshot"))
			.clientIdSnapshot(text(event.input(), "clientIdSnapshot"))
			.build();
		String answer = invocation.invoke(request);
		log.info("DataAgent Shadow DRY_RUN 完成. agentId={}, candidateId={}, shadowRuntimeRequestId={}", event.agentId(),
				candidate.getId(), runtimeRequestId);
		return answer;
	}

	String extractPromptOverlay(DataAgentOptCandidate candidate) {
		if (candidate == null || !StringUtils.hasText(candidate.getPatchJson())) {
			return null;
		}
		try {
			JsonNode patch = objectMapper.readTree(candidate.getPatchJson());
			JsonNode overlay = patch == null ? null : patch.get("snapshotOverlay");
			JsonNode promptSnapshot = overlay == null ? null : overlay.get("promptSnapshot");
			if (promptSnapshot == null || promptSnapshot.isNull()) {
				return null;
			}
			if (promptSnapshot.hasNonNull("systemInstruction")) {
				return promptSnapshot.get("systemInstruction").asText();
			}
			if (promptSnapshot.hasNonNull("prompt")) {
				return promptSnapshot.get("prompt").asText();
			}
			return null;
		}
		catch (Exception ex) {
			return null;
		}
	}

	private static String text(Map<String, Object> input, String key) {
		if (input == null || !input.containsKey(key) || input.get(key) == null) {
			return null;
		}
		String value = String.valueOf(input.get(key));
		return StringUtils.hasText(value) ? value : null;
	}

	private static Long parseLong(String value) {
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
