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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.vo.AgentResponse;
import com.sn68.agent.dataagent.enums.TextType;
import io.agentscope.core.hook.ActingChunkEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

/**
 * 将 AgentScope 流式事件转换为 DataAgent 前端可消费的进度消息。
 */
public class AgentScopeStreamingHook implements Hook {

	private static final String PLANNER_REASONING_NODE = "planner-reasoning";

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final String agentId;

	private final String threadId;

	private final AgentRuntimeEventPublisher eventPublisher;

	private final AgentRuntimeToolMetrics toolMetrics;

	private final int maxIterations;

	private final AgentRuntimeDeadline deadline;

	private final Duration finishBuffer;

	private final Runnable searchResultSetEmitter;

	public AgentScopeStreamingHook(String agentId, String threadId, AgentRuntimeEventPublisher eventPublisher) {
		this(agentId, threadId, eventPublisher, null, 0, null, Duration.ZERO, null);
	}

	public AgentScopeStreamingHook(String agentId, String threadId, AgentRuntimeEventPublisher eventPublisher,
			AgentRuntimeToolMetrics toolMetrics, int maxIterations) {
		this(agentId, threadId, eventPublisher, toolMetrics, maxIterations, null, Duration.ZERO, null);
	}

	public AgentScopeStreamingHook(String agentId, String threadId, AgentRuntimeEventPublisher eventPublisher,
			AgentRuntimeToolMetrics toolMetrics, int maxIterations, AgentRuntimeDeadline deadline, Duration finishBuffer) {
		this(agentId, threadId, eventPublisher, toolMetrics, maxIterations, deadline, finishBuffer, null);
	}

	public AgentScopeStreamingHook(String agentId, String threadId, AgentRuntimeEventPublisher eventPublisher,
			AgentRuntimeToolMetrics toolMetrics, int maxIterations, AgentRuntimeDeadline deadline, Duration finishBuffer,
			Runnable searchResultSetEmitter) {
		this.agentId = agentId;
		this.threadId = threadId;
		this.eventPublisher = eventPublisher;
		this.toolMetrics = toolMetrics;
		this.maxIterations = maxIterations;
		this.deadline = deadline;
		this.finishBuffer = finishBuffer == null ? Duration.ZERO : finishBuffer;
		this.searchResultSetEmitter = searchResultSetEmitter;
	}

	/**
	 * 处理AgentScopeStreaming钩子。
	 */
	@Override
	public <T extends HookEvent> Mono<T> onEvent(T event) {
		if (event instanceof ReasoningChunkEvent reasoningChunkEvent) {
			emit(PLANNER_REASONING_NODE, reasoningChunkEvent.getIncrementalChunk().getTextContent());
		}
		else if (event instanceof io.agentscope.core.hook.PreReasoningEvent) {
			if (toolMetrics != null) {
				toolMetrics.recordReactIteration(maxIterations);
			}
		}
		else if (event instanceof PostReasoningEvent postReasoningEvent) {
			handlePostReasoning(postReasoningEvent);
		}
		else if (event instanceof PreActingEvent preActingEvent) {
			String toolName = preActingEvent.getToolUse().getName();
			emit(resolveToolNodeName(toolName), AgentRuntimeToolDisplayNameResolver.runningText(toolName));
		}
		else if (event instanceof ActingChunkEvent actingChunkEvent) {
			emitToolResult(actingChunkEvent.getToolUse().getName(), extractToolResultText(actingChunkEvent.getChunk()));
		}
		else if (event instanceof PostActingEvent postActingEvent) {
			ToolResultBlock toolResult = postActingEvent.getToolResult();
			emitToolResult(postActingEvent.getToolUse().getName(), extractToolResultText(toolResult));
			if (isBusinessFailure(toolResult)) {
				postActingEvent.stopAgent();
			}
		}
		return Mono.just(event);
	}

	private void handlePostReasoning(PostReasoningEvent event) {
		if (toolMetrics != null && toolMetrics.clarificationReason() != null) {
			event.setReasoningMessage(unknownBusinessTermClarification());
			event.stopAgent();
			return;
		}
		Msg reasoningMessage = event.getReasoningMessage();
		if (hasPublicText(reasoningMessage) || hasNativeToolUse(reasoningMessage)) {
			return;
		}
		if (toolMetrics != null
				&& toolMetrics.tryStartProtocolRepair(maxIterations, deadline, finishBuffer)) {
			event.gotoReasoning(protocolRepairInstruction());
			return;
		}
		if (toolMetrics != null) {
			toolMetrics.recordTerminalOutcome(AgentRuntimeTerminalOutcome.MODEL_PROTOCOL_ERROR);
		}
		event.stopAgent();
	}

	private boolean hasPublicText(Msg message) {
		return message != null && message.getContentBlocks(TextBlock.class)
			.stream()
			.map(TextBlock::getText)
			.anyMatch(text -> text != null && !text.isBlank());
	}

	private boolean hasNativeToolUse(Msg message) {
		return message != null && message.hasContentBlocks(ToolUseBlock.class);
	}

	private Msg protocolRepairInstruction() {
		return Msg.builder()
			.name("protocol-repair")
			.role(MsgRole.USER)
			.textContent("Internal protocol correction: respond with a native tool call when a tool is required, "
					+ "or with a public final answer as plain text. Do not emit tool-call XML or JSON inside reasoning.")
			.build();
	}

	private Msg unknownBusinessTermClarification() {
		return Msg.builder()
			.name("assistant")
			.role(MsgRole.ASSISTANT)
			.textContent("当前检索结果还不能确认这个业务词对应的准确口径。请补充项目名称、来源类型或业务类型中的至少一项，我会按明确口径继续查询。")
			.build();
	}

	private void emit(String nodeName, String text) {
		if (eventPublisher == null || text == null || text.isBlank()
				|| AgentRuntimeErrorClassifier.isBudgetExhaustedText(text)) {
			return;
		}
		eventPublisher.publish(AgentResponse.builder()
			.agentId(agentId)
			.threadId(threadId)
			.nodeName(nodeName)
			.textType(TextType.TEXT)
			.text(text)
			.build());
	}

	private void emitToolResult(String toolName, String text) {
		if (eventPublisher == null) {
			return;
		}
		Optional<AgentResponse> richResponse = AgentUiResponseSupport.parseTrustedToolResult(agentId,
				threadId, toolName, text, objectMapper);
		if (richResponse.isPresent()) {
			eventPublisher.publish(richResponse.get());
			return;
		}
		if (isStructuredToolJson(toolName, text)) {
			emitSearchResultSetIfPresent(toolName);
			return;
		}
		emit(resolveToolNodeName(toolName), text);
	}

	private void emitSearchResultSetIfPresent(String toolName) {
		if (searchResultSetEmitter == null
				|| !com.sn68.agent.dataagent.agentscope.tool.AgentModelToolName.DATASOURCE_SKILL_SEARCH.equals(toolName)) {
			return;
		}
		searchResultSetEmitter.run();
	}

	private boolean isStructuredToolJson(String toolName, String text) {
		if (toolName == null || text == null) {
			return false;
		}
		String trimmed = text.trim();
		return trimmed.startsWith("{") || trimmed.startsWith("[");
	}

	private String resolveToolNodeName(String toolName) {
		return AgentRuntimeToolDisplayNameResolver.displayName(toolName);
	}

	private boolean isBusinessFailure(ToolResultBlock toolResultBlock) {
		return toolResultBlock != null && toolResultBlock.getMetadata() != null
				&& Boolean.TRUE.equals(
						toolResultBlock.getMetadata().get(SpringToolCallbackAgentAdapter.METADATA_BUSINESS_FAILED));
	}

	private String extractToolResultText(ToolResultBlock toolResultBlock) {
		if (toolResultBlock == null) {
			return "";
		}
		List<ContentBlock> output = toolResultBlock.getOutput();
		if (output == null || output.isEmpty()) {
			return "";
		}
		return output.stream()
			.filter(TextBlock.class::isInstance)
			.map(TextBlock.class::cast)
			.map(TextBlock::getText)
			.filter(text -> text != null && !text.isBlank())
			.collect(Collectors.joining(System.lineSeparator()));
	}

}
