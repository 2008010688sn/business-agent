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
package com.sn68.agent.dataagent.tool;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.dto.tool.ToolPageQueryReq;
import com.sn68.agent.dataagent.dto.tool.ToolResourceDTO;
import com.sn68.agent.dataagent.entity.AgentExecutionResource;
import com.sn68.agent.dataagent.properties.ToolCenterProperties;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceMapper;
import com.sn68.agent.dataagent.entity.AgentMcpServer;
import com.sn68.agent.dataagent.entity.AgentMcpTool;
import com.sn68.agent.dataagent.dto.tool.ToolReferenceResp;
import com.sn68.agent.dataagent.repository.AgentMcpServerMapper;
import com.sn68.agent.dataagent.repository.AgentMcpToolMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillToolRefMapper;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ToolDirectoryServiceImplTest {

	private static final String SERVER_CODE = "demo-echo";

	private static final String TOOL_NAME = "customerOptions";

	private static final String RESOURCE_KEY = SERVER_CODE + "." + TOOL_NAME;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void pageResourcesOnlyQueriesPersistedResources() {
		AgentExecutionResourceMapper resourceMapper = mock(AgentExecutionResourceMapper.class);
		AgentMcpToolMapper mcpToolMapper = mock(AgentMcpToolMapper.class);
		AgentMcpServerMapper mcpServerMapper = mock(AgentMcpServerMapper.class);
		McpClientService mcpClientService = mock(McpClientService.class);
		ToolPageQueryReq query = new ToolPageQueryReq();
		Page<AgentExecutionResource> page = new Page<>(1, 20);
		page.setTotal(1);
		page.setRecords(List.of(resource("{}")));
		AuthenticationContext authenticationContext = mock(AuthenticationContext.class);
		when(authenticationContext.tenantId()).thenReturn("tenant-a");
		when(resourceMapper.selectResourcePage(any(), same(query), eq("tenant-a"))).thenReturn(page);

		var result = service(resourceMapper, mcpToolMapper, mcpServerMapper, mcpClientService,
				mock(DataAgentSkillToolRefMapper.class), authenticationContext)
			.pageResources(query);

		assertEquals(1, result.getRecords().size());
		verify(resourceMapper).selectResourcePage(any(), same(query), eq("tenant-a"));
		verify(resourceMapper, never()).insert(any(AgentExecutionResource.class));
		verify(resourceMapper, never()).updateById(any(AgentExecutionResource.class));
		verifyNoInteractions(mcpToolMapper, mcpServerMapper, mcpClientService);
	}

	@Test
	void listResourcesDoesNotRebuildMcpDirectory() {
		AgentExecutionResourceMapper resourceMapper = mock(AgentExecutionResourceMapper.class);
		AgentMcpToolMapper mcpToolMapper = mock(AgentMcpToolMapper.class);
		AgentMcpServerMapper mcpServerMapper = mock(AgentMcpServerMapper.class);
		McpClientService mcpClientService = mock(McpClientService.class);
		AuthenticationContext authenticationContext = mock(AuthenticationContext.class);
		when(authenticationContext.tenantId()).thenReturn("tenant-a");
		when(resourceMapper.findAllOrdered("tenant-a")).thenReturn(List.of(resource("{}")));

		List<ToolResourceDTO> resources = service(resourceMapper, mcpToolMapper, mcpServerMapper,
				mcpClientService, mock(DataAgentSkillToolRefMapper.class), authenticationContext).listResources();

		assertEquals(1, resources.size());
		verify(resourceMapper).findAllOrdered("tenant-a");
		verify(resourceMapper, never()).insert(any(AgentExecutionResource.class));
		verify(resourceMapper, never()).updateById(any(AgentExecutionResource.class));
		verifyNoInteractions(mcpToolMapper, mcpServerMapper, mcpClientService);
	}

	@Test
	void listMcpServersDoesNotSynchronizeConfiguredServers() {
		AgentExecutionResourceMapper resourceMapper = mock(AgentExecutionResourceMapper.class);
		AgentMcpToolMapper mcpToolMapper = mock(AgentMcpToolMapper.class);
		AgentMcpServerMapper mcpServerMapper = mock(AgentMcpServerMapper.class);
		McpClientService mcpClientService = mock(McpClientService.class);
		ToolCenterProperties properties = new ToolCenterProperties();
		ToolCenterProperties.McpServer configuredServer = new ToolCenterProperties.McpServer();
		configuredServer.setEnabled(true);
		configuredServer.setServiceName(SERVER_CODE);
		properties.getMcp().getServers().put(SERVER_CODE, configuredServer);
		AgentMcpServer persistedServer = mcpServer();
		when(mcpServerMapper.findAllActive()).thenReturn(List.of(persistedServer));

		List<AgentMcpServer> servers = new ToolDirectoryServiceImpl(resourceMapper, mcpServerMapper,
				mcpToolMapper, mock(DataAgentSkillToolRefMapper.class), mcpClientService, properties, objectMapper,
				mock(AuthenticationContext.class), directTransactionTemplate()).listMcpServers();

		assertEquals(List.of(persistedServer), servers);
		verify(mcpServerMapper).findAllActive();
		verify(mcpServerMapper, never()).findByServerCode(any());
		verify(mcpServerMapper, never()).insert(any(AgentMcpServer.class));
		verify(mcpServerMapper, never()).updateById(any(AgentMcpServer.class));
		verifyNoInteractions(resourceMapper, mcpToolMapper, mcpClientService);
	}

	@Test
	void listReferencesUsesTenantVisiblePublishedSkillReferences() {
		AgentExecutionResourceMapper resourceMapper = mock(AgentExecutionResourceMapper.class);
		DataAgentSkillToolRefMapper skillToolRefMapper = mock(DataAgentSkillToolRefMapper.class);
		AuthenticationContext authenticationContext = mock(AuthenticationContext.class);
		ToolReferenceResp reference = new ToolReferenceResp();
		reference.setSkillCode("demand-create");
		when(authenticationContext.tenantId()).thenReturn("tenant-a");
		when(skillToolRefMapper.findVisiblePublishedReferences(RESOURCE_KEY, "tenant-a"))
			.thenReturn(List.of(reference));

		List<ToolReferenceResp> references = service(resourceMapper, mock(AgentMcpToolMapper.class),
				mock(AgentMcpServerMapper.class), mock(McpClientService.class), skillToolRefMapper,
				authenticationContext).listReferences(RESOURCE_KEY);

		assertEquals(List.of(reference), references);
	}

	@Test
	void deleteResourceRejectsAnyPublishedSkillReference() {
		AgentExecutionResourceMapper resourceMapper = mock(AgentExecutionResourceMapper.class);
		DataAgentSkillToolRefMapper skillToolRefMapper = mock(DataAgentSkillToolRefMapper.class);
		AgentExecutionResource resource = resource("{}");
		when(resourceMapper.findByResourceKey(RESOURCE_KEY)).thenReturn(resource);
		when(skillToolRefMapper.countPublishedReferences(RESOURCE_KEY)).thenReturn(1L);

		ToolDirectoryServiceImpl service = service(resourceMapper, mock(AgentMcpToolMapper.class),
				mock(AgentMcpServerMapper.class), mock(McpClientService.class), skillToolRefMapper,
				mock(AuthenticationContext.class));

		assertThrows(RuntimeException.class, () -> service.deleteResource(RESOURCE_KEY));
	}

	@Test
	void addMcpToolCopiesToolDescriptionIntoResourceExtConfig() {
		AgentExecutionResourceMapper resourceMapper = mock(AgentExecutionResourceMapper.class);
		AgentMcpToolMapper mcpToolMapper = mock(AgentMcpToolMapper.class);
		AgentMcpTool tool = mcpTool("查询下单客户候选。");
		AtomicReference<AgentExecutionResource> saved = new AtomicReference<>();
		when(mcpToolMapper.findByServerCodeAndToolName(SERVER_CODE, TOOL_NAME)).thenReturn(tool);
		when(resourceMapper.findByResourceKey(RESOURCE_KEY)).thenAnswer(invocation -> saved.get());
		doAnswer(invocation -> {
			AgentExecutionResource resource = invocation.getArgument(0);
			resource.setId(1L);
			saved.set(resource);
			return 1;
		}).when(resourceMapper).insert(any(AgentExecutionResource.class));

		ToolResourceDTO resource = service(resourceMapper, mcpToolMapper).addMcpTool(SERVER_CODE, TOOL_NAME);

		assertEquals("查询下单客户候选。", resource.extConfig().get("description"));
		assertEquals(Boolean.TRUE, resource.extConfig().get("readonly"));
		assertFalse(resource.extConfig().containsKey("mcpArgumentWrapper"));
	}

	@Test
	void addMcpToolPreservesExistingUsageWhenRefreshingDescription() {
		AgentExecutionResourceMapper resourceMapper = mock(AgentExecutionResourceMapper.class);
		AgentMcpToolMapper mcpToolMapper = mock(AgentMcpToolMapper.class);
		AgentMcpTool tool = mcpTool("查询下单客户候选。");
		AgentExecutionResource existing = new AgentExecutionResource();
		existing.setId(1L);
		existing.setResourceKey(RESOURCE_KEY);
		existing.setResourceType("MCP_TOOL");
		existing.setDisplayOrder(10);
		existing.setExtConfig("{\"description\":\"旧描述\",\"usage\":\"客户字段补全\"}");
		AtomicReference<AgentExecutionResource> saved = new AtomicReference<>(existing);
		when(mcpToolMapper.findByServerCodeAndToolName(SERVER_CODE, TOOL_NAME)).thenReturn(tool);
		when(resourceMapper.findByResourceKeyIncludingDeleted(RESOURCE_KEY)).thenAnswer(invocation -> saved.get());
		when(resourceMapper.findByResourceKey(RESOURCE_KEY)).thenAnswer(invocation -> saved.get());
		doAnswer(invocation -> {
			saved.set(invocation.getArgument(0));
			return 1;
		}).when(resourceMapper).updateById(any(AgentExecutionResource.class));

		ToolResourceDTO resource = service(resourceMapper, mcpToolMapper).addMcpTool(SERVER_CODE, TOOL_NAME);

		assertEquals("查询下单客户候选。", resource.extConfig().get("description"));
		assertEquals("客户字段补全", resource.extConfig().get("usage"));
	}

	@Test
	void addMcpToolPreservesLocalMcpArgumentContractWhenRefreshingSchema() {
		AgentExecutionResourceMapper resourceMapper = mock(AgentExecutionResourceMapper.class);
		AgentMcpToolMapper mcpToolMapper = mock(AgentMcpToolMapper.class);
		AgentMcpTool tool = mcpTool("创建需求单。");
		tool.setInputSchema("{\"type\":\"object\",\"properties\":{\"companyName\":{\"type\":\"string\"}}}");
		AgentExecutionResource existing = new AgentExecutionResource();
		existing.setId(1L);
		existing.setResourceKey(RESOURCE_KEY);
		existing.setResourceType("MCP_TOOL");
		existing.setDisplayOrder(10);
		existing.setExtConfig("""
				{
				  "mcpArgumentWrapper":"request",
				  "mcpExposure":"WRAPPED_ONLY",
				  "mcpTemporalFields":["arrivalTime"],
				  "capabilityParameterSchema":{"type":"object","properties":{"request":{"type":"object"}}},
				  "description":"旧描述"
				}
				""");
		AtomicReference<AgentExecutionResource> saved = new AtomicReference<>(existing);
		when(mcpToolMapper.findByServerCodeAndToolName(SERVER_CODE, TOOL_NAME)).thenReturn(tool);
		when(resourceMapper.findByResourceKeyIncludingDeleted(RESOURCE_KEY)).thenAnswer(invocation -> saved.get());
		when(resourceMapper.findByResourceKey(RESOURCE_KEY)).thenAnswer(invocation -> saved.get());
		doAnswer(invocation -> {
			saved.set(invocation.getArgument(0));
			return 1;
		}).when(resourceMapper).updateById(any(AgentExecutionResource.class));

		ToolResourceDTO resource = service(resourceMapper, mcpToolMapper).addMcpTool(SERVER_CODE, TOOL_NAME);

		assertEquals("创建需求单。", resource.extConfig().get("description"));
		assertEquals("request", resource.extConfig().get("mcpArgumentWrapper"));
		assertEquals("WRAPPED_ONLY", resource.extConfig().get("mcpExposure"));
		assertEquals("arrivalTime", ((java.util.List<?>) resource.extConfig().get("mcpTemporalFields")).get(0));
		assertEquals("object",
				((java.util.Map<?, ?>) resource.extConfig().get("capabilityParameterSchema")).get("type"));
		assertEquals("object", ((java.util.Map<?, ?>) resource.extConfig().get("inputSchema")).get("type"));
	}

	@Test
	void syncMcpToolsPreservesExistingSafetyFlagsWhenHintsAreMissing() throws Exception {
		AgentExecutionResourceMapper resourceMapper = mock(AgentExecutionResourceMapper.class);
		AgentMcpToolMapper mcpToolMapper = mock(AgentMcpToolMapper.class);
		AgentMcpServerMapper mcpServerMapper = mock(AgentMcpServerMapper.class);
		McpClientService mcpClientService = mock(McpClientService.class);
		AgentMcpTool existingTool = mcpTool("old description");
		existingTool.setId(1L);
		existingTool.setReadonly(true);
		existingTool.setConfirmRequired(false);
		AtomicReference<AgentMcpTool> savedTool = new AtomicReference<>(existingTool);
		AgentExecutionResource existingResource = resource(
				"{\"readonly\":true,\"confirmRequired\":false,\"usage\":\"customer fill\"}");
		AtomicReference<AgentExecutionResource> savedResource = new AtomicReference<>(existingResource);
		AgentMcpServer server = mcpServer();
		when(mcpServerMapper.findAllActive()).thenReturn(List.of(server));
		when(mcpClientService.listTools(server)).thenReturn(List.of(mcpToolPayload(null, null)));
		when(mcpToolMapper.findByServerCodeAndToolNameIncludingDeleted(SERVER_CODE, TOOL_NAME))
			.thenAnswer(invocation -> savedTool.get());
		when(resourceMapper.findByResourceKeyIncludingDeleted(RESOURCE_KEY)).thenAnswer(invocation -> savedResource.get());
		when(resourceMapper.findByResourceKey(RESOURCE_KEY)).thenAnswer(invocation -> savedResource.get());
		when(resourceMapper.findAllOrdered()).thenReturn(List.of());
		doAnswer(invocation -> {
			savedTool.set(invocation.getArgument(0));
			return 1;
		}).when(mcpToolMapper).updateById(any(AgentMcpTool.class));
		doAnswer(invocation -> {
			savedResource.set(invocation.getArgument(0));
			return 1;
		}).when(resourceMapper).updateById(any(AgentExecutionResource.class));

		service(resourceMapper, mcpToolMapper, mcpServerMapper, mcpClientService).syncMcpTools();

		assertEquals(Boolean.TRUE, savedTool.get().getReadonly());
		assertEquals(Boolean.FALSE, savedTool.get().getConfirmRequired());
		assertEquals("READ", savedTool.get().getRiskLevel());
		Map<?, ?> extConfig = objectMapper.readValue(savedResource.get().getExtConfig(), Map.class);
		assertEquals(Boolean.TRUE, extConfig.get("readonly"));
		assertEquals(Boolean.FALSE, extConfig.get("confirmRequired"));
		assertEquals("customer fill", extConfig.get("usage"));
	}

	@Test
	void syncMcpToolsUsesExplicitMcpHintsWhenProvided() throws Exception {
		AgentExecutionResourceMapper resourceMapper = mock(AgentExecutionResourceMapper.class);
		AgentMcpToolMapper mcpToolMapper = mock(AgentMcpToolMapper.class);
		AgentMcpServerMapper mcpServerMapper = mock(AgentMcpServerMapper.class);
		McpClientService mcpClientService = mock(McpClientService.class);
		AgentMcpTool existingTool = mcpTool("old description");
		existingTool.setId(1L);
		existingTool.setReadonly(true);
		existingTool.setConfirmRequired(false);
		AtomicReference<AgentMcpTool> savedTool = new AtomicReference<>(existingTool);
		AgentExecutionResource existingResource = resource("{\"readonly\":true,\"confirmRequired\":false}");
		AtomicReference<AgentExecutionResource> savedResource = new AtomicReference<>(existingResource);
		AgentMcpServer server = mcpServer();
		when(mcpServerMapper.findAllActive()).thenReturn(List.of(server));
		when(mcpClientService.listTools(server)).thenReturn(List.of(mcpToolPayload(false, true)));
		when(mcpToolMapper.findByServerCodeAndToolNameIncludingDeleted(SERVER_CODE, TOOL_NAME))
			.thenAnswer(invocation -> savedTool.get());
		when(resourceMapper.findByResourceKeyIncludingDeleted(RESOURCE_KEY)).thenAnswer(invocation -> savedResource.get());
		when(resourceMapper.findByResourceKey(RESOURCE_KEY)).thenAnswer(invocation -> savedResource.get());
		when(resourceMapper.findAllOrdered()).thenReturn(List.of());
		doAnswer(invocation -> {
			savedTool.set(invocation.getArgument(0));
			return 1;
		}).when(mcpToolMapper).updateById(any(AgentMcpTool.class));
		doAnswer(invocation -> {
			savedResource.set(invocation.getArgument(0));
			return 1;
		}).when(resourceMapper).updateById(any(AgentExecutionResource.class));

		service(resourceMapper, mcpToolMapper, mcpServerMapper, mcpClientService).syncMcpTools();

		assertEquals(Boolean.FALSE, savedTool.get().getReadonly());
		assertEquals(Boolean.TRUE, savedTool.get().getConfirmRequired());
		assertEquals("WRITE", savedTool.get().getRiskLevel());
		Map<?, ?> extConfig = objectMapper.readValue(savedResource.get().getExtConfig(), Map.class);
		assertEquals(Boolean.FALSE, extConfig.get("readonly"));
		assertEquals(Boolean.TRUE, extConfig.get("confirmRequired"));
	}

	@Test
	void syncMcpToolsDefaultsNewToolWithoutHintsToNonReadonly() throws Exception {
		AgentExecutionResourceMapper resourceMapper = mock(AgentExecutionResourceMapper.class);
		AgentMcpToolMapper mcpToolMapper = mock(AgentMcpToolMapper.class);
		AgentMcpServerMapper mcpServerMapper = mock(AgentMcpServerMapper.class);
		McpClientService mcpClientService = mock(McpClientService.class);
		AtomicReference<AgentMcpTool> savedTool = new AtomicReference<>();
		AtomicReference<AgentExecutionResource> savedResource = new AtomicReference<>();
		AgentMcpServer server = mcpServer();
		when(mcpServerMapper.findAllActive()).thenReturn(List.of(server));
		when(mcpClientService.listTools(server)).thenReturn(List.of(mcpToolPayload(null, null)));
		when(mcpToolMapper.findByServerCodeAndToolNameIncludingDeleted(SERVER_CODE, TOOL_NAME)).thenReturn(null);
		when(resourceMapper.findByResourceKeyIncludingDeleted(RESOURCE_KEY)).thenAnswer(invocation -> savedResource.get());
		when(resourceMapper.findByResourceKey(RESOURCE_KEY)).thenAnswer(invocation -> savedResource.get());
		when(resourceMapper.findAllOrdered()).thenReturn(List.of());
		doAnswer(invocation -> {
			AgentMcpTool tool = invocation.getArgument(0);
			tool.setId(1L);
			savedTool.set(tool);
			return 1;
		}).when(mcpToolMapper).insert(any(AgentMcpTool.class));
		doAnswer(invocation -> {
			AgentExecutionResource resource = invocation.getArgument(0);
			resource.setId(2L);
			savedResource.set(resource);
			return 1;
		}).when(resourceMapper).insert(any(AgentExecutionResource.class));

		service(resourceMapper, mcpToolMapper, mcpServerMapper, mcpClientService).syncMcpTools();

		assertEquals(Boolean.FALSE, savedTool.get().getReadonly());
		assertEquals(Boolean.FALSE, savedTool.get().getConfirmRequired());
		assertEquals("WRITE", savedTool.get().getRiskLevel());
		Map<?, ?> extConfig = objectMapper.readValue(savedResource.get().getExtConfig(), Map.class);
		assertEquals(Boolean.FALSE, extConfig.get("readonly"));
		assertEquals(Boolean.FALSE, extConfig.get("confirmRequired"));
	}

	@Test
	void syncMcpToolsDeletesToolsMissingFromServerList() throws Exception {
		AgentExecutionResourceMapper resourceMapper = mock(AgentExecutionResourceMapper.class);
		AgentMcpToolMapper mcpToolMapper = mock(AgentMcpToolMapper.class);
		AgentMcpServerMapper mcpServerMapper = mock(AgentMcpServerMapper.class);
		McpClientService mcpClientService = mock(McpClientService.class);
		AgentMcpTool currentTool = mcpTool("current description");
		currentTool.setId(1L);
		AgentExecutionResource currentResource = resource("{}");
		AgentExecutionResource staleResource = resource("{}");
		staleResource.setId(99L);
		staleResource.setServerCode(SERVER_CODE);
		staleResource.setToolName("demandProductOptions");
		// MCP 资源的 resourceKey 恒为 serverCode + "." + toolName，夹具要与线上数据保持一致。
		staleResource.setResourceKey(SERVER_CODE + ".demandProductOptions");
		staleResource.setEnabled(true);
		staleResource.setStatus("enabled");
		AgentMcpServer server = mcpServer();
		when(mcpServerMapper.findAllActive()).thenReturn(List.of(server));
		when(mcpClientService.listTools(server)).thenReturn(List.of(mcpToolPayload(null, null)));
		when(mcpToolMapper.findByServerCodeAndToolNameIncludingDeleted(SERVER_CODE, TOOL_NAME))
			.thenReturn(currentTool);
		when(resourceMapper.findByResourceKeyIncludingDeleted(RESOURCE_KEY)).thenReturn(currentResource);
		when(resourceMapper.findByResourceKey(RESOURCE_KEY)).thenReturn(currentResource);
		when(resourceMapper.findAllOrdered()).thenReturn(List.of(staleResource));

		service(resourceMapper, mcpToolMapper, mcpServerMapper, mcpClientService).syncMcpTools();

		verify(mcpToolMapper).deleteToolsNotIn(SERVER_CODE, List.of(TOOL_NAME));
		verify(resourceMapper).deleteById(99L);
		assertEquals(Boolean.FALSE, staleResource.getEnabled());
		assertEquals("disabled", staleResource.getStatus());
	}

	private ToolDirectoryServiceImpl service(AgentExecutionResourceMapper resourceMapper,
			AgentMcpToolMapper mcpToolMapper) {
		return service(resourceMapper, mcpToolMapper, mock(AgentMcpServerMapper.class), mock(McpClientService.class));
	}

	private ToolDirectoryServiceImpl service(AgentExecutionResourceMapper resourceMapper,
			AgentMcpToolMapper mcpToolMapper, AgentMcpServerMapper mcpServerMapper, McpClientService mcpClientService) {
		return service(resourceMapper, mcpToolMapper, mcpServerMapper, mcpClientService,
				mock(DataAgentSkillToolRefMapper.class), mock(AuthenticationContext.class));
	}

	private ToolDirectoryServiceImpl service(AgentExecutionResourceMapper resourceMapper,
			AgentMcpToolMapper mcpToolMapper, AgentMcpServerMapper mcpServerMapper, McpClientService mcpClientService,
			DataAgentSkillToolRefMapper skillToolRefMapper, AuthenticationContext authenticationContext) {
		return new ToolDirectoryServiceImpl(resourceMapper, mcpServerMapper, mcpToolMapper, skillToolRefMapper,
				mcpClientService, new ToolCenterProperties(), objectMapper, authenticationContext,
				directTransactionTemplate());
	}

	@SuppressWarnings("unchecked")
	private TransactionTemplate directTransactionTemplate() {
		TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
		doAnswer(invocation -> {
			invocation.getArgument(0, Consumer.class).accept(mock(TransactionStatus.class));
			return null;
		}).when(transactionTemplate).executeWithoutResult(any());
		return transactionTemplate;
	}

	private AgentMcpTool mcpTool(String description) {
		AgentMcpTool tool = new AgentMcpTool();
		tool.setServerCode(SERVER_CODE);
		tool.setToolName(TOOL_NAME);
		tool.setToolTitle("下单客户候选");
		tool.setDescription(description);
		tool.setInputSchema("{\"type\":\"object\"}");
		tool.setReadonly(true);
		tool.setConfirmRequired(false);
		tool.setStatus("enabled");
		return tool;
	}

	private AgentExecutionResource resource(String extConfig) {
		AgentExecutionResource resource = new AgentExecutionResource();
		resource.setId(2L);
		resource.setResourceKey(RESOURCE_KEY);
		resource.setResourceType("MCP_TOOL");
		resource.setDisplayOrder(10);
		resource.setExtConfig(extConfig);
		return resource;
	}

	private AgentMcpServer mcpServer() {
		AgentMcpServer server = new AgentMcpServer();
		server.setId(3L);
		server.setServerCode(SERVER_CODE);
		server.setStatus("enabled");
		return server;
	}

	private Map<String, Object> mcpToolPayload(Boolean readOnlyHint, Boolean destructiveHint) {
		Map<String, Object> tool = new LinkedHashMap<>();
		tool.put("name", TOOL_NAME);
		tool.put("title", "customer options");
		tool.put("description", "customer options");
		tool.put("inputSchema", Map.of("type", "object"));
		tool.put("outputSchema", Map.of("type", "object"));
		if (readOnlyHint != null) {
			tool.put("readOnlyHint", readOnlyHint);
		}
		if (destructiveHint != null) {
			tool.put("destructiveHint", destructiveHint);
		}
		return tool;
	}

}
