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

import com.sn68.agent.dataagent.agentscope.tool.AgentModelToolName;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilteringMcpClientWrapperTest {

	@Test
	void listToolsAliasesOnlyGrantedToolsAndCallMapsBackToRealName() {
		StubClient delegate = new StubClient();
		AgentScopeMcpToolGrant grant = new AgentScopeMcpToolGrant("demo-echo", "echo.create", "zeus:echo.create",
				"test");
		// 别名不写死：`echo.create` 里的点会被折叠成下划线，`AgentModelToolName.normalize` 因此
		// 追加一段 sha256 短哈希做消歧（该行为由 AgentModelToolNameTest 断言，勿去掉）。本用例要证的是
		// 「缓存/列表按别名暴露、调用时映射回真实工具名」，不是哈希本身，写死摘要只会让它随实现漂移。
		String alias = grant.alias();
		FilteringMcpClientWrapper wrapper = new FilteringMcpClientWrapper("demo-echo", delegate, List.of(grant));

		wrapper.initialize().block();

		assertTrue(delegate.isInitialized());
		assertTrue(wrapper.isInitialized());
		assertTrue(AgentModelToolName.isValid(alias));
		assertNotNull(wrapper.getCachedTool(alias));
		assertNull(wrapper.getCachedTool("order.query"));
		assertNull(wrapper.getCachedTool("echo.create"));

		List<McpSchema.Tool> tools = wrapper.listTools().block();
		assertEquals(List.of(alias), tools.stream().map(McpSchema.Tool::name).toList());

		wrapper.callTool(alias, Map.of("name", "box")).block();
		assertEquals("echo.create", delegate.calledTools.get(0));
	}

	@Test
	void callToolRejectsUnauthorizedAlias() {
		FilteringMcpClientWrapper wrapper = new FilteringMcpClientWrapper("demo-echo", new StubClient(), List.of());

		assertThrows(IllegalArgumentException.class,
				() -> wrapper.callTool("zeus__missing", Map.of()).block());
	}

	private static final class StubClient extends McpClientWrapper {

		private final List<String> calledTools = new ArrayList<>();

		private StubClient() {
			super("stub");
		}

		@Override
		public Mono<Void> initialize() {
			return Mono.fromRunnable(() -> initialized = true);
		}

		@Override
		public Mono<List<McpSchema.Tool>> listTools() {
			if (!initialized) {
				return Mono.error(new IllegalStateException("MCP client '" + name + "' not initialized"));
			}
			return Mono.just(List.of(tool("echo.create"), tool("order.query")));
		}

		@Override
		public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments) {
			calledTools.add(toolName);
			return Mono.just(new McpSchema.CallToolResult("ok", false));
		}

		@Override
		public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments,
				Map<String, Object> metadata) {
			return callTool(toolName, arguments);
		}

		@Override
		public void close() {
		}

		private McpSchema.Tool tool(String name) {
			return new McpSchema.Tool(name, name, name,
					new McpSchema.JsonSchema("object", Map.of(), List.of(), true, Map.of(), Map.of()), Map.of(),
					null, Map.of());
		}

	}

}
