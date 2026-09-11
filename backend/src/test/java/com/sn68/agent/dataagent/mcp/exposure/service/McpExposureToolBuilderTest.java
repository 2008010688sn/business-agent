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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.tool.skilltool.ExecutionResourceToolCallbackFactory;
import com.sn68.agent.dataagent.entity.AgentExecutionResource;
import com.sn68.agent.dataagent.mcp.exposure.entity.AgentMcpExposure;
import com.sn68.agent.dataagent.mcp.exposure.repository.AgentMcpExposureMapper;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpExposureToolBuilderTest {

	private final AgentMcpExposureMapper exposureMapper = mock(AgentMcpExposureMapper.class);

	private final AgentExecutionResourceMapper resourceMapper = mock(AgentExecutionResourceMapper.class);

	private final ExecutionResourceToolCallbackFactory callbackFactory = mock(ExecutionResourceToolCallbackFactory.class);

	private final McpExposureToolBuilder builder = new McpExposureToolBuilder(exposureMapper, resourceMapper,
			callbackFactory, new ObjectMapper());

	@Test
	void callbackRevalidatesExposureAndResourceBeforeEveryInvocation() {
		AgentMcpExposure enabled = exposure("enabled");
		AgentMcpExposure disabled = exposure("disabled");
		AgentExecutionResource resource = resource();
		ToolCallback delegate = mock(ToolCallback.class);
		when(delegate.getToolDefinition()).thenReturn(ToolDefinition.builder()
			.name("customer_data_query")
			.description("客户数据查询")
			.inputSchema("{\"type\":\"object\"}")
			.build());
		when(delegate.call("{}")).thenReturn("{\"ok\":true}");
		when(exposureMapper.findAllOrdered(null, "enabled")).thenReturn(List.of(enabled));
		when(resourceMapper.findEnabledByResourceKey("customer.data.query")).thenReturn(resource);
		when(callbackFactory.create("customer_data_query", "客户数据查询", "{\"type\":\"object\"}",
				"customer.data.query")).thenReturn(delegate);
		when(exposureMapper.selectById(41L)).thenReturn(enabled, disabled);

		McpExposureToolBuilder.ExposureTool tool = builder.buildEnabledTools().get("customer_data_query");

		assertEquals("{\"ok\":true}", tool.callback().call("{}"));
		assertThrows(IllegalStateException.class, () -> tool.callback().call("{}"));
		verify(delegate, times(1)).call("{}");
	}

	private AgentMcpExposure exposure(String status) {
		return AgentMcpExposure.builder()
			.id(41L)
			.exposureCode("customer_query")
			.exposureName("客户数据查询")
			.toolKey("customer.data.query")
			.exposedToolName("customer_data_query")
			.exposureType("TOOL")
			.status(status)
			.extConfig("{\"description\":\"客户数据查询\",\"inputSchema\":{\"type\":\"object\"}}")
			.deleted(false)
			.build();
	}

	private AgentExecutionResource resource() {
		return AgentExecutionResource.builder()
			.id(7L)
			.resourceKey("customer.data.query")
			.resourceName("客户数据查询")
			.enabled(true)
			.status("enabled")
			.deleted(false)
			.build();
	}

}
