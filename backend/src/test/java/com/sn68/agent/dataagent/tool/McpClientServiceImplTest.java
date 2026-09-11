package com.sn68.agent.dataagent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.runtime.mcp.AgentScopeMcpClientFactory;
import com.sn68.agent.dataagent.agentscope.runtime.mcp.DataAgentMcpHeaderProvider;
import com.sn68.agent.dataagent.dto.tool.McpToolCallResult;
import com.sn68.agent.dataagent.entity.AgentMcpServer;
import com.sn68.agent.framework.commons.exception.CheckedException;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpClientServiceImplTest {

	private final AgentScopeMcpClientFactory clientFactory = mock(AgentScopeMcpClientFactory.class);

	private final DataAgentMcpHeaderProvider headerProvider = mock(DataAgentMcpHeaderProvider.class);

	private final McpClientWrapper client = mock(McpClientWrapper.class);

	private final McpClientServiceImpl service = new McpClientServiceImpl(clientFactory, headerProvider,
			new ObjectMapper());

	@Test
	void parsesBusinessJsonFromTextContent() {
		stubClient(new McpSchema.CallToolResult(content("""
				{"success":false,"message":"request is required","missingFields":["request"]}
				"""), false, null, null));

		McpToolCallResult result = service.callTool(server(), "echoExecute", Map.of());

		assertTrue(result.success());
		assertFalse(result.toolError());
		assertEquals(false, result.data().get("success"));
		assertEquals("request is required", result.message());
		assertEquals(List.of("request"), result.data().get("missingFields"));
	}

	@Test
	void structuredContentWinsOverTextJson() {
		stubClient(new McpSchema.CallToolResult(content("""
				{"success":false,"message":"text failed","code":"TEXT_CODE"}
				"""), false, Map.of("success", true, "message", "structured ok"), null));

		McpToolCallResult result = service.callTool(server(), "demoTool", Map.of());

		assertTrue(result.success());
		assertEquals(true, result.data().get("success"));
		assertEquals("structured ok", result.message());
		assertEquals("TEXT_CODE", result.data().get("code"));
	}

	@Test
	void keepsPlainTextContentAsTextOnly() {
		stubClient(new McpSchema.CallToolResult(content("plain ok"), false, null, null));

		McpToolCallResult result = service.callTool(server(), "plainTool", Map.of());

		assertTrue(result.success());
		assertEquals("plain ok", result.message());
		assertEquals("plain ok", result.data().get("text"));
		assertFalse(result.data().containsKey("success"));
	}

	@Test
	void exposesTopLevelArrayAsItems() {
		stubClient(new McpSchema.CallToolResult(content("""
				[{"id":"1","name":"Customer A"},{"id":"2","name":"Customer B"}]
				"""), false, null, null));

		McpToolCallResult result = service.callTool(server(), "demoTool", Map.of());

		assertEquals(2, ((List<?>) result.data().get("items")).size());
	}

	@Test
	void preservesToolTimeoutCauseForFlowFailureClassification() {
		when(headerProvider.effectiveHeaders()).thenReturn(Map.of());
		when(clientFactory.create(any(AgentMcpServer.class), anyMap())).thenReturn(client);
		when(client.initialize()).thenReturn(Mono.empty());
		when(client.callTool(eq("echoExecute"), anyMap()))
			.thenReturn(Mono.error(new TimeoutException("request timed out")));

		CheckedException error = assertThrows(CheckedException.class,
				() -> service.callTool(server(), "echoExecute", Map.of()));

		assertEquals("MCP tool execution failed.", error.getMessage());
		assertTrue(hasCause(error, TimeoutException.class));
	}

	private void stubClient(McpSchema.CallToolResult result) {
		when(headerProvider.effectiveHeaders()).thenReturn(Map.of());
		when(clientFactory.create(any(AgentMcpServer.class), anyMap())).thenReturn(client);
		when(client.initialize()).thenReturn(Mono.empty());
		when(client.callTool(eq("echoExecute"), anyMap())).thenReturn(Mono.just(result));
		when(client.callTool(eq("demoTool"), anyMap())).thenReturn(Mono.just(result));
		when(client.callTool(eq("plainTool"), anyMap())).thenReturn(Mono.just(result));
	}

	private List<McpSchema.Content> content(String text) {
		return List.of(new McpSchema.TextContent(text));
	}

	private AgentMcpServer server() {
		AgentMcpServer server = new AgentMcpServer();
		server.setServerCode("demo-echo");
		return server;
	}

	private boolean hasCause(Throwable error, Class<? extends Throwable> type) {
		Throwable current = error;
		while (current != null) {
			if (type.isInstance(current)) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

}
