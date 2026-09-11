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
package com.sn68.agent.dataagent.agentscope.runtime.mcp;

import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeProgressService;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeToolDisplayNameResolver;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeToolMetrics;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.ToolExecutionRecord;
import com.sn68.agent.dataagent.runtime.hook.service.RuntimeHookDispatcher;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * 包装 MCP 客户端调用，记录工具调用指标和可展示摘要。
 */
@Slf4j
public class ObservedMcpClientWrapper extends McpClientWrapper {

	private static final int TOOL_SUMMARY_MAX_LENGTH = 800;

	private static final String STATUS_SUCCESS = "success";

	private static final String STATUS_FAILED = "failed";

	private final McpClientWrapper delegate;

	private final AgentRequest request;

	private final AgentRuntimeToolMetrics toolMetrics;

	private final AnswerTraceExplainStore answerTraceExplainStore;

	private final ObjectMapper objectMapper;

	private final RuntimeHookDispatcher runtimeHookDispatcher;

	private final AgentRuntimeProgressService runtimeProgressService;

	public ObservedMcpClientWrapper(String name, McpClientWrapper delegate, AgentRequest request,
			AgentRuntimeToolMetrics toolMetrics, AnswerTraceExplainStore answerTraceExplainStore,
			ObjectMapper objectMapper, RuntimeHookDispatcher runtimeHookDispatcher,
			AgentRuntimeProgressService runtimeProgressService) {
		super(name);
		this.delegate = delegate;
		this.request = request;
		this.toolMetrics = toolMetrics;
		this.answerTraceExplainStore = answerTraceExplainStore;
		this.objectMapper = objectMapper;
		this.runtimeHookDispatcher = runtimeHookDispatcher;
		this.runtimeProgressService = runtimeProgressService;
	}

	/**
	 * 创建ObservedMcpClientWrapper。
	 */
	@Override
	public Mono<Void> initialize() {
		return delegate.initialize().doOnSuccess(ignored -> initialized = delegate.isInitialized());
	}

	/**
	 * 查询ObservedMcpClientWrapper。
	 */
	@Override
	public Mono<java.util.List<McpSchema.Tool>> listTools() {
		return delegate.listTools();
	}

	/**
	 * 处理ObservedMcpClientWrapper。
	 */
	@Override
	public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments) {
		long startNanos = System.nanoTime();
		long startEpochMs = System.currentTimeMillis();
		Integer sequenceNo = reserveToolExecutionSequenceNo();
		if (toolMetrics != null) {
			toolMetrics.recordCall();
		}
		emitToolRunning(toolName);
		return observe(delegate.callTool(toolName, arguments), toolName, arguments, startNanos, startEpochMs,
				sequenceNo);
	}

	@Override
	public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments,
			Map<String, Object> metadata) {
		long startNanos = System.nanoTime();
		long startEpochMs = System.currentTimeMillis();
		Integer sequenceNo = reserveToolExecutionSequenceNo();
		if (toolMetrics != null) {
			toolMetrics.recordCall();
		}
		emitToolRunning(toolName);
		return observe(delegate.callTool(toolName, arguments, metadata), toolName, arguments, startNanos, startEpochMs,
				sequenceNo);
	}

	private Mono<McpSchema.CallToolResult> observe(Mono<McpSchema.CallToolResult> call, String toolName,
			Map<String, Object> arguments, long startNanos, long startEpochMs, Integer sequenceNo) {
		return call.doOnNext(result -> recordSuccess(toolName, arguments, result, sequenceNo, startNanos, startEpochMs))
			.doOnError(ex -> recordFailure(toolName, arguments, ex, sequenceNo, startNanos, startEpochMs));
	}

	/**
	 * 处理ObservedMcpClientWrapper。
	 */
	@Override
	public void close() {
		delegate.close();
	}

	private Integer reserveToolExecutionSequenceNo() {
		if (answerTraceExplainStore == null || request == null) {
			return null;
		}
		return answerTraceExplainStore.reserveToolExecutionSequenceNo(request);
	}

	private void recordSuccess(String toolName, Map<String, Object> arguments, McpSchema.CallToolResult result,
			Integer sequenceNo, long startNanos, long startEpochMs) {
		boolean toolError = result != null && Boolean.TRUE.equals(result.isError());
		if (toolError && toolMetrics != null) {
			toolMetrics.recordFailure(AgentRuntimeToolDisplayNameResolver.failureMessage(toolName, null, arguments));
		}
		long durationMs = elapsedMs(startNanos);
		long endEpochMs = System.currentTimeMillis();
		String outputSummary = summarize(result);
		String errorMessage = toolError ? outputSummary : null;
		recordToolExecution(toolName, sequenceNo, toolError ? STATUS_FAILED : STATUS_SUCCESS, startEpochMs,
				endEpochMs, durationMs, summarize(arguments), toolError ? null : outputSummary,
				toolError ? "MCP_TOOL_ERROR" : null, errorMessage);
		emitToolFinished(toolName, toolError ? STATUS_FAILED : STATUS_SUCCESS, durationMs);
		if (toolError) {
			dispatchToolFailure(toolName, arguments, "MCP_TOOL_ERROR", errorMessage);
		}
	}

	private void recordFailure(String toolName, Map<String, Object> arguments, Throwable ex, Integer sequenceNo,
			long startNanos, long startEpochMs) {
		if (toolMetrics != null) {
			toolMetrics.recordFailure(AgentRuntimeToolDisplayNameResolver.failureMessage(toolName, null, arguments));
		}
		long durationMs = elapsedMs(startNanos);
		long endEpochMs = System.currentTimeMillis();
		recordToolExecution(toolName, sequenceNo, STATUS_FAILED, startEpochMs, endEpochMs, durationMs,
				summarize(arguments), null, ex == null ? "UNKNOWN" : ex.getClass().getSimpleName(),
				abbreviate(ex == null ? null : ex.getMessage()));
		emitToolFinished(toolName, STATUS_FAILED, durationMs);
		dispatchToolFailure(toolName, arguments, ex == null ? "UNKNOWN" : ex.getClass().getSimpleName(),
				abbreviate(ex == null ? null : ex.getMessage()));
	}

	private void emitToolRunning(String toolName) {
		if (runtimeProgressService != null) {
			runtimeProgressService.emitToolRunning(request, toolName, AgentRuntimeToolDisplayNameResolver.displayName(toolName));
		}
	}

	private void emitToolFinished(String toolName, String status, long durationMs) {
		if (runtimeProgressService != null) {
			runtimeProgressService.emitToolFinished(request, toolName,
					AgentRuntimeToolDisplayNameResolver.displayName(toolName), status, durationMs);
		}
	}

	private void dispatchToolFailure(String toolName, Map<String, Object> arguments, String errorCode,
			String errorMessage) {
		if (runtimeHookDispatcher == null) {
			return;
		}
		try {
			Map<String, Object> input = new java.util.LinkedHashMap<>();
			input.put("toolName", toolName);
			input.put("arguments", arguments == null ? Map.of() : arguments);
			Map<String, Object> output = new java.util.LinkedHashMap<>();
			output.put("message", errorMessage);
			output.put("errorCode", errorCode);
			runtimeHookDispatcher.dispatchAfterToolFailed(parseLong(request == null ? null : request.getAgentId()), null,
					null, toolName, request == null ? null : request.getThreadId(),
					request == null ? null : request.getRuntimeRequestId(), input, output);
		}
		catch (Exception ex) {
			// Observability hooks must not affect MCP tool execution, but a dead hook must still be visible.
			log.warn("Failed to dispatch MCP tool failure runtime hook. toolName={}, runtimeRequestId={}", toolName,
					request == null ? null : request.getRuntimeRequestId(), ex);
		}
	}

	private void recordToolExecution(String toolName, Integer sequenceNo, String status, long startEpochMs,
			long endEpochMs, long durationMs, String inputSummary, String outputSummary, String errorCode,
			String errorMessage) {
		if (answerTraceExplainStore == null || request == null) {
			return;
		}
		String summary = STATUS_SUCCESS.equals(status) ? "Tool executed successfully, duration %dms".formatted(durationMs)
				: "Tool execution failed, duration %dms".formatted(durationMs);
		String detail = STATUS_SUCCESS.equals(status) ? outputSummary : errorMessage;
		answerTraceExplainStore.recordToolExecution(request, sequenceNo,
				new ToolExecutionRecord(toolName, status, startEpochMs, endEpochMs, durationMs, inputSummary,
						outputSummary, errorCode, errorMessage, summary, detail));
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

	private String abbreviate(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.replaceAll("\\s+", " ").trim();
		return normalized.length() <= 160 ? normalized : normalized.substring(0, 157) + "...";
	}

	private long elapsedMs(long startNanos) {
		return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
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

}
