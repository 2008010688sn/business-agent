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
package com.sn68.agent.dataagent.agentscope.v2;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.vo.AgentResponse;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.dataagent.service.agent.AgentModelConfigService;
import com.sn68.agent.dataagent.service.aimodelconfig.DynamicModelFactory;
import com.sn68.agent.dataagent.service.aimodelconfig.ModelConfigDataService;
import com.sn68.agent.dataagent.service.analysis.AnalysisWorkspaceStore;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.DataPermission;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

/**
 * 按请求装配无状态 HarnessAgent，并把已有租户快照写入 RuntimeContext。
 */
@Slf4j
@RequiredArgsConstructor
public class HarnessAgentFactory {

	private final V2SpringAiChatModelAdapter modelAdapter;

	private final V2EventToAgentResponseMapper eventMapper;

	private final DynamicModelFactory dynamicModelFactory;

	private final AgentModelConfigService agentModelConfigService;

	private final ModelConfigDataService modelConfigDataService;

	private final com.sn68.agent.dataagent.service.agent.DataAgentService agentService;

	private final V2AgentStateStore stateStore;

	private final V2TenantGuardMiddleware tenantGuardMiddleware;

	private final AgentScopeV2Properties properties;

	private final V2BudgetMiddleware budgetMiddleware;

	private final V2ActingPermissionMiddleware permissionMiddleware;

	private final V2CompactionGuardMiddleware compactionGuardMiddleware;

	private final V2ReasoningThrottleMiddleware reasoningThrottleMiddleware;

	private final AnalysisWorkspaceStore workspaceStore;

	public Flux<ServerSentEvent<AgentResponse>> streamSearch(AgentRequest request) {
		ResolvedModel resolved = resolveModel(request);
		RuntimeContext runtimeContext = runtimeContext(request, resolved.reasoningProtocol());
		String query = request == null || request.getQuery() == null ? "" : request.getQuery();
		V2EventToAgentResponseMapper.StreamMapState mapState = new V2EventToAgentResponseMapper.StreamMapState();
		return Flux.using(() -> create(resolved.model(), "You are a helpful assistant.", null, List.of(), 0, null,
				snapshotFor(request)),
				agent -> agent.streamEvents(new UserMessage(query), runtimeContext)
					.concatMap(event -> Flux.fromIterable(eventMapper.map(event, request, mapState))),
				HarnessAgent::close);
	}

	public HarnessAgent create(Model model) {
		return create(model, "You are a helpful assistant.", null, List.of(), 0, null, V2RuntimeSnapshot.from(null));
	}

	public HarnessAgent create(Model model, String sysPrompt, Toolkit toolkit, List<Hook> hooks, int maxIters,
			Memory memory, V2RuntimeSnapshot snapshot) {
		return create(model, sysPrompt, toolkit, hooks, maxIters, memory, snapshot, null, null);
	}

	/**
	 * 装配 Harness。{@code toolExecutionContext} 是 2.0 工具调用上下文（含本轮 {@code graphRequest}
	 * 技能快照），与 {@code ReActAgent.builder().toolExecutionContext} 同一框架 API。
	 */
	public HarnessAgent create(Model model, String sysPrompt, Toolkit toolkit, List<Hook> hooks, int maxIters,
			Memory memory, V2RuntimeSnapshot snapshot, ToolExecutionContext toolExecutionContext,
			ExecutionConfig toolExecutionConfig) {
		V2RuntimeSnapshot bound = snapshot == null ? V2RuntimeSnapshot.from(null) : snapshot;
		HarnessAgent.Builder builder = applyNl2sqlHarnessDisables(HarnessAgent.builder()
			.name("data-agent-v2")
			.sysPrompt(StringUtils.hasText(sysPrompt) ? sysPrompt : "You are a helpful assistant.")
			.model(model));
		applyCompaction(builder);
		applyToolResultEviction(builder, properties);
		int iters = maxIters > 0 ? maxIters : properties == null ? 0 : properties.resolvedMaxIters();
		if (iters > 0) {
			builder.maxIters(iters);
		}
		if (toolkit != null) {
			builder.toolkit(toolkit);
		}
		if (hooks != null && !hooks.isEmpty()) {
			builder.hooks(hooks);
		}
		if (toolExecutionContext != null) {
			builder.toolExecutionContext(toolExecutionContext);
		}
		if (toolExecutionConfig != null) {
			builder.toolExecutionConfig(toolExecutionConfig);
		}
		if (tenantGuardMiddleware != null) {
			builder.middleware(tenantGuardMiddleware);
		}
		if (budgetMiddleware != null) {
			builder.middleware(budgetMiddleware);
		}
		if (permissionMiddleware != null) {
			builder.middleware(permissionMiddleware);
		}
		if (compactionGuardMiddleware != null) {
			builder.middleware(compactionGuardMiddleware);
		}
		if (reasoningThrottleMiddleware != null) {
			builder.middleware(reasoningThrottleMiddleware);
		}
		AgentStateStore boundStore = stateStore.bind(bound);
		if (memory != null && StringUtils.hasText(bound.sessionId())) {
			memory.saveTo(boundStore, bound.userId(), bound.sessionId());
		}
		builder.stateStore(boundStore);
		return builder.build();
	}

	/**
	 * NL2SQL 不走 Claude-Code 式工作区日记。filesystem / memory 工具和 workspace 上下文已经关掉，
	 * 但默认 MemoryFlush / MemoryConsolidator 仍会在终答后用聊天模型写 {@code memory/YYYY-MM-DD.md}，
	 * 阻塞 SSE 关流。会话短记忆走 {@link V2AgentStateStore}，跨会话走平台长期记忆。
	 * 工具结果驱逐不再在此关闭，见 {@link #applyToolResultEviction}。
	 */
	static HarnessAgent.Builder applyNl2sqlHarnessDisables(HarnessAgent.Builder builder) {
		return builder.disableSubagents()
			.disableFilesystemTools()
			.disableShellTool()
			.disableMemoryTools()
			.disableMemoryHooks()
			.disableWorkspaceContext()
			.disableDynamicSkills()
			.disableDefaultWorkspaceSkills()
			.disableToolsConfig();
	}

	/**
	 * 框架工具结果驱逐：把超限旧结果换成预览，是源上裁剪之外的第二道防线。
	 * NL2SQL 单一工具 {@code datasource_skill_search} 混合了 schema 与 SEARCH 动作，无法按名豁免；
	 * 源上 8k 裁剪已保证 SEARCH 结果（约 2～3KB）不会被驱逐，schema dump 超限时交给驱逐换成预览，
	 * 避免全文长期驻留上下文。配置关闭时显式禁用，保持旧行为。
	 */
	static HarnessAgent.Builder applyToolResultEviction(HarnessAgent.Builder builder,
			AgentScopeV2Properties properties) {
		AgentScopeV2Properties.ContextGovernance governance = properties == null
				? new AgentScopeV2Properties().getContextGovernance()
				: properties.getContextGovernance();
		if (!governance.isToolResultEvictionEnabled()) {
			return builder.disableToolResultEviction();
		}
		return builder.toolResultEviction(ToolResultEvictionConfig.builder()
			.maxResultChars(governance.resolvedToolResultEvictionMaxChars())
			.previewChars(governance.resolvedToolResultEvictionPreviewChars())
			.build());
	}

	/**
	 * 压缩总开关与参数全部来自 {@code contextGovernance} 配置；关闭时显式 {@code disableCompaction}。
	 */
	private void applyCompaction(HarnessAgent.Builder builder) {
		if (properties != null && !properties.getContextGovernance().isCompactionEnabled()) {
			builder.disableCompaction();
			return;
		}
		builder.compaction(nl2sqlCompaction());
	}

	/**
	 * 对齐模型窗口而不是信封：首包已约 6k，8k 触发会在写出 SQL 前摘要、耗时且易丢列名。
	 * 常规 3～5 轮问数单次 prompt 约 9k，不压；单次上下文接近 2W 或消息较多再压。
	 * keepMessages/keepTokens 必须同时小于实际消息数与总 token：旧值 12/12000 在 16 iter 的
	 * 会话里消息数或 token 常未达保留线，cutoff=1 等于压不动；新默认 8/8000 保证真的能裁。
	 * {@code reserved} 必须覆盖框架默认 20000，否则 32k 窗口会过早开火。
	 */
	CompactionConfig nl2sqlCompaction() {
		AgentScopeV2Properties.ContextGovernance governance = properties == null
				? new AgentScopeV2Properties().getContextGovernance()
				: properties.getContextGovernance();
		CompactionConfig.Builder builder = CompactionConfig.builder()
			.triggerTokens(governance.resolvedCompactionTriggerTokens())
			.triggerMessages(governance.resolvedCompactionTriggerMessages())
			.keepMessages(governance.resolvedCompactionKeepMessages())
			.keepTokens(governance.resolvedCompactionKeepTokens())
			.reserved(governance.resolvedCompactionReserved())
			.flushBeforeCompact(false)
			.offloadBeforeCompact(false);
		Model summaryModel = compactionSummaryModel(governance);
		if (summaryModel != null) {
			builder.model(summaryModel);
		}
		return builder.build();
	}

	/**
	 * 压缩摘要模型挂钩：配置 {@code compactionSummaryModelConfigId} 时用小模型做历史摘要。
	 * 解析失败（配置缺失、非 CHAT、租户不匹配、模型构造异常）降级为主模型，仅告警不打断请求。
	 */
	private Model compactionSummaryModel(AgentScopeV2Properties.ContextGovernance governance) {
		Long summaryModelConfigId = governance == null ? null : governance.getCompactionSummaryModelConfigId();
		if (summaryModelConfigId == null || summaryModelConfigId <= 0L || modelConfigDataService == null
				|| dynamicModelFactory == null || modelAdapter == null) {
			return null;
		}
		try {
			ModelConfigDTO config = modelConfigDataService.getRuntimeConfigById(summaryModelConfigId, ModelType.CHAT);
			if (config == null) {
				log.warn("压缩摘要模型配置不存在，回退主模型。compactionSummaryModelConfigId={}", summaryModelConfigId);
				return null;
			}
			return modelAdapter.adapt(dynamicModelFactory.createChatModel(config), config.getModelName());
		}
		catch (Exception ex) {
			log.warn("解析压缩摘要模型失败，回退主模型。compactionSummaryModelConfigId={}", summaryModelConfigId, ex);
			return null;
		}
	}

	public V2EventToAgentResponseMapper eventMapper() {
		return eventMapper;
	}

	public UserMessage userMessage(String userPrompt, List<ContentBlock> extraBlocks) {
		if (extraBlocks == null || extraBlocks.isEmpty()) {
			return new UserMessage("user", userPrompt == null ? "" : userPrompt);
		}
		ContentBlock[] blocks = extraBlocks.toArray(ContentBlock[]::new);
		if (!StringUtils.hasText(userPrompt)) {
			return new UserMessage("user", blocks);
		}
		ContentBlock[] withPrompt = new ContentBlock[blocks.length + 1];
		withPrompt[0] = io.agentscope.core.message.TextBlock.builder().text(userPrompt).build();
		System.arraycopy(blocks, 0, withPrompt, 1, blocks.length);
		return new UserMessage("user", withPrompt);
	}

	public RuntimeContext runtimeContext(AgentRequest request) {
		return runtimeContext(request, null);
	}

	/**
	 * 协议重载：thinking 降档兜底（{@link V2ReasoningThrottleMiddleware}）需要 run 级推理协议
	 * 标志。主链路（runHarnessAgentV2）从本次实际解析的 {@code ModelConfigDTO.reasoningProtocol}
	 * 传入，保证与真正使用的模型一致；解析不出来时不下发 key，中间件按未知协议原样放行。
	 */
	public RuntimeContext runtimeContext(AgentRequest request, String reasoningProtocol) {
		V2RuntimeSnapshot snapshot = snapshotFor(request);
		RuntimeContext.Builder builder = RuntimeContext.builder()
			.sessionId(snapshot.sessionId())
			.userId(snapshot.userId())
			.put(V2RuntimeSnapshot.class, snapshot)
			.put(V2RequestBudget.class, V2RequestBudget.from(request));
		if (snapshot.dataPermission() != null) {
			builder.put(DataPermission.class, snapshot.dataPermission());
		}
		if (StringUtils.hasText(snapshot.tenantId())) {
			builder.put("tenantId", snapshot.tenantId());
		}
		if (StringUtils.hasText(snapshot.tenantCode())) {
			builder.put("tenantCode", snapshot.tenantCode());
		}
		String analysisIntent = request == null ? null : request.getV2AnalysisIntent();
		if (StringUtils.hasText(analysisIntent)) {
			builder.put(V2ActingPermissionMiddleware.ANALYSIS_INTENT_KEY, analysisIntent);
		}
		if (StringUtils.hasText(reasoningProtocol)) {
			builder.put(V2ReasoningThrottleMiddleware.REASONING_PROTOCOL_KEY, reasoningProtocol);
		}
		return builder.build();
	}

	public V2RuntimeSnapshot snapshotFor(AgentRequest request) {
		V2RuntimeSnapshot snapshot = V2RuntimeSnapshot.from(request);
		if (workspaceStore == null || snapshot == null) {
			return snapshot;
		}
		return snapshot.withAnalysisWorkspaceHandles(
				workspaceStore.handles(snapshot.tenantId(), snapshot.sessionId()));
	}

	private ResolvedModel resolveModel(AgentRequest request) {
		if (request == null || !StringUtils.hasText(request.getAgentId())) {
			throw CheckedException.badRequest("agentId不能为空");
		}
		Long agentId;
		try {
			agentId = Long.valueOf(request.getAgentId().trim());
		}
		catch (NumberFormatException ex) {
			throw CheckedException.badRequest("agentId必须为数字");
		}
		DataAgent agent = agentService.findById(agentId);
		if (agent == null) {
			throw CheckedException.notFound("智能体不存在");
		}
		ModelConfigDTO config = agentModelConfigService.resolveChatModelConfig(agent, request.getChatModelConfigId());
		ChatModel chatModel = dynamicModelFactory.createChatModel(config);
		String modelName = config == null ? null : config.getModelName();
		return new ResolvedModel(modelAdapter.adapt(chatModel, modelName),
				config == null ? null : config.getReasoningProtocol());
	}

	/**
	 * streamSearch 内部复用的模型解析结果：Model 与 thinking 降档兜底需要的推理协议。
	 * 协议直接取自解析出的 {@link ModelConfigDTO#reasoningProtocol}（可能为 AUTO，中间件按原样放行处理）。
	 */
	private record ResolvedModel(Model model, String reasoningProtocol) {
	}

}
