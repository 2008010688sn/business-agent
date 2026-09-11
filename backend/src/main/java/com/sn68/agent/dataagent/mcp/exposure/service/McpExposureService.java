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

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.mcp.exposure.dto.McpExposurePageQueryRequest;
import com.sn68.agent.dataagent.mcp.exposure.dto.McpExposureDTO;
import com.sn68.agent.dataagent.mcp.exposure.dto.McpExposureRuntimeSyncResult;
import com.sn68.agent.dataagent.mcp.exposure.entity.AgentMcpExposure;
import com.sn68.agent.dataagent.mcp.exposure.repository.AgentMcpExposureMapper;
import com.sn68.agent.dataagent.mcp.exposure.service.McpExposureRuntimeSyncEvent.Reason;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * McpExposure组件，封装 DataAgent 对应业务入口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpExposureService {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private static final String STATUS_ENABLED = "enabled";

	private final AgentMcpExposureMapper exposureMapper;

	private final AgentExecutionResourceMapper resourceMapper;

	private final ObjectMapper objectMapper;

	private final McpExposureRuntimeRegistry runtimeRegistry;

	private final McpExposureRuntimeSyncCoordinator runtimeSyncCoordinator;

	/**
	 * 查询McpExposure。
	 */
	@Deprecated(since = "2026-07", forRemoval = true)
	public List<McpExposureDTO> list(String toolKey, String status) {
		return exposureMapper.findAllOrdered(toolKey, status).stream().map(this::toDTO).toList();
	}

	/**
	 * 处理McpExposure。
	 */
	public IPage<McpExposureDTO> page(McpExposurePageQueryRequest request) {
		McpExposurePageQueryRequest query = request == null ? new McpExposurePageQueryRequest() : request;
		return exposureMapper.selectExposurePage(query.buildPage(), query).convert(this::toDTO);
	}

	/**
	 * 创建McpExposure。
	 */
	@Transactional(rollbackFor = Exception.class)
	public McpExposureDTO create(McpExposureDTO request) {
		if (request == null) {
			throw CheckedException.badRequest("MCP exposure config is required.");
		}
		String exposureCode = requiredText(request.exposureCode(), "Exposure code is required.");
		AgentMcpExposure existing = exposureMapper.findByExposureCodeIncludingDeleted(exposureCode);
		if (existing != null && !Boolean.TRUE.equals(existing.getDeleted())) {
			throw duplicateExposureCode(exposureCode);
		}
		AgentMcpExposure exposure = existing == null ? new AgentMcpExposure() : existing;
		apply(exposure, request, exposureCode);
		try {
			if (existing == null) {
				exposureMapper.insert(exposure);
			}
			else {
				exposureMapper.restoreById(existing.getId());
				exposure.setDeleted(false);
				exposureMapper.updateById(exposure);
			}
		}
		catch (DuplicateKeyException ex) {
			throw duplicateExposureCode(exposureCode);
		}
		runtimeSyncCoordinator.requestAfterCommit(Reason.CREATE);
		return toDTO(exposureMapper.findByExposureCode(exposureCode));
	}

	/**
	 * 保存McpExposure。
	 */
	@Transactional(rollbackFor = Exception.class)
	public McpExposureDTO update(Long id, McpExposureDTO request) {
		if (id == null) {
			throw CheckedException.badRequest("Exposure ID is required.");
		}
		if (request == null) {
			throw CheckedException.badRequest("MCP exposure config is required.");
		}
		AgentMcpExposure exposure = exposureMapper.selectById(id);
		if (exposure == null || Boolean.TRUE.equals(exposure.getDeleted())) {
			throw CheckedException.notFound("MCP exposure does not exist.");
		}
		String exposureCode = firstText(request.exposureCode(), exposure.getExposureCode());
		AgentMcpExposure duplicate = exposureMapper.findByExposureCodeIncludingDeleted(exposureCode);
		if (duplicate != null && !Objects.equals(duplicate.getId(), id)) {
			throw duplicateExposureCode(exposureCode);
		}
		apply(exposure, request, exposureCode);
		try {
			exposureMapper.updateById(exposure);
		}
		catch (DuplicateKeyException ex) {
			throw duplicateExposureCode(exposureCode);
		}
		runtimeSyncCoordinator.requestAfterCommit(Reason.UPDATE);
		return toDTO(exposureMapper.selectById(id));
	}

	/**
	 * 清理McpExposure。
	 */
	@Transactional(rollbackFor = Exception.class)
	public void delete(Long id) {
		if (id != null) {
			exposureMapper.deleteById(id);
			runtimeSyncCoordinator.requestAfterCommit(Reason.DELETE);
		}
	}

	/**
	 * 手动同步 MCP 暴露运行时。
	 */
	public McpExposureRuntimeSyncResult syncRuntime() {
		return runtimeSyncCoordinator.synchronizeManually();
	}

	private void apply(AgentMcpExposure exposure, McpExposureDTO request, String exposureCode) {
		String toolKey = requiredText(request.toolKey(), "Tool key is required.");
		String exposedToolName = requiredText(request.exposedToolName(), "Exposed tool name is required.");
		String status = firstText(request.status(), "disabled");
		validateEnabledExposure(exposure.getId(), toolKey, exposedToolName, status);
		exposure.setExposureCode(requiredText(exposureCode, "Exposure code is required."));
		exposure.setExposureName(firstText(request.exposureName(), exposure.getExposureCode()));
		exposure.setToolKey(toolKey);
		exposure.setExposedToolName(exposedToolName);
		exposure.setExposureType(firstText(request.exposureType(), "TOOL"));
		exposure.setRiskLevel(firstText(request.riskLevel(), "LOW"));
		exposure.setRequireUserContext(!Boolean.FALSE.equals(request.requireUserContext()));
		exposure.setStatus(status);
		exposure.setDisplayOrder(request.displayOrder() == null ? 0 : request.displayOrder());
		exposure.setExtConfig(writeJson(request.extConfig()));
	}

	private void validateEnabledExposure(Long currentId, String toolKey, String exposedToolName, String status) {
		if (!STATUS_ENABLED.equalsIgnoreCase(status)) {
			return;
		}
		if (resourceMapper.findEnabledByResourceKey(toolKey) == null) {
			throw CheckedException.badRequest("启用 MCP 暴露时，内部 Tool 不存在或未启用: " + toolKey);
		}
		AgentMcpExposure duplicate = exposureMapper.findEnabledByExposedToolName(exposedToolName);
		if (duplicate != null && !Objects.equals(duplicate.getId(), currentId)) {
			throw CheckedException.badRequest("MCP Tool Name 已被其他启用配置占用: " + exposedToolName);
		}
		if (runtimeRegistry.isStaticToolName(exposedToolName)) {
			throw CheckedException.badRequest("MCP Tool Name 与系统静态 Tool 冲突: " + exposedToolName);
		}
	}

	private CheckedException duplicateExposureCode(String exposureCode) {
		return CheckedException.badRequest("MCP 暴露编码已存在: " + exposureCode);
	}

	private McpExposureDTO toDTO(AgentMcpExposure exposure) {
		return new McpExposureDTO(exposure.getId(), exposure.getExposureCode(), exposure.getExposureName(),
				exposure.getToolKey(), exposure.getExposedToolName(), exposure.getExposureType(), exposure.getRiskLevel(),
				exposure.getRequireUserContext(), exposure.getStatus(), exposure.getDisplayOrder(),
				readJsonObject(exposure.getExtConfig()));
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
			log.debug("Failed to write MCP exposure json", ex);
			return null;
		}
	}

	private Map<String, Object> readJsonObject(String value) {
		if (!StringUtils.hasText(value)) {
			return Map.of();
		}
		try {
			Map<String, Object> map = objectMapper.readValue(value, MAP_TYPE);
			return map == null ? Map.of() : new LinkedHashMap<>(map);
		}
		catch (Exception ex) {
			log.debug("Failed to parse MCP exposure json", ex);
			return Map.of();
		}
	}

	private String requiredText(String value, String message) {
		if (!StringUtils.hasText(value)) {
			throw CheckedException.badRequest(message);
		}
		return value.trim();
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

}
