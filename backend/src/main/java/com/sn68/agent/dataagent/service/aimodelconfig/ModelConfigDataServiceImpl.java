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
package com.sn68.agent.dataagent.service.aimodelconfig;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.converter.ModelConfigConverter;
import com.sn68.agent.dataagent.dto.ModelConfigPageQueryReq;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.dto.ModelConfigSummaryResp;
import com.sn68.agent.dataagent.entity.ModelConfig;
import com.sn68.agent.dataagent.event.ModelConfigChangedEvent;
import com.sn68.agent.dataagent.enums.ModelCapabilityProfile;
import com.sn68.agent.dataagent.enums.ModelEndpointDialect;
import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.dataagent.enums.ModelPreservedReasoningPolicy;
import com.sn68.agent.dataagent.enums.ModelReasoningLevel;
import com.sn68.agent.dataagent.enums.ModelReasoningMode;
import com.sn68.agent.dataagent.enums.ModelReasoningProtocol;
import com.sn68.agent.dataagent.enums.ModelStructuredOutputMode;
import com.sn68.agent.dataagent.enums.ModelTemperaturePolicy;
import com.sn68.agent.dataagent.enums.ModelTokenLimitMode;
import com.sn68.agent.dataagent.repository.ModelConfigMapper;
import com.sn68.agent.dataagent.service.aimodelconfig.options.ModelRequestOptionsResolver;
import com.sn68.agent.dataagent.service.aimodelconfig.options.ResolvedModelRequestOptions;
import com.sn68.agent.dataagent.service.permission.PlatformScopePermissionService;
import com.sn68.agent.dataagent.service.security.SensitiveConfigCryptoService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import static com.sn68.agent.dataagent.converter.ModelConfigConverter.toEntity;

/**
 * 模型配置Data服务组件，封装 DataAgent 对应业务入口。
 */
@Slf4j
@Service
@AllArgsConstructor
public class ModelConfigDataServiceImpl implements ModelConfigDataService {

	private static final long MAX_CONTEXT_WINDOW_TOKENS = 1_000_000_000L;

	private static final Map<String, ModelEndpointDialect> PROVIDER_NATIVE_DIALECTS = Map.ofEntries(
			Map.entry("qwen", ModelEndpointDialect.DASHSCOPE_NATIVE),
			Map.entry("dashscope", ModelEndpointDialect.DASHSCOPE_NATIVE),
			Map.entry("step", ModelEndpointDialect.STEPFUN_NATIVE),
			Map.entry("stepfun", ModelEndpointDialect.STEPFUN_NATIVE),
			Map.entry("deepseek", ModelEndpointDialect.DEEPSEEK_NATIVE),
			Map.entry("glm", ModelEndpointDialect.ZHIPU_NATIVE),
			Map.entry("zhipu", ModelEndpointDialect.ZHIPU_NATIVE),
			Map.entry("kimi", ModelEndpointDialect.MOONSHOT_NATIVE),
			Map.entry("moonshot", ModelEndpointDialect.MOONSHOT_NATIVE));

	private static final String MODEL_CONFIG_RESOURCE = "模型配置";

	private final ModelConfigMapper modelConfigMapper;

	private final SensitiveConfigCryptoService cryptoService;

	private final ModelRequestOptionsResolver requestOptionsResolver;

	private final ApplicationEventPublisher eventPublisher;

	private final PlatformScopePermissionService platformScopePermissionService;

	/**
	 * 按主键查询模型配置实体，未命中返回 {@code null}。
	 */
	@Override
	public ModelConfig findById(Long id) {
		return modelConfigMapper.findById(id);
	}

	/**
	 * 切换指定配置为该模型类型的唯一启用项：事务内停用同类型其它配置，需平台管理员权限。
	 */
	@Transactional(rollbackFor = Exception.class)
	@Override
	public void switchActiveStatus(Long id, ModelType type) {
		String tenantId = requireCurrentTenantId();
		ModelConfig entity = requireOwnedConfig(id, tenantId);
		List<Long> deactivatedConfigIds = modelConfigMapper.findActiveIdsByTypeExcluding(type.getCode(), id, tenantId);
		modelConfigMapper.deactivateOthers(type.getCode(), id, tenantId);
		entity.setIsActive(true);
		entity.setLastModifyTime(Instant.now());
		modelConfigMapper.updateById(entity);
		publishModelConfigChangesAfterCommit(deactivatedConfigIds);
		publishModelConfigChangedAfterCommit(entity.getId());
	}

	/**
	 * 查询全部模型配置（脱敏 DTO，不含明文密钥）。
	 */
	@Override
	public List<ModelConfigDTO> listConfigs() {
		return modelConfigMapper.findAllByTenant(requireCurrentTenantId())
			.stream()
			.map(entity -> ModelConfigConverter.toSafeDTO(entity, cryptoService))
			.collect(Collectors.toList());
	}

	/**
	 * 分页查询模型配置（脱敏 DTO）。
	 */
	@Override
	public IPage<ModelConfigDTO> queryPage(ModelConfigPageQueryReq request) {
		ModelConfigPageQueryReq query = request == null ? new ModelConfigPageQueryReq() : request;
		return modelConfigMapper.selectConfigPage(query.buildPage(), query, requireCurrentTenantId())
			.convert(this::toSafeDTO);
	}

	/**
	 * 统计模型配置概览：总数、启用数与各模型类型的数量。
	 */
	@Override
	public ModelConfigSummaryResp summary() {
		List<ModelConfig> configs = modelConfigMapper.findAllByTenant(requireCurrentTenantId());
		return new ModelConfigSummaryResp((long) configs.size(), countActive(configs), count(configs, ModelType.CHAT),
			count(configs, ModelType.EMBEDDING), count(configs, ModelType.AUDIO_TRANSCRIPTION),
			count(configs, ModelType.TEXT_TO_SPEECH), count(configs, ModelType.REALTIME_VOICE));
	}

	@Override
	public ModelConfigDTO getConfigById(Long id, ModelType modelType) {
		return ModelConfigConverter.toSafeDTO(requireTypedConfig(id, modelType), cryptoService);
	}

	@Override
	public ModelConfigDTO getRuntimeConfigById(Long id, ModelType modelType) {
		return ModelConfigConverter.toRuntimeDTO(requireTypedConfig(id, modelType), cryptoService);
	}

	/**
	 * 新增模型配置：校验必填与选项合法性后事务内落库，密钥加密存储。
	 */
	@Transactional(rollbackFor = Exception.class)
	@Override
	public void addConfig(ModelConfigDTO dto) {
		validateForSave(dto);
		ModelConfig entity = toEntity(dto);
		entity.setTenantId(requireCurrentTenantId());
		encryptSecrets(entity);
		modelConfigMapper.insert(entity);
		publishModelConfigChangedAfterCommit(entity.getId());
	}

	/**
	 * 更新模型配置：校验必填与选项合法性后事务内落库，密钥字段按需加密回写。
	 */
	@Transactional(rollbackFor = Exception.class)
	@Override
	public ModelConfig updateConfigInDb(ModelConfigDTO dto) {
		ModelType modelType = validateForSave(dto);
		String tenantId = requireCurrentTenantId();
		ModelConfig entity = requireOwnedConfig(dto.getId(), tenantId);
		mergeDtoToEntity(dto, entity);
		entity.setTenantId(tenantId);
		List<Long> deactivatedConfigIds = Boolean.TRUE.equals(entity.getIsActive())
				? modelConfigMapper.findActiveIdsByTypeExcluding(modelType.getCode(), entity.getId(), tenantId)
				: List.of();
		if (Boolean.TRUE.equals(entity.getIsActive())) {
			modelConfigMapper.deactivateOthers(modelType.getCode(), entity.getId(), tenantId);
		}
		entity.setLastModifyTime(Instant.now());
		modelConfigMapper.updateById(entity);
		publishModelConfigChangesAfterCommit(deactivatedConfigIds);
		publishModelConfigChangedAfterCommit(entity.getId());
		return entity;
	}

	/**
	 * 删除模型配置：启用中的配置禁止删除，事务提交后发布配置变更事件以刷新运行时缓存。
	 */
	@Transactional(rollbackFor = Exception.class)
	@Override
	public void deleteConfig(Long id) {
		ModelConfig entity = requireOwnedConfig(id, requireCurrentTenantId());
		if (entity == null) {
			throw CheckedException.notFound("模型配置不存在, id=" + id);
		}
		if (Boolean.TRUE.equals(entity.getIsActive())) {
			throw CheckedException.badRequest("该配置当前正在使用中，无法删除，请先激活其他配置后再删除, id=" + id);
		}

		int updated = modelConfigMapper.deleteById(id);
		if (updated == 0) {
			throw CheckedException.fail("删除失败, id=" + id);
		}
		publishModelConfigChangedAfterCommit(id);
	}

	@Override
	public ModelConfigDTO getActiveConfigByType(ModelType modelType) {
		return toActiveSafe(modelType, currentTenantIdOrNull());
	}

	@Override
	public ModelConfigDTO getActiveRuntimeConfigByType(ModelType modelType) {
		return getActiveRuntimeConfigByType(modelType, currentTenantIdOrNull());
	}

	@Override
	public ModelConfigDTO getActiveRuntimeConfigByType(ModelType modelType, String tenantId) {
		if (modelType == null || !StringUtils.hasText(tenantId)) {
			return null;
		}
		ModelConfig entity = modelConfigMapper.selectActiveByType(modelType.getCode(), tenantId.trim());
		if (entity == null) {
			log.warn("Activation model configuration of type [{}] not found for tenant {}, attempting to downgrade...",
					modelType, tenantId.trim());
			return null;
		}
		return ModelConfigConverter.toRuntimeDTO(entity, cryptoService);
	}

	@Override
	public ModelConfigDTO getRuntimeConfigById(Long id, ModelType modelType, String tenantId) {
		return ModelConfigConverter.toRuntimeDTO(requireTypedConfig(id, modelType, tenantId), cryptoService);
	}

	@Override
	public ModelConfigDTO getConfigById(Long id, ModelType modelType, String tenantId) {
		return ModelConfigConverter.toSafeDTO(requireTypedConfig(id, modelType, tenantId), cryptoService);
	}

	/**
	 * 组装运行时可用配置：按 ID 回读实体并解密密钥，供模型工厂消费。
	 */
	@Override
	public ModelConfigDTO prepareRuntimeConfig(ModelConfigDTO dto) {
		if (dto == null || dto.getId() == null) {
			return dto;
		}
		ModelConfig existing = modelConfigMapper.findById(dto.getId());
		if (existing == null) {
			return dto;
		}
		ModelConfigDTO runtime = new ModelConfigDTO();
		BeanUtils.copyProperties(dto, runtime);
		if (cryptoService.shouldKeepExisting(dto.getApiKey())) {
			runtime.setApiKey(cryptoService.decryptRuntimeSecret(existing.getApiKey()));
		}
		if (cryptoService.shouldKeepExisting(dto.getProxyPassword())) {
			runtime.setProxyPassword(cryptoService.decryptRuntimeSecret(existing.getProxyPassword()));
		}
		runtime.setApiKeyConfigured(cryptoService.hasConfiguredSecret(existing.getApiKey()));
		runtime.setProxyPasswordConfigured(cryptoService.hasConfiguredSecret(existing.getProxyPassword()));
		return runtime;
	}

	private ModelConfig requireTypedConfig(Long id, ModelType modelType) {
		return requireTypedConfig(id, modelType, requireCurrentTenantId());
	}

	private ModelConfig requireTypedConfig(Long id, ModelType modelType, String tenantId) {
		ModelConfig entity = requireOwnedConfig(id, tenantId);
		if (entity.getModelType() == null || !entity.getModelType().equals(modelType)) {
			throw CheckedException.badRequest(
					"模型配置类型不匹配, id=" + id + ", 期望=" + modelType + ", 实际=" + entity.getModelType());
		}
		return entity;
	}

	private ModelConfigDTO toActiveSafe(ModelType modelType, String tenantId) {
		if (modelType == null || !StringUtils.hasText(tenantId)) {
			return null;
		}
		ModelConfig entity = modelConfigMapper.selectActiveByType(modelType.getCode(), tenantId.trim());
		if (entity == null) {
			log.warn("Activation model configuration of type [{}] not found for tenant {}, attempting to downgrade...",
					modelType, tenantId.trim());
			return null;
		}
		return ModelConfigConverter.toSafeDTO(entity, cryptoService);
	}

	private ModelConfig requireOwnedConfig(Long id, String tenantId) {
		ModelConfig entity = modelConfigMapper.findById(id);
		if (entity == null || !StringUtils.hasText(tenantId) || !tenantId.equals(entity.getTenantId())) {
			throw CheckedException.notFound("模型配置不存在, id=" + id);
		}
		return entity;
	}

	private String requireCurrentTenantId() {
		return platformScopePermissionService.requireCurrentTenantId(MODEL_CONFIG_RESOURCE);
	}

	private String currentTenantIdOrNull() {
		return platformScopePermissionService.currentTenantId();
	}

	private void mergeDtoToEntity(ModelConfigDTO dto, ModelConfig oldEntity) {
		oldEntity.setProvider(dto.getProvider());
		oldEntity.setBaseUrl(dto.getBaseUrl());
		oldEntity.setModelName(dto.getModelName());
		oldEntity.setEndpointDialect(dto.getEndpointDialect());
		oldEntity.setCapabilityProfile(dto.getCapabilityProfile());
		oldEntity.setReasoningProtocol(dto.getReasoningProtocol());
		oldEntity.setReasoningMode(dto.getReasoningMode());
		oldEntity.setReasoningLevel(dto.getReasoningLevel());
		oldEntity.setReasoningBudgetTokens(dto.getReasoningBudgetTokens());
		oldEntity.setTokenLimitMode(dto.getTokenLimitMode());
		oldEntity.setTemperaturePolicy(dto.getTemperaturePolicy());
		oldEntity.setStructuredOutputMode(dto.getStructuredOutputMode());
		oldEntity.setPreservedReasoningPolicy(dto.getPreservedReasoningPolicy());
		oldEntity.setTemperature(dto.getTemperature());
		oldEntity.setMaxTokens(dto.getMaxTokens());
		oldEntity.setContextWindowTokens(dto.getContextWindowTokens());
		oldEntity.setModelType(ModelType.fromCode(dto.getModelType()));
		oldEntity.setCompletionsPath(dto.getCompletionsPath());
		oldEntity.setEmbeddingsPath(dto.getEmbeddingsPath());
		oldEntity.setTranscriptionsPath(dto.getTranscriptionsPath());
		oldEntity.setSupportVision(Boolean.TRUE.equals(dto.getSupportVision()));
		oldEntity.setProxyEnabled(dto.getProxyEnabled());
		oldEntity.setProxyHost(dto.getProxyHost());
		oldEntity.setProxyPort(dto.getProxyPort());
		oldEntity.setProxyUsername(dto.getProxyUsername());

		if (!cryptoService.shouldKeepExisting(dto.getApiKey())) {
			oldEntity.setApiKey(cryptoService.encryptIfNecessary(dto.getApiKey()));
		}
		if (!cryptoService.shouldKeepExisting(dto.getProxyPassword())) {
			oldEntity.setProxyPassword(cryptoService.encryptIfNecessary(dto.getProxyPassword()));
		}
	}

	private void clean(ModelConfigDTO dto) {
		if (dto == null) {
			return;
		}
		dto.setProvider(trimToNull(dto.getProvider()));
		dto.setModelName(trimToNull(dto.getModelName()));
		dto.setModelType(trimToNull(dto.getModelType()));
		dto.setBaseUrl(trimToNull(dto.getBaseUrl()));
		dto.setEndpointDialect(normalizeEnum(dto.getEndpointDialect(), ModelEndpointDialect.class,
				ModelEndpointDialect.OPENAI_COMPATIBLE));
		dto.setCapabilityProfile(normalizeEnum(dto.getCapabilityProfile(), ModelCapabilityProfile.class,
				ModelCapabilityProfile.AUTO));
		dto.setReasoningProtocol(normalizeEnum(dto.getReasoningProtocol(), ModelReasoningProtocol.class,
				ModelReasoningProtocol.AUTO));
		dto.setReasoningMode(normalizeEnum(dto.getReasoningMode(), ModelReasoningMode.class,
				ModelReasoningMode.AUTO));
		dto.setReasoningLevel(normalizeOptionalEnum(dto.getReasoningLevel(), ModelReasoningLevel.class));
		dto.setTokenLimitMode(normalizeOptionalEnum(dto.getTokenLimitMode(), ModelTokenLimitMode.class));
		dto.setTemperaturePolicy(normalizeOptionalEnum(dto.getTemperaturePolicy(), ModelTemperaturePolicy.class));
		dto.setStructuredOutputMode(normalizeEnum(dto.getStructuredOutputMode(), ModelStructuredOutputMode.class,
				ModelStructuredOutputMode.AUTO));
		dto.setPreservedReasoningPolicy(normalizeEnum(dto.getPreservedReasoningPolicy(),
				ModelPreservedReasoningPolicy.class, ModelPreservedReasoningPolicy.DROP));
		dto.setApiKey(trimToEmpty(dto.getApiKey()));
		dto.setCompletionsPath(trimToNull(dto.getCompletionsPath()));
		dto.setEmbeddingsPath(trimToNull(dto.getEmbeddingsPath()));
		dto.setTranscriptionsPath(trimToNull(dto.getTranscriptionsPath()));
		dto.setProxyHost(trimToNull(dto.getProxyHost()));
		dto.setProxyUsername(trimToNull(dto.getProxyUsername()));
		dto.setProxyPassword(trimToNull(dto.getProxyPassword()));
	}

	private ModelType validateForSave(ModelConfigDTO dto) {
		if (dto == null) {
			throw new IllegalArgumentException("模型配置不能为空");
		}
		clean(dto);
		ModelType modelType = ModelType.fromCode(dto.getModelType());
		dto.setModelType(modelType.getCode());
		validateTokenLimits(dto);
		if (modelType != ModelType.CHAT) {
			if (ModelPreservedReasoningPolicy.KEEP.name().equals(dto.getPreservedReasoningPolicy())) {
				throw new IllegalArgumentException("当前不支持 preservedReasoningPolicy=KEEP：缺少跨轮 reasoning 历史消费者");
			}
			return modelType;
		}

		ResolvedModelRequestOptions resolved = requestOptionsResolver.validatePersistable(dto);
		validateProviderNativeDialect(dto.getProvider(), resolved.endpointDialect());
		return modelType;
	}

	private void validateProviderNativeDialect(String provider, ModelEndpointDialect endpointDialect) {
		if (!StringUtils.hasText(provider) || !isNativeDialect(endpointDialect)) {
			return;
		}
		ModelEndpointDialect expected = PROVIDER_NATIVE_DIALECTS.get(provider.toLowerCase(java.util.Locale.ROOT));
		if (expected == null || expected == endpointDialect) {
			return;
		}
		throw new IllegalArgumentException("provider " + provider + " 与 endpointDialect " + endpointDialect
				+ " 不匹配，应使用 " + expected);
	}

	private boolean isNativeDialect(ModelEndpointDialect endpointDialect) {
		return switch (endpointDialect) {
			case DASHSCOPE_NATIVE, STEPFUN_NATIVE, DEEPSEEK_NATIVE, ZHIPU_NATIVE, MOONSHOT_NATIVE -> true;
			case OPENAI_COMPATIBLE, CUSTOM -> false;
		};
	}

	private void validateTokenLimits(ModelConfigDTO dto) {
		if (dto == null) {
			return;
		}
		Long maxTokens = dto.getMaxTokens();
		if (maxTokens != null) {
			if (maxTokens <= 0) {
				throw new IllegalArgumentException("最大输出 Token 必须大于 0");
			}
			if (maxTokens > Integer.MAX_VALUE) {
				throw new IllegalArgumentException("最大输出 Token 超出 Spring AI 当前支持范围：" + Integer.MAX_VALUE);
			}
		}
		Long contextWindowTokens = dto.getContextWindowTokens();
		if (contextWindowTokens != null) {
			if (contextWindowTokens <= 0) {
				throw new IllegalArgumentException("上下文窗口 Token 必须大于 0");
			}
			if (contextWindowTokens > MAX_CONTEXT_WINDOW_TOKENS) {
				throw new IllegalArgumentException("上下文窗口 Token 不能超过 " + MAX_CONTEXT_WINDOW_TOKENS);
			}
		}
		Long reasoningBudgetTokens = dto.getReasoningBudgetTokens();
		if (reasoningBudgetTokens != null
				&& (reasoningBudgetTokens <= 0 || reasoningBudgetTokens > Integer.MAX_VALUE)) {
			throw new IllegalArgumentException("reasoningBudgetTokens must be between 1 and " + Integer.MAX_VALUE);
		}
		if (dto.getTemperature() != null && !Double.isFinite(dto.getTemperature())) {
			throw new IllegalArgumentException("temperature must be finite");
		}
	}

	private void encryptSecrets(ModelConfig entity) {
		entity.setApiKey(cryptoService.encryptIfNecessary(entity.getApiKey()));
		entity.setProxyPassword(cryptoService.encryptIfNecessary(entity.getProxyPassword()));
	}

	private ModelConfigDTO toSafeDTO(ModelConfig entity) {
		return ModelConfigConverter.toSafeDTO(entity, cryptoService);
	}

	private long count(List<ModelConfig> configs, ModelType type) {
		return configs.stream().filter(item -> item.getModelType() == type).count();
	}

	private long countActive(List<ModelConfig> configs) {
		return configs.stream().filter(item -> Boolean.TRUE.equals(item.getIsActive())).count();
	}

	private String trimToNull(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		return value.trim();
	}

	private String trimToEmpty(String value) {
		if (!StringUtils.hasText(value)) {
			return "";
		}
		return value.trim();
	}

	private void publishModelConfigChangedAfterCommit(Long modelConfigId) {
		if (modelConfigId == null) {
			return;
		}
		ModelConfigChangedEvent event = new ModelConfigChangedEvent(this, modelConfigId);
		if (!TransactionSynchronizationManager.isActualTransactionActive()
				|| !TransactionSynchronizationManager.isSynchronizationActive()) {
			eventPublisher.publishEvent(event);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				eventPublisher.publishEvent(event);
			}
		});
	}

	private void publishModelConfigChangesAfterCommit(List<Long> modelConfigIds) {
		if (modelConfigIds == null || modelConfigIds.isEmpty()) {
			return;
		}
		modelConfigIds.stream().distinct().forEach(this::publishModelConfigChangedAfterCommit);
	}

	private <E extends Enum<E>> String normalizeEnum(String value, Class<E> type, E defaultValue) {
		if (!StringUtils.hasText(value)) {
			return defaultValue.name();
		}
		return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT)).name();
	}

	private <E extends Enum<E>> String normalizeOptionalEnum(String value, Class<E> type) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT)).name();
	}

}
