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
import com.sn68.agent.dataagent.agentscope.vo.AgentResponse;
import com.sn68.agent.dataagent.enums.TextType;
import com.sn68.agent.dataagent.multimodal.ExtractCard;
import com.sn68.agent.dataagent.multimodal.FusionEvent;
import com.sn68.agent.dataagent.multimodal.TurnArtifact;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeEventType;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeEventService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStateService;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Sinks;

/**
 * 维护运行时进度事件的订阅和广播，用于会话流式诊断展示。
 */
@Slf4j
@Service
public class AgentRuntimeProgressService {

	public static final String STREAM_EVENT_RUNTIME_PROGRESS = "runtime_progress";

	public static final String EVENT_TYPE_RUNTIME_PROGRESS = "runtime_progress";

	public static final String STATUS_RUNNING = "running";

	public static final String STATUS_SUCCESS = "success";

	public static final String STATUS_FAILED = "failed";

	public static final String STATUS_CANCELLED = "cancelled";

	public static final String STATUS_WAITING = "waiting";

	private static final String RUNTIME_NODE_NAME = "AgentScopeRuntime";

	private static final int MAX_TOOL_NAME_LENGTH = 80;

	private static final long DELTA_THROTTLE_MS = 400L;

	/**
	 * 单个工具执行耗时达到该阈值（毫秒）时，在 TOOL_FINISHED 之外补发一条 HINT 进度提示耐心等待，
	 * 避免长静默窗让用户以为会话卡死；与数据源查询分段日志、queryDurationMs 契约字段共用 5000ms 口径。
	 */
	private static final long SLOW_TOOL_WARN_MS = 5000L;

	private static final int TEXT_PREVIEW_LIMIT = 512;

	/**
	 * 参与 FLOW 回合步骤轨迹的阶段码：Resolver/节点的运行与完成事件加上四个终态事件。
	 * 卡片步骤与 SSE 进度时间线共用同一事实源，阶段码集合需与 DefaultFlowEngine 的发射口径保持一致。
	 */
	private static final Set<String> FLOW_STEP_STAGE_CODES = Set.of("FLOW_RESOLVER_RUNNING", "FLOW_RESOLVER_FINISHED",
			"FLOW_NODE_RUNNING", "FLOW_NODE_FINISHED", "FLOW_WAITING", "FLOW_INPUT_FAILED", "FLOW_FAILED",
			"FLOW_FINISHED");

	/** 终态阶段码：视图里只保留最后一条并恒为末尾，表达本回合结局。 */
	private static final Set<String> FLOW_TERMINAL_STAGE_CODES = Set.of("FLOW_WAITING", "FLOW_INPUT_FAILED",
			"FLOW_FAILED", "FLOW_FINISHED");

	/** 卡片步骤视图的非 state 条目上限，超出按序截断；终态不受影响。 */
	private static final int MAX_FLOW_STEP_VIEWS = 30;

	private static final Duration CHAT_LEASE_RENEW = Duration.ofMinutes(3);

	private final Map<String, RuntimeProgressHandle> handles = new ConcurrentHashMap<>();

	private final Map<String, Long> lastDeltaAt = new ConcurrentHashMap<>();

	private final Map<String, StringBuilder> assistantSnapshots = new ConcurrentHashMap<>();

	private final ObjectProvider<RuntimeEventService> eventService;

	private final ObjectProvider<RuntimeStateService> stateService;

	public AgentRuntimeProgressService() {
		this(null, null);
	}

	@Autowired
	public AgentRuntimeProgressService(ObjectProvider<RuntimeEventService> eventService,
			ObjectProvider<RuntimeStateService> stateService) {
		this.eventService = eventService;
		this.stateService = stateService;
	}

	/**
	 * 处理Agent运行时进度。
	 */
	public void register(AgentRequest request, Sinks.Many<ServerSentEvent<AgentResponse>> sink) {
		String key = key(request);
		if (key == null || sink == null) {
			return;
		}
		handles.put(key, new RuntimeProgressHandle(request.getAgentId(), request.getThreadId(),
				request.getRuntimeRequestId(), sink));
	}

	/**
	 * 处理Agent运行时进度。
	 */
	public void unregister(AgentRequest request) {
		String key = key(request);
		if (key != null) {
			handles.remove(key);
			lastDeltaAt.remove(key);
			assistantSnapshots.remove(key);
		}
	}

	public void registerOrchestrationChild(AgentRequest parentRequest, AgentRequest childRequest, Long runId,
			Long stepId, String collaboratorName, String collaboratorRole) {
		String parentKey = key(parentRequest);
		String childKey = key(childRequest);
		RuntimeProgressHandle parentHandle = parentKey == null ? null : handles.get(parentKey);
		if (parentHandle == null || childKey == null) {
			return;
		}
		Map<String, Object> details = new LinkedHashMap<>();
		if (runId != null) {
			details.put("orchestrationRunId", runId);
		}
		if (stepId != null) {
			details.put("orchestrationStepId", stepId);
		}
		putIfText(details, "collaboratorName", collaboratorName);
		putIfText(details, "collaboratorRole", collaboratorRole);
		putIfText(details, "childRuntimeRequestId", childRequest.getRuntimeRequestId());
		handles.put(childKey, parentHandle.child(details));
	}

	/**
	 * 处理Agent运行时进度。
	 */
	public void emit(AgentRequest request, String stageCode, String status) {
		emit(request, stageCode, status, null, null);
	}

	/**
	 * 处理Agent运行时进度。
	 */
	public void emit(AgentRequest request, String stageCode, String status, Long durationMs) {
		emit(request, stageCode, status, durationMs, null);
	}

	/**
	 * 处理Agent运行时进度。
	 */
	public void emitToolRunning(AgentRequest request, String toolName) {
		emitToolRunning(request, toolName, null);
	}

	/**
	 * 处理Agent运行时进度。
	 */
	public void emitToolRunning(AgentRequest request, String toolName, String displayName) {
		emit(request, "TOOL_RUNNING", STATUS_RUNNING, null, toolName,
				firstText(displayName, AgentRuntimeToolDisplayNameResolver.displayName(toolName)));
	}

	/**
	 * 处理Agent运行时进度。
	 */
	public void emitToolFinished(AgentRequest request, String toolName, String status, long durationMs) {
		emitToolFinished(request, toolName, null, status, durationMs);
	}

	/**
	 * 处理Agent运行时进度。
	 */
	public void emitToolFinished(AgentRequest request, String toolName, String displayName, String status, long durationMs) {
		emitToolFinished(request, toolName, displayName, status, durationMs, null);
	}

	public void emitToolFinished(AgentRequest request, String toolName, String displayName, String status,
			long durationMs, String errorCode) {
		Map<String, Object> details = new LinkedHashMap<>();
		putIfText(details, "errorCode", errorCode);
		emit(request, "TOOL_FINISHED", status, durationMs, toolName,
				firstText(displayName, AgentRuntimeToolDisplayNameResolver.displayName(toolName)), details);
		emitSlowToolHint(request, durationMs);
	}

	/**
	 * 工具执行达到 {@link #SLOW_TOOL_WARN_MS} 时补发一条 HINT 进度，durationMs 复用 TOOL_FINISHED 的参数值。
	 * 该方法被所有工具路径共用，因此 HINT 对全部工具生效；未注册会话（handle 为 null）时 emit 内部直接返回，无副作用。
	 * 文案放入 displayName（与 emitFlow/emitFusionTrace 的展示字段口径一致）；不携带 toolName，
	 * 避免干扰 TOOL_RUNNING/TOOL_FINISHED 的 toolExecutionSeq 配对；两次 emit 依次递增 seq，不会产生重复序号。
	 */
	private void emitSlowToolHint(AgentRequest request, long durationMs) {
		if (durationMs < SLOW_TOOL_WARN_MS) {
			return;
		}
		emit(request, "HINT", STATUS_SUCCESS, durationMs, null,
				"工具执行耗时 " + String.format(Locale.ROOT, "%.1f", durationMs / 1000.0) + " 秒");
	}

	/**
	 * 通用 HINT 进度。displayName 直接给前端展示；不携带 toolName，避免干扰 TOOL_RUNNING/FINISHED 配对。
	 * 旧前端若未识别某条专用 stageCode，仍能显示 HINT 文案。
	 */
	public void emitHint(AgentRequest request, String displayName, long durationMs, Map<String, Object> details) {
		if (!StringUtils.hasText(displayName)) {
			return;
		}
		emit(request, "HINT", STATUS_SUCCESS, durationMs, null, displayName, details);
	}

	/**
	 * 发布 FLOW 专用进度，保留 Resolver、节点和流程实例定位信息，便于前端展示及运行时排障。
	 */
	public void emitFlow(AgentRequest request, String flowInstanceId, String nodeId, String resolverId,
			String stageCode, String status, Long durationMs, String displayName) {
		Map<String, Object> details = new LinkedHashMap<>();
		putIfText(details, "flowInstanceId", flowInstanceId);
		putIfText(details, "nodeId", nodeId);
		putIfText(details, "resolverId", resolverId);
		emit(request, stageCode, status, durationMs, null, displayName, details);
	}

	/**
	 * 可选 fusion_trace：页数/表数/留图数/token 估算进进度 details，不把抽取正文或图片字节送进用户气泡。
	 */
	public void emitFusionTrace(AgentRequest request, TurnArtifact artifact) {
		emitFusionTrace(request, artifact, request == null ? null : request.getExtractCard(), null);
	}

	public void emitFusionTrace(AgentRequest request, TurnArtifact artifact, ExtractCard card, Long visionExtractMs) {
		if (artifact == null) {
			return;
		}
		Map<String, Object> details = new LinkedHashMap<>();
		putIfText(details, "artifactId", artifact.artifactId());
		putIfText(details, "routeSummary", artifact.routeSummary());
		details.put("totalTokensEstimate", artifact.totalTokensEstimate());
		details.put("eventCount", artifact.events().size());
		details.put("keepAsImage", artifact.hasKeepAsImage());
		details.put("extractedContextLength", artifact.extractedContext().length());
		details.put("chartsKept", keepAsImageCount(artifact));
		if (card != null) {
			putIfText(details, "extractStatus", card.extractStatus());
			details.put("cardFields", card.fieldCount());
			putIfText(details, "unreadReason", card.unreadReason());
		}
		if (visionExtractMs != null) {
			details.put("visionExtractMs", visionExtractMs);
		}
		long durationMs = putFusionEventTraces(details, artifact.events());
		emit(request, "FUSION_TRACE", STATUS_SUCCESS, durationMs > 0L ? durationMs : null, null, "fusion_trace",
				details);
	}

	/**
	 * FLOW 回合步骤视图：kind ∈ tool|node|state，label 为展示名，status/durationMs 与进度事件一致。
	 */
	public record FlowStepView(String kind, String label, String status, Long durationMs) {
	}

	/**
	 * 输出本回合的 FLOW 步骤视图，随 skill-flow 卡片透出给用户；与 SSE 进度事件共用同一事实源。
	 * 未注册会话（handle 不存在）时返回空列表。
	 */
	public List<FlowStepView> flowSteps(AgentRequest request) {
		String key = key(request);
		RuntimeProgressHandle handle = key == null ? null : handles.get(key);
		if (handle == null) {
			return List.of();
		}
		List<Map<String, Object>> trace;
		// 读取与 emit 共用 handle 锁：Resolver 在 worker 线程并发追加轨迹，快照拷贝避免读到中间状态。
		synchronized (handle) {
			trace = new ArrayList<>(handle.flowStepTrace);
		}
		return buildFlowStepViews(trace);
	}

	/**
	 * 纯函数：把 FLOW 步骤轨迹压缩为去重视图。
	 * FINISHED（工具/节点）全量按序保留（含重试历史）；RUNNING 一律丢弃——同 key 有后续 FINISHED 时被其吸收，
	 * 无后续 FINISHED 时由终态表达结局；终态只保留最后一条并恒为视图末尾；非 state 条目按序截断至前 30 条。
	 */
	private static List<FlowStepView> buildFlowStepViews(List<Map<String, Object>> trace) {
		Map<String, Object> lastState = null;
		for (Map<String, Object> step : trace) {
			if (FLOW_TERMINAL_STAGE_CODES.contains(String.valueOf(step.get("stageCode")))) {
				lastState = step;
			}
		}
		List<FlowStepView> views = new ArrayList<>();
		for (Map<String, Object> step : trace) {
			String stageCode = String.valueOf(step.get("stageCode"));
			if (FLOW_TERMINAL_STAGE_CODES.contains(stageCode) || stageCode.endsWith("_RUNNING")
					|| views.size() >= MAX_FLOW_STEP_VIEWS) {
				continue;
			}
			views.add(new FlowStepView("FLOW_RESOLVER_FINISHED".equals(stageCode) ? "tool" : "node",
					flowStepLabel(step), (String) step.get("status"), (Long) step.get("durationMs")));
		}
		if (lastState != null) {
			views.add(new FlowStepView("state", flowStepLabel(lastState), (String) lastState.get("status"),
					(Long) lastState.get("durationMs")));
		}
		return List.copyOf(views);
	}

	private static String flowStepLabel(Map<String, Object> step) {
		if (step.get("displayName") instanceof String displayName && StringUtils.hasText(displayName)) {
			return displayName;
		}
		return String.valueOf(step.get("stageCode"));
	}

	private void emit(AgentRequest request, String stageCode, String status, Long durationMs, String toolName) {
		emit(request, stageCode, status, durationMs, toolName, null);
	}

	private void emit(AgentRequest request, String stageCode, String status, Long durationMs, String toolName,
			String displayName) {
		emit(request, stageCode, status, durationMs, toolName, displayName, Map.of());
	}

	public void emit(AgentRequest request, String stageCode, String status, Long durationMs, String toolName,
			String displayName, Map<String, Object> details) {
		if (!StringUtils.hasText(stageCode)) {
			return;
		}
		String key = key(request);
		RuntimeProgressHandle handle = key == null ? null : handles.get(key);
		if (handle == null) {
			return;
		}
		Map<String, Object> merged = details == null ? new LinkedHashMap<>() : new LinkedHashMap<>(details);
		if (request != null && request.getDurableRunId() != null) {
			merged.put("runtimeRunId", request.getDurableRunId());
		}
		persistQuiet(request, "PROGRESS:" + request.getRuntimeRequestId() + ":" + stageCode + ":"
				+ (handle.sequence.get() + 1), RuntimeEventType.RUNTIME_PROGRESS, merged);
		handle.emit(stageCode, status, durationMs, safeToolName(toolName), safeToolName(displayName), merged);
	}

	/**
	 * 受理用户消息后落 USER_MESSAGE 事件，刷新恢复时提问气泡由服务端事件保证在场。
	 */
	public void recordAcceptedUserMessage(AgentRequest request) {
		if (request == null || !StringUtils.hasText(request.getQuery())) {
			return;
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("role", "user");
		payload.put("preview", preview(request.getQuery()));
		persistQuiet(request, "USER_MESSAGE:" + request.getRuntimeRequestId(), RuntimeEventType.USER_MESSAGE, payload);
	}

	/**
	 * 助手正文节流落库：DELTA 可 quiet；同一窗口再写 SNAPSHOT，重连跳过 DELTA 只放快照。
	 */
	public void recordAssistantDelta(AgentRequest request, String delta) {
		if (request == null || !StringUtils.hasText(delta)) {
			return;
		}
		String key = key(request);
		if (key == null) {
			return;
		}
		assistantSnapshots.computeIfAbsent(key, ignored -> new StringBuilder()).append(delta);
		long now = System.currentTimeMillis();
		Long previous = lastDeltaAt.get(key);
		if (previous != null && now - previous < DELTA_THROTTLE_MS) {
			return;
		}
		lastDeltaAt.put(key, now);
		long bucket = now / DELTA_THROTTLE_MS;
		Long fence = request.getFenceToken();
		Map<String, Object> deltaPayload = new LinkedHashMap<>();
		deltaPayload.put("preview", preview(delta));
		persistQuiet(request, "DELTA:" + (fence == null ? "0" : fence) + ":" + bucket, RuntimeEventType.ASSISTANT_DELTA,
				deltaPayload);
		Map<String, Object> snapshotPayload = new LinkedHashMap<>();
		snapshotPayload.put("text", assistantSnapshots.get(key).toString());
		persistQuiet(request, "SNAPSHOT:" + request.getRuntimeRequestId() + ":" + bucket,
				RuntimeEventType.ASSISTANT_SNAPSHOT, snapshotPayload);
	}

	private void persistQuiet(AgentRequest request, String eventKey, RuntimeEventType eventType,
			Map<String, Object> payload) {
		RuntimeEventService events = eventService == null ? null : eventService.getIfAvailable();
		if (events == null || request == null || request.getDurableRunId() == null || request.getFenceToken() == null
				|| !StringUtils.hasText(request.getLeaseOwner()) || !StringUtils.hasText(eventKey)) {
			return;
		}
		try {
			events.appendFenced(request.getTenantIdSnapshot(), request.getDurableRunId(), eventKey, eventType,
					request.getRuntimeRequestId(), payload, request.getFenceToken(), request.getLeaseOwner());
		}
		catch (RuntimeException ex) {
			log.error("会话运行事件落库失败. runId={}, eventKey={}, eventType={}", request.getDurableRunId(), eventKey,
					eventType, ex);
		}
		renewChatLease(request);
	}

	private void renewChatLease(AgentRequest request) {
		RuntimeStateService states = stateService == null ? null : stateService.getIfAvailable();
		if (states == null || request == null || request.getDurableRunId() == null || request.getFenceToken() == null
				|| !StringUtils.hasText(request.getLeaseOwner())) {
			return;
		}
		try {
			states.renewRunLease(request.getDurableRunId(), request.getLeaseOwner(), request.getFenceToken(),
					CHAT_LEASE_RENEW);
		}
		catch (RuntimeException ex) {
			log.warn("会话运行租约续期异常. runId={}", request.getDurableRunId(), ex);
		}
	}

	private static String preview(String text) {
		if (!StringUtils.hasText(text)) {
			return "";
		}
		String trimmed = text.trim();
		return trimmed.length() <= TEXT_PREVIEW_LIMIT ? trimmed : trimmed.substring(0, TEXT_PREVIEW_LIMIT);
	}

	private long putFusionEventTraces(Map<String, Object> details, List<FusionEvent> fusionEvents) {
		if (fusionEvents == null || fusionEvents.isEmpty()) {
			return 0L;
		}
		List<Map<String, Object>> eventTraces = new ArrayList<>();
		int pages = 0;
		int tables = 0;
		int chartsDropped = 0;
		boolean hasPages = false;
		boolean hasTables = false;
		boolean hasChartsDropped = false;
		long processingMs = 0L;
		for (FusionEvent event : fusionEvents) {
			if (event == null) {
				continue;
			}
			Map<String, Object> item = new LinkedHashMap<>();
			if (event.modality() != null) {
				item.put("modality", event.modality().name());
			}
			putIfText(item, "method", event.method());
			item.put("tokensOut", event.tokensOut());
			item.put("processingMs", event.processingMs());
			processingMs += Math.max(0L, event.processingMs());
			if (event.pages() != null) {
				item.put("pages", event.pages());
				pages += event.pages();
				hasPages = true;
			}
			if (event.tables() != null) {
				item.put("tables", event.tables());
				tables += event.tables();
				hasTables = true;
			}
			if (event.chartsKept() != null) {
				item.put("chartsKept", event.chartsKept());
			}
			if (event.chartsDropped() != null) {
				item.put("chartsDropped", event.chartsDropped());
				chartsDropped += event.chartsDropped();
				hasChartsDropped = true;
			}
			eventTraces.add(item);
		}
		if (hasPages) {
			details.put("pages", pages);
		}
		if (hasTables) {
			details.put("tables", tables);
		}
		if (hasChartsDropped) {
			details.put("chartsDropped", chartsDropped);
		}
		if (!eventTraces.isEmpty()) {
			details.put("events", eventTraces);
		}
		return processingMs;
	}

	private static int keepAsImageCount(TurnArtifact artifact) {
		return (int) artifact.blocks().stream().filter(block -> block != null && block.keepAsImage()).count();
	}

	private void putIfText(Map<String, Object> target, String key, String value) {
		if (StringUtils.hasText(value)) {
			target.put(key, value.trim());
		}
	}

	private String key(AgentRequest request) {
		if (request == null) {
			return null;
		}
		return key(request.getThreadId(), request.getRuntimeRequestId());
	}

	private String key(String threadId, String runtimeRequestId) {
		if (!StringUtils.hasText(threadId) || !StringUtils.hasText(runtimeRequestId)) {
			return null;
		}
		return threadId + ":" + runtimeRequestId;
	}

	private String safeToolName(String toolName) {
		if (!StringUtils.hasText(toolName)) {
			return null;
		}
		String sanitized = toolName.trim().replaceAll("[^\\p{L}\\p{N}_.: -]", "_");
		if (sanitized.length() <= MAX_TOOL_NAME_LENGTH) {
			return sanitized;
		}
		return sanitized.substring(0, MAX_TOOL_NAME_LENGTH);
	}

	private String firstText(String... values) {
		if (values == null) {
			return null;
		}
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value.trim();
			}
		}
		return null;
	}

	private static final class RuntimeProgressHandle {

		private final String agentId;

		private final String threadId;

		private final String runtimeRequestId;

		private final Sinks.Many<ServerSentEvent<AgentResponse>> sink;

		private final long startedAtMs;

		private final AtomicLong sequence;

		private final Map<String, Object> baseDetails;

		private final Map<String, Deque<Long>> toolExecutionSequences = new HashMap<>();

		/** FLOW 回合步骤轨迹，供 skill-flow 卡片 steps 展示；只在 emit 的同步块内追加。 */
		private final List<Map<String, Object>> flowStepTrace = new ArrayList<>();

		private RuntimeProgressHandle(String agentId, String threadId, String runtimeRequestId,
				Sinks.Many<ServerSentEvent<AgentResponse>> sink) {
			this(agentId, threadId, runtimeRequestId, sink, System.currentTimeMillis(), new AtomicLong(), Map.of());
		}

		private RuntimeProgressHandle(String agentId, String threadId, String runtimeRequestId,
				Sinks.Many<ServerSentEvent<AgentResponse>> sink, long startedAtMs, AtomicLong sequence,
				Map<String, Object> baseDetails) {
			this.agentId = agentId;
			this.threadId = threadId;
			this.runtimeRequestId = runtimeRequestId;
			this.sink = sink;
			this.startedAtMs = startedAtMs;
			this.sequence = sequence;
			this.baseDetails = baseDetails == null ? Map.of() : Map.copyOf(baseDetails);
		}

		private RuntimeProgressHandle child(Map<String, Object> details) {
			return new RuntimeProgressHandle(agentId, threadId, runtimeRequestId, sink, startedAtMs, sequence, details);
		}

		private synchronized void emit(String stageCode, String status, Long durationMs, String toolName,
				String displayName, Map<String, Object> details) {
			// 追加必须在同步块内：Resolver 在 worker 线程 emit，与 flowSteps 的快照读取共用本方法锁。
			recordFlowStep(stageCode, status, durationMs, displayName, details);
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("eventType", EVENT_TYPE_RUNTIME_PROGRESS);
			metadata.put("runtimeRequestId", runtimeRequestId);
			long eventSequence = sequence.incrementAndGet();
			metadata.put("seq", eventSequence);
			metadata.put("stageCode", stageCode);
			metadata.put("status", StringUtils.hasText(status) ? status : STATUS_RUNNING);
			metadata.put("elapsedMs", Math.max(0L, System.currentTimeMillis() - startedAtMs));
			metadata.put("durationMs", durationMs);
			metadata.put("displayName", StringUtils.hasText(displayName) ? displayName
					: (StringUtils.hasText(toolName) ? AgentRuntimeToolDisplayNameResolver.displayName(toolName)
							: stageCode));
			if (!baseDetails.isEmpty()) {
				metadata.putAll(baseDetails);
			}
			if (details != null && !details.isEmpty()) {
				metadata.putAll(details);
			}
			if (StringUtils.hasText(toolName)) {
				Deque<Long> executions = toolExecutionSequences.computeIfAbsent(toolName,
						ignored -> new ArrayDeque<>());
				if ("TOOL_RUNNING".equals(stageCode)) {
					executions.addLast(eventSequence);
				}
				Long toolExecutionSeq = "TOOL_FINISHED".equals(stageCode) ? executions.pollFirst()
						: Long.valueOf(eventSequence);
				if (toolExecutionSeq != null) {
					metadata.put("toolExecutionSeq", toolExecutionSeq);
				}
				if (executions.isEmpty()) {
					toolExecutionSequences.remove(toolName);
				}
			}
			AgentResponse response = AgentResponse.builder()
				.agentId(agentId)
				.threadId(threadId)
				.nodeName(RUNTIME_NODE_NAME)
				.textType(TextType.TEXT)
				.text("")
				.metadata(metadata)
				.build();
			Sinks.EmitResult result;
			synchronized (sink) {
				result = sink.tryEmitNext(ServerSentEvent.builder(response).event(STREAM_EVENT_RUNTIME_PROGRESS).build());
			}
			if (result.isFailure()) {
				log.debug("Runtime progress event dropped. threadId={}, runtimeRequestId={}, stageCode={}, result={}",
						threadId, runtimeRequestId, stageCode, result);
			}
		}

		private void recordFlowStep(String stageCode, String status, Long durationMs, String displayName,
				Map<String, Object> details) {
			if (!FLOW_STEP_STAGE_CODES.contains(stageCode)) {
				return;
			}
			Map<String, Object> step = new LinkedHashMap<>();
			step.put("stageCode", stageCode);
			step.put("status", status);
			step.put("durationMs", durationMs);
			step.put("displayName", displayName);
			step.put("resolverId", details == null ? null : details.get("resolverId"));
			step.put("nodeId", details == null ? null : details.get("nodeId"));
			flowStepTrace.add(step);
		}

	}

}
