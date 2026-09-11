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

import com.sn68.agent.dataagent.agentscope.tool.AgentModelToolName;
import com.sn68.agent.dataagent.agentscope.tool.AgentToolPolicyService;
import com.sn68.agent.dataagent.agentscope.v2.AgentScopeV2Properties;
import com.sn68.agent.dataagent.capability.CapabilityGateway;
import com.sn68.agent.dataagent.capability.CapabilityGatewayToolCallback;
import com.sn68.agent.dataagent.context.DataAgentAsyncContextBridge;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeArtifactMapper;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeEventService;
import com.sn68.agent.dataagent.runtime.hook.service.RuntimeHookDispatcher;
import com.sn68.agent.dataagent.util.McpServerToolUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Toolkit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 根据 Agent 授权和工具策略构造 AgentScope 可调用工具集合。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentScopeToolkitFactory {

	private final AgentToolPolicyService agentToolPolicyService;

	private final GenericApplicationContext applicationContext;

	private final ObjectMapper objectMapper;

	private final AnswerTraceExplainStore answerTraceExplainStore;

	private final DataAgentAsyncContextBridge asyncContextBridge;

	private final RuntimeHookDispatcher runtimeHookDispatcher;

	private final AgentRuntimeProgressService runtimeProgressService;

	private final CapabilityGateway capabilityGateway;

	private final RuntimeEventService runtimeEventService;

	private final AgentRuntimeArtifactMapper artifactMapper;

	private final AgentScopeV2Properties agentScopeV2Properties;

	private final DataAgentProperties dataAgentProperties;

	private volatile Map<String, ToolCallback> commonToolCallbacksSnapshot = Collections.emptyMap();

	/**
	 * 创建AgentScopeToolkit。
	 */
	public Toolkit create(String agentId) {
		return buildToolkit(getToolCallbacks(agentId));
	}

	/**
	 * 创建AgentScopeToolkit。
	 */
	public Toolkit create(String agentId, String agentType) {
		return buildToolkit(getToolCallbacks(agentId, agentType));
	}

	/**
	 * 创建AgentScopeToolkit。
	 */
	public Toolkit buildToolkit(Map<String, ToolCallback> toolCallbacks) {
		Toolkit toolkit = new Toolkit();
		if (toolCallbacks == null || toolCallbacks.isEmpty()) {
			return toolkit;
		}
		// 所有 AgentScope 工具统一先经 CapabilityGateway（capabilityKind=TOOL）完成检查链与调用记录，
		// 再执行原始 Spring AI 回调；智能体类型级授权仍由装配期 AgentToolPolicyService 过滤承担。
		// 工具结果进入上下文前的源上裁剪预算来自 contextGovernance 配置（三层预算第一层），
		// 同请求内只读工具结果缓存同样来自该配置组，命中时跳过底层回调。
		toolCallbacks.values()
			.forEach(toolCallback -> toolkit
				.registerAgentTool(new SpringToolCallbackAgentAdapter(
						new CapabilityGatewayToolCallback(toolCallback, capabilityGateway, objectMapper,
								runtimeEventService, artifactMapper), objectMapper,
						answerTraceExplainStore, asyncContextBridge, runtimeHookDispatcher, runtimeProgressService,
						toolResultMaxChars(), toolResultHeadKeepChars(), resultCacheEnabled(),
						resultCacheMaxEntries(), resultCacheableTools())));
		log.debug("Mapped {} Spring AI tool callbacks into AgentScope toolkit", toolCallbacks.size());
		return toolkit;
	}

	private int toolResultMaxChars() {
		return agentScopeV2Properties == null ? 0
				: agentScopeV2Properties.getContextGovernance().resolvedToolResultMaxChars();
	}

	private int toolResultHeadKeepChars() {
		return agentScopeV2Properties == null ? 0
				: agentScopeV2Properties.getContextGovernance().resolvedToolResultHeadKeepChars();
	}

	private boolean resultCacheEnabled() {
		return agentScopeV2Properties != null
				&& agentScopeV2Properties.getContextGovernance().resolvedResultCacheEnabled();
	}

	private int resultCacheMaxEntries() {
		return agentScopeV2Properties == null ? 0
				: agentScopeV2Properties.getContextGovernance().resolvedResultCacheMaxEntries();
	}

	private Set<String> resultCacheableTools() {
		return agentScopeV2Properties == null ? Set.of()
				: agentScopeV2Properties.getContextGovernance().resolvedResultCacheableTools();
	}

	public Map<String, ToolCallback> getToolCallbacks(String agentId) {
		return applyRuntimeFilters(getCommonToolCallbacks());
	}

	public Map<String, ToolCallback> getToolCallbacks(String agentId, String agentType) {
		return Collections.unmodifiableMap(
				agentToolPolicyService.filter(agentType, applyRuntimeFilters(getCommonToolCallbacks())));
	}

	/**
	 * 按当前配置过滤本轮模型可见工具。{@code fetch-enabled=false} 时去掉 {@code web_fetch}，
	 * 不在启动期 {@code @ConditionalOnProperty} 绑死，Nacos 热更新后下一轮生效。
	 */
	Map<String, ToolCallback> applyRuntimeFilters(Map<String, ToolCallback> callbacks) {
		if (callbacks == null || callbacks.isEmpty()) {
			return Map.of();
		}
		if (webEvidenceFetchEnabled() || !callbacks.containsKey(AgentModelToolName.WEB_FETCH)) {
			return callbacks;
		}
		Map<String, ToolCallback> filtered = new LinkedHashMap<>(callbacks);
		filtered.remove(AgentModelToolName.WEB_FETCH);
		return Collections.unmodifiableMap(filtered);
	}

	private boolean webEvidenceFetchEnabled() {
		return dataAgentProperties != null && dataAgentProperties.getWebEvidence() != null
				&& dataAgentProperties.getWebEvidence().isFetchEnabled();
	}

	/**
	 * 处理AgentScopeToolkit。
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void warmUpCommonToolCallbacks() {
		refreshCommonToolCallbacks();
	}

	/**
	 * 处理AgentScopeToolkit。
	 */
	public synchronized void refreshCommonToolCallbacks() {
		Map<String, ToolCallback> snapshot = new LinkedHashMap<>();
		for (ToolCallback toolCallback : McpServerToolUtil.excludeMcpServerTool(applicationContext,
				ToolCallback.class)) {
			register(snapshot, toolCallback);
		}
		for (ToolCallbackProvider provider : McpServerToolUtil.excludeMcpServerTool(applicationContext,
				ToolCallbackProvider.class)) {
			for (ToolCallback toolCallback : provider.getToolCallbacks()) {
				register(snapshot, toolCallback);
			}
		}
		this.commonToolCallbacksSnapshot = Collections.unmodifiableMap(snapshot);
		log.debug("Warmed up {} common Spring AI tool callbacks.", snapshot.size());
	}

	private Map<String, ToolCallback> getCommonToolCallbacks() {
		Map<String, ToolCallback> snapshot = this.commonToolCallbacksSnapshot;
		if (!snapshot.isEmpty()) {
			return snapshot;
		}
		synchronized (this) {
			if (this.commonToolCallbacksSnapshot.isEmpty()) {
				refreshCommonToolCallbacks();
			}
			return this.commonToolCallbacksSnapshot;
		}
	}

	private void register(Map<String, ToolCallback> callbacks, ToolCallback toolCallback) {
		if (toolCallback == null || toolCallback.getToolDefinition() == null
				|| toolCallback.getToolDefinition().name() == null) {
			return;
		}
		register(callbacks, toolCallback.getToolDefinition().name(), toolCallback);
	}

	private void register(Map<String, ToolCallback> callbacks, String toolName, ToolCallback toolCallback) {
		if (toolCallback == null || toolName == null) {
			return;
		}
		ToolCallback previous = callbacks.putIfAbsent(toolName, toolCallback);
		if (previous != null && previous != toolCallback) {
			log.warn("Duplicate Spring AI tool callback detected, keep first one. toolName={}", toolName);
		}
	}

}
