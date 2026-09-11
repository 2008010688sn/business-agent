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

import com.sn68.agent.dataagent.agentscope.vo.AgentResponse;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentScopeStreamingHookTest {

	@Test
	void postActingStopsAgentWhenToolResultIsBusinessFailure() {
		PostActingEvent event = postActingEvent(ToolResultBlock.of("tool-call-1",
				"skill.demand_create.execute", TextBlock.builder().text("Error: missing arrivalTime").build(),
				Map.of(SpringToolCallbackAgentAdapter.METADATA_BUSINESS_FAILED, true)));
		AgentScopeStreamingHook hook = new AgentScopeStreamingHook("1", "100", ignored -> {
		});

		hook.onEvent(event).block();

		assertTrue(event.isStopRequested());
	}

	@Test
	void searchJsonToolResultTriggersResultSetEmitter() {
		java.util.concurrent.atomic.AtomicBoolean emitted = new java.util.concurrent.atomic.AtomicBoolean();
		PostActingEvent event = postActingEvent(ToolResultBlock.of("tool-call-1", "datasource_skill_search",
				TextBlock.builder().text("{\"action\":\"SEARCH\",\"rows\":[]}").build()));
		AgentScopeStreamingHook hook = new AgentScopeStreamingHook("1", "100", ignored -> {
		}, null, 0, null, Duration.ZERO, () -> emitted.set(true));

		hook.onEvent(event).block();

		assertTrue(emitted.get());
		assertFalse(event.isStopRequested());
	}

	@Test
	void postActingKeepsAgentRunningForNormalToolResult() {
		List<String> emitted = new ArrayList<>();
		PostActingEvent event = postActingEvent(ToolResultBlock.of("tool-call-1", "demo.tool",
				TextBlock.builder().text("ok").build()));
		AgentScopeStreamingHook hook = new AgentScopeStreamingHook("1", "100", response -> emitted.add(response.getText()));

		hook.onEvent(event).block();

		assertFalse(event.isStopRequested());
		assertTrue(emitted.contains("ok"));
	}

	@Test
	void skillToolResultDoesNotExposeRawToolNameInPublicResponse() {
		List<AgentResponse> emitted = new ArrayList<>();
		String rawToolName = "skill.sales_analysis.__forbidden_raw_tool_name__";
		PostActingEvent event = postActingEvent(ToolResultBlock.of("tool-call-1", rawToolName,
				TextBlock.builder().text("ok").build()));
		AgentScopeStreamingHook hook = new AgentScopeStreamingHook("1", "100", emitted::add);

		hook.onEvent(event).block();

		assertEquals(1, emitted.size());
		assertEquals("执行工具操作", emitted.get(0).getNodeName());
		assertFalse(String.valueOf(emitted.get(0).getNodeName()).contains(rawToolName));
		assertFalse(String.valueOf(emitted.get(0).getText()).contains(rawToolName));
	}

	@Test
	void thinkingOnlyResponseRequestsOneProtocolRepairThenStops() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics();
		AgentScopeStreamingHook hook = new AgentScopeStreamingHook("1", "100", ignored -> {
		}, metrics, 10, null, Duration.ZERO);

		PostReasoningEvent first = postReasoningEvent(thinkingOnly("I should call a tool"));
		hook.onEvent(first).block();
		PostReasoningEvent second = postReasoningEvent(thinkingOnly("<tool_call>fake</tool_call>"));
		hook.onEvent(second).block();

		assertTrue(first.isGotoReasoningRequested());
		assertFalse(first.isStopRequested());
		assertFalse(second.isGotoReasoningRequested());
		assertTrue(second.isStopRequested());
		assertEquals(1, metrics.protocolRepairCount());
		assertEquals(AgentRuntimeTerminalOutcome.MODEL_PROTOCOL_ERROR, metrics.terminalOutcome());
	}

	@Test
	void protocolRepairDoesNotStartAfterDeadline() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics();
		AgentRuntimeDeadline expired = AgentRuntimeDeadline.startAt(0L, Duration.ofNanos(1));
		AgentScopeStreamingHook hook = new AgentScopeStreamingHook("1", "100", ignored -> {
		}, metrics, 10, expired, Duration.ZERO);
		PostReasoningEvent event = postReasoningEvent(thinkingOnly("unfinished"));

		hook.onEvent(event).block();

		assertTrue(event.isStopRequested());
		assertFalse(event.isGotoReasoningRequested());
		assertEquals(0, metrics.protocolRepairCount());
	}

	@Test
	void pendingUnknownTermClarificationReplacesModelResponseAndStops() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics();
		metrics.recordClarificationReason("UNKNOWN_BUSINESS_TERM_UNRESOLVED");
		AgentScopeStreamingHook hook = new AgentScopeStreamingHook("1", "100", ignored -> {
		}, metrics, 10, null, Duration.ZERO);
		PostReasoningEvent event = postReasoningEvent(thinkingOnly("I will search another table"));

		hook.onEvent(event).block();

		assertTrue(event.isStopRequested());
		assertFalse(event.isGotoReasoningRequested());
		assertTrue(event.getReasoningMessage().getTextContent().contains("项目名称、来源类型或业务类型"));
	}

	private PostActingEvent postActingEvent(ToolResultBlock toolResultBlock) {
		ToolUseBlock toolUseBlock = ToolUseBlock.builder()
			.id("tool-call-1")
			.name(toolResultBlock.getName())
			.input(Map.of())
			.build();
		return new PostActingEvent(mock(Agent.class), new Toolkit(), toolUseBlock, toolResultBlock);
	}

	private PostReasoningEvent postReasoningEvent(Msg message) {
		return new PostReasoningEvent(mock(Agent.class), "test-model", mock(GenerateOptions.class), message);
	}

	private Msg thinkingOnly(String text) {
		return Msg.builder()
			.name("assistant")
			.role(MsgRole.ASSISTANT)
			.content(ThinkingBlock.builder().thinking(text).build())
			.build();
	}

}
