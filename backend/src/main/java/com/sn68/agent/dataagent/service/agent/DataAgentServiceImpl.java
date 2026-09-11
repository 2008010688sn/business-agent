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
package com.sn68.agent.dataagent.service.agent;

import com.sn68.agent.dataagent.constant.AgentStatusConstant;
import com.sn68.agent.dataagent.constant.AgentTypeConstant;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.repository.DataAgentMapper;
import com.sn68.agent.dataagent.service.file.FilePreviewResp;
import com.sn68.agent.dataagent.service.file.LocalFileService;
import com.sn68.agent.dataagent.service.vectorstore.AgentVectorStoreService;
import com.sn68.agent.dataagent.temporal.AgentTemporalService;
import com.sn68.agent.dataagent.util.ApiKeyUtil;
import com.sn68.agent.dataagent.vo.ApiKeyResp;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent Service Class
 */
@Slf4j
@Service
@AllArgsConstructor
public class DataAgentServiceImpl implements DataAgentService {

	private final DataAgentMapper dataAgentMapper;

	private final AgentVectorStoreService agentVectorStoreService;

	private final LocalFileService localFileService;

	private final DataAgentProperties dataAgentProperties;

	private final AgentTemporalService agentTemporalService;

	private final AuthenticationContext authenticationContext;

	@Override
	public List<DataAgent> list(String status, String keyword) {
		return enrichAvatarPreview(dataAgentMapper.findByConditions(status, keyword, requireCurrentTenantId()));
	}

	@Override
	public List<DataAgent> findAll() {
		return enrichAvatarPreview(dataAgentMapper.findAll(requireCurrentTenantId()));
	}

	@Override
	public DataAgent findById(Long id) {
		return ownedOrNull(dataAgentMapper.findById(id));
	}

	@Override
	public DataAgent findByIdWithPreview(Long id) {
		return enrichAvatarPreview(findById(id));
	}

	@Override
	public DataAgent requireAgent(Long id) {
		DataAgent dataAgent = findById(id);
		if (dataAgent == null) {
			throw CheckedException.notFound("Data agent does not exist");
		}
		return dataAgent;
	}

	@Override
	public DataAgent requireAgentWithPreview(Long id) {
		DataAgent dataAgent = findByIdWithPreview(id);
		if (dataAgent == null) {
			throw CheckedException.notFound("Data agent does not exist");
		}
		return dataAgent;
	}

	@Override
	public List<DataAgent> findByStatus(String status) {
		return enrichAvatarPreview(dataAgentMapper.findByStatus(status, requireCurrentTenantId()));
	}

	@Override
	public List<DataAgent> findByStatusRaw(String status) {
		return dataAgentMapper.findByStatus(status);
	}

	@Override
	public List<DataAgent> search(String keyword) {
		return enrichAvatarPreview(dataAgentMapper.searchByKeyword(keyword, requireCurrentTenantId()));
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public DataAgent create(DataAgent dataAgent) {
		if (!StringUtils.hasText(dataAgent.getStatus())) {
			dataAgent.setStatus(AgentStatusConstant.DRAFT);
		}
		return save(dataAgent);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public DataAgent update(Long id, DataAgent dataAgent) {
		requireAgent(id);
		dataAgent.setId(id);
		return save(dataAgent);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public DataAgent publish(Long id) {
		DataAgent dataAgent = requireAgent(id);
		dataAgent.setStatus(AgentStatusConstant.PUBLISHED);
		return save(dataAgent);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public DataAgent offline(Long id) {
		DataAgent dataAgent = requireAgent(id);
		dataAgent.setStatus(AgentStatusConstant.OFFLINE);
		return save(dataAgent);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public DataAgent save(DataAgent dataAgent) {
		if (dataAgent == null) {
			throw CheckedException.badRequest("Data agent is required");
		}
		assignTenant(dataAgent);
		Instant now = Instant.now();
		validateAvatar(dataAgent);
		dataAgent.setTemporalPolicy(resolveTemporalPolicy(dataAgent));
		if (dataAgent.getAgentType() == null || dataAgent.getAgentType().isBlank()) {
			dataAgent.setAgentType(AgentTypeConstant.DATA_ANALYSIS);
		}
		else {
			dataAgent.setAgentType(AgentTypeConstant.normalize(dataAgent.getAgentType()));
		}
		dataAgent.setRuntimeTimeoutSeconds(resolveRuntimeTimeoutSeconds(dataAgent));
		validateRuntimeLimits(dataAgent);

		if (dataAgent.getId() == null) {
			// Add
			dataAgent.setCreateTime(now);
			dataAgent.setLastModifyTime(now);
			if (dataAgent.getApiKeyEnabled() == null) {
				dataAgent.setApiKeyEnabled(false);
			}

			dataAgentMapper.insert(dataAgent);
		}
		else {
			// Update
			dataAgent.setLastModifyTime(now);
			if (dataAgent.getApiKeyEnabled() == null) {
				dataAgent.setApiKeyEnabled(false);
			}
			dataAgentMapper.updateById(dataAgent);
		}

		return enrichAvatarPreview(dataAgentMapper.findById(dataAgent.getId()));
	}

	private void assignTenant(DataAgent dataAgent) {
		String currentTenantId = requireCurrentTenantId();
		if (dataAgent.getId() == null) {
			if (StringUtils.hasText(dataAgent.getTenantId()) && !currentTenantId.equals(dataAgent.getTenantId())) {
				throw CheckedException.forbidden();
			}
			dataAgent.setTenantId(currentTenantId);
			return;
		}
		DataAgent existing = dataAgentMapper.findById(dataAgent.getId());
		if (existing == null) {
			throw CheckedException.notFound("Data agent does not exist");
		}
		if (!currentTenantId.equals(existing.getTenantId())) {
			throw CheckedException.forbidden();
		}
		dataAgent.setTenantId(existing.getTenantId());
	}

	private DataAgent ownedOrNull(DataAgent dataAgent) {
		if (dataAgent == null) {
			return null;
		}
		String currentTenantId = requireCurrentTenantId();
		if (!currentTenantId.equals(dataAgent.getTenantId())) {
			return null;
		}
		return dataAgent;
	}

	private String requireCurrentTenantId() {
		String tenantId;
		try {
			tenantId = authenticationContext.tenantId();
		}
		catch (Exception ex) {
			// The guard below still fails closed; without this the caller cannot tell absent from unresolvable.
			log.warn("解析当前租户上下文失败, 将按缺失租户拒绝本次 Agent 写操作", ex);
			tenantId = null;
		}
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.badRequest("Tenant context is required");
		}
		return tenantId;
	}

	private void validateRuntimeLimits(DataAgent dataAgent) {
		if (dataAgent.getReactMaxIterations() != null && dataAgent.getReactMaxIterations() < 1) {
			throw CheckedException.badRequest("reactMaxIterations必须大于0");
		}
		if (dataAgent.getMaxModelCalls() != null && dataAgent.getMaxModelCalls() < 1) {
			throw CheckedException.badRequest("maxModelCalls必须大于0");
		}
		if (dataAgent.getMaxToolCalls() != null && dataAgent.getMaxToolCalls() < 1) {
			throw CheckedException.badRequest("maxToolCalls必须大于0");
		}
		if (dataAgent.getMaxPromptTokens() != null && dataAgent.getMaxPromptTokens() < 1L) {
			throw CheckedException.badRequest("maxPromptTokens必须大于0");
		}
	}

	private Map<String, Object> resolveTemporalPolicy(DataAgent dataAgent) {
		if (dataAgent != null && dataAgent.getTemporalPolicy() != null) {
			return agentTemporalService.normalizePolicy(dataAgent.getTemporalPolicy());
		}
		if (dataAgent != null && dataAgent.getId() != null) {
			DataAgent existing = dataAgentMapper.findById(dataAgent.getId());
			if (existing != null && existing.getTemporalPolicy() != null) {
				return agentTemporalService.normalizePolicy(existing.getTemporalPolicy());
			}
		}
		return agentTemporalService.normalizePolicy(Map.of());
	}

	private Integer resolveRuntimeTimeoutSeconds(DataAgent dataAgent) {
		if (dataAgent != null && dataAgent.getRuntimeTimeoutSeconds() != null && dataAgent.getRuntimeTimeoutSeconds() > 0) {
			return dataAgent.getRuntimeTimeoutSeconds();
		}
		if (dataAgent != null && dataAgent.getId() != null) {
			DataAgent existing = dataAgentMapper.findById(dataAgent.getId());
			if (existing != null && existing.getRuntimeTimeoutSeconds() != null
					&& existing.getRuntimeTimeoutSeconds() > 0) {
				return existing.getRuntimeTimeoutSeconds();
			}
		}
		Duration configuredTimeout = dataAgentProperties.getRuntime().getTotalTimeout();
		if (configuredTimeout == null || configuredTimeout.isNegative() || configuredTimeout.isZero()) {
			throw new IllegalStateException("Agent runtime total timeout must be greater than zero");
		}
		long configuredSeconds = configuredTimeout.toSeconds();
		if (configuredSeconds < 1 || configuredSeconds > Integer.MAX_VALUE) {
			throw new IllegalStateException("Agent runtime total timeout must be between 1 and "
					+ Integer.MAX_VALUE + " seconds");
		}
		return (int) configuredSeconds;
	}

	@Override
	public void deleteById(Long id) {
		try {
			// 获取头像信息用于文件清理
			DataAgent existing = requireAgent(id);
			String avatar = existing.getAvatar();

			// Delete agent record from database
			dataAgentMapper.deleteById(id);

			// Also clean up the agent's vector data
			if (agentVectorStoreService != null) {
				try {
					agentVectorStoreService.deleteDocumentsByMetedata(id.toString(), new HashMap<>());
					log.info("Successfully deleted vector data for agent: {}", id);
				}
				catch (Exception vectorException) {
					log.warn("Failed to delete vector data for agent: {}, error: {}", id, vectorException.getMessage());
					// Vector data deletion failure does not affect the main process
				}
			}

			// 清理头像文件
			try {
				if (avatar != null && !avatar.isBlank()) {
					log.info("Skip physical suite avatar deletion, only business reference is removed. avatar={}, agent={}",
							avatar, id);
				}
			}
			catch (Exception avatarEx) {
				log.warn("Failed to cleanup avatar file: {} for agent: {}, error: {}", avatar, id,
						avatarEx.getMessage());
			}

			log.info("Successfully deleted agent: {}", id);
		}
		catch (Exception e) {
			log.error("Failed to delete agent: {}", id, e);
			throw e;
		}
	}

	@Override
	public ApiKeyResp getApiKey(Long id) {
		DataAgent dataAgent = requireAgent(id);
		return new ApiKeyResp(getApiKeyMasked(id), dataAgent.getApiKeyEnabled());
	}

	@Override
	public ApiKeyResp generateApiKeyResponse(Long id) {
		DataAgent dataAgent = generateApiKey(id);
		return new ApiKeyResp(dataAgent.getApiKey(), dataAgent.getApiKeyEnabled());
	}

	@Override
	public ApiKeyResp resetApiKeyResponse(Long id) {
		DataAgent dataAgent = resetApiKey(id);
		return new ApiKeyResp(dataAgent.getApiKey(), dataAgent.getApiKeyEnabled());
	}

	@Override
	public ApiKeyResp deleteApiKeyResponse(Long id) {
		DataAgent dataAgent = deleteApiKey(id);
		return new ApiKeyResp(dataAgent.getApiKey(), dataAgent.getApiKeyEnabled());
	}

	@Override
	public ApiKeyResp toggleApiKeyResponse(Long id, boolean enabled) {
		DataAgent dataAgent = toggleApiKey(id, enabled);
		return new ApiKeyResp(ApiKeyUtil.mask(dataAgent.getApiKey()), dataAgent.getApiKeyEnabled());
	}

	@Override
	public DataAgent generateApiKey(Long id) {
		DataAgent dataAgent = requireAgent(id);
		String apiKey = ApiKeyUtil.generate();
		dataAgentMapper.updateApiKey(id, apiKey, true);
		dataAgent.setApiKey(apiKey);
		dataAgent.setApiKeyEnabled(true);
		return dataAgent;
	}

	@Override
	public DataAgent resetApiKey(Long id) {
		return generateApiKey(id);
	}

	@Override
	public DataAgent deleteApiKey(Long id) {
		DataAgent dataAgent = requireAgent(id);
		dataAgentMapper.updateApiKey(id, null, false);
		dataAgent.setApiKey(null);
		dataAgent.setApiKeyEnabled(false);
		return dataAgent;
	}

	@Override
	public DataAgent toggleApiKey(Long id, boolean enabled) {
		dataAgentMapper.toggleApiKey(id, enabled);
		DataAgent dataAgent = requireAgent(id);
		dataAgent.setApiKeyEnabled(enabled);
		return dataAgent;
	}

	@Override
	public String getApiKeyMasked(Long id) {
		DataAgent dataAgent = requireAgent(id);
		String apiKey = dataAgent.getApiKey();
		if (apiKey == null || apiKey.isBlank()) {
			return null;
		}
		return ApiKeyUtil.mask(apiKey);
	}

	private List<DataAgent> enrichAvatarPreview(List<DataAgent> agents) {
		if (agents == null || agents.isEmpty()) {
			return agents;
		}
		Set<String> avatarPaths = new LinkedHashSet<>();
		for (DataAgent agent : agents) {
			if (agent != null && StringUtils.hasText(agent.getAvatar())) {
				avatarPaths.add(agent.getAvatar().trim());
			}
		}
		Map<String, FilePreviewResp> previewMap = avatarPaths.isEmpty() ? Map.of()
				: localFileService.findDisplayPreviews(avatarPaths);
		for (DataAgent agent : agents) {
			enrichAvatarPreview(agent, previewMap);
		}
		return agents;
	}

	private DataAgent enrichAvatarPreview(DataAgent agent) {
		if (agent == null || !StringUtils.hasText(agent.getAvatar())) {
			return agent;
		}
		return enrichAvatarPreview(agent, localFileService.findDisplayPreviews(List.of(agent.getAvatar())));
	}

	private DataAgent enrichAvatarPreview(DataAgent agent, Map<String, FilePreviewResp> previewMap) {
		if (agent == null || !StringUtils.hasText(agent.getAvatar()) || previewMap == null || previewMap.isEmpty()) {
			return agent;
		}
		FilePreviewResp preview = previewMap.get(agent.getAvatar().trim());
		if (preview != null && StringUtils.hasText(preview.getPreviewUrl())) {
			agent.setAvatarPreviewUrl(preview.getPreviewUrl());
		}
		return agent;
	}

	private void validateAvatar(DataAgent dataAgent) {
		if (dataAgent == null || !StringUtils.hasText(dataAgent.getAvatar())) {
			return;
		}
		try {
			localFileService.validateSuiteReference(dataAgent.getAvatar());
		}
		catch (RuntimeException ex) {
			log.warn("Ignore invalid data agent avatar. agentId={}, avatar={}, reason={}",
					dataAgent.getId(), dataAgent.getAvatar(), ex.getMessage());
			dataAgent.setAvatar(null);
		}
	}

}
