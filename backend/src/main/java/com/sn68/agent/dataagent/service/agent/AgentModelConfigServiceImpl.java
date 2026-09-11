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

import com.sn68.agent.dataagent.converter.ModelConfigConverter;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.dto.agent.AgentModelConfigItemResp;
import com.sn68.agent.dataagent.dto.agent.RuntimeChatModelDTO;
import com.sn68.agent.dataagent.dto.agent.UpdateAgentModelConfigReq;
import com.sn68.agent.dataagent.entity.AgentModelConfig;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.entity.ModelConfig;
import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.dataagent.repository.AgentModelConfigMapper;
import com.sn68.agent.dataagent.repository.ModelConfigMapper;
import com.sn68.agent.dataagent.service.aimodelconfig.ModelConfigDataService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent 模型配置管理实现：维护 Agent 与模型配置的绑定及生效规则。
 */
@Service
@AllArgsConstructor
public class AgentModelConfigServiceImpl implements AgentModelConfigService {

	private final AgentModelConfigMapper agentModelConfigMapper;

	private final ModelConfigMapper modelConfigMapper;

	private final ModelConfigDataService modelConfigDataService;

	private final DataAgentService agentService;

	@Override
	public List<AgentModelConfigItemResp> listAgentModelConfigs(Long agentId) {
		agentService.requireAgent(agentId);
		return agentModelConfigMapper.findByAgentId(agentId).stream().map(this::toItem).toList();
	}

	@Override
	public List<ModelConfigDTO> listAvailableChatModels(Long agentId) {
		DataAgent dataAgent = agentService.requireAgent(agentId);
		List<AgentModelConfig> configs = agentModelConfigMapper.findEnabledByAgentId(agentId)
			.stream()
			.filter(config -> Boolean.TRUE.equals(config.getUserSelectable()))
			.toList();
		if (configs.isEmpty()) {
			return fallbackAvailableModels(dataAgent);
		}
		return configs.stream()
			.map(config -> modelConfigMapper.findById(config.getModelConfigId()))
			.filter(this::isChatModel)
			.map(ModelConfigConverter::toDTO)
			.toList();
	}

	@Override
	public List<RuntimeChatModelDTO> listRuntimeChatModels(Long agentId) {
		return listRuntimeChatModels(agentService.requireAgent(agentId));
	}

	@Override
	public List<RuntimeChatModelDTO> listRuntimeChatModels(DataAgent dataAgent) {
		if (dataAgent == null || dataAgent.getId() == null) {
			return List.of();
		}
		List<AgentModelConfig> configs = agentModelConfigMapper.findByAgentId(dataAgent.getId());
		if (configs.isEmpty()) {
			return fallbackRuntimeChatModels(dataAgent);
		}
		return configs.stream().map(this::toRuntimeChatModel).filter(Objects::nonNull).toList();
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public List<AgentModelConfigItemResp> updateAgentModelConfigs(Long agentId, UpdateAgentModelConfigReq request) {
		DataAgent dataAgent = agentService.requireAgent(agentId);
		List<UpdateAgentModelConfigReq.ModelItem> items = request == null || request.models() == null ? List.of()
				: request.models();
		LinkedHashSet<Long> modelConfigIdSet = items.stream()
			.map(UpdateAgentModelConfigReq.ModelItem::modelConfigId)
			.filter(Objects::nonNull)
			.collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
		List<Long> modelConfigIds = new ArrayList<>(modelConfigIdSet);
		Long defaultModelConfigId = request == null ? null : request.defaultModelConfigId();
		if (defaultModelConfigId != null && !modelConfigIds.contains(defaultModelConfigId)) {
			modelConfigIds.add(defaultModelConfigId);
		}
		for (Long modelConfigId : modelConfigIds) {
			requireEnabledChatModel(modelConfigId);
		}
		agentModelConfigMapper.softDeleteByAgentId(agentId);
		for (Long modelConfigId : modelConfigIds) {
			UpdateAgentModelConfigReq.ModelItem item = findItem(items, modelConfigId);
			agentModelConfigMapper.insert(AgentModelConfig.builder()
				.agentId(agentId)
				.modelConfigId(modelConfigId)
				.isDefault(Objects.equals(defaultModelConfigId, modelConfigId))
				.userSelectable(item == null || item.userSelectable() == null || item.userSelectable())
				.enabled(item == null || item.enabled() == null || item.enabled())
				.createTime(Instant.now())
				.lastModifyTime(Instant.now())
				.deleted(false)
				.build());
		}
		dataAgent.setChatModelConfigId(defaultModelConfigId);
		agentService.save(dataAgent);
		return listAgentModelConfigs(agentId);
	}

	@Override
	public ModelConfigDTO resolveChatModelConfig(DataAgent dataAgent, Long selectedChatModelConfigId) {
		Long agentId = dataAgent == null ? null : dataAgent.getId();
		if (selectedChatModelConfigId != null) {
			validateSelectedModel(agentId, selectedChatModelConfigId);
			return modelConfigDataService.getRuntimeConfigById(selectedChatModelConfigId, ModelType.CHAT);
		}
		Long defaultModelConfigId = resolveAgentDefaultModelConfigId(dataAgent);
		if (defaultModelConfigId != null) {
			return modelConfigDataService.getRuntimeConfigById(defaultModelConfigId, ModelType.CHAT);
		}
		ModelConfigDTO active = modelConfigDataService.getActiveRuntimeConfigByType(ModelType.CHAT);
		if (active == null) {
			throw CheckedException.badRequest("未配置模型，请在模型配置页添加");
		}
		return active;
	}

	@Override
	public boolean isUserSelectable(Long agentId, Long modelConfigId) {
		if (agentId == null || modelConfigId == null) {
			return false;
		}
		AgentModelConfig config = agentModelConfigMapper.findByAgentIdAndModelConfigId(agentId, modelConfigId);
		return config != null && Boolean.TRUE.equals(config.getEnabled())
				&& Boolean.TRUE.equals(config.getUserSelectable());
	}

	private List<ModelConfigDTO> fallbackAvailableModels(DataAgent dataAgent) {
		if (dataAgent != null && dataAgent.getChatModelConfigId() != null) {
			ModelConfig modelConfig = modelConfigMapper.findById(dataAgent.getChatModelConfigId());
			if (isChatModel(modelConfig)) {
				return List.of(ModelConfigConverter.toDTO(modelConfig));
			}
		}
		ModelConfigDTO active = modelConfigDataService.getActiveConfigByType(ModelType.CHAT);
		return active == null ? List.of() : List.of(active);
	}

	private List<RuntimeChatModelDTO> fallbackRuntimeChatModels(DataAgent dataAgent) {
		return fallbackAvailableModels(dataAgent).stream()
			.map(modelConfig -> RuntimeChatModelDTO.builder()
				.modelConfigId(modelConfig.getId())
				.modelConfig(modelConfig)
				.enabled(true)
				.userSelectable(true)
				.isDefault(true)
				.selectable(true)
				.build())
			.toList();
	}

	private Long resolveAgentDefaultModelConfigId(DataAgent dataAgent) {
		if (dataAgent == null || dataAgent.getId() == null) {
			return null;
		}
		AgentModelConfig defaultConfig = agentModelConfigMapper.findDefaultByAgentId(dataAgent.getId());
		if (defaultConfig != null) {
			return defaultConfig.getModelConfigId();
		}
		return dataAgent.getChatModelConfigId();
	}

	private void validateSelectedModel(Long agentId, Long selectedChatModelConfigId) {
		requireEnabledChatModel(selectedChatModelConfigId);
		if (agentId == null) {
			return;
		}
		List<AgentModelConfig> agentConfigs = agentModelConfigMapper.findEnabledByAgentId(agentId);
		if (agentConfigs.isEmpty()) {
			return;
		}
		if (!isUserSelectable(agentId, selectedChatModelConfigId)) {
			throw new CheckedException(403, "Model is not selectable for current Agent");
		}
	}

	private ModelConfig requireEnabledChatModel(Long modelConfigId) {
		ModelConfig modelConfig = modelConfigMapper.findById(modelConfigId);
		if (!isChatModel(modelConfig)) {
			throw CheckedException.badRequest("CHAT model config is not available: " + modelConfigId);
		}
		return modelConfig;
	}

	private boolean isChatModel(ModelConfig modelConfig) {
		return modelConfig != null && ModelType.CHAT.equals(modelConfig.getModelType());
	}

	private AgentModelConfigItemResp toItem(AgentModelConfig config) {
		ModelConfig modelConfig = modelConfigMapper.findById(config.getModelConfigId());
		return AgentModelConfigItemResp.builder()
			.id(config.getId())
			.agentId(config.getAgentId())
			.modelConfigId(config.getModelConfigId())
			.isDefault(config.getIsDefault())
			.userSelectable(config.getUserSelectable())
			.enabled(config.getEnabled())
			.modelConfig(ModelConfigConverter.toDTO(modelConfig))
			.build();
	}

	private RuntimeChatModelDTO toRuntimeChatModel(AgentModelConfig config) {
		ModelConfig modelConfig = modelConfigMapper.findById(config.getModelConfigId());
		if (!isChatModel(modelConfig)) {
			return null;
		}
		boolean enabled = Boolean.TRUE.equals(config.getEnabled());
		boolean userSelectable = Boolean.TRUE.equals(config.getUserSelectable());
		return RuntimeChatModelDTO.builder()
			.modelConfigId(config.getModelConfigId())
			.modelConfig(ModelConfigConverter.toDTO(modelConfig))
			.enabled(enabled)
			.userSelectable(userSelectable)
			.isDefault(Boolean.TRUE.equals(config.getIsDefault()))
			.selectable(enabled && userSelectable)
			.build();
	}

	private UpdateAgentModelConfigReq.ModelItem findItem(List<UpdateAgentModelConfigReq.ModelItem> items,
			Long modelConfigId) {
		return items.stream()
			.filter(item -> Objects.equals(modelConfigId, item.modelConfigId()))
			.findFirst()
			.orElse(null);
	}

}
