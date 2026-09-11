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
package com.sn68.agent.dataagent.converter;

import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.entity.ModelConfig;
import com.sn68.agent.dataagent.enums.ModelCapabilityProfile;
import com.sn68.agent.dataagent.enums.ModelEndpointDialect;
import com.sn68.agent.dataagent.enums.ModelPreservedReasoningPolicy;
import com.sn68.agent.dataagent.enums.ModelReasoningMode;
import com.sn68.agent.dataagent.enums.ModelReasoningProtocol;
import com.sn68.agent.dataagent.enums.ModelStructuredOutputMode;
import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.dataagent.service.security.SensitiveConfigCryptoService;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.time.Instant;

/**
 * 模型配置 Entity 与 DTO 转换器：区分「安全视图」（脱敏，给前端）与「运行时视图」（解密，给模型调用）。
 */
public class ModelConfigConverter {

	/**
	 * Entity -> DTO 用于把数据库数据转给前端看
	 */
	public static ModelConfigDTO toDTO(ModelConfig entity) {
		return toSafeDTO(entity, null);
	}

	/**
	 * 安全视图：apiKey/proxyPassword 一律置 null，仅回传"是否已配置"布尔标记，防止密钥外泄。
	 */
	public static ModelConfigDTO toSafeDTO(ModelConfig entity, SensitiveConfigCryptoService cryptoService) {
		if (entity == null) {
			return null;
		}
		return baseBuilder(entity)
			.apiKey(null)
			.apiKeyConfigured(hasConfiguredSecret(entity.getApiKey(), cryptoService))
			.proxyPassword(null)
			.proxyPasswordConfigured(hasConfiguredSecret(entity.getProxyPassword(), cryptoService))
			.build();
	}

	/**
	 * 运行时视图：解密 apiKey/proxyPassword 明文供模型调用链使用，严禁回传前端。
	 */
	public static ModelConfigDTO toRuntimeDTO(ModelConfig entity, SensitiveConfigCryptoService cryptoService) {
		if (entity == null) {
			return null;
		}
		String apiKey = entity.getApiKey();
		String proxyPassword = entity.getProxyPassword();
		if (cryptoService != null) {
			apiKey = cryptoService.decryptRuntimeSecret(apiKey);
			proxyPassword = cryptoService.decryptRuntimeSecret(proxyPassword);
		}
		return baseBuilder(entity)
			.apiKey(apiKey)
			.apiKeyConfigured(hasConfiguredSecret(entity.getApiKey(), cryptoService))
			.proxyPassword(proxyPassword)
			.proxyPasswordConfigured(hasConfiguredSecret(entity.getProxyPassword(), cryptoService))
			.build();
	}

	/**
	 * 公共字段装配（不含敏感字段）；方言/画像/协议等空白时回退 AUTO/OPENAI_COMPATIBLE 默认值。
	 */
	private static ModelConfigDTO.ModelConfigDTOBuilder baseBuilder(ModelConfig entity) {
		return ModelConfigDTO.builder()
			.id(entity.getId())
			.provider(entity.getProvider())
			.baseUrl(entity.getBaseUrl())
			.modelName(entity.getModelName())
			.endpointDialect(defaultIfBlank(entity.getEndpointDialect(), ModelEndpointDialect.OPENAI_COMPATIBLE.name()))
			.capabilityProfile(defaultIfBlank(entity.getCapabilityProfile(), ModelCapabilityProfile.AUTO.name()))
			.reasoningProtocol(defaultIfBlank(entity.getReasoningProtocol(), ModelReasoningProtocol.AUTO.name()))
			.reasoningMode(defaultIfBlank(entity.getReasoningMode(), ModelReasoningMode.AUTO.name()))
			.reasoningLevel(entity.getReasoningLevel())
			.reasoningBudgetTokens(entity.getReasoningBudgetTokens())
			.tokenLimitMode(entity.getTokenLimitMode())
			.temperaturePolicy(entity.getTemperaturePolicy())
			.structuredOutputMode(defaultIfBlank(entity.getStructuredOutputMode(), ModelStructuredOutputMode.AUTO.name()))
			.preservedReasoningPolicy(defaultIfBlank(entity.getPreservedReasoningPolicy(),
				ModelPreservedReasoningPolicy.DROP.name()))
			.temperature(entity.getTemperature())
			.maxTokens(entity.getMaxTokens())
			.contextWindowTokens(entity.getContextWindowTokens())
			.isActive(entity.getIsActive())
			.modelType(entity.getModelType().getCode())
			.completionsPath(entity.getCompletionsPath())
			.embeddingsPath(entity.getEmbeddingsPath())
			.transcriptionsPath(entity.getTranscriptionsPath())
			.supportVision(entity.getSupportVision())
			.proxyEnabled(entity.getProxyEnabled())
			.proxyHost(entity.getProxyHost())
			.proxyPort(entity.getProxyPort())
			.proxyUsername(entity.getProxyUsername())
			.lastModifyTime(entity.getLastModifyTime());
	}

	/**
	 * 判断密钥是否已配置：有加密服务时以其判定为准，否则以非空且非掩码（****）近似判断。
	 */
	private static boolean hasConfiguredSecret(String value, SensitiveConfigCryptoService cryptoService) {
		if (cryptoService != null) {
			return cryptoService.hasConfiguredSecret(value);
		}
		return StringUtils.hasText(value) && !value.contains("****");
	}

	/**
	 * 空白串回退默认值。
	 */
	private static String defaultIfBlank(String value, String defaultValue) {
		return StringUtils.hasText(value) ? value : defaultValue;
	}

	/**
	 * DTO -> Entity 用于新增配置
	 */
	public static ModelConfig toEntity(ModelConfigDTO dto) {
		Assert.notNull(dto, "ModelConfigDTO cannot be null.");
		ModelConfig entity = new ModelConfig();
		// 新增时 ID 由数据库生成，所以这里通常不设置 ID，或者仅当 dto.id 有值时设置
		entity.setId(dto.getId());
		entity.setProvider(dto.getProvider());
		entity.setBaseUrl(dto.getBaseUrl());
		// 新增时，DTO 里的 Key 肯定是明文，直接存
		entity.setApiKey(dto.getApiKey());
		entity.setModelName(dto.getModelName());
		entity.setEndpointDialect(defaultIfBlank(dto.getEndpointDialect(), ModelEndpointDialect.OPENAI_COMPATIBLE.name()));
		entity.setCapabilityProfile(defaultIfBlank(dto.getCapabilityProfile(), ModelCapabilityProfile.AUTO.name()));
		entity.setReasoningProtocol(defaultIfBlank(dto.getReasoningProtocol(), ModelReasoningProtocol.AUTO.name()));
		entity.setReasoningMode(defaultIfBlank(dto.getReasoningMode(), ModelReasoningMode.AUTO.name()));
		entity.setReasoningLevel(dto.getReasoningLevel());
		entity.setReasoningBudgetTokens(dto.getReasoningBudgetTokens());
		entity.setTokenLimitMode(dto.getTokenLimitMode());
		entity.setTemperaturePolicy(dto.getTemperaturePolicy());
		entity.setStructuredOutputMode(defaultIfBlank(dto.getStructuredOutputMode(), ModelStructuredOutputMode.AUTO.name()));
		entity.setPreservedReasoningPolicy(defaultIfBlank(dto.getPreservedReasoningPolicy(),
				ModelPreservedReasoningPolicy.DROP.name()));
		entity.setTemperature(dto.getTemperature());
		entity.setMaxTokens(dto.getMaxTokens());
		entity.setContextWindowTokens(dto.getContextWindowTokens());
		entity.setModelType(ModelType.fromCode(dto.getModelType()));
		entity.setCompletionsPath(dto.getCompletionsPath());
		entity.setEmbeddingsPath(dto.getEmbeddingsPath());
		entity.setTranscriptionsPath(dto.getTranscriptionsPath());
		entity.setSupportVision(Boolean.TRUE.equals(dto.getSupportVision()));
		entity.setProxyEnabled(dto.getProxyEnabled());
		entity.setProxyHost(dto.getProxyHost());
		entity.setProxyPort(dto.getProxyPort());
		entity.setProxyUsername(dto.getProxyUsername());
		entity.setProxyPassword(dto.getProxyPassword());
		// 默认值处理
		entity.setIsActive(false);
		entity.setDeleted(false);
		entity.setCreateTime(Instant.now());
		entity.setLastModifyTime(Instant.now());

		return entity;
	}

}
