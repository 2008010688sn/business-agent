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
import com.sn68.agent.dataagent.ui.AgentUiMessage;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class V2EventToAgentResponseMapperTest {

	private final V2EventToAgentResponseMapper mapper = new V2EventToAgentResponseMapper();

	private final AgentRequest request = AgentRequest.builder()
		.agentId("1")
		.threadId("100")
		.runtimeRequestId("runtime-1")
		.build();

	@Test
	void mapsTextDeltaToMessageEvent() {
		List<ServerSentEvent<AgentResponse>> events = mapper.map(new TextBlockDeltaEvent("r1", "b1", "hello"),
				request);

		assertEquals(1, events.size());
		assertEquals(V2EventToAgentResponseMapper.STREAM_EVENT_MESSAGE, events.get(0).event());
		assertEquals("hello", events.get(0).data().getText());
		assertFrontendContract(events);
	}

	@Test
	void mapsAgentEndToCompleteEvent() {
		List<ServerSentEvent<AgentResponse>> events = mapper.map(new AgentEndEvent("r1"), request);

		assertEquals(1, events.size());
		assertEquals(Constant.STREAM_EVENT_COMPLETE, events.get(0).event());
		assertTrue(events.get(0).data().isComplete());
		assertFrontendContract(events);
	}

	@Test
	void mapsThrowableToErrorEvent() {
		ServerSentEvent<AgentResponse> event = mapper.mapError(request, new IllegalStateException("boom"));

		assertEquals(Constant.STREAM_EVENT_ERROR, event.event());
		assertTrue(event.data().isError());
		assertEquals("boom", event.data().getText());
		assertFrontendContract(List.of(event));
	}

	@Test
	void mapsRequireUserConfirmToMessageCardAndWaitingProgress() {
		ToolUseBlock tool = new ToolUseBlock("call-9", "demand_create_execute", Map.of("projectName", "太阳食品"));
		List<ServerSentEvent<AgentResponse>> events = mapper
			.map(new RequireUserConfirmEvent("reply-1", List.of(tool)), request);

		assertEquals(2, events.size());
		assertEquals(AgentRuntimeProgressService.STREAM_EVENT_RUNTIME_PROGRESS, events.get(0).event());
		assertEquals("WAITING_CONFIRM", events.get(0).data().getMetadata().get("stageCode"));
		assertEquals(V2EventToAgentResponseMapper.STREAM_EVENT_MESSAGE, events.get(1).event());
		Map<String, Object> metadata = events.get(1).data().getMetadata();
		assertEquals(AgentUiMessage.SCHEMA_VERSION, metadata.get("uiSchemaVersion"));
		AgentUiMessage ui = (AgentUiMessage) metadata.get("agentUi");
		assertEquals(AgentUiMessage.KIND_TOOL_CONFIRM, ui.kind());
		assertEquals("call-9", ui.payload().values().get("toolCallId"));
		assertEquals("reply-1", ui.payload().values().get("replyId"));
		assertTrue(ui.payload().values().containsKey("paramFingerprint"));
		assertFrontendContract(events);
	}

	@Test
	void mapsToolCallToRuntimeProgressWithoutRawEventName() {
		List<ServerSentEvent<AgentResponse>> events = mapper.map(
				new ToolCallStartEvent("r1", "call-1", "datasource_skill_search"), request);

		assertEquals(1, events.size());
		assertEquals(AgentRuntimeProgressService.STREAM_EVENT_RUNTIME_PROGRESS, events.get(0).event());
		Map<String, Object> metadata = events.get(0).data().getMetadata();
		assertEquals("TOOL_RUNNING", metadata.get("stageCode"));
		assertEquals("TOOL_RUNNING", metadata.get("displayName"));
		assertEquals(AgentRuntimeProgressService.STATUS_RUNNING, metadata.get("status"));
		assertEquals("datasource_skill_search", metadata.get("toolName"));
		assertEquals("call-1", metadata.get("toolCallId"));
		assertEquals("call-1", metadata.get("toolExecutionSeq"));
		assertNoRawEventNameLeak(events.get(0), "TOOL_CALL_START");
		assertFrontendContract(events);
	}

	@Test
	void dropsThinkingAndToolCallDeltasWithinOpenStages() {
		V2EventToAgentResponseMapper.StreamMapState state = new V2EventToAgentResponseMapper.StreamMapState();
		assertEquals(1, mapper.map(new ThinkingBlockStartEvent("r1", "b1"), request, state).size());
		assertTrue(mapper.map(new ThinkingBlockDeltaEvent("r1", "b1", "think"), request, state).isEmpty());
		assertTrue(mapper.map(new ThinkingBlockDeltaEvent("r1", "b1", "more"), request, state).isEmpty());

		assertEquals(1,
				mapper.map(new ToolCallStartEvent("r1", "call-1", "datasource_skill_search"), request, state).size());
		assertTrue(mapper
			.map(new ToolCallDeltaEvent("r1", "call-1", "datasource_skill_search", "{}"), request, state)
			.isEmpty());
		assertTrue(mapper
			.map(new ToolCallDeltaEvent("r1", "call-1", "datasource_skill_search", "{\"k\":1}"), request, state)
			.isEmpty());
	}

	@Test
	void backfillsImplicitStageStartWhenDeltaArrivesFirst() {
		V2EventToAgentResponseMapper.StreamMapState state = new V2EventToAgentResponseMapper.StreamMapState();

		List<ServerSentEvent<AgentResponse>> thinkingEvents = mapper
			.map(new ThinkingBlockDeltaEvent("r1", "b1", "think"), request, state);
		assertEquals(1, thinkingEvents.size());
		assertEquals("THINKING", thinkingEvents.get(0).data().getMetadata().get("stageCode"));
		assertEquals(AgentRuntimeProgressService.STATUS_RUNNING,
				thinkingEvents.get(0).data().getMetadata().get("status"));
		assertTrue(mapper.map(new ThinkingBlockDeltaEvent("r1", "b1", "more"), request, state).isEmpty());

		List<ServerSentEvent<AgentResponse>> toolEvents = mapper
			.map(new ToolCallDeltaEvent("r1", "call-1", "datasource_skill_search", "{}"), request, state);
		assertEquals(1, toolEvents.size());
		assertEquals("TOOL_RUNNING", toolEvents.get(0).data().getMetadata().get("stageCode"));
		assertEquals("datasource_skill_search", toolEvents.get(0).data().getMetadata().get("toolName"));
		assertEquals("call-1", toolEvents.get(0).data().getMetadata().get("toolCallId"));
		assertTrue(mapper
			.map(new ToolCallDeltaEvent("r1", "call-1", "datasource_skill_search", "{\"k\":1}"), request, state)
			.isEmpty());
	}

	@Test
	void mapsToolResultEndToFinishedProgress() {
		List<ServerSentEvent<AgentResponse>> events = mapper.map(
				new ToolResultEndEvent("r1", "call-1", "datasource_skill_search", ToolResultState.SUCCESS), request);

		assertEquals(1, events.size());
		Map<String, Object> metadata = events.get(0).data().getMetadata();
		assertEquals("TOOL_FINISHED", metadata.get("stageCode"));
		assertEquals("TOOL_FINISHED", metadata.get("displayName"));
		assertEquals(AgentRuntimeProgressService.STATUS_SUCCESS, metadata.get("status"));
		assertEquals("datasource_skill_search", metadata.get("toolName"));
		assertEquals("call-1", metadata.get("toolCallId"));
		assertEquals("call-1", metadata.get("toolExecutionSeq"));
		assertFrontendContract(events);
	}

	@Test
	void mapsFailedToolResultEndToFailedProgress() {
		List<ServerSentEvent<AgentResponse>> events = mapper.map(
				new ToolResultEndEvent("r1", "call-2", "datasource_skill_search", ToolResultState.ERROR), request);

		assertEquals(1, events.size());
		Map<String, Object> metadata = events.get(0).data().getMetadata();
		assertEquals("TOOL_FINISHED", metadata.get("stageCode"));
		assertEquals(AgentRuntimeProgressService.STATUS_FAILED, metadata.get("status"));
		assertEquals("datasource_skill_search", metadata.get("toolName"));
		assertEquals("call-2", metadata.get("toolExecutionSeq"));
		assertFrontendContract(events);
	}

	@Test
	void mapsModelCallEndToSuccessProgress() {
		List<ServerSentEvent<AgentResponse>> events = mapper.map(new ModelCallEndEvent("r1", null), request);

		assertEquals(1, events.size());
		Map<String, Object> metadata = events.get(0).data().getMetadata();
		assertEquals("MODEL_CALL", metadata.get("stageCode"));
		assertEquals(AgentRuntimeProgressService.STATUS_SUCCESS, metadata.get("status"));
		assertFrontendContract(events);
	}

	@Test
	void unknownEventGoesToRuntimeProgressAndIncrementsWarnCounter() {
		long before = mapper.unknownEventWarnCount();
		List<ServerSentEvent<AgentResponse>> events = mapper.map(new CustomEvent("payload"), request);

		assertEquals(1, events.size());
		assertEquals(AgentRuntimeProgressService.STREAM_EVENT_RUNTIME_PROGRESS, events.get(0).event());
		assertEquals("AGENT_PROGRESS", events.get(0).data().getMetadata().get("stageCode"));
		assertEquals(before + 1, mapper.unknownEventWarnCount());
		assertNoRawEventNameLeak(events.get(0), "CUSTOM");
		assertFrontendContract(events);
	}

	@Test
	void agentResultDoesNotDuplicateMessageWhenDeltasAlreadyStreamed() {
		V2EventToAgentResponseMapper.StreamMapState state = new V2EventToAgentResponseMapper.StreamMapState();
		List<ServerSentEvent<AgentResponse>> events = new ArrayList<>();
		events.addAll(mapper.map(new TextBlockDeltaEvent("r1", "b1", "hel"), request, state));
		events.addAll(mapper.map(new TextBlockDeltaEvent("r1", "b1", "lo"), request, state));
		events.addAll(mapper.map(new AgentResultEvent(Msg.builder()
			.name("assistant")
			.role(MsgRole.ASSISTANT)
			.textContent("hello")
			.build()), request, state));

		List<String> messages = events.stream()
			.filter(event -> V2EventToAgentResponseMapper.STREAM_EVENT_MESSAGE.equals(event.event()))
			.map(event -> event.data().getText())
			.toList();
		assertEquals(List.of("hel", "lo"), messages);
		assertEquals("hello", String.join("", messages));
		assertFrontendContract(events);
	}

	@Test
	void agentResultFallsBackToMessageWhenNoDeltas() {
		V2EventToAgentResponseMapper.StreamMapState state = new V2EventToAgentResponseMapper.StreamMapState();
		List<ServerSentEvent<AgentResponse>> events = mapper.map(new AgentResultEvent(Msg.builder()
			.name("assistant")
			.role(MsgRole.ASSISTANT)
			.textContent("done")
			.build()), request, state);

		assertEquals(1, events.size());
		assertEquals(V2EventToAgentResponseMapper.STREAM_EVENT_MESSAGE, events.get(0).event());
		assertEquals("done", events.get(0).data().getText());
	}

	@Test
	void exceedMaxItersAndAllToolsDeniedAreErrorEvents() {
		List<ServerSentEvent<AgentResponse>> maxIters = mapper.map(new ExceedMaxItersEvent("r1", 8, 8), request);
		assertEquals(Constant.STREAM_EVENT_ERROR, maxIters.get(0).event());
		assertTrue(maxIters.get(0).data().isError());

		List<ServerSentEvent<AgentResponse>> denied = mapper.map(new AllToolsDeniedEvent(List.of()), request);
		assertEquals(Constant.STREAM_EVENT_ERROR, denied.get(0).event());
		assertTrue(denied.get(0).data().isError());
		assertFrontendContract(maxIters);
		assertFrontendContract(denied);
	}

	@Test
	void nullEventTypeGoesToRuntimeProgress() {
		AgentEvent event = mock(AgentEvent.class);
		when(event.getType()).thenReturn(null);
		when(event.getId()).thenReturn("unknown-1");
		long before = mapper.unknownEventWarnCount();
		List<ServerSentEvent<AgentResponse>> events = mapper.map(event, request);
		assertEquals(1, events.size());
		assertEquals(AgentRuntimeProgressService.STREAM_EVENT_RUNTIME_PROGRESS, events.get(0).event());
		assertEquals(before + 1, mapper.unknownEventWarnCount());
	}

	@Test
	void frontendOnlySeesFourEventNamesAcrossKnownTypes() {
		List<ServerSentEvent<AgentResponse>> events = new ArrayList<>();
		events.addAll(mapper.map(new AgentStartEvent(null, "r1", "data-agent-v2"), request));
		events.addAll(mapper.map(new TextBlockDeltaEvent("r1", "b1", "chunk"), request));
		events.addAll(mapper.map(new AgentResultEvent(Msg.builder()
			.name("assistant")
			.role(MsgRole.ASSISTANT)
			.textContent("done")
			.build()), request));
		events.addAll(mapper.map(new AgentEndEvent("r1"), request));
		events.add(mapper.mapError(request, new RuntimeException("fail")));
		events.addAll(mapper.map(new CustomEvent("x"), request));

		assertFalse(events.isEmpty());
		assertFrontendContract(events);
	}

	private static void assertFrontendContract(List<ServerSentEvent<AgentResponse>> events) {
		for (ServerSentEvent<AgentResponse> event : events) {
			assertTrue(V2EventToAgentResponseMapper.FRONTEND_EVENT_NAMES.contains(event.event()),
					() -> "unexpected SSE event name: " + event.event());
		}
	}

	private static void assertNoRawEventNameLeak(ServerSentEvent<AgentResponse> event, String rawName) {
		assertFalse(rawName.equals(event.event()));
		Map<String, Object> metadata = event.data() == null ? Map.of() : event.data().getMetadata();
		if (metadata == null) {
			return;
		}
		assertFalse(rawName.equals(String.valueOf(metadata.get("stageCode"))));
		assertFalse(rawName.equals(String.valueOf(metadata.get("displayName"))));
		assertFalse(rawName.equals(String.valueOf(metadata.get("eventType"))));
	}

}
