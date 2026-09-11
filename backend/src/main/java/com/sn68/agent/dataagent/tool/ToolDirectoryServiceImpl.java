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

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.dto.tool.ToolPageQueryReq;
import com.sn68.agent.dataagent.dto.tool.ToolResourceDTO;
import com.sn68.agent.dataagent.entity.AgentExecutionResource;
import com.sn68.agent.dataagent.properties.ToolCenterProperties;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceMapper;
import com.sn68.agent.dataagent.dto.tool.ToolReferenceResp;
import com.sn68.agent.dataagent.entity.AgentMcpServer;
import com.sn68.agent.dataagent.entity.AgentMcpTool;
import com.sn68.agent.dataagent.repository.AgentMcpServerMapper;
import com.sn68.agent.dataagent.repository.AgentMcpToolMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillToolRefMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Slf4j
/**
 * 工具目录服务：统一管理执行资源与 MCP 工具的目录视图（分页/详情/启停/引用关系），供工具中心管理端使用。
 */
@Service
@RequiredArgsConstructor
public class ToolDirectoryServiceImpl implements ToolDirectoryService {

	private static final String STATUS_ENABLED = "enabled";

	private static final String STATUS_DISABLED = "disabled";

	private static final String RESOURCE_MCP_TOOL = "MCP_TOOL";

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final AgentExecutionResourceMapper resourceMapper;

	private final AgentMcpServerMapper mcpServerMapper;

	private final AgentMcpToolMapper mcpToolMapper;

	private final DataAgentSkillToolRefMapper skillToolRefMapper;

	private final McpClientService mcpClientService;

	private final ToolCenterProperties properties;

	private final ObjectMapper objectMapper;

	private final AuthenticationContext authenticationContext;

	private final TransactionTemplate transactionTemplate;

	@Override
	public List<ToolResourceDTO> listResources() {
		return resourceMapper.findAllOrdered(currentTenantId()).stream().map(this::toDTO).toList();
	}

	@Override
	public IPage<ToolResourceDTO> pageResources(ToolPageQueryReq request) {
		ToolPageQueryReq query = request == null ? new ToolPageQueryReq() : request;
		return resourceMapper.selectResourcePage(query.buildPage(), query, currentTenantId()).convert(this::toDTO);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public ToolResourceDTO saveResource(ToolResourceDTO request) {
		if (request == null) {
			throw CheckedException.badRequest("Execution resource config is required.");
		}
		String resourceType = requiredText(request.resourceType(), "Execution resource type is required.");
		String resourceKey = firstText(request.resourceKey(), buildResourceKey(request));
		if (!StringUtils.hasText(resourceKey)) {
			throw CheckedException.badRequest("Execution resource code is required.");
		}
		AgentExecutionResource entity = resourceMapper.findByResourceKey(resourceKey);
		boolean creating = entity == null;
		if (creating) {
			entity = new AgentExecutionResource();
		}
		applyResource(entity, request, resourceType, resourceKey);
		String tenantId = currentTenantId();
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.badRequest("Tenant context is required");
		}
		if (creating || !StringUtils.hasText(entity.getTenantId())) {
			entity.setTenantId(tenantId);
		}
		else if (!tenantId.equals(entity.getTenantId())) {
			throw CheckedException.notFound("Tool resource does not exist");
		}
		if (creating) {
			resourceMapper.insert(entity);
		}
		else {
			resourceMapper.updateById(entity);
		}
		return toDTO(resourceMapper.findByResourceKey(resourceKey));
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void deleteResource(String resourceKey) {
		AgentExecutionResource resource = resourceMapper.findByResourceKey(resourceKey);
		if (resource == null) {
			return;
		}
		if (skillToolRefMapper.countPublishedReferences(resource.getResourceKey()) > 0) {
			throw CheckedException.badRequest("Tool resource is referenced by a published Skill version.");
		}
		resourceMapper.deleteById(resource.getId());
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public ToolResourceDTO addMcpTool(String serverCode, String toolName) {
		AgentMcpTool tool = requireMcpTool(serverCode, toolName);
		return toDTO(upsertMcpResource(tool));
	}

	@Override
	public List<ToolReferenceResp> listReferences(String resourceKey) {
		if (!StringUtils.hasText(resourceKey)) {
			return List.of();
		}
		return skillToolRefMapper.findVisiblePublishedReferences(resourceKey.trim(), currentTenantId());
	}

	@Override
	public List<AgentMcpServer> listMcpServers() {
		return mcpServerMapper.findAllActive();
	}

	@Override
	public List<AgentMcpTool> listMcpTools(String serverCode) {
		if (!StringUtils.hasText(serverCode)) {
			return List.of();
		}
		return mcpToolMapper.findByServerCode(serverCode);
	}

	/**
	 * 同步 MCP 工具清单。
	 *
	 * <p>本方法刻意不加 {@code @Transactional}：每个 MCP Server 的 {@code listTools} 都是远程调用，
	 * 放在事务里会让事务时长等于 N 个远端往返之和，整段时间都占着数据库连接。改为先在事务外把全部远程
	 * 结果汇总成内存快照，再按 Server 逐个开短事务落库。
	 */
	@Override
	public void syncMcpTools() {
		transactionTemplate.executeWithoutResult(status -> syncConfiguredServers());
		List<AgentMcpServer> servers = mcpServerMapper.findAllActive()
			.stream()
			.filter(server -> server != null && !STATUS_DISABLED.equalsIgnoreCase(server.getStatus()))
			.toList();
		if (servers.isEmpty()) {
			return;
		}
		List<String> failedServers = new ArrayList<>();
		List<McpServerTools> snapshots = new ArrayList<>();
		for (AgentMcpServer server : servers) {
			try {
				snapshots.add(new McpServerTools(server, mcpClientService.listTools(server)));
			}
			catch (Exception ex) {
				failedServers.add(String.valueOf(server.getServerCode()));
				log.warn("拉取 MCP 工具清单失败, serverCode={}", server.getServerCode(), ex);
			}
		}
		persistMcpTools(snapshots, failedServers);
		if (!failedServers.isEmpty()) {
			throw CheckedException.fail("MCP 工具同步存在失败服务, 请查看日志排查: " + String.join(", ", failedServers));
		}
	}

	/**
	 * 按 Server 逐个开短事务落库。
	 *
	 * <p>一个 Server 一个事务，单个 Server 失败只回滚它自己的写入，已经同步成功的 Server 不被牵连；
	 * 失败明细先累计，由调用方在全部 Server 处理完后统一上报，避免像旧实现那样只 {@code warn} 一句就当成功返回。
	 */
	private void persistMcpTools(List<McpServerTools> snapshots, List<String> failedServers) {
		if (snapshots.isEmpty()) {
			return;
		}
		ExecutionResourceSnapshot resources = loadExecutionResources();
		for (McpServerTools snapshot : snapshots) {
			try {
				transactionTemplate.executeWithoutResult(status -> upsertTools(snapshot.server(), snapshot.tools(),
						resources));
			}
			catch (Exception ex) {
				failedServers.add(String.valueOf(snapshot.server().getServerCode()));
				log.warn("落库 MCP 工具清单失败, serverCode={}", snapshot.server().getServerCode(), ex);
			}
		}
	}

	private void syncConfiguredServers() {
		Map<String, ToolCenterProperties.McpServer> servers = properties.getMcp() == null ? Map.of()
				: properties.getMcp().getServers();
		if (servers == null || servers.isEmpty()) {
			return;
		}
		Map<String, AgentMcpServer> persisted = mcpServerMapper.findAllActive()
			.stream()
			.filter(server -> server != null && StringUtils.hasText(server.getServerCode()))
			.collect(Collectors.toMap(AgentMcpServer::getServerCode, Function.identity(), (first, second) -> first,
					LinkedHashMap::new));
		for (Map.Entry<String, ToolCenterProperties.McpServer> entry : servers.entrySet()) {
			ToolCenterProperties.McpServer config = entry.getValue();
			if (config == null) {
				continue;
			}
			if (!StringUtils.hasText(config.getServiceName()) && !StringUtils.hasText(config.getBaseUrl())) {
				continue;
			}
			AgentMcpServer server = persisted.get(entry.getKey());
			if (server == null) {
				server = new AgentMcpServer();
				server.setServerCode(entry.getKey());
			}
			server.setServiceName(config.getServiceName());
			server.setEndpointPath(config.getEndpointPath());
			server.setTransportType(config.getTransportType());
			server.setBaseUrl(config.getBaseUrl());
			server.setStatus(config.isEnabled() ? STATUS_ENABLED : STATUS_DISABLED);
			if (server.getId() == null) {
				mcpServerMapper.insert(server);
			}
			else {
				mcpServerMapper.updateById(server);
			}
		}
	}

	private void upsertTools(AgentMcpServer server, List<Map<String, Object>> tools,
			ExecutionResourceSnapshot resources) {
		List<String> toolNames = tools == null ? List.of()
				: tools.stream().map(item -> stringValue(item.get("name"))).filter(StringUtils::hasText).toList();
		if (tools == null || tools.isEmpty()) {
			mcpToolMapper.deleteToolsNotIn(server.getServerCode(), List.of());
			deleteMcpResourcesNotIn(server.getServerCode(), List.of(), resources);
			return;
		}
		Map<String, AgentMcpTool> persistedTools = indexToolsByName(server.getServerCode());
		for (Map<String, Object> tool : tools) {
			String toolName = stringValue(tool.get("name"));
			if (!StringUtils.hasText(toolName)) {
				continue;
			}
			AgentMcpTool entity = resolveExistingTool(persistedTools, server.getServerCode(), toolName);
			if (entity == null) {
				entity = new AgentMcpTool();
				entity.setServerCode(server.getServerCode());
				entity.setToolName(toolName);
			}
			else if (Boolean.TRUE.equals(entity.getDeleted())) {
				mcpToolMapper.restoreById(entity.getId());
				entity.setDeleted(false);
			}
			entity.setToolTitle(firstText(stringValue(tool.get("title")), toolName));
			entity.setDescription(stringValue(tool.get("description")));
			entity.setInputSchema(writeJson(tool.get("inputSchema")));
			entity.setOutputSchema(writeJson(tool.get("outputSchema")));
			String resourceKey = joinKey(server.getServerCode(), toolName);
			AgentExecutionResource existingResource = resolveExistingResource(resources, resourceKey);
			Map<String, Object> existingExtConfig = readJsonObject(
					existingResource == null ? null : existingResource.getExtConfig());
			entity.setReadonly(resolveMcpSafetyFlag(tool.get("readOnlyHint"), entity.getReadonly(),
					existingExtConfig.get("readonly"), false));
			entity.setConfirmRequired(resolveMcpSafetyFlag(tool.get("destructiveHint"), entity.getConfirmRequired(),
					existingExtConfig.get("confirmRequired"), false));
			entity.setRiskLevel(Boolean.TRUE.equals(entity.getReadonly()) ? "READ" : "WRITE");
			entity.setStatus(STATUS_ENABLED);
			entity.setMetadata(writeJson(tool));
			if (entity.getId() == null) {
				mcpToolMapper.insert(entity);
			}
			else {
				mcpToolMapper.updateById(entity);
			}
			writeMcpResource(entity, resourceKey, existingResource);
		}
		mcpToolMapper.deleteToolsNotIn(server.getServerCode(), toolNames);
		deleteMcpResourcesNotIn(server.getServerCode(), toolNames, resources);
	}

	private Map<String, AgentMcpTool> indexToolsByName(String serverCode) {
		return mcpToolMapper.findByServerCode(serverCode)
			.stream()
			.filter(tool -> tool != null && StringUtils.hasText(tool.getToolName()))
			.collect(Collectors.toMap(AgentMcpTool::getToolName, Function.identity(), (first, second) -> first,
					LinkedHashMap::new));
	}

	/**
	 * 命中本服务已批量加载的工具索引就直接用，未命中才回表。
	 *
	 * <p>索引来自 {@code findByServerCode}，它受 {@code @TableLogic} 过滤查不到软删行，而「按编码判重后恢复」
	 * 链路正依赖软删行，所以未命中时仍要走 {@code findByServerCodeAndToolNameIncludingDeleted} 兜底。
	 */
	private AgentMcpTool resolveExistingTool(Map<String, AgentMcpTool> persistedTools, String serverCode,
			String toolName) {
		AgentMcpTool indexed = persistedTools.get(toolName);
		if (indexed != null) {
			return indexed;
		}
		return mcpToolMapper.findByServerCodeAndToolNameIncludingDeleted(serverCode, toolName);
	}

	/**
	 * 同上：快照由 {@code findAllOrdered} 构建，看不到软删资源，未命中必须回表兜底，
	 * 否则软删资源会被当成新建走 {@code insert}，撞 resource_key 唯一约束或产生重复行。
	 */
	private AgentExecutionResource resolveExistingResource(ExecutionResourceSnapshot resources, String resourceKey) {
		AgentExecutionResource indexed = resources.find(resourceKey);
		if (indexed != null) {
			return indexed;
		}
		return resourceMapper.findByResourceKeyIncludingDeleted(resourceKey);
	}

	private AgentExecutionResource upsertMcpResource(AgentMcpTool tool) {
		String resourceKey = joinKey(tool.getServerCode(), tool.getToolName());
		writeMcpResource(tool, resourceKey, resourceMapper.findByResourceKeyIncludingDeleted(resourceKey));
		return resourceMapper.findByResourceKey(resourceKey);
	}

	private void writeMcpResource(AgentMcpTool tool, String resourceKey, AgentExecutionResource existing) {
		AgentExecutionResource resource = existing;
		boolean creating = resource == null;
		if (creating) {
			resource = new AgentExecutionResource();
			resource.setResourceKey(resourceKey);
			resource.setResourceType(RESOURCE_MCP_TOOL);
			resource.setDisplayOrder(0);
		}
		else if (Boolean.TRUE.equals(resource.getDeleted())) {
			resourceMapper.restoreById(resource.getId());
			resource.setDeleted(false);
		}
		resource.setResourceName(firstText(tool.getToolTitle(), tool.getToolName()));
		resource.setServerCode(tool.getServerCode());
		resource.setToolName(tool.getToolName());
		resource.setEnabled(STATUS_ENABLED.equalsIgnoreCase(tool.getStatus()));
		resource.setStatus(firstText(tool.getStatus(), STATUS_ENABLED));
		Map<String, Object> extConfig = new LinkedHashMap<>(readJsonObject(resource.getExtConfig()));
		putOrRemove(extConfig, "description", tool.getDescription());
		putOrRemove(extConfig, "inputSchema", readJsonObject(tool.getInputSchema()));
		putOrRemove(extConfig, "outputSchema", readJsonObject(tool.getOutputSchema()));
		extConfig.put("readonly", Boolean.TRUE.equals(tool.getReadonly()));
		extConfig.put("confirmRequired", Boolean.TRUE.equals(tool.getConfirmRequired()));
		resource.setExtConfig(writeJson(extConfig));
		if (creating) {
			resourceMapper.insert(resource);
		}
		else {
			resourceMapper.updateById(resource);
		}
	}

	private ExecutionResourceSnapshot loadExecutionResources() {
		List<AgentExecutionResource> all = resourceMapper.findAllOrdered();
		Map<String, AgentExecutionResource> byKey = all.stream()
			.filter(resource -> resource != null && StringUtils.hasText(resource.getResourceKey()))
			.collect(Collectors.toMap(AgentExecutionResource::getResourceKey, Function.identity(),
					(first, second) -> first, LinkedHashMap::new));
		return new ExecutionResourceSnapshot(all, byKey);
	}

	/**
	 * 一次同步内共享的执行资源快照。
	 *
	 * <p>旧实现每个 Server 都要全表读一次 {@code agent_execution_resource}（清理下线工具），每个工具还要按
	 * resourceKey 回表两次（取旧 extConfig 一次、写资源前再取一次）。这里改为整次同步只读一次，按 resourceKey
	 * 建索引；软删记录不在索引里，按 key 未命中时才回表兜底。{@code all} 保留原始顺序与重复行，供下线清理沿用
	 * 逐行语义。
	 */
	private record ExecutionResourceSnapshot(List<AgentExecutionResource> all,
			Map<String, AgentExecutionResource> byKey) {

		private AgentExecutionResource find(String resourceKey) {
			return StringUtils.hasText(resourceKey) ? byKey.get(resourceKey) : null;
		}

	}

	private record McpServerTools(AgentMcpServer server, List<Map<String, Object>> tools) {
	}

	private Boolean resolveMcpSafetyFlag(Object mcpHint, Boolean existingToolValue, Object existingResourceValue,
			boolean defaultValue) {
		Boolean hinted = booleanValue(mcpHint);
		if (hinted != null) {
			return hinted;
		}
		Boolean resourceValue = booleanValue(existingResourceValue);
		if (resourceValue != null) {
			return resourceValue;
		}
		return existingToolValue == null ? defaultValue : existingToolValue;
	}

	private AgentMcpTool requireMcpTool(String serverCode, String toolName) {
		if (!StringUtils.hasText(serverCode) || !StringUtils.hasText(toolName)) {
			throw CheckedException.badRequest("MCP server and tool name are required.");
		}
		AgentMcpTool tool = mcpToolMapper.findByServerCodeAndToolName(serverCode.trim(), toolName.trim());
		if (tool == null) {
			throw CheckedException.notFound("MCP tool does not exist.");
		}
		return tool;
	}

	private void deleteMcpResourcesNotIn(String serverCode, List<String> toolNames,
			ExecutionResourceSnapshot snapshot) {
		Set<String> retained = toolNames == null ? Set.of() : Set.copyOf(toolNames);
		List<AgentExecutionResource> resources = snapshot.all().stream()
			.filter(resource -> RESOURCE_MCP_TOOL.equalsIgnoreCase(resource.getResourceType()))
			.filter(resource -> StringUtils.hasText(resource.getServerCode())
					&& resource.getServerCode().equals(serverCode))
			.filter(resource -> retained.isEmpty() || !retained.contains(resource.getToolName()))
			.toList();
		for (AgentExecutionResource resource : resources) {
			resource.setEnabled(false);
			resource.setStatus(STATUS_DISABLED);
			resourceMapper.updateById(resource);
			resourceMapper.deleteById(resource.getId());
		}
	}

	private void applyResource(AgentExecutionResource entity, ToolResourceDTO dto, String resourceType,
			String resourceKey) {
		entity.setResourceType(resourceType);
		entity.setResourceKey(resourceKey.trim());
		entity.setResourceName(firstText(dto.resourceName(), resourceKey));
		entity.setServerCode(dto.serverCode());
		entity.setServiceName(dto.serviceName());
		entity.setBaseUrl(dto.baseUrl());
		entity.setToolName(dto.toolName());
		entity.setEndpointUrl(dto.endpointUrl());
		entity.setHttpMethod(firstText(dto.httpMethod(), "POST"));
		entity.setHeaderTemplate(writeJson(dto.headerTemplate()));
		entity.setAuthType(dto.authType());
		entity.setCredentialRef(dto.credentialRef());
		entity.setParamMapping(writeJson(dto.paramMapping()));
		entity.setRequestTemplate(writeJson(dto.requestTemplate()));
		entity.setResponseMapping(writeJson(dto.responseMapping()));
		entity.setEnabled(!Boolean.FALSE.equals(dto.enabled()));
		entity.setStatus(firstText(dto.status(), !Boolean.FALSE.equals(dto.enabled()) ? STATUS_ENABLED : STATUS_DISABLED));
		entity.setDisplayOrder(dto.displayOrder() == null ? 0 : dto.displayOrder());
		entity.setExtConfig(writeJson(dto.extConfig()));
	}

	private ToolResourceDTO toDTO(AgentExecutionResource entity) {
		if (entity == null) {
			return null;
		}
		return new ToolResourceDTO(entity.getId(), entity.getResourceType(), entity.getResourceKey(),
				entity.getResourceName(), entity.getServerCode(),
				entity.getServiceName(), entity.getBaseUrl(), entity.getToolName(), entity.getEndpointUrl(),
				entity.getHttpMethod(), readJsonObject(entity.getHeaderTemplate()), entity.getAuthType(),
				entity.getCredentialRef(), readJsonObject(entity.getParamMapping()),
				readJsonObject(entity.getRequestTemplate()), readJsonObject(entity.getResponseMapping()),
				entity.getEnabled(), entity.getStatus(), entity.getDisplayOrder(), readJsonObject(entity.getExtConfig()));
	}

	private String buildResourceKey(ToolResourceDTO dto) {
		if (dto == null) {
			return null;
		}
		if (RESOURCE_MCP_TOOL.equalsIgnoreCase(dto.resourceType())) {
			return joinKey(dto.serverCode(), dto.toolName());
		}
		return firstText(dto.endpointUrl(), dto.serviceName(), dto.baseUrl(), dto.serverCode(), dto.toolName());
	}

	private String joinKey(String first, String second) {
		if (!StringUtils.hasText(first) || !StringUtils.hasText(second)) {
			return null;
		}
		return first.trim() + "." + second.trim();
	}

	private String writeJson(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Collection<?> collection && collection.isEmpty()) {
			return null;
		}
		if (value instanceof Map<?, ?> map && map.isEmpty()) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(value instanceof Map<?, ?> map ? new LinkedHashMap<>(map) : value);
		}
		catch (Exception ex) {
			log.debug("Failed to write execution resource json", ex);
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> readJsonObject(String value) {
		if (!StringUtils.hasText(value)) {
			return Map.of();
		}
		try {
			Map<String, Object> map = objectMapper.readValue(value, MAP_TYPE);
			return map == null ? Map.of() : new LinkedHashMap<>(map);
		}
		catch (Exception ex) {
			log.debug("Failed to parse execution resource json", ex);
			return Map.of();
		}
	}

	private String stringValue(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private Boolean booleanValue(Object value) {
		if (value instanceof Boolean bool) {
			return bool;
		}
		if (value instanceof String text && StringUtils.hasText(text)) {
			String normalized = text.trim();
			if ("true".equalsIgnoreCase(normalized)) {
				return true;
			}
			if ("false".equalsIgnoreCase(normalized)) {
				return false;
			}
		}
		return null;
	}

	private String requiredText(String value, String message) {
		if (!StringUtils.hasText(value)) {
			throw CheckedException.badRequest(message);
		}
		return value.trim();
	}

	private String currentTenantId() {
		try {
			return authenticationContext.tenantId();
		}
		catch (Exception ex) {
			log.warn("解析当前租户失败, 工具引用查询将失败关闭(findVisiblePublishedReferences 命中 AND 1 = 0)返回空列表", ex);
			return null;
		}
	}

	private String firstText(String... values) {
		if (values == null) {
			return null;
		}
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value.trim();
			}
		}
		return null;
	}

	private void putOrRemove(Map<String, Object> target, String key, Object value) {
		if (target == null || !StringUtils.hasText(key) || value == null) {
			if (target != null && StringUtils.hasText(key)) {
				target.remove(key);
			}
			return;
		}
		if (value instanceof String text && !StringUtils.hasText(text)) {
			target.remove(key);
			return;
		}
		if (value instanceof Map<?, ?> map && map.isEmpty()) {
			target.remove(key);
			return;
		}
		target.put(key, value);
	}

}
