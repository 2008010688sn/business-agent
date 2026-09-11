/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.tool;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.dto.tool.ToolPageQueryReq;
import com.sn68.agent.dataagent.dto.tool.ToolResourceDTO;
import com.sn68.agent.dataagent.entity.AgentExecutionResource;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceMapper;
import com.sn68.agent.dataagent.dto.tool.ToolDetailResp;
import com.sn68.agent.dataagent.dto.tool.ToolReferenceResp;
import com.sn68.agent.dataagent.dto.tool.ToolVersionPublishReq;
import com.sn68.agent.dataagent.dto.tool.ToolTestReq;
import com.sn68.agent.dataagent.entity.AgentExecutionResourceVersion;
import com.sn68.agent.dataagent.entity.AgentMcpServer;
import com.sn68.agent.dataagent.entity.AgentMcpTool;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceVersionMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Tool center implementation with immutable publish snapshots.
 */
@Service
@RequiredArgsConstructor
public class ToolResourceServiceImpl implements ToolResourceService {

	private final ToolDirectoryService toolDirectoryService;

	private final AgentExecutionResourceMapper resourceMapper;

	private final AgentExecutionResourceVersionMapper versionMapper;

	private final AuthenticationContext authenticationContext;

	private final ToolPermissionService permissionService;

	private final ToolTransportInvoker toolTransportInvoker;

	private final ObjectMapper objectMapper;

	@Override
	public IPage<ToolResourceDTO> page(ToolPageQueryReq request) {
		return toolDirectoryService.pageResources(request);
	}

	@Override
	public ToolDetailResp detail(String resourceKey) {
		AgentExecutionResource resource = requireResource(resourceKey);
		ToolResourceDTO dto = toolDirectoryService.listResources().stream()
			.filter(item -> resourceKey.equals(item.resourceKey()))
			.findFirst()
			.orElseThrow(() -> CheckedException.notFound("Tool resource does not exist"));
		List<AgentExecutionResourceVersion> versions = versionMapper.selectList(
				Wraps.<AgentExecutionResourceVersion>lbQ()
					.eq(AgentExecutionResourceVersion::getResourceId, resource.getId())
					.eq(AgentExecutionResourceVersion::getDeleted, false)
					.orderByDesc(AgentExecutionResourceVersion::getVersionNo));
		return new ToolDetailResp(dto, versions);
	}

	@Override
	public ToolResourceDTO save(ToolResourceDTO request) {
		return toolDirectoryService.saveResource(request);
	}

	@Override
	public void delete(String resourceKey) {
		toolDirectoryService.deleteResource(resourceKey);
	}

	@Override
	public List<ToolReferenceResp> listReferences(String resourceKey) {
		return toolDirectoryService.listReferences(resourceKey);
	}

	@Override
	public List<AgentMcpServer> listMcpServers() {
		return toolDirectoryService.listMcpServers();
	}

	@Override
	public List<AgentMcpTool> listMcpTools(String serverCode) {
		return toolDirectoryService.listMcpTools(serverCode);
	}

	@Override
	public ToolResourceDTO addMcpTool(String serverCode, String toolName) {
		return toolDirectoryService.addMcpTool(serverCode, toolName);
	}

	@Override
	public void syncMcpTools() {
		toolDirectoryService.syncMcpTools();
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public AgentExecutionResourceVersion publish(String resourceKey, ToolVersionPublishReq request) {
		if (request == null) {
			throw CheckedException.badRequest("Tool version config is required");
		}
		AgentExecutionResource resource = requireResource(resourceKey);
		String accessMode = normalize(request.accessMode(), "accessMode");
		String exposureMode = normalize(request.exposureMode(), "exposureMode");
		if (!("READ".equals(accessMode) || "WRITE".equals(accessMode))) {
			throw CheckedException.badRequest("accessMode must be READ or WRITE");
		}
		if (!("MODEL".equals(exposureMode) || "FLOW_ONLY".equals(exposureMode))) {
			throw CheckedException.badRequest("exposureMode must be MODEL or FLOW_ONLY");
		}
		if ("WRITE".equals(accessMode) && "MODEL".equals(exposureMode)) {
			throw CheckedException.badRequest("WRITE + MODEL tool configuration is forbidden");
		}
		if ("WRITE".equals(accessMode) && !Boolean.TRUE.equals(request.confirmRequired())) {
			throw CheckedException.badRequest("Write tools must require confirmation");
		}
		Integer latestVersion = versionMapper.selectList(Wraps.<AgentExecutionResourceVersion>lbQ()
			.eq(AgentExecutionResourceVersion::getResourceId, resource.getId())
			.eq(AgentExecutionResourceVersion::getDeleted, false)
			.orderByDesc(AgentExecutionResourceVersion::getVersionNo)
			.last("LIMIT 1"))
			.stream()
			.map(AgentExecutionResourceVersion::getVersionNo)
			.findFirst()
			.orElse(0);
		AgentExecutionResourceVersion version = AgentExecutionResourceVersion.builder()
			.tenantId(currentTenantId())
			.resourceId(resource.getId())
			.resourceKey(resource.getResourceKey())
			.versionNo(latestVersion + 1)
			.status("PUBLISHED")
			.accessMode(accessMode)
			.exposureMode(exposureMode)
			.permissionCode(trim(request.permissionCode()))
			.confirmRequired(Boolean.TRUE.equals(request.confirmRequired()))
			.idempotencyRequired(Boolean.TRUE.equals(request.idempotencyRequired()))
			.timeoutMs(request.timeoutMs() == null ? 30000 : request.timeoutMs())
			.inputSchema(write(request.inputSchema()))
			.outputSchema(write(request.outputSchema()))
			.runtimeParamMappings(write(request.runtimeParamMappings()))
			.responseMappings(write(request.responseMappings()))
			.sensitiveFields(write(request.sensitiveFields()))
			.snapshot(write(snapshot(resource, request)))
			.publishedAt(Instant.now())
			.build();
		versionMapper.insert(version);
		return version;
	}

	@Override
	public Map<String, Object> test(String resourceKey, ToolTestReq request) {
		if (request == null || request.resourceVersionId() == null) {
			throw CheckedException.badRequest("Published tool version is required");
		}
		requireResource(resourceKey);
		AgentExecutionResourceVersion version = versionMapper.findPublished(request.resourceVersionId());
		if (version == null || !resourceKey.equals(version.getResourceKey())) {
			throw CheckedException.notFound("Published tool version does not exist for resource: " + resourceKey);
		}
		if (!"READ".equals(version.getAccessMode())) {
			throw CheckedException.badRequest("Tool test supports published READ versions only");
		}
		String tenantId = currentTenantId();
		if (version.getTenantId() != null && !version.getTenantId().equals(tenantId)) {
			throw CheckedException.forbidden();
		}
		List<String> permissions = StringUtils.hasText(version.getPermissionCode())
				? List.of(version.getPermissionCode()) : List.of();
		if (!permissionService.canAccess(permissions, null).allowed()) {
			throw CheckedException.forbidden();
		}
		AgentExecutionResource snapshot = readSnapshot(version);
		Map<String, Object> result = toolTransportInvoker.invoke(snapshot,
				request.arguments() == null ? Map.of() : new LinkedHashMap<>(request.arguments()));
		return maskSensitive(result, sensitiveFields(version));
	}

	private Map<String, Object> snapshot(AgentExecutionResource resource, ToolVersionPublishReq request) {
		Map<String, Object> snapshot = objectMapper.convertValue(resource, Map.class);
		Map<String, Object> extConfig = readMap(resource.getExtConfig());
		extConfig.put("inputSchema", request.inputSchema() == null ? Map.of() : request.inputSchema());
		extConfig.put("runtimeParamMappings",
				request.runtimeParamMappings() == null ? Map.of() : request.runtimeParamMappings());
		snapshot.put("extConfig", write(extConfig));
		snapshot.put("responseMapping", write(request.responseMappings()));
		return snapshot;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> readMap(String value) {
		if (!StringUtils.hasText(value)) {
			return new LinkedHashMap<>();
		}
		try {
			return new LinkedHashMap<>(objectMapper.readValue(value, Map.class));
		}
		catch (Exception ex) {
			throw CheckedException.badRequest("Tool resource extConfig is invalid");
		}
	}

	private AgentExecutionResource requireResource(String resourceKey) {
		AgentExecutionResource resource = resourceMapper.findEnabledByResourceKey(resourceKey);
		if (resource == null) {
			throw CheckedException.notFound("Enabled tool resource does not exist: " + resourceKey);
		}
		return resource;
	}

	private AgentExecutionResource readSnapshot(AgentExecutionResourceVersion version) {
		try {
			AgentExecutionResource snapshot = objectMapper.readValue(version.getSnapshot(), AgentExecutionResource.class);
			if (snapshot == null || !version.getResourceKey().equals(snapshot.getResourceKey())) {
				throw CheckedException.badRequest("Published tool snapshot does not match its resource key");
			}
			return snapshot;
		}
		catch (CheckedException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw CheckedException.badRequest("Published tool snapshot is invalid");
		}
	}

	private Set<String> sensitiveFields(AgentExecutionResourceVersion version) {
		if (!StringUtils.hasText(version.getSensitiveFields())) {
			return Set.of();
		}
		try {
			List<?> fields = objectMapper.readValue(version.getSensitiveFields(), List.class);
			return fields.stream().filter(item -> item != null && StringUtils.hasText(String.valueOf(item)))
				.map(item -> String.valueOf(item).trim()).collect(java.util.stream.Collectors.toUnmodifiableSet());
		}
		catch (Exception ex) {
			throw CheckedException.badRequest("Published tool sensitive fields are invalid");
		}
	}

	private Map<String, Object> maskSensitive(Map<String, Object> value, Set<String> sensitiveFields) {
		if (value == null || value.isEmpty() || sensitiveFields.isEmpty()) {
			return value == null ? Map.of() : value;
		}
		Map<String, Object> masked = new LinkedHashMap<>();
		value.forEach((key, item) -> masked.put(key,
				sensitiveFields.contains(key) ? "****" : maskValue(item, sensitiveFields)));
		return masked;
	}

	private Object maskValue(Object value, Set<String> sensitiveFields) {
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> nested = new LinkedHashMap<>();
			map.forEach((key, item) -> {
				String field = String.valueOf(key);
				nested.put(field, sensitiveFields.contains(field) ? "****" : maskValue(item, sensitiveFields));
			});
			return nested;
		}
		if (value instanceof List<?> list) {
			return list.stream().map(item -> maskValue(item, sensitiveFields)).toList();
		}
		return value;
	}

	private String write(Object value) {
		try {
			return objectMapper.writeValueAsString(value == null ? java.util.Map.of() : value);
		}
		catch (Exception ex) {
			throw CheckedException.badRequest("Tool version JSON config is invalid");
		}
	}

	private String normalize(String value, String fieldName) {
		if (!StringUtils.hasText(value)) {
			throw CheckedException.badRequest(fieldName + " is required");
		}
		return value.trim().toUpperCase(Locale.ROOT);
	}

	private String trim(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	private String currentTenantId() {
		try {
			return authenticationContext.tenantId();
		}
		catch (Exception ex) {
			return null;
		}
	}

}
