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
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeProgressService;
import com.sn68.agent.dataagent.agentscope.vo.AgentResponse;
import com.sn68.agent.dataagent.constant.Constant;
import com.sn68.agent.dataagent.enums.TextType;
import com.sn68.agent.dataagent.ui.AgentUiMessage;
import com.sn68.agent.dataagent.ui.ToolConfirmUiAssembler;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;

/**
 * 将 AgentScope 2.0 {@code streamEvents()} 映射为现有 SSE 契约。
 * 浏览器只允许看到 {@code message}/{@code complete}/{@code error}/{@code runtime_progress}。
 * <p>阶段事件（思考/工具调用/数据）按状态机收敛：只在状态迁移点（阶段 START、TOOL_RESULT_END、
 * MODEL_CALL_END）发 runtime_progress，DELTA 级事件一律吞噬，避免逐事件透传造成下游重复持久化；
 * 若阶段未 START 就先收到 DELTA/END，视为隐式 START 先补发一条 START 防丢。
 * 工具展示名不在本类做映射，由下游经 Resolver 处理。
 */
@Slf4j
public class V2EventToAgentResponseMapper {

	public static final String STREAM_EVENT_MESSAGE = "message";

	public static final Set<String> FRONTEND_EVENT_NAMES = Set.of(STREAM_EVENT_MESSAGE, Constant.STREAM_EVENT_COMPLETE,
			Constant.STREAM_EVENT_ERROR, AgentRuntimeProgressService.STREAM_EVENT_RUNTIME_PROGRESS);

	private static final String RUNTIME_NODE_NAME = "AgentScopeRuntime";

	private static final String UNKNOWN_STAGE_CODE = "AGENT_PROGRESS";

	private static final String STAGE_THINKING = "THINKING";

	private static final String STAGE_TOOL_RUNNING = "TOOL_RUNNING";

	private static final String STAGE_TOOL_FINISHED = "TOOL_FINISHED";

	private static final String STAGE_MODEL_CALL = "MODEL_CALL";

	private static final String STAGE_DATA = "DATA";

	private static final ToolEventInfo NO_TOOL_INFO = new ToolEventInfo(null, null);

	private final AtomicLong unknownEventWarnCount = new AtomicLong();

	public long unknownEventWarnCount() {
		return unknownEventWarnCount.get();
	}

	public List<ServerSentEvent<AgentResponse>> map(AgentEvent event, AgentRequest request) {
		return map(event, request, new StreamMapState());
	}

	public List<ServerSentEvent<AgentResponse>> map(AgentEvent event, AgentRequest request, StreamMapState state) {
		if (event == null) {
			return List.of();
		}
		AgentEventType type = event.getType();
		if (type == null) {
			return List.of(unknownProgress(event, request));
		}
		StreamMapState streamState = state == null ? new StreamMapState() : state;
		return switch (type) {
			case TEXT_BLOCK_DELTA -> textDeltaMessage(request, streamState, textDelta(event));
			case AGENT_RESULT -> messageFromResult(event, request, streamState);
			case AGENT_END, REQUEST_STOP -> List.of(complete(request));
			case EXCEED_MAX_ITERS -> List.of(error(request, "已达到最大推理轮次"));
			case ALL_TOOLS_DENIED -> List.of(error(request, "工具调用被拒绝"));
			case TEXT_BLOCK_START, TEXT_BLOCK_END -> List.of();
			case TOOL_CALL_START -> toolCallStart(request, streamState, toolInfo(event));
			case TOOL_CALL_DELTA, TOOL_CALL_END -> stageDeltaOrEmpty(request, streamState, STAGE_TOOL_RUNNING,
					toolInfo(event));
			case TOOL_RESULT_END -> toolFinishedProgress(request, event);
			case TOOL_RESULT_START, TOOL_RESULT_TEXT_DELTA, TOOL_RESULT_DATA_DELTA -> List.of();
			case THINKING_BLOCK_START -> stageStart(request, streamState, STAGE_THINKING);
			case THINKING_BLOCK_DELTA, THINKING_BLOCK_END -> stageDeltaOrEmpty(request, streamState, STAGE_THINKING,
					NO_TOOL_INFO);
			case MODEL_CALL_START -> List
				.of(progress(request, STAGE_MODEL_CALL, AgentRuntimeProgressService.STATUS_RUNNING));
			case MODEL_CALL_END -> List
				.of(progress(request, STAGE_MODEL_CALL, AgentRuntimeProgressService.STATUS_SUCCESS));
			case AGENT_START -> List.of(progress(request, "AGENT_ENTERED", AgentRuntimeProgressService.STATUS_RUNNING));
			case DATA_BLOCK_START -> stageStart(request, streamState, STAGE_DATA);
			case DATA_BLOCK_DELTA, DATA_BLOCK_END -> stageDeltaOrEmpty(request, streamState, STAGE_DATA, NO_TOOL_INFO);
			case REQUIRE_USER_CONFIRM -> requireUserConfirm(event, request);
			case REQUIRE_EXTERNAL_EXECUTION, USER_CONFIRM_RESULT, EXTERNAL_EXECUTION_RESULT -> List
				.of(progress(request, "WAITING_CONFIRM", AgentRuntimeProgressService.STATUS_WAITING));
			case SUBAGENT_EXPOSED -> List.of(progress(request, "SUBAGENT", AgentRuntimeProgressService.STATUS_RUNNING));
			case HINT_BLOCK -> List.of(progress(request, "HINT", AgentRuntimeProgressService.STATUS_RUNNING));
			case CUSTOM -> List.of(unknownProgress(event, request));
			default -> List.of(unknownProgress(event, request));
		};
	}

	public ServerSentEvent<AgentResponse> mapError(AgentRequest request, Throwable error) {
		String text = error == null || !StringUtils.hasText(error.getMessage()) ? "智能体运行失败" : error.getMessage();
		return error(request, text);
	}

	private List<ServerSentEvent<AgentResponse>> requireUserConfirm(AgentEvent event, AgentRequest request) {
		List<ServerSentEvent<AgentResponse>> events = new ArrayList<>();
		events.add(progress(request, "WAITING_CONFIRM", AgentRuntimeProgressService.STATUS_WAITING));
		if (!(event instanceof RequireUserConfirmEvent confirm) || confirm.getToolCalls() == null
				|| confirm.getToolCalls().isEmpty()) {
			return events;
		}
		Instant expiresAt = ToolConfirmUiAssembler.expiresAt(Instant.now());
		for (ToolUseBlock tool : confirm.getToolCalls()) {
			if (tool == null || !StringUtils.hasText(tool.getId())) {
				continue;
			}
			AgentUiMessage ui = ToolConfirmUiAssembler.fromToolCall(
					request == null ? null : request.getRuntimeRequestId(), confirm.getReplyId(), tool.getId(),
					tool.getName(), tool.getInput(), expiresAt);
			Map<String, Object> metadata = ToolConfirmUiAssembler.toMetadata(ui);
			if (request != null && StringUtils.hasText(request.getRuntimeRequestId())) {
				metadata.put("runtimeRequestId", request.getRuntimeRequestId());
			}
			String text = ui.content() == null ? "" : ui.content().text();
			events.add(ServerSentEvent.builder(AgentResponse.builder()
				.agentId(agentId(request))
				.threadId(threadId(request))
				.nodeName(RUNTIME_NODE_NAME)
				.textType(TextType.JSON)
				.text(text)
				.metadata(metadata)
				.build()).event(STREAM_EVENT_MESSAGE).build());
		}
		return events;
	}

	private List<ServerSentEvent<AgentResponse>> textDeltaMessage(AgentRequest request, StreamMapState state,
			String text) {
		if (!StringUtils.hasText(text)) {
			return List.of();
		}
		state.markTextDelta();
		return List.of(message(request, text));
	}

	private List<ServerSentEvent<AgentResponse>> messageOrEmpty(AgentRequest request, String text) {
		if (!StringUtils.hasText(text)) {
			return List.of();
		}
		return List.of(message(request, text));
	}

	private List<ServerSentEvent<AgentResponse>> messageFromResult(AgentEvent event, AgentRequest request,
			StreamMapState state) {
		if (state.seenTextDelta()) {
			return List.of();
		}
		if (!(event instanceof AgentResultEvent resultEvent) || resultEvent.getResult() == null) {
			return List.of();
		}
		return messageOrEmpty(request, resultEvent.getResult().getTextContent());
	}

	private String textDelta(AgentEvent event) {
		if (event instanceof TextBlockDeltaEvent deltaEvent) {
			return deltaEvent.getDelta();
		}
		return null;
	}

	private List<ServerSentEvent<AgentResponse>> stageStart(AgentRequest request, StreamMapState state, String stage) {
		state.markStageOpen(stage);
		return List.of(progress(request, stage, AgentRuntimeProgressService.STATUS_RUNNING));
	}

	private List<ServerSentEvent<AgentResponse>> stageDeltaOrEmpty(AgentRequest request, StreamMapState state,
			String stage, ToolEventInfo info) {
		if (state.isStageOpen(stage)) {
			return List.of();
		}
		state.markStageOpen(stage);
		return List.of(progress(request, stage, AgentRuntimeProgressService.STATUS_RUNNING, info.toolName(),
				info.toolCallId()));
	}

	private List<ServerSentEvent<AgentResponse>> toolCallStart(AgentRequest request, StreamMapState state,
			ToolEventInfo info) {
		state.markStageOpen(STAGE_TOOL_RUNNING);
		return List.of(progress(request, STAGE_TOOL_RUNNING, AgentRuntimeProgressService.STATUS_RUNNING,
				info.toolName(), info.toolCallId()));
	}

	private List<ServerSentEvent<AgentResponse>> toolFinishedProgress(AgentRequest request, AgentEvent event) {
		ToolEventInfo info = toolInfo(event);
		boolean success = event instanceof ToolResultEndEvent end && end.getState() == ToolResultState.SUCCESS;
		String status = success ? AgentRuntimeProgressService.STATUS_SUCCESS : AgentRuntimeProgressService.STATUS_FAILED;
		return List.of(progress(request, STAGE_TOOL_FINISHED, status, info.toolName(), info.toolCallId()));
	}

	private ToolEventInfo toolInfo(AgentEvent event) {
		if (event instanceof ToolCallStartEvent start) {
			return new ToolEventInfo(start.getToolCallName(), start.getToolCallId());
		}
		if (event instanceof ToolCallDeltaEvent delta) {
			return new ToolEventInfo(delta.getToolCallName(), delta.getToolCallId());
		}
		if (event instanceof ToolCallEndEvent end) {
			return new ToolEventInfo(end.getToolCallName(), end.getToolCallId());
		}
		if (event instanceof ToolResultEndEvent resultEnd) {
			return new ToolEventInfo(resultEnd.getToolCallName(), resultEnd.getToolCallId());
		}
		return NO_TOOL_INFO;
	}

	private ServerSentEvent<AgentResponse> unknownProgress(AgentEvent event, AgentRequest request) {
		unknownEventWarnCount.incrementAndGet();
		log.warn("Unmapped AgentScope 2.0 event dropped from frontend contract. eventId={}", event.getId());
		return progress(request, UNKNOWN_STAGE_CODE, AgentRuntimeProgressService.STATUS_RUNNING);
	}

	private ServerSentEvent<AgentResponse> message(AgentRequest request, String text) {
		return ServerSentEvent.builder(AgentResponse.builder()
			.agentId(agentId(request))
			.threadId(threadId(request))
			.nodeName(RUNTIME_NODE_NAME)
			.textType(TextType.TEXT)
			.text(text)
			.metadata(baseMetadata(request))
			.build()).event(STREAM_EVENT_MESSAGE).build();
	}

	private ServerSentEvent<AgentResponse> complete(AgentRequest request) {
		return ServerSentEvent.builder(AgentResponse.complete(agentId(request), threadId(request)))
			.event(Constant.STREAM_EVENT_COMPLETE)
			.build();
	}

	private ServerSentEvent<AgentResponse> error(AgentRequest request, String text) {
		return ServerSentEvent.builder(AgentResponse.error(agentId(request), threadId(request), text))
			.event(Constant.STREAM_EVENT_ERROR)
			.build();
	}

	private ServerSentEvent<AgentResponse> progress(AgentRequest request, String stageCode, String status) {
		return progress(request, stageCode, status, null, null);
	}

	private ServerSentEvent<AgentResponse> progress(AgentRequest request, String stageCode, String status,
			String toolName, String toolCallId) {
		Map<String, Object> metadata = baseMetadata(request);
		metadata.put("eventType", AgentRuntimeProgressService.EVENT_TYPE_RUNTIME_PROGRESS);
		metadata.put("stageCode", stageCode);
		metadata.put("status", status);
		metadata.put("displayName", stageCode);
		if (StringUtils.hasText(toolName)) {
			metadata.put("toolName", toolName);
		}
		if (StringUtils.hasText(toolCallId)) {
			metadata.put("toolCallId", toolCallId);
			metadata.put("toolExecutionSeq", toolCallId);
		}
		return ServerSentEvent.builder(AgentResponse.builder()
			.agentId(agentId(request))
			.threadId(threadId(request))
			.nodeName(RUNTIME_NODE_NAME)
			.textType(TextType.TEXT)
			.text("")
			.metadata(metadata)
			.build()).event(AgentRuntimeProgressService.STREAM_EVENT_RUNTIME_PROGRESS).build();
	}

	private Map<String, Object> baseMetadata(AgentRequest request) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		if (request != null && StringUtils.hasText(request.getRuntimeRequestId())) {
			metadata.put("runtimeRequestId", request.getRuntimeRequestId());
		}
		return metadata;
	}

	private String agentId(AgentRequest request) {
		return request == null ? null : request.getAgentId();
	}

	private String threadId(AgentRequest request) {
		return request == null ? null : request.getThreadId();
	}

	private record ToolEventInfo(String toolName, String toolCallId) {
	}

	/**
	 * Per-stream mapper state. The mapper bean is a singleton; callers must keep one instance
	 * for the lifetime of a {@code streamEvents()} subscription so AGENT_RESULT does not
	 * duplicate TEXT_BLOCK_DELTA text, and so the stage-open flags of the progress state
	 * machine backfill implicit STARTs correctly.
	 */
	public static final class StreamMapState {

		private final AtomicBoolean seenTextDelta = new AtomicBoolean(false);

		private final Set<String> openedStages = ConcurrentHashMap.newKeySet();

		void markTextDelta() {
			seenTextDelta.set(true);
		}

		boolean seenTextDelta() {
			return seenTextDelta.get();
		}

		void markStageOpen(String stage) {
			openedStages.add(stage);
		}

		boolean isStageOpen(String stage) {
			return openedStages.contains(stage);
		}

	}

}
