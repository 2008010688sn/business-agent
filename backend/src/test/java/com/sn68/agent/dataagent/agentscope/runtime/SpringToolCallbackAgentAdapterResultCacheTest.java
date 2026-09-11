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
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.context.DataAgentAsyncContextBridge;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolExecutionContext;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 同请求内工具结果缓存行为：命中跳过底层执行、白名单外不缓存、超限淘汰、失败结果不入缓存。
 */
class SpringToolCallbackAgentAdapterResultCacheTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final DataAgentAsyncContextBridge asyncContextBridge = new DataAgentAsyncContextBridge();

	@Test
	void sameArgsSecondCallReusesCachedResultWithoutExecutingCallback() {
		AtomicInteger executions = new AtomicInteger();
		ToolCallback callback = countingCallback("datasource_skill_search", executions, payload -> "{\"ok\":true}");
		SpringToolCallbackAgentAdapter adapter = cacheEnabledAdapter(callback, Set.of("datasource_skill_search"), 32);

		ToolResultBlock first = adapter.callAsync(param(Map.of("query", "订单"))).block();
		ToolResultBlock second = adapter.callAsync(param(Map.of("query", "订单"))).block();

		assertNotNull(first);
		assertNotNull(second);
		assertEquals(1, executions.get());
		assertEquals(text(first), text(second));
	}

	@Test
	void toolOutsideWhitelistIsNeverCached() {
		AtomicInteger executions = new AtomicInteger();
		ToolCallback callback = countingCallback("demo.write", executions, payload -> "{\"ok\":true}");
		SpringToolCallbackAgentAdapter adapter = cacheEnabledAdapter(callback, Set.of("datasource_skill_search"), 32);

		adapter.callAsync(param(Map.of("query", "a"))).block();
		adapter.callAsync(param(Map.of("query", "a"))).block();

		assertEquals(2, executions.get());
	}

	@Test
	void evictsOldestEntryWhenMaxEntriesExceeded() {
		AtomicInteger executions = new AtomicInteger();
		ToolCallback callback = countingCallback("semantic_model_search", executions, payload -> "result:" + payload);
		SpringToolCallbackAgentAdapter adapter = cacheEnabledAdapter(callback, Set.of("semantic_model_search"), 1);

		ToolResultBlock first = adapter.callAsync(param(Map.of("query", "a"))).block();
		adapter.callAsync(param(Map.of("query", "b"))).block();
		ToolResultBlock third = adapter.callAsync(param(Map.of("query", "a"))).block();

		assertEquals(3, executions.get());
		assertNotNull(first);
		assertNotNull(third);
		assertEquals(text(first), text(third));
	}

	@Test
	void failedCallIsNotCachedAndRetriesUnderlyingCallback() {
		AtomicInteger executions = new AtomicInteger();
		ToolCallback callback = mock(ToolCallback.class);
		when(callback.getToolDefinition())
			.thenReturn(ToolDefinition.builder().name("sql_guard_check").description("sql_guard_check")
					.inputSchema("{}").build());
		when(callback.call(any(String.class), any(ToolContext.class))).thenAnswer(invocation -> {
			executions.incrementAndGet();
			throw new IllegalStateException("工具不可用");
		});
		SpringToolCallbackAgentAdapter adapter = cacheEnabledAdapter(callback, Set.of("sql_guard_check"), 32);

		adapter.callAsync(param(Map.of("query", "a"))).block();
		adapter.callAsync(param(Map.of("query", "a"))).block();

		assertEquals(2, executions.get());
	}

	private SpringToolCallbackAgentAdapter cacheEnabledAdapter(ToolCallback callback, Set<String> whitelist,
			int maxEntries) {
		return new SpringToolCallbackAgentAdapter(callback, objectMapper, new AnswerTraceExplainStore(),
				asyncContextBridge, null, null, 0, 0, true, maxEntries, whitelist);
	}

	private ToolCallback countingCallback(String name, AtomicInteger executions, Function<String, String> result) {
		ToolCallback callback = mock(ToolCallback.class);
		when(callback.getToolDefinition())
			.thenReturn(ToolDefinition.builder().name(name).description(name).inputSchema("{}").build());
		when(callback.call(any(String.class), any(ToolContext.class))).thenAnswer(invocation -> {
			executions.incrementAndGet();
			return result.apply(invocation.getArgument(0, String.class));
		});
		return callback;
	}

	private String text(ToolResultBlock result) {
		return ((TextBlock) result.getOutput().get(0)).getText();
	}

	private ToolCallParam param(Map<String, Object> input) {
		AgentRequest request = AgentRequest.builder()
			.agentId("1")
			.threadId("100")
			.runtimeRequestId("runtime-1")
			.query("查询订单")
			.build();
		AgentRuntimeRequestMetadata metadata = new AgentRuntimeRequestMetadata("1", "100", "runtime-1", false, null);
		ToolExecutionContext context = ToolExecutionContext.builder()
			.register("graphRequest", request)
			.register(metadata)
			.register(new AgentRuntimeToolMetrics())
			.build();
		return ToolCallParam.builder()
			.toolUseBlock(ToolUseBlock.builder().id("tool-call-1").name("demo.tool").input(input).build())
			.input(input)
			.context(context)
			.build();
	}

}
