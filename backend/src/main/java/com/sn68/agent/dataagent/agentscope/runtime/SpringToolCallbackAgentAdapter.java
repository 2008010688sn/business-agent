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

import cn.hutool.crypto.SecureUtil;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.context.DataAgentAsyncContextBridge;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.ToolExecutionRecord;
import com.sn68.agent.dataagent.runtime.hook.service.RuntimeHookDispatcher;
import com.sn68.agent.dataagent.service.security.UntrustedContentBoundary;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.tool.AgentModelToolName;
import com.sn68.agent.dataagent.agentscope.tool.ToolResultBudgetSanitizer;
import com.sn68.agent.dataagent.agentscope.tool.datasource.PostgresBooleanLiteralNormalizer;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolExecutionContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 将 Spring AI ToolCallback 适配为 AgentScope AgentTool。
 */
@Slf4j
public class SpringToolCallbackAgentAdapter implements AgentTool {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private static final int TOOL_SUMMARY_MAX_LENGTH = 800;

	private static final String STATUS_SUCCESS = "success";

	private static final String STATUS_FAILED = "failed";

	public static final String METADATA_BUSINESS_FAILED = "xx_business_failed";

	public static final String UNTRUSTED_CONTENT_EGRESS_REJECTED = "UNTRUSTED_CONTENT_EGRESS_REJECTED";

	/** 单条结果长度超过该值不写缓存，避免个别超大结果撑爆每请求缓存。 */
	private static final int MAX_CACHEABLE_RESULT_CHARS = 65_536;

	private static final String UNTRUSTED_CONTENT_EGRESS_MESSAGE = "工具入参中出现了不可信数据边界标记，本次调用已被拒绝，工具没有执行。"
			+ "请不要把检索结果原样粘贴进入参，改用你自己组织的参数重新调用。";

	private final ToolCallback toolCallback;

	private final ObjectMapper objectMapper;

	private final AnswerTraceExplainStore answerTraceExplainStore;

	private final DataAgentAsyncContextBridge asyncContextBridge;

	private final RuntimeHookDispatcher runtimeHookDispatcher;

	private final AgentRuntimeProgressService runtimeProgressService;

	private final int toolResultMaxChars;

	private final int toolResultHeadKeepChars;

	private final Set<String> resultCacheableTools;

	/** 每请求级工具结果缓存（key -> 裁剪后回给模型的最终字符串），关闭时为 null。 */
	private final Map<String, String> toolResultCache;

	public SpringToolCallbackAgentAdapter(ToolCallback toolCallback, ObjectMapper objectMapper,
			AnswerTraceExplainStore answerTraceExplainStore, DataAgentAsyncContextBridge asyncContextBridge) {
		this(toolCallback, objectMapper, answerTraceExplainStore, asyncContextBridge, null);
	}

	public SpringToolCallbackAgentAdapter(ToolCallback toolCallback, ObjectMapper objectMapper,
			AnswerTraceExplainStore answerTraceExplainStore, DataAgentAsyncContextBridge asyncContextBridge,
			RuntimeHookDispatcher runtimeHookDispatcher) {
		this(toolCallback, objectMapper, answerTraceExplainStore, asyncContextBridge, runtimeHookDispatcher, null);
	}

	public SpringToolCallbackAgentAdapter(ToolCallback toolCallback, ObjectMapper objectMapper,
			AnswerTraceExplainStore answerTraceExplainStore, DataAgentAsyncContextBridge asyncContextBridge,
			RuntimeHookDispatcher runtimeHookDispatcher, AgentRuntimeProgressService runtimeProgressService) {
		this(toolCallback, objectMapper, answerTraceExplainStore, asyncContextBridge, runtimeHookDispatcher,
				runtimeProgressService, 0, 0);
	}

	/**
	 * 旧参构造：工具结果源上裁剪生效，结果缓存关闭（旧行为）。
	 */
	public SpringToolCallbackAgentAdapter(ToolCallback toolCallback, ObjectMapper objectMapper,
			AnswerTraceExplainStore answerTraceExplainStore, DataAgentAsyncContextBridge asyncContextBridge,
			RuntimeHookDispatcher runtimeHookDispatcher, AgentRuntimeProgressService runtimeProgressService,
			int toolResultMaxChars, int toolResultHeadKeepChars) {
		this(toolCallback, objectMapper, answerTraceExplainStore, asyncContextBridge, runtimeHookDispatcher,
				runtimeProgressService, toolResultMaxChars, toolResultHeadKeepChars, false, 0, Set.of());
	}

	/**
	 * {@code toolResultMaxChars}/{@code toolResultHeadKeepChars} 来自
	 * {@code AgentScopeV2Properties.contextGovernance}，是工具结果进入模型上下文前的源上裁剪预算；
	 * 非正数表示关闭裁剪，结果全文透传（旧行为）。
	 * <p>
	 * {@code resultCacheEnabled}/{@code resultCacheMaxEntries}/{@code resultCacheableTools} 是同一配置组下的
	 * 同请求工具结果缓存：仅当启用、容量为正且工具在只读白名单内时生效。缓存随请求级 Toolkit 每请求
	 * 重建，不跨请求共享；key 为工具名 + 入参整串 SHA-256，不做参数语义规范化（同参不同序视为不同
	 * key，保守正确优先）；命中跳过底层工具执行，直接复用上次回给模型的最终字符串。写工具绝不能
	 * 进入白名单。
	 */
	public SpringToolCallbackAgentAdapter(ToolCallback toolCallback, ObjectMapper objectMapper,
			AnswerTraceExplainStore answerTraceExplainStore, DataAgentAsyncContextBridge asyncContextBridge,
			RuntimeHookDispatcher runtimeHookDispatcher, AgentRuntimeProgressService runtimeProgressService,
			int toolResultMaxChars, int toolResultHeadKeepChars, boolean resultCacheEnabled, int resultCacheMaxEntries,
			Set<String> resultCacheableTools) {
		this.toolCallback = toolCallback;
		this.objectMapper = objectMapper;
		this.answerTraceExplainStore = answerTraceExplainStore;
		this.asyncContextBridge = asyncContextBridge;
		this.runtimeHookDispatcher = runtimeHookDispatcher;
		this.runtimeProgressService = runtimeProgressService;
		this.toolResultMaxChars = toolResultMaxChars;
		this.toolResultHeadKeepChars = toolResultHeadKeepChars;
		this.resultCacheableTools = resultCacheableTools == null ? Set.of() : resultCacheableTools;
		this.toolResultCache = resultCacheEnabled && resultCacheMaxEntries > 0
				? newResultCache(resultCacheMaxEntries) : null;
	}

	/** 插入序淘汰最旧的线程安全结果缓存；单轮内工具可能并行调用，统一经互斥锁访问。 */
	private static Map<String, String> newResultCache(int maxEntries) {
		return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {

			@Override
			protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
				return size() > maxEntries;
			}
		});
	}

	@Override
	public String getName() {
		return toolCallback.getToolDefinition().name();
	}

	@Override
	public String getDescription() {
		return toolCallback.getToolDefinition().description();
	}

	@Override
	public Map<String, Object> getParameters() {
		try {
			return objectMapper.readValue(toolCallback.getToolDefinition().inputSchema(), MAP_TYPE);
		}
		catch (Exception ex) {
			log.warn("Failed to parse Spring AI tool schema, fallback to empty object. tool={}", getName(), ex);
			return Map.of("type", "object", "properties", Map.of());
		}
	}

	/**
	 * 处理SpringToolCallbackAgentAdapter。
	 */
	@Override
	public Mono<ToolResultBlock> callAsync(ToolCallParam toolCallParam) {
		DataAgentAsyncContextBridge.Snapshot asyncContext = asyncContextBridge.capture();
		return Mono.fromCallable(() -> asyncContextBridge.callWith(asyncContext, () -> invoke(toolCallParam)))
			.subscribeOn(Schedulers.boundedElastic());
	}

	private ToolResultBlock invoke(ToolCallParam toolCallParam) throws Exception {
		ToolInvocation invocation = prepareToolInvocation(toolCallParam);
		ToolResultBlock rejected = rejectByRuntimeBudget(toolCallParam, invocation);
		if (rejected != null) {
			return rejected;
		}
		ToolResultBlock egressRejected = rejectByUntrustedContentEgress(toolCallParam, invocation);
		if (egressRejected != null) {
			return egressRejected;
		}
		try {
			return completeToolCall(toolCallParam, invocation);
		}
		catch (Exception ex) {
			return failToolCall(toolCallParam, invocation, ex);
		}
	}

	/** 阶段：固化本次调用的入参、请求元数据、执行序号与指标句柄。 */
	private ToolInvocation prepareToolInvocation(ToolCallParam toolCallParam) throws Exception {
		long startNanos = System.nanoTime();
		long startEpochMs = System.currentTimeMillis();
		Map<String, Object> toolInput = toolCallParam == null || toolCallParam.getInput() == null ? Map.of()
				: toolCallParam.getInput();
		String payload = objectMapper.writeValueAsString(toolInput);
		AgentRuntimeRequestMetadata requestMetadata = requestMetadata(toolCallParam);
		AgentRequest agentRequest = agentRequest(toolCallParam, requestMetadata);
		Integer sequenceNo = reserveToolExecutionSequenceNo(agentRequest);
		AgentRuntimeToolMetrics toolMetrics = toolMetrics(toolCallParam);
		String toolDisplayName = AgentRuntimeToolDisplayNameResolver.displayName(getName(), getDescription(), toolInput);
		return new ToolInvocation(startNanos, startEpochMs, toolInput, payload, requestMetadata, agentRequest,
				sequenceNo, toolMetrics, toolDisplayName);
	}

	/**
	 * 阶段：调用前的守卫与预算检查（未解析业务术语、运行截止时间、只读空转、工具调用预算）。
	 * 返回非 null 表示已被守卫拦截，直接把该结果回给模型；返回 null 表示放行。
	 */
	private ToolResultBlock rejectByRuntimeBudget(ToolCallParam toolCallParam, ToolInvocation invocation) {
		AgentRequest agentRequest = invocation.agentRequest();
		AgentRuntimeToolMetrics toolMetrics = invocation.toolMetrics();
		String toolDisplayName = invocation.toolDisplayName();
		String payload = invocation.payload();
		Integer sequenceNo = invocation.sequenceNo();
		emitToolRunning(agentRequest, toolDisplayName);
		if (toolMetrics == null) {
			return null;
		}
		if (!toolMetrics.allowControlledFieldSearch(getName(), payload)) {
			emitToolFinished(agentRequest, toolDisplayName, STATUS_SUCCESS, 0L);
			return ToolResultBlock.of(toolCallParam.getToolUseBlock().getId(), getName(), TextBlock.builder()
				.text("{\"clarificationRequired\":true,\"reason\":\"UNKNOWN_BUSINESS_TERM_UNRESOLVED\"}")
				.build());
		}
		if (agentRequest != null && agentRequest.getRuntimeDeadline() != null
				&& !agentRequest.getRuntimeDeadline().canStart(agentRequest.getRuntimeFinishBuffer())) {
			toolMetrics.recordFailure(sequenceNo, "RUNTIME_DEADLINE_EXHAUSTED",
					"本轮运行时间已用尽，未启动新的工具调用。");
			emitToolFinished(agentRequest, toolDisplayName, STATUS_FAILED, 0L, "RUNTIME_DEADLINE_EXHAUSTED");
			return ToolResultBlock.error("本轮运行时间已用尽，未启动新的工具调用。")
				.withIdAndName(toolCallParam.getToolUseBlock().getId(), getName());
		}
		if (!toolMetrics.allowReadOnlyCall(getName(), payload)) {
			toolMetrics.recordFailure(sequenceNo, "NO_PROGRESS_LOOP",
					"相同只读工具调用已重复且没有新进展。");
			emitToolFinished(agentRequest, toolDisplayName, STATUS_FAILED, 0L, "NO_PROGRESS_LOOP");
			return ToolResultBlock.of(toolCallParam.getToolUseBlock().getId(), getName(),
					TextBlock.builder().text("相同只读工具调用已重复且没有新进展，请基于已有结果继续或调整查询条件。").build(),
					Map.of(METADATA_BUSINESS_FAILED, true, "errorCode", "NO_PROGRESS_LOOP"));
		}
		if (!toolMetrics.allowDatasourceSearch(getName(), payload)) {
			String message = toolMetrics.emptySearchNoProgressMessage();
			toolMetrics.recordFailure(sequenceNo, "NO_PROGRESS_EMPTY_RESULTS", message);
			emitToolFinished(agentRequest, toolDisplayName, STATUS_FAILED, 0L, "NO_PROGRESS_EMPTY_RESULTS");
			return ToolResultBlock.of(toolCallParam.getToolUseBlock().getId(), getName(),
					TextBlock.builder().text(message).build(),
					Map.of(METADATA_BUSINESS_FAILED, true, "errorCode", "NO_PROGRESS_EMPTY_RESULTS"));
		}
		if (!toolMetrics.allowSearchExecution(getName(), payload)) {
			String message = toolMetrics.searchExecutionNoProgressMessage();
			toolMetrics.recordFailure(sequenceNo, "NO_PROGRESS_SEARCH_FAILED", message);
			emitToolFinished(agentRequest, toolDisplayName, STATUS_FAILED, 0L, "NO_PROGRESS_SEARCH_FAILED");
			return ToolResultBlock.of(toolCallParam.getToolUseBlock().getId(), getName(),
					TextBlock.builder().text(message).build(),
					Map.of(METADATA_BUSINESS_FAILED, true, "errorCode", "NO_PROGRESS_SEARCH_FAILED"));
		}
		if (!toolMetrics.tryRecordCall()) {
			String message = toolMetrics.toolBudgetExceededMessage();
			toolMetrics.recordFailure(sequenceNo, "TOOL_CALL_LIMIT_EXCEEDED", message);
			emitToolFinished(agentRequest, toolDisplayName, STATUS_FAILED, 0L, "TOOL_CALL_LIMIT_EXCEEDED");
			return ToolResultBlock.of(toolCallParam.getToolUseBlock().getId(), getName(),
					TextBlock.builder().text(message).build(),
					Map.of(METADATA_BUSINESS_FAILED, true, "errorCode", "TOOL_CALL_LIMIT_EXCEEDED"));
		}
		return null;
	}

	/**
	 * 阶段：工具入参的出口校验。检索内容进入模型上下文时被 {@link UntrustedContentBoundary} 包裹，
	 * 合法的模型入参永远不需要携带这个哨兵；一旦出现，说明模型把包裹后的不可信内容原样转发进了工具调用
	 * ——最坏的形态就是把数据库里的一行文本直接喂给代码执行工具或外部 MCP 工具。因此这里失败关闭，
	 * 工具不执行。返回非 null 表示已拦截。
	 * <p>
	 * 这里只做结构性判断，不做「识别 ignore previous instructions 之类措辞」的启发式过滤：后者容易绕过，
	 * 又会误伤合法业务文本（工单正文引用这句话是正常的）。SQL 入参也不在此重复校验——S-4 的静态 SQL
	 * 守卫已独立做过 AST 重序列化、表/列白名单、函数黑名单与强制授权基表。
	 * <p>
	 * 校验对所有工具统一生效，因此代码执行工具一旦被注册进 Toolkit 就自动纳入，无需再改这里。
	 */
	private ToolResultBlock rejectByUntrustedContentEgress(ToolCallParam toolCallParam, ToolInvocation invocation) {
		if (!UntrustedContentBoundary.containsSentinel(invocation.payload())) {
			return null;
		}
		AgentRequest agentRequest = invocation.agentRequest();
		AgentRuntimeToolMetrics toolMetrics = invocation.toolMetrics();
		if (toolMetrics != null) {
			toolMetrics.recordFailure(invocation.sequenceNo(), UNTRUSTED_CONTENT_EGRESS_REJECTED,
					UNTRUSTED_CONTENT_EGRESS_MESSAGE);
		}
		long endEpochMs = System.currentTimeMillis();
		recordToolExecution(agentRequest, invocation.sequenceNo(), STATUS_FAILED, invocation.startEpochMs(), endEpochMs,
				elapsedMs(invocation.startNanos()), summarize(invocation.toolInput()), null,
				UNTRUSTED_CONTENT_EGRESS_REJECTED, UNTRUSTED_CONTENT_EGRESS_MESSAGE);
		// 入参本身不入日志：它按定义携带的就是租户业务数据。
		log.warn("Rejected tool call carrying untrusted-content boundary markers in its arguments. "
				+ "toolName={}, agentId={}, runtimeRequestId={}, argumentLength={}", getName(),
				agentRequest == null ? null : agentRequest.getAgentId(),
				agentRequest == null ? null : agentRequest.getRuntimeRequestId(),
				invocation.payload() == null ? 0 : invocation.payload().length());
		emitToolFinished(agentRequest, invocation.toolDisplayName(), STATUS_FAILED, 0L,
				UNTRUSTED_CONTENT_EGRESS_REJECTED);
		return ToolResultBlock.of(toolCallParam.getToolUseBlock().getId(), getName(),
				TextBlock.builder().text(UNTRUSTED_CONTENT_EGRESS_MESSAGE).build());
	}

	/** 阶段：执行工具并归类结果——业务失败与成功都要落执行流水、派发钩子并上报进度。 */
	private ToolResultBlock completeToolCall(ToolCallParam toolCallParam, ToolInvocation invocation) throws Exception {
		AgentRequest agentRequest = invocation.agentRequest();
		AgentRuntimeToolMetrics toolMetrics = invocation.toolMetrics();
		AgentRuntimeRequestMetadata requestMetadata = invocation.requestMetadata();
		Map<String, Object> toolInput = invocation.toolInput();
		String payload = invocation.payload();
		Integer sequenceNo = invocation.sequenceNo();
		String toolDisplayName = invocation.toolDisplayName();
		String cacheKey = resultCacheKey(payload);
		if (cacheKey != null) {
			String cached = toolResultCache.get(cacheKey);
			if (cached != null) {
				return cachedToolResult(toolCallParam, invocation, cached);
			}
		}
		String result = toolCallback.call(payload, toToolContext(toolCallParam));
		if (toolMetrics != null) {
			toolMetrics.recordToolResult(getName(), payload, result);
			toolMetrics.recordSearchExecutionOutcome(getName(), payload, isSearchExecutionFailure(result, null));
			toolMetrics.recordReadOnlyCallCompleted(getName(), payload,
					parseBusinessFailure(result) != null ? "BUSINESS_FAILED"
							: (result == null || result.isBlank() ? "EMPTY" : "NON_EMPTY"));
		}
		long endEpochMs = System.currentTimeMillis();
		long durationMs = elapsedMs(invocation.startNanos());
		if (toolMetrics != null) {
			toolMetrics.recordDuration(durationMs);
		}
		ToolFailure businessFailure = parseBusinessFailure(result);
		if (businessFailure != null) {
			String userFailureMessage = firstText(businessFailure.reason(),
					AgentRuntimeToolDisplayNameResolver.failureMessage(getName(), getDescription(), toolInput));
			if (toolMetrics != null) {
				toolMetrics.recordFailure(sequenceNo, businessFailure.errorCode(), userFailureMessage);
			}
			recordToolExecution(agentRequest, sequenceNo, STATUS_FAILED, invocation.startEpochMs(), endEpochMs,
					durationMs, summarize(toolInput), summarize(result), businessFailure.errorCode(),
					businessFailure.reason());
			dispatchToolFailure(agentRequest, toolCallParam, businessFailure);
			log.warn(
					"AgentScope tool execution finished with business failure. toolName={}, agentId={}, runtimeRequestId={}, durationMs={}, errorCode={}, reason={}",
					getName(), requestMetadata == null ? null : requestMetadata.agentId(),
					requestMetadata == null ? null : requestMetadata.runtimeRequestId(), durationMs,
					businessFailure.errorCode(), businessFailure.reason());
			emitToolFinished(agentRequest, toolDisplayName, STATUS_FAILED, durationMs,
					businessFailure.errorCode());
			return ToolResultBlock.of(toolCallParam.getToolUseBlock().getId(), getName(),
					TextBlock.builder().text(userFailureMessage).build(),
					Map.of(METADATA_BUSINESS_FAILED, true, "errorCode", businessFailure.errorCode()));
		}
		recordToolExecution(agentRequest, sequenceNo, STATUS_SUCCESS, invocation.startEpochMs(), endEpochMs, durationMs,
				summarize(toolInput), summarize(result), null, null);
		dispatchToolSuccess(agentRequest, toolCallParam, result);
		log.info("AgentScope tool execution finished. toolName={}, agentId={}, runtimeRequestId={}, success=true, durationMs={}",
				getName(), requestMetadata == null ? null : requestMetadata.agentId(),
				requestMetadata == null ? null : requestMetadata.runtimeRequestId(), durationMs);
		emitToolFinished(agentRequest, toolDisplayName, STATUS_SUCCESS, durationMs);
		// 结果回给模型前做源上裁剪（三层预算第一层）；指标、执行流水与钩子仍记录原始结果。
		String modelResult = ToolResultBudgetSanitizer.sanitize(result, toolResultMaxChars, toolResultHeadKeepChars);
		cacheToolResult(cacheKey, result, modelResult, durationMs);
		return ToolResultBlock.of(toolCallParam.getToolUseBlock().getId(), getName(),
				TextBlock.builder().text(modelResult == null ? "" : modelResult).build());
	}

	/**
	 * 缓存 key：工具名 + 入参整串 SHA-256。只在缓存开启且工具命中只读白名单时返回非 null。
	 * 不做参数语义规范化，同参不同序视为不同 key——保守正确优先，宁可少命中也不串结果。
	 */
	private String resultCacheKey(String payload) {
		if (toolResultCache == null) {
			return null;
		}
		String toolName = getName();
		if (toolName == null || !resultCacheableTools.contains(toolName)) {
			return null;
		}
		return toolName + ":" + SecureUtil.sha256(payload == null ? "" : payload);
	}

	/** 未命中后的写入：只缓存不抛异常、非空且长度可控的结果，值为裁剪后回给模型的最终字符串。 */
	private void cacheToolResult(String cacheKey, String result, String modelResult, long durationMs) {
		if (cacheKey == null || result == null || result.isBlank() || result.length() > MAX_CACHEABLE_RESULT_CHARS
				|| modelResult == null) {
			return;
		}
		toolResultCache.put(cacheKey, modelResult);
		log.debug("AgentScope tool result cached. toolName={}, cacheHit=false, durationMs={}", getName(), durationMs);
	}

	/**
	 * 缓存命中路径：跳过底层工具执行，直接复用上次回给模型的最终字符串。
	 * 预算与守卫已在前置阶段计数（tryRecordCall 等不受影响）；这里补记只读完成类别与耗时，
	 * 保证无进展守卫对命中调用与真实执行收敛行为一致。工具本次没有真实执行，不派发成功钩子、
	 * 不回写依赖原始结果的指标，避免裁剪后的缓存串污染按原始结果驱动的知识/语义命中判定。
	 */
	private ToolResultBlock cachedToolResult(ToolCallParam toolCallParam, ToolInvocation invocation, String cached) {
		long durationMs = elapsedMs(invocation.startNanos());
		AgentRequest agentRequest = invocation.agentRequest();
		AgentRuntimeToolMetrics toolMetrics = invocation.toolMetrics();
		if (toolMetrics != null) {
			toolMetrics.recordReadOnlyCallCompleted(getName(), invocation.payload(),
					parseBusinessFailure(cached) != null ? "BUSINESS_FAILED" : "NON_EMPTY");
			toolMetrics.recordDuration(durationMs);
		}
		recordToolExecution(agentRequest, invocation.sequenceNo(), STATUS_SUCCESS, invocation.startEpochMs(),
				System.currentTimeMillis(), durationMs, summarize(invocation.toolInput()), summarize(cached), null,
				null);
		log.debug("AgentScope tool result cache hit, skip tool execution. toolName={}, cacheHit=true, durationMs={}",
				getName(), durationMs);
		emitToolFinished(agentRequest, invocation.toolDisplayName(), STATUS_SUCCESS, durationMs);
		return ToolResultBlock.of(toolCallParam.getToolUseBlock().getId(), getName(),
				TextBlock.builder().text(cached).build());
	}

	/** 阶段：工具抛异常时的归类与落库。进度条用展示名；回给模型的是可纠错原因。 */
	private ToolResultBlock failToolCall(ToolCallParam toolCallParam, ToolInvocation invocation, Exception ex) {
		AgentRequest agentRequest = invocation.agentRequest();
		AgentRuntimeToolMetrics toolMetrics = invocation.toolMetrics();
		AgentRuntimeRequestMetadata requestMetadata = invocation.requestMetadata();
		Map<String, Object> toolInput = invocation.toolInput();
		Integer sequenceNo = invocation.sequenceNo();
		String toolDisplayName = invocation.toolDisplayName();
		if (toolMetrics != null) {
			toolMetrics.recordSearchExecutionOutcome(getName(), invocation.payload(), true);
			toolMetrics.recordReadOnlyCallCompleted(getName(), invocation.payload(), "ERROR");
		}
		long endEpochMs = System.currentTimeMillis();
		long durationMs = elapsedMs(invocation.startNanos());
		if (toolMetrics != null) {
			toolMetrics.recordDuration(durationMs);
		}
		ToolFailure toolFailure = parseToolFailure(ex);
		String userFailureMessage = AgentRuntimeToolDisplayNameResolver.failureMessage(getName(), getDescription(),
				toolInput);
		String modelFailureMessage = modelFacingFailureMessage(toolFailure, userFailureMessage, ex);
		if (toolMetrics != null) {
			toolMetrics.recordFailure(sequenceNo, toolFailure.errorCode(), userFailureMessage);
		}
		recordToolExecution(agentRequest, sequenceNo, STATUS_FAILED, invocation.startEpochMs(), endEpochMs, durationMs,
				summarize(toolInput), null, toolFailure.errorCode(), toolFailure.reason());
		dispatchToolFailure(agentRequest, toolCallParam, toolFailure);
		log.error(
				"AgentScope tool execution finished. toolName={}, agentId={}, runtimeRequestId={}, success=false, durationMs={}, errorCode={}, reason={}",
				getName(), requestMetadata == null ? null : requestMetadata.agentId(),
				requestMetadata == null ? null : requestMetadata.runtimeRequestId(), durationMs, toolFailure.errorCode(),
				toolFailure.reason(), ex);
		emitToolFinished(agentRequest, toolDisplayName, STATUS_FAILED, durationMs, toolFailure.errorCode());
		if (isUnknownOutcome(ex)) {
			String wrapUp = toolMetrics != null && !toolMetrics.allowSearchExecution(getName(), invocation.payload())
					? toolMetrics.searchExecutionNoProgressMessage()
					: "工具执行结果未知，请基于已有结果或材料作答，不要重复同一调用。";
			return ToolResultBlock.of(toolCallParam.getToolUseBlock().getId(), getName(),
					TextBlock.builder().text(wrapUp).build(),
					Map.of(METADATA_BUSINESS_FAILED, true, "errorCode", "OUTCOME_UNKNOWN"));
		}
		if (modelFailureMessage != null && !modelFailureMessage.isBlank()) {
			return ToolResultBlock.error(modelFailureMessage)
				.withIdAndName(toolCallParam.getToolUseBlock().getId(), getName());
		}
		return ToolResultBlock.error(ex.getMessage() == null ? "工具执行失败。" : ex.getMessage())
			.withIdAndName(toolCallParam.getToolUseBlock().getId(), getName());
	}

	private boolean isUnknownOutcome(Exception ex) {
		String message = ex == null ? null : ex.getMessage();
		if (message == null || message.isBlank()) {
			return false;
		}
		return message.contains("OUTCOME_UNKNOWN") || message.contains("禁止自动重试") || message.contains("外部调用结果未知");
	}

	private boolean isSearchExecutionFailure(String result, Exception error) {
		if (error != null) {
			return true;
		}
		if (result == null || result.isBlank()) {
			return false;
		}
		if (parseBusinessFailure(result) != null) {
			return true;
		}
		String lower = result.toLowerCase(Locale.ROOT);
		return result.contains("EXECUTION_FAILED") || result.contains("不被允许") || result.contains("SELECT *")
				|| lower.contains("datasource exploration failed");
	}

	/**
	 * 回给模型的失败文本要能纠错；回给用户的进度文案仍用 {@link AgentRuntimeToolDisplayNameResolver}。
	 * PostgreSQL {@code boolean = integer} 收成固定提示，不带表名。
	 */
	private String modelFacingFailureMessage(ToolFailure toolFailure, String fallback, Exception error) {
		if (PostgresBooleanLiteralNormalizer.isBooleanIntegerMismatch(error)
				|| PostgresBooleanLiteralNormalizer.isBooleanIntegerMismatch(toolFailure == null ? null
						: toolFailure.reason())) {
			return PostgresBooleanLiteralNormalizer.MODEL_HINT;
		}
		if (AgentModelToolName.isWebEvidenceTool(getName()) && toolFailure != null
				&& toolFailure.reason() != null && !toolFailure.reason().isBlank()) {
			return toolFailure.reason();
		}
		if (!AgentModelToolName.isDatasourceTool(getName()) || toolFailure == null
				|| toolFailure.reason() == null || toolFailure.reason().isBlank()) {
			return fallback;
		}
		String reason = toolFailure.reason().trim();
		String prefix = "Datasource exploration failed:";
		if (reason.startsWith(prefix)) {
			reason = reason.substring(prefix.length()).trim();
		}
		return reason.isBlank() ? fallback : reason;
	}

	private void emitToolRunning(AgentRequest request, String displayName) {
		if (runtimeProgressService != null) {
			runtimeProgressService.emitToolRunning(request, getName(), displayName);
		}
	}

	private void emitToolFinished(AgentRequest request, String displayName, String status, long durationMs) {
		emitToolFinished(request, displayName, status, durationMs, null);
	}

	private void emitToolFinished(AgentRequest request, String displayName, String status, long durationMs,
			String errorCode) {
		if (runtimeProgressService != null) {
			runtimeProgressService.emitToolFinished(request, getName(), displayName, status, durationMs, errorCode);
		}
	}

	private AgentRequest agentRequest(ToolCallParam toolCallParam, AgentRuntimeRequestMetadata requestMetadata) {
		if (toolCallParam != null && toolCallParam.getContext() != null) {
			AgentRequest request = toolCallParam.getContext().get("graphRequest", AgentRequest.class);
			if (request != null) {
				return request;
			}
		}
		if (requestMetadata == null) {
			return null;
		}
		return AgentRequest.builder()
			.agentId(requestMetadata.agentId())
			.threadId(requestMetadata.threadId())
			.runtimeRequestId(requestMetadata.runtimeRequestId())
			.humanFeedback(requestMetadata.humanFeedback())
			.humanFeedbackContent(requestMetadata.humanFeedbackContent())
			.build();
	}

	private Integer reserveToolExecutionSequenceNo(AgentRequest request) {
		if (answerTraceExplainStore == null || request == null) {
			return null;
		}
		return answerTraceExplainStore.reserveToolExecutionSequenceNo(request);
	}

	private void recordToolExecution(AgentRequest request, Integer sequenceNo, String status, long startEpochMs, long endEpochMs,
			long durationMs, String inputSummary, String outputSummary, String errorCode, String errorMessage) {
		if (answerTraceExplainStore == null || request == null) {
			return;
		}
		String summary = STATUS_SUCCESS.equals(status) ? "工具执行成功，耗时 %dms".formatted(durationMs)
				: "工具执行失败，耗时 %dms".formatted(durationMs);
		String detail = STATUS_SUCCESS.equals(status) ? outputSummary : errorMessage;
		answerTraceExplainStore.recordToolExecution(request, sequenceNo,
				new ToolExecutionRecord(getName(), status, startEpochMs, endEpochMs, durationMs, inputSummary,
						outputSummary, errorCode, errorMessage, summary, detail));
	}

	private void dispatchToolFailure(AgentRequest request, ToolCallParam toolCallParam, ToolFailure toolFailure) {
		if (runtimeHookDispatcher == null) {
			return;
		}
		try {
			runtimeHookDispatcher.dispatchAfterToolFailed(parseLong(request == null ? null : request.getAgentId()), null,
					null, getName(), request == null ? null : request.getThreadId(),
					request == null ? null : request.getRuntimeRequestId(), toolFailureInput(toolCallParam),
					toolFailureOutput(toolFailure));
		}
		catch (Exception ex) {
			log.warn("Failed to dispatch tool failure runtime hook. toolName={}, runtimeRequestId={}", getName(),
					request == null ? null : request.getRuntimeRequestId(), ex);
		}
	}

	private void dispatchToolSuccess(AgentRequest request, ToolCallParam toolCallParam, String result) {
		if (runtimeHookDispatcher == null) {
			return;
		}
		try {
			runtimeHookDispatcher.dispatchAfterToolSuccess(parseLong(request == null ? null : request.getAgentId()), null,
					null, getName(), request == null ? null : request.getThreadId(),
					request == null ? null : request.getRuntimeRequestId(), toolSuccessInput(toolCallParam),
					toolSuccessOutput(result));
		}
		catch (Exception ex) {
			log.warn("Failed to dispatch tool success runtime hook. toolName={}, runtimeRequestId={}", getName(),
					request == null ? null : request.getRuntimeRequestId(), ex);
		}
	}

	private Map<String, Object> toolSuccessInput(ToolCallParam toolCallParam) {
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("toolName", getName());
		input.put("arguments", toolCallParam == null ? Map.of() : toolCallParam.getInput());
		return input;
	}

	private Map<String, Object> toolSuccessOutput(String result) {
		Map<String, Object> output = new LinkedHashMap<>();
		output.put("message", "Tool execution succeeded.");
		output.put("result", result == null ? "" : result);
		return output;
	}

	private Map<String, Object> toolFailureInput(ToolCallParam toolCallParam) {
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("toolName", getName());
		input.put("arguments", toolCallParam == null ? Map.of() : toolCallParam.getInput());
		return input;
	}

	private Map<String, Object> toolFailureOutput(ToolFailure toolFailure) {
		Map<String, Object> output = new LinkedHashMap<>();
		output.put("message", toolFailure == null ? "Tool execution failed." : toolFailure.reason());
		output.put("errorCode", toolFailure == null ? null : toolFailure.errorCode());
		if (toolFailure != null && !toolFailure.details().isEmpty()) {
			output.put("details", toolFailure.details());
		}
		return output;
	}

	private Long parseLong(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Long.valueOf(value.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private AgentRuntimeRequestMetadata requestMetadata(ToolCallParam toolCallParam) {
		if (toolCallParam == null || toolCallParam.getContext() == null) {
			return null;
		}
		return toolCallParam.getContext().get(AgentRuntimeRequestMetadata.class);
	}

	private AgentRuntimeToolMetrics toolMetrics(ToolCallParam toolCallParam) {
		if (toolCallParam == null || toolCallParam.getContext() == null) {
			return null;
		}
		return toolCallParam.getContext().get(AgentRuntimeToolMetrics.class);
	}

	private ToolFailure parseToolFailure(Exception ex) {
		String message = ex == null ? null : ex.getMessage();
		if (message != null) {
			try {
				JsonNode node = objectMapper.readTree(message);
				if (node != null && node.isObject()) {
					String code = node.path("code").asText(ex.getClass().getSimpleName());
					String reason = node.path("message").asText(message);
					return new ToolFailure(abbreviate(code), abbreviate(reason));
				}
			}
			catch (Exception ignored) {
				// 工具异常不一定是 JSON，无法解析时使用异常类型和原始消息兜底。
			}
		}
		return new ToolFailure(ex == null ? "UNKNOWN" : ex.getClass().getSimpleName(), abbreviate(message));
	}

	private ToolFailure parseBusinessFailure(String result) {
		if (result == null || result.isBlank()) {
			return null;
		}
		try {
			JsonNode node = objectMapper.readTree(result);
			if (node == null || !node.isObject() || !isManagedToolResult(node)) {
				return null;
			}
			JsonNode data = node.path("data");
			boolean failed = isFailedStatus(text(node.get("status"))) || isFalse(node.get("success"))
					|| isFalse(data.get("success")) || isTrue(node.get("toolError")) || isTrue(data.get("toolError"));
			if (!failed) {
				return null;
			}
			String message = firstText(text(node.get("message")), text(data.get("message")), text(data.get("text")),
					"工具业务执行失败。");
			String reason = appendBusinessFailureFields(message, data);
			Map<String, Object> details = new LinkedHashMap<>();
			putIfPresent(details, "status", text(node.get("status")));
			putIfPresent(details, "message", message);
			putIfPresent(details, "missingFields", jsonValue(data.get("missingFields")));
			putIfPresent(details, "invalidFields", jsonValue(data.get("invalidFields")));
			return new ToolFailure("BUSINESS_FAILED", abbreviate(reason), details);
		}
		catch (Exception ex) {
			return null;
		}
	}

	private boolean isManagedToolResult(JsonNode node) {
		String eventType = text(node.path("data").get("eventType"));
		return AgentModelToolName.isSkillTool(getName()) || node.has("resourceKey")
				|| (eventType != null && (AgentModelToolName.isSkillTool(eventType) || eventType.startsWith("tool.")));
	}

	private boolean isFailedStatus(String status) {
		if (status == null) {
			return false;
		}
		return STATUS_FAILED.equalsIgnoreCase(status) || "unavailable".equalsIgnoreCase(status)
				|| "disabled".equalsIgnoreCase(status);
	}

	private boolean isFalse(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return false;
		}
		return node.isBoolean() ? !node.asBoolean() : "false".equalsIgnoreCase(node.asText());
	}

	private boolean isTrue(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return false;
		}
		return node.isBoolean() ? node.asBoolean() : "true".equalsIgnoreCase(node.asText());
	}

	private String appendBusinessFailureFields(String message, JsonNode data) {
		String missingFields = arrayText(data == null ? null : data.get("missingFields"));
		String invalidFields = arrayText(data == null ? null : data.get("invalidFields"));
		StringBuilder reason = new StringBuilder(firstText(message, "工具业务执行失败。"));
		if (missingFields != null) {
			reason.append(" missingFields=").append(missingFields);
		}
		if (invalidFields != null) {
			reason.append(" invalidFields=").append(invalidFields);
		}
		return reason.toString();
	}

	private String arrayText(JsonNode node) {
		if (node == null || !node.isArray() || node.isEmpty()) {
			return null;
		}
		return node.toString();
	}

	private Object jsonValue(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		return objectMapper.convertValue(node, Object.class);
	}

	private void putIfPresent(Map<String, Object> target, String key, Object value) {
		if (value != null) {
			target.put(key, value);
		}
	}

	private String firstText(String... values) {
		if (values == null) {
			return null;
		}
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value.trim();
			}
		}
		return null;
	}

	private String text(JsonNode node) {
		return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
	}

	private String abbreviate(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.replaceAll("\\s+", " ").trim();
		return normalized.length() <= 160 ? normalized : normalized.substring(0, 157) + "...";
	}

	private String summarize(Object value) {
		if (value == null) {
			return null;
		}
		try {
			JsonNode node = objectMapper.valueToTree(value);
			JsonNode sanitized = sanitizeNode(node);
			return limit(objectMapper.writeValueAsString(sanitized));
		}
		catch (Exception ex) {
			return limit(String.valueOf(value));
		}
	}

	private JsonNode sanitizeNode(JsonNode node) {
		if (node == null || node.isNull()) {
			return node;
		}
		if (node.isObject()) {
			ObjectNode sanitized = objectMapper.createObjectNode();
			node.fields().forEachRemaining(entry -> {
				if (isSensitiveKey(entry.getKey())) {
					sanitized.put(entry.getKey(), "***");
				}
				else {
					sanitized.set(entry.getKey(), sanitizeNode(entry.getValue()));
				}
			});
			return sanitized;
		}
		if (node.isArray()) {
			ArrayNode sanitized = objectMapper.createArrayNode();
			int count = 0;
			for (JsonNode item : node) {
				if (count++ >= 20) {
					break;
				}
				sanitized.add(sanitizeNode(item));
			}
			return sanitized;
		}
		return node;
	}

	private boolean isSensitiveKey(String key) {
		if (key == null) {
			return false;
		}
		String normalized = key.toLowerCase(Locale.ROOT).replace("-", "_").replace(".", "_");
		return normalized.contains("password") || normalized.contains("passwd") || normalized.contains("secret")
				|| normalized.contains("token") || normalized.contains("api_key") || normalized.contains("apikey")
				|| normalized.contains("access_key") || normalized.contains("authorization")
				|| normalized.contains("cookie") || normalized.contains("credential") || normalized.contains("signature");
	}

	private String limit(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.replaceAll("\\s+", " ").trim();
		return normalized.length() <= TOOL_SUMMARY_MAX_LENGTH ? normalized
				: normalized.substring(0, TOOL_SUMMARY_MAX_LENGTH - 3) + "...";
	}

	private long elapsedMs(long startNanos) {
		return java.time.Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
	}

	/** 单次工具调用的上下文快照：调用开始后即固定，供守卫、执行与失败归类三个阶段共用。 */
	private record ToolInvocation(long startNanos, long startEpochMs, Map<String, Object> toolInput, String payload,
			AgentRuntimeRequestMetadata requestMetadata, AgentRequest agentRequest, Integer sequenceNo,
			AgentRuntimeToolMetrics toolMetrics, String toolDisplayName) {
	}

	private record ToolFailure(String errorCode, String reason, Map<String, Object> details) {

		private ToolFailure(String errorCode, String reason) {
			this(errorCode, reason, Map.of());
		}

	}

	private ToolContext toToolContext(ToolCallParam toolCallParam) {
		Map<String, Object> contextMap = new LinkedHashMap<>();
		if (toolCallParam.getContext() != null) {
			contextMap.put("agentScopeContext", toolCallParam.getContext());
			ToolExecutionContext toolExecutionContext = toolCallParam.getContext();
			AgentRequest agentRequest = toolExecutionContext.get("graphRequest", AgentRequest.class);
			if (agentRequest != null) {
				contextMap.put("graphRequest", agentRequest);
			}
			AgentRuntimeRequestMetadata requestMetadata = toolExecutionContext.get(AgentRuntimeRequestMetadata.class);
			if (requestMetadata != null) {
				contextMap.put("runtimeRequestMetadata", requestMetadata);
			}
		}
		if (toolCallParam.getAgent() != null) {
			contextMap.put("agentScopeAgent", toolCallParam.getAgent());
		}
		if (toolCallParam.getEmitter() != null) {
			contextMap.put("agentScopeEmitter", toolCallParam.getEmitter());
		}
		if (toolCallParam.getToolUseBlock() != null && toolCallParam.getToolUseBlock().getName() != null) {
			contextMap.put("agentScopeToolName", toolCallParam.getToolUseBlock().getName());
		}
		contextMap.put("agentScopeToolInput", toolCallParam.getInput() == null ? Map.of() : toolCallParam.getInput());
		return new ToolContext(contextMap);
	}

}
