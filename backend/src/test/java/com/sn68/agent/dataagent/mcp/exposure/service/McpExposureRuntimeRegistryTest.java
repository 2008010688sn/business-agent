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
package com.sn68.agent.dataagent.mcp.exposure.service;

import com.sn68.agent.dataagent.mcp.exposure.service.McpExposureToolBuilder.ExposureTool;
import com.sn68.agent.dataagent.mcp.exposure.service.McpExposureToolBuilder.ExposureToolSnapshot;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpExposureRuntimeRegistryTest {

	@Test
	void applicationReadyReconcilesEnabledExposureTools() {
		McpSyncServer server = mock(McpSyncServer.class);
		McpExposureToolBuilder builder = mock(McpExposureToolBuilder.class);
		McpExposureRuntimeRegistry registry = new McpExposureRuntimeRegistry(server, builder);
		when(server.listTools()).thenReturn(List.of());
		when(builder.buildEnabledTools()).thenReturn(Map.of("dynamic_tool", tool("dynamic_tool", "启动加载")));

		registry.onApplicationReady();

		verify(server).addTool(any(McpServerFeatures.SyncToolSpecification.class));
	}

	@Test
	void reconcileAddsChangesAndRemovesOnlyManagedExposureTools() {
		McpSyncServer server = mock(McpSyncServer.class);
		McpExposureToolBuilder builder = mock(McpExposureToolBuilder.class);
		McpExposureRuntimeRegistry registry = new McpExposureRuntimeRegistry(server, builder);
		ExposureTool initial = tool("dynamic_tool", "初始描述");
		ExposureTool changed = tool("dynamic_tool", "变更描述");
		when(server.listTools()).thenReturn(List.of(schemaTool("static_tool")));
		when(builder.buildEnabledTools())
			.thenReturn(Map.of("dynamic_tool", initial))
			.thenReturn(Map.of("dynamic_tool", initial))
			.thenReturn(Map.of("dynamic_tool", changed))
			.thenReturn(Map.of());

		registry.reconcile();
		verify(server).addTool(any(McpServerFeatures.SyncToolSpecification.class));
		verify(server, never()).removeTool(any());

		clearInvocations(server);
		registry.reconcile();
		verify(server, never()).addTool(any(McpServerFeatures.SyncToolSpecification.class));
		verify(server, never()).removeTool(any());

		registry.reconcile();
		verify(server).removeTool("dynamic_tool");
		verify(server).addTool(any(McpServerFeatures.SyncToolSpecification.class));

		registry.reconcile();
		verify(server, times(2)).removeTool("dynamic_tool");
		verify(server, never()).removeTool("static_tool");
		verify(server, never()).notifyToolsListChanged();
	}

	@Test
	void reconcileNeverOverwritesStaticTool() {
		McpSyncServer server = mock(McpSyncServer.class);
		McpExposureToolBuilder builder = mock(McpExposureToolBuilder.class);
		McpExposureRuntimeRegistry registry = new McpExposureRuntimeRegistry(server, builder);
		when(server.listTools()).thenReturn(List.of(schemaTool("static_tool")));
		when(builder.buildEnabledTools()).thenReturn(Map.of("static_tool", tool("static_tool", "冲突描述")));

		registry.reconcile();

		assertTrue(registry.isStaticToolName("static_tool"));
		verify(server, never()).addTool(any(McpServerFeatures.SyncToolSpecification.class));
		verify(server, never()).removeTool(any());
	}

	@Test
	void reconcilePublishesToolDefinitionThroughStandardSpringAiConverter() {
		McpSyncServer server = mock(McpSyncServer.class);
		McpExposureToolBuilder builder = mock(McpExposureToolBuilder.class);
		McpExposureRuntimeRegistry registry = new McpExposureRuntimeRegistry(server, builder);
		when(server.listTools()).thenReturn(List.of());
		when(builder.buildEnabledTools()).thenReturn(Map.of("dynamic_tool", tool("dynamic_tool", "对外描述")));

		registry.reconcile();

		ArgumentCaptor<McpServerFeatures.SyncToolSpecification> captor =
				ArgumentCaptor.forClass(McpServerFeatures.SyncToolSpecification.class);
		verify(server).addTool(captor.capture());
		assertEquals("dynamic_tool", captor.getValue().tool().name());
		assertEquals("对外描述", captor.getValue().tool().description());
	}

	private ExposureTool tool(String name, String description) {
		ExposureToolSnapshot snapshot = new ExposureToolSnapshot(41L, "exposure_code", name, description,
				"{\"type\":\"object\"}", "customer.data.query");
		ToolCallback callback = new ToolCallback() {
			private final ToolDefinition definition = ToolDefinition.builder()
				.name(name)
				.description(description)
				.inputSchema("{\"type\":\"object\"}")
				.build();

			@Override
			public ToolDefinition getToolDefinition() {
				return definition;
			}

			@Override
			public String call(String toolInput) {
				return "{}";
			}
		};
		return new ExposureTool(snapshot, callback);
	}

	private McpSchema.Tool schemaTool(String name) {
		return McpSchema.Tool.builder()
			.name(name)
			.description(name)
			.inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), true, Map.of(), Map.of()))
			.build();
	}

}
