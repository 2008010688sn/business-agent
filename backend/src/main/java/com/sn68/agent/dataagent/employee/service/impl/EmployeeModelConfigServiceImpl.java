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
package com.sn68.agent.dataagent.employee.service.impl;

import com.sn68.agent.dataagent.converter.ModelConfigConverter;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.dto.agent.RuntimeChatModelDTO;
import com.sn68.agent.dataagent.employee.dto.EmployeeModelConfigItemResp;
import com.sn68.agent.dataagent.employee.dto.EmployeeReleaseSnapshot;
import com.sn68.agent.dataagent.employee.dto.UpdateEmployeeModelConfigReq;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeModelConfig;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeModelConfigMapper;
import com.sn68.agent.dataagent.employee.service.EmployeeModelConfigService;
import com.sn68.agent.dataagent.entity.ModelConfig;
import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.dataagent.repository.ModelConfigMapper;
import com.sn68.agent.dataagent.service.aimodelconfig.ModelConfigDataService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 数字员工可用模型配置实现。独立表 {@code digital_employee_model_config}，
 * 禁止把员工主键写入 {@code agent_model_config.agent_id}。
 */
@Service
@RequiredArgsConstructor
public class EmployeeModelConfigServiceImpl implements EmployeeModelConfigService {

	private final DigitalEmployeeMapper employeeMapper;

	private final DigitalEmployeeModelConfigMapper employeeModelConfigMapper;

	private final ModelConfigMapper modelConfigMapper;

	private final ModelConfigDataService modelConfigDataService;

	private final AuthenticationContext authenticationContext;

	@Override
	public List<EmployeeModelConfigItemResp> listModelConfigs(Long employeeId) {
		DigitalEmployee employee = requireEmployee(employeeId);
		return employeeModelConfigMapper.findByEmployeeId(employee.getId(), employee.getTenantId())
			.stream()
			.map(this::toItem)
			.toList();
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public List<EmployeeModelConfigItemResp> updateModelConfigs(Long employeeId, UpdateEmployeeModelConfigReq request) {
		DigitalEmployee employee = requireEmployee(employeeId);
		List<UpdateEmployeeModelConfigReq.ModelItem> items = request == null || request.models() == null ? List.of()
				: request.models();
		LinkedHashSet<Long> modelConfigIdSet = items.stream()
			.map(UpdateEmployeeModelConfigReq.ModelItem::modelConfigId)
			.filter(Objects::nonNull)
			.collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
		List<Long> modelConfigIds = new ArrayList<>(modelConfigIdSet);
		Long defaultModelConfigId = request == null ? null : request.defaultModelConfigId();
		if (defaultModelConfigId != null && !modelConfigIds.contains(defaultModelConfigId)) {
			throw CheckedException.badRequest("默认模型必须包含在可用模型列表中: " + defaultModelConfigId);
		}
		for (Long modelConfigId : modelConfigIds) {
			requireEnabledChatModel(modelConfigId);
			UpdateEmployeeModelConfigReq.ModelItem item = findItem(items, modelConfigId);
			if (Objects.equals(defaultModelConfigId, modelConfigId)
					&& item != null && Boolean.FALSE.equals(item.enabled())) {
				throw CheckedException.badRequest("默认模型必须保持启用: " + defaultModelConfigId);
			}
		}
		employeeModelConfigMapper.softDeleteByEmployeeId(employee.getId(), employee.getTenantId());
		Instant now = Instant.now();
		for (Long modelConfigId : modelConfigIds) {
			UpdateEmployeeModelConfigReq.ModelItem item = findItem(items, modelConfigId);
			employeeModelConfigMapper.insert(DigitalEmployeeModelConfig.builder()
				.tenantId(employee.getTenantId())
				.employeeId(employee.getId())
				.modelConfigId(modelConfigId)
				.isDefault(Objects.equals(defaultModelConfigId, modelConfigId))
				.userSelectable(item == null || item.userSelectable() == null || item.userSelectable())
				.enabled(item == null || item.enabled() == null || item.enabled())
				.createTime(now)
				.lastModifyTime(now)
				.deleted(false)
				.build());
		}
		employee.setModelConfigId(defaultModelConfigId);
		employeeMapper.updateById(employee);
		return listModelConfigs(employeeId);
	}

	@Override
	public List<RuntimeChatModelDTO> listRuntimeChatModels(Long employeeId) {
		DigitalEmployee employee = requireEmployee(employeeId);
		List<DigitalEmployeeModelConfig> configs = employeeModelConfigMapper.findByEmployeeId(employee.getId(),
				employee.getTenantId());
		if (configs.isEmpty()) {
			return fallbackRuntimeChatModels(employee);
		}
		return configs.stream().map(this::toRuntimeChatModel).filter(Objects::nonNull).toList();
	}

	@Override
	public List<Long> listEnabledModelConfigIds(Long employeeId, String tenantId) {
		if (employeeId == null || !StringUtils.hasText(tenantId)) {
			return List.of();
		}
		return employeeModelConfigMapper.findEnabledByEmployeeId(employeeId, tenantId)
			.stream()
			.map(DigitalEmployeeModelConfig::getModelConfigId)
			.filter(Objects::nonNull)
			.toList();
	}

	@Override
	public ModelConfigDTO resolveChatModelConfig(Long employeeId, String tenantId, Long selectedChatModelConfigId,
			EmployeeReleaseSnapshot snapshot) {
		List<Long> available = snapshot != null && snapshot.availableModelConfigIds() != null
				&& !snapshot.availableModelConfigIds().isEmpty()
						? snapshot.availableModelConfigIds() : listEnabledModelConfigIds(employeeId, tenantId);
		Long defaultId = snapshot != null && snapshot.modelConfigId() != null ? snapshot.modelConfigId()
				: resolveDraftDefault(employeeId, tenantId);
		if (selectedChatModelConfigId != null) {
			if (!available.isEmpty() && !available.contains(selectedChatModelConfigId)
					&& !Objects.equals(selectedChatModelConfigId, defaultId)) {
				throw CheckedException.badRequest("所选模型不在该员工可用列表内");
			}
			if (available.isEmpty() && defaultId != null
					&& !Objects.equals(selectedChatModelConfigId, defaultId)) {
				throw CheckedException.badRequest("所选模型不在该员工可用列表内");
			}
			return modelConfigDataService.getRuntimeConfigById(selectedChatModelConfigId, ModelType.CHAT);
		}
		if (defaultId != null) {
			return modelConfigDataService.getRuntimeConfigById(defaultId, ModelType.CHAT);
		}
		return modelConfigDataService.getActiveRuntimeConfigByType(ModelType.CHAT);
	}

	private Long resolveDraftDefault(Long employeeId, String tenantId) {
		DigitalEmployeeModelConfig defaultConfig = employeeModelConfigMapper.findDefaultByEmployeeId(employeeId,
				tenantId);
		if (defaultConfig != null) {
			return defaultConfig.getModelConfigId();
		}
		DigitalEmployee employee = employeeMapper.findByIdAndTenantId(employeeId, tenantId);
		return employee == null ? null : employee.getModelConfigId();
	}

	private List<RuntimeChatModelDTO> fallbackRuntimeChatModels(DigitalEmployee employee) {
		if (employee.getModelConfigId() != null) {
			ModelConfig modelConfig = modelConfigMapper.findById(employee.getModelConfigId());
			if (isChatModel(modelConfig)) {
				return List.of(RuntimeChatModelDTO.builder()
					.modelConfigId(modelConfig.getId())
					.modelConfig(ModelConfigConverter.toDTO(modelConfig))
					.enabled(true)
					.userSelectable(true)
					.isDefault(true)
					.selectable(true)
					.build());
			}
		}
		ModelConfigDTO active = modelConfigDataService.getActiveConfigByType(ModelType.CHAT);
		if (active == null) {
			return List.of();
		}
		return List.of(RuntimeChatModelDTO.builder()
			.modelConfigId(active.getId())
			.modelConfig(active)
			.enabled(true)
			.userSelectable(true)
			.isDefault(true)
			.selectable(true)
			.build());
	}

	private ModelConfig requireEnabledChatModel(Long modelConfigId) {
		ModelConfig modelConfig = modelConfigMapper.findById(modelConfigId);
		if (!isChatModel(modelConfig)) {
			throw CheckedException.badRequest("CHAT 模型配置不可用: " + modelConfigId);
		}
		return modelConfig;
	}

	private boolean isChatModel(ModelConfig modelConfig) {
		return modelConfig != null && ModelType.CHAT.equals(modelConfig.getModelType());
	}

	private EmployeeModelConfigItemResp toItem(DigitalEmployeeModelConfig config) {
		ModelConfig modelConfig = modelConfigMapper.findById(config.getModelConfigId());
		return EmployeeModelConfigItemResp.builder()
			.id(config.getId())
			.employeeId(config.getEmployeeId())
			.modelConfigId(config.getModelConfigId())
			.isDefault(config.getIsDefault())
			.userSelectable(config.getUserSelectable())
			.enabled(config.getEnabled())
			.modelConfig(ModelConfigConverter.toDTO(modelConfig))
			.build();
	}

	private RuntimeChatModelDTO toRuntimeChatModel(DigitalEmployeeModelConfig config) {
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

	private UpdateEmployeeModelConfigReq.ModelItem findItem(List<UpdateEmployeeModelConfigReq.ModelItem> items,
			Long modelConfigId) {
		return items.stream()
			.filter(item -> Objects.equals(modelConfigId, item.modelConfigId()))
			.findFirst()
			.orElse(null);
	}

	private DigitalEmployee requireEmployee(Long employeeId) {
		if (employeeId == null) {
			throw CheckedException.badRequest("数字员工ID不能为空");
		}
		String tenantId = currentTenantId();
		DigitalEmployee employee = employeeMapper.findByIdAndTenantId(employeeId, tenantId);
		if (employee == null) {
			throw CheckedException.notFound("数字员工不存在: " + employeeId);
		}
		return employee;
	}

	private String currentTenantId() {
		String tenantId = authenticationContext.tenantId();
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.forbidden("无法解析当前租户");
		}
		return tenantId.trim();
	}

}
