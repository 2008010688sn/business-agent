package com.sn68.agent.dataagent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.temporal.AgentTemporalService;
import com.sn68.agent.dataagent.dto.tool.McpToolCallResult;
import com.sn68.agent.dataagent.entity.AgentExecutionResource;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceMapper;
import com.sn68.agent.dataagent.entity.AgentMcpServer;
import com.sn68.agent.dataagent.repository.AgentMcpServerMapper;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolTransportInvokerImplTest {

	private final AgentExecutionResourceMapper resourceMapper = mock(AgentExecutionResourceMapper.class);

	private final AgentMcpServerMapper mcpServerMapper = mock(AgentMcpServerMapper.class);

	private final McpClientService mcpClientService = mock(McpClientService.class);

	private final McpToolArgumentNormalizer mcpToolArgumentNormalizer = new McpToolArgumentNormalizer(new ObjectMapper(),
			new AgentTemporalService());

	private final DataQueryToolService dataQueryToolService = mock(DataQueryToolService.class);

	private final DiscoveryClient discoveryClient = mock(DiscoveryClient.class);

	private final AuthenticationContext authenticationContext = mock(AuthenticationContext.class);

	private final ToolTransportInvokerImpl service = new ToolTransportInvokerImpl(resourceMapper,
			mcpServerMapper, mcpClientService, mcpToolArgumentNormalizer, dataQueryToolService, WebClient.builder(),
			discoveryClient, new ObjectMapper(), authenticationContext);

	@Test
	void invokeMcpPreservesBusinessSuccessFalse() {
		AgentExecutionResource resource = new AgentExecutionResource();
		resource.setResourceKey("demo.echo.execute");
		resource.setResourceType("MCP_TOOL");
		resource.setServerCode("demo-echo");
		resource.setToolName("echoExecute");
		AgentMcpServer server = new AgentMcpServer();
		server.setServerCode("demo-echo");
		server.setStatus("enabled");
		when(resourceMapper.findEnabledByResourceKey("demo.echo.execute")).thenReturn(resource);
		when(mcpServerMapper.findByServerCode("demo-echo")).thenReturn(server);
		when(mcpClientService.callTool(eq(server), eq("echoExecute"), anyMap()))
			.thenReturn(new McpToolCallResult(true, false, "下单信息不完整",
					Map.of("success", false, "message", "下单信息不完整")));

		Map<String, Object> result = service.invoke("demo.echo.execute", Map.of());

		assertEquals(false, result.get("success"));
		assertEquals(true, result.get("mcpSuccess"));
		assertEquals(false, result.get("toolError"));
		assertEquals("下单信息不完整", result.get("message"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void invokeMcpWrapsArgumentsByResourceInputSchema() {
		AgentExecutionResource resource = new AgentExecutionResource();
		resource.setResourceKey("demo.echo.latest");
		resource.setResourceType("MCP_TOOL");
		resource.setServerCode("demo-echo");
		resource.setToolName("echoLatest");
		resource.setExtConfig(wrapperSchema());
		AgentMcpServer server = new AgentMcpServer();
		server.setServerCode("demo-echo");
		server.setStatus("enabled");
		when(resourceMapper.findEnabledByResourceKey("demo.echo.latest")).thenReturn(resource);
		when(mcpServerMapper.findByServerCode("demo-echo")).thenReturn(server);
		when(mcpClientService.callTool(eq(server), eq("echoLatest"), anyMap()))
			.thenReturn(new McpToolCallResult(true, false, "ok", Map.of("success", true)));

		service.invoke("demo.echo.latest",
				Map.of("companyId", "CN022203", "oneProjectId", "ed4645", "twoProjectId", "202974",
						"runtimeRequestId", "runtime-1"));

		ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
		verify(mcpClientService).callTool(eq(server), eq("echoLatest"), captor.capture());
		Map<String, Object> normalized = captor.getValue();
		Map<String, Object> request = (Map<String, Object>) normalized.get("request");
		assertEquals(1, normalized.size());
		assertEquals("CN022203", request.get("companyId"));
		assertEquals("ed4645", request.get("oneProjectId"));
		assertEquals("202974", request.get("twoProjectId"));
		assertFalse(request.containsKey("runtimeRequestId"));
	}

	private String wrapperSchema() {
		return """
				{"inputSchema":{"type":"object","required":["request"],"properties":{"request":{"type":"object","properties":{"companyId":{"type":"string"},"oneProjectId":{"type":"string"},"twoProjectId":{"type":"string"}}}}}}
				""";
	}

}
