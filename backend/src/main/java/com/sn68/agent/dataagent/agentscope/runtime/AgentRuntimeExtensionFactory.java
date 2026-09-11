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
package com.sn68.agent.dataagent.agentscope.runtime;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.template.AgentRuntimeExtensions;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.service.skill.SkillVersionService;
import com.sn68.agent.dataagent.skill.SkillExecutionMode;
import com.sn68.agent.dataagent.skill.execution.ReactRuntimeBudgetPolicy;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.model.ExecutionConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Agent运行时Extension组件，封装 DataAgent 对应业务入口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRuntimeExtensionFactory {

	private final AgentScopeToolkitFactory toolkitFactory;

	private final AgentScopeMemoryFactory memoryFactory;

	private final AgentScopeHookFactory hookFactory;

	private final SkillVersionService skillVersionService;

	private final DataAgentProperties properties;

	/**
	 * 创建Agent运行时Extension。
	 */
	public AgentRuntimeExtensions create(AgentRequest request, @Nullable AgentRuntimeEventPublisher eventPublisher,
			Map<String, ToolCallback> toolCallbacks, PreparedMemory preparedMemory) {
		return create(request, eventPublisher, toolCallbacks, preparedMemory, null);
	}

	public AgentRuntimeExtensions create(AgentRequest request, @Nullable AgentRuntimeEventPublisher eventPublisher,
			Map<String, ToolCallback> toolCallbacks, PreparedMemory preparedMemory,
			AgentRuntimeToolMetrics existingToolMetrics) {
		Map<String, ToolCallback> effectiveToolCallbacks = new LinkedHashMap<>();
		if (toolCallbacks != null) {
			effectiveToolCallbacks.putAll(toolCallbacks);
		}
		if (log.isInfoEnabled()) {
			log.info("Agent runtime tool callbacks prepared. agentId={}, runtimeRequestId={}, effectiveToolNames={}",
					request.getAgentId(), request.getRuntimeRequestId(), effectiveToolCallbacks.keySet());
		}
		Toolkit toolkit = toolkitFactory.buildToolkit(effectiveToolCallbacks);
		Memory memory = preparedMemory == null ? memoryFactory.create(request).memory() : preparedMemory.memory();
		AgentRuntimeRequestMetadata requestMetadata = new AgentRuntimeRequestMetadata(request.getAgentId(),
				request.getThreadId(), request.getRuntimeRequestId(), request.isHumanFeedback(),
				request.getHumanFeedbackContent());
		DataAgentProperties.Runtime runtime = properties.getRuntime();
		ReactRuntimeBudgetPolicy.Budget budget = ReactRuntimeBudgetPolicy.resolve(runtime,
				request.getReactMaxIterations(), request.getMaxModelCalls(), request.getMaxToolCalls(),
				request.getMaxPromptTokens());
		AgentRuntimeToolMetrics toolMetrics = existingToolMetrics == null
				? new AgentRuntimeToolMetrics(runtime.getNoProgressMaxCompletedIdenticalCalls(),
						budget.maxModelCalls(), budget.maxToolCalls(), budget.maxPromptTokens())
				: existingToolMetrics;
		toolMetrics.configureEmptySearchNoProgress(runtime.isEmptySearchNoProgressEnabled(),
				runtime.getEmptySearchNoProgressMaxConsecutive());
		toolMetrics.setOriginalQuery(request.getQuery());
		ToolExecutionContext toolExecutionContext = ToolExecutionContext.builder()
			.register(requestMetadata)
			.register(toolMetrics)
			.register("graphRequest", request)
			.build();
		int maxIterations = reserveSummaryModelCall(budget);
		List<Hook> hooks = new ArrayList<>(hookFactory.create(request, eventPublisher, toolMetrics,
				maxIterations, runtime.getFinishBuffer()));
		Map<String, Object> attributes = new HashMap<>();
		attributes.put("threadId", request.getThreadId());
		attributes.put("memoryLoadedFromNative", preparedMemory != null && preparedMemory.loadedFromNative());
		attributes.put("toolMetrics", toolMetrics);
		List<AutoCloseable> closeables = new ArrayList<>();
		ExecutionConfig toolExecutionConfig = ExecutionConfig.builder()
			.timeout(effectiveTimeout(request, runtime.getToolTimeout(), runtime.getFinishBuffer()))
			.maxAttempts(1)
			.retryOn(error -> false)
			.build();
		return new AgentRuntimeExtensions(toolkit, memory, toolExecutionContext, toolExecutionConfig, hooks, attributes,
				null, skillInstructions(request), closeables, maxIterations,
			effectiveTimeout(request, runtime.getModelTimeout(), runtime.getFinishBuffer()), runtime.getFinishBuffer(),
			request.getRuntimeDeadline());
	}

	public void emitSearchResultSet(AgentRequest request, @Nullable AgentRuntimeEventPublisher eventPublisher) {
		hookFactory.emitSearchResultSet(request, eventPublisher);
	}

	/**
	 * AgentScope 在打满 maxIters 后还会再调一次模型做 summary。maxModelCalls 与 maxIterations
	 * 配成同一值时，这次收尾调用会触发 MODEL_CALLS 超限，英文异常被当成最终答案。预留 1 次给 summary。
	 */
	private int reserveSummaryModelCall(ReactRuntimeBudgetPolicy.Budget budget) {
		int maxIterations = budget.maxIterations();
		if (budget.maxModelCalls() > 1 && budget.maxModelCalls() <= maxIterations) {
			return Math.max(1, budget.maxModelCalls() - 1);
		}
		return maxIterations;
	}

	private java.time.Duration effectiveTimeout(AgentRequest request, java.time.Duration configured,
			java.time.Duration finishBuffer) {
		if (request == null || request.getRuntimeDeadline() == null) {
			return configured;
		}
		java.time.Duration timeout = request.getRuntimeDeadline().timeoutFor(configured, finishBuffer);
		return timeout.isZero() ? java.time.Duration.ofMillis(1) : timeout;
	}

	private String skillInstructions(AgentRequest request) {
		if (request.getRoutedSkillExecutionMode() != SkillExecutionMode.REACT
				|| request.getRoutedSkillVersionId() == null) {
			return "";
		}
		String instructions = request.getRoutedSkillInstructions();
		if (!StringUtils.hasText(instructions)) {
			DataAgentSkillVersion version = skillVersionService.getRequired(request.getRoutedSkillVersionId());
			instructions = version.getSkillMarkdown();
		}
		String businessContext = request.getSkillBusinessContext() == null ? ""
				: request.getSkillBusinessContext().promptBlock();
		return java.util.stream.Stream.of(instructions, businessContext)
			.filter(StringUtils::hasText)
			.collect(java.util.stream.Collectors.joining("\n\n"));
	}

}
