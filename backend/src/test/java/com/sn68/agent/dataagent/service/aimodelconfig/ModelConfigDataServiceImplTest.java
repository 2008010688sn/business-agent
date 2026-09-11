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

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.sn68.agent.dataagent.converter.ModelConfigConverter;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.entity.ModelConfig;
import com.sn68.agent.dataagent.event.ModelConfigChangedEvent;
import com.sn68.agent.dataagent.enums.ModelCapabilityProfile;
import com.sn68.agent.dataagent.enums.ModelEndpointDialect;
import com.sn68.agent.dataagent.enums.ModelPreservedReasoningPolicy;
import com.sn68.agent.dataagent.enums.ModelReasoningLevel;
import com.sn68.agent.dataagent.enums.ModelReasoningMode;
import com.sn68.agent.dataagent.enums.ModelReasoningProtocol;
import com.sn68.agent.dataagent.enums.ModelStructuredOutputMode;
import com.sn68.agent.dataagent.enums.ModelTemperaturePolicy;
import com.sn68.agent.dataagent.enums.ModelTokenLimitMode;
import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.dataagent.repository.ModelConfigMapper;
import com.sn68.agent.dataagent.service.aimodelconfig.options.ModelRequestOptionsResolver;
import com.sn68.agent.dataagent.service.permission.PlatformScopePermissionService;
import com.sn68.agent.dataagent.service.security.SensitiveConfigCryptoService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ModelConfigDataServiceImplTest {

	private final ModelConfigMapper modelConfigMapper = mock(ModelConfigMapper.class);

	private final SensitiveConfigCryptoService cryptoService = mock(SensitiveConfigCryptoService.class);

	private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

	private final PlatformScopePermissionService platformScopePermissionService = mock(
			PlatformScopePermissionService.class);

	private final ModelConfigDataServiceImpl service = new ModelConfigDataServiceImpl(modelConfigMapper, cryptoService,
			new ModelRequestOptionsResolver(), eventPublisher, platformScopePermissionService);

	@BeforeEach
	void setUp() {
		when(cryptoService.shouldKeepExisting(anyString())).thenReturn(false);
		when(cryptoService.encryptIfNecessary(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
		when(platformScopePermissionService.requireCurrentTenantId(anyString())).thenReturn("tenant-1");
		when(platformScopePermissionService.currentTenantId()).thenReturn("tenant-1");
	}

	@Test
	void addConfig_rejectsMaxTokensOutsideSpringAiIntegerRange() {
		ModelConfigDTO config = chatConfig();
		config.setMaxTokens((long) Integer.MAX_VALUE + 1);

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.addConfig(config));

		assertTrue(ex.getMessage().contains("Spring AI"));
		verifyNoInteractions(modelConfigMapper);
	}

	@Test
	void addConfig_rejectsClearlyUnreasonableContextWindow() {
		ModelConfigDTO config = chatConfig();
		config.setContextWindowTokens(1_000_000_001L);

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.addConfig(config));

		assertTrue(ex.getMessage().contains("上下文窗口"));
		verifyNoInteractions(modelConfigMapper);
	}

	@AfterEach
	void clearTransactionSynchronization() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
		TransactionSynchronizationManager.setActualTransactionActive(false);
	}

	@Test
	void modelConfigBuilderPreservesDeclaredDefaults() {
		ModelConfigDTO config = ModelConfigDTO.builder().build();

		assertEquals(0D, config.getTemperature());
		assertEquals(ModelCapabilityProfile.AUTO.name(), config.getCapabilityProfile());
		assertEquals(2000L, config.getMaxTokens());
		assertEquals(32768L, config.getContextWindowTokens());
		assertEquals(true, config.getIsActive());
		assertEquals(false, config.getProxyEnabled());
	}

	@Test
	void addConfigPersistsExplicitCapabilityProfileWithoutInspectingModelName() {
		ModelConfigDTO config = chatConfig();
		config.setProvider("kimi");
		config.setEndpointDialect(ModelEndpointDialect.MOONSHOT_NATIVE.name());
		config.setCapabilityProfile(ModelCapabilityProfile.KIMI_K3_REASONING.name());
		config.setModelName("private-deployment-alias");
		config.setReasoningMode(ModelReasoningMode.ENABLED.name());
		config.setReasoningLevel(ModelReasoningLevel.HIGH.name());

		service.addConfig(config);

		ArgumentCaptor<ModelConfig> captor = ArgumentCaptor.forClass(ModelConfig.class);
		verify(modelConfigMapper).insert(captor.capture());
		assertEquals(ModelCapabilityProfile.KIMI_K3_REASONING.name(), captor.getValue().getCapabilityProfile());
	}

	@Test
	void addConfig_rejectsNonPositiveReasoningBudget() {
		ModelConfigDTO config = chatConfig();
		config.setReasoningBudgetTokens(0L);

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.addConfig(config));

		assertTrue(ex.getMessage().contains("reasoningBudgetTokens"));
		verifyNoInteractions(modelConfigMapper);
	}

	@Test
	void addConfigRejectsInvalidPersistentOptionsBeforeInsert() {
		ModelConfigDTO config = chatConfig();
		config.setEndpointDialect(ModelEndpointDialect.STEPFUN_NATIVE.name());
		config.setReasoningMode(ModelReasoningMode.ENABLED.name());
		config.setReasoningLevel(ModelReasoningLevel.MEDIUM.name());
		config.setReasoningBudgetTokens(512L);

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> service.addConfig(config));

		assertTrue(error.getMessage().contains("reasoningBudgetTokens"));
		verify(modelConfigMapper, never()).insert(any(ModelConfig.class));
	}

	@Test
	void updateConfigInDbRejectsInvalidPersistentOptionsBeforeUpdate() {
		ModelConfigDTO config = chatConfig();
		config.setId(14L);
		config.setEndpointDialect(ModelEndpointDialect.DEEPSEEK_NATIVE.name());
		config.setTemperaturePolicy(ModelTemperaturePolicy.SEND.name());
		when(modelConfigMapper.findById(14L)).thenReturn(modelConfig(14L, ModelType.CHAT));

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> service.updateConfigInDb(config));

		assertTrue(error.getMessage().contains("temperaturePolicy"));
		verify(modelConfigMapper, never()).updateById(any(ModelConfig.class));
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@MethodSource("knownProvidersWithForeignNativeDialect")
	void addConfigRejectsKnownProviderUsingForeignNativeDialect(String provider,
			ModelEndpointDialect endpointDialect) {
		ModelConfigDTO config = chatConfig();
		config.setProvider(provider);
		config.setEndpointDialect(endpointDialect.name());

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> service.addConfig(config));

		assertTrue(error.getMessage().contains("provider"));
		assertTrue(error.getMessage().contains("endpointDialect"));
		verify(modelConfigMapper, never()).insert(any(ModelConfig.class));
	}

	@Test
	void addConfigAllowsKnownProviderUsingMatchingNativeDialect() {
		ModelConfigDTO config = chatConfig();
		config.setProvider("qwen");
		config.setEndpointDialect(ModelEndpointDialect.DASHSCOPE_NATIVE.name());

		service.addConfig(config);

		verify(modelConfigMapper).insert(any(ModelConfig.class));
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = ModelEndpointDialect.class, names = { "OPENAI_COMPATIBLE", "CUSTOM" })
	void addConfigAllowsKnownProviderUsingCompatibleDialect(ModelEndpointDialect endpointDialect) {
		ModelConfigDTO config = chatConfig();
		config.setProvider("deepseek");
		config.setEndpointDialect(endpointDialect.name());

		service.addConfig(config);

		verify(modelConfigMapper).insert(any(ModelConfig.class));
	}

	@Test
	void addConfigAllowsUnknownProviderUsingNativeDialect() {
		ModelConfigDTO config = chatConfig();
		config.setProvider("private-gateway");
		config.setEndpointDialect(ModelEndpointDialect.DEEPSEEK_NATIVE.name());

		service.addConfig(config);

		verify(modelConfigMapper).insert(any(ModelConfig.class));
	}

	@Test
	void addConfigRejectsUnsupportedKeepPolicyForAudioTranscription() {
		ModelConfigDTO config = chatConfig();
		config.setModelType(ModelType.AUDIO_TRANSCRIPTION.getCode());
		config.setProvider("qwen");
		config.setEndpointDialect(ModelEndpointDialect.DEEPSEEK_NATIVE.name());
		config.setReasoningProtocol(ModelReasoningProtocol.ENABLE_THINKING.name());
		config.setTemperaturePolicy(ModelTemperaturePolicy.SEND.name());
		config.setPreservedReasoningPolicy(ModelPreservedReasoningPolicy.KEEP.name());

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.addConfig(config));

		assertTrue(error.getMessage().contains("preservedReasoningPolicy=KEEP"));
		verify(modelConfigMapper, never()).insert(any(ModelConfig.class));
	}

	@Test
	void updateConfigRejectsUnsupportedKeepPolicyForNonChatModel() {
		ModelConfigDTO config = chatConfig();
		config.setId(15L);
		config.setModelType(ModelType.EMBEDDING.getCode());
		config.setPreservedReasoningPolicy(ModelPreservedReasoningPolicy.KEEP.name());
		when(modelConfigMapper.findById(15L)).thenReturn(modelConfig(15L, ModelType.EMBEDDING));

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> service.updateConfigInDb(config));

		assertTrue(error.getMessage().contains("preservedReasoningPolicy=KEEP"));
		verify(modelConfigMapper, never()).updateById(any(ModelConfig.class));
	}

	@Test
	void modelConfigOptionFieldsRoundTripBetweenDtoAndEntity() {
		ModelConfigDTO source = chatConfig();
		source.setEndpointDialect(ModelEndpointDialect.DEEPSEEK_NATIVE.name());
		source.setReasoningProtocol(ModelReasoningProtocol.THINKING_OBJECT.name());
		source.setReasoningMode(ModelReasoningMode.ENABLED.name());
		source.setReasoningLevel(ModelReasoningLevel.NONE.name());
		source.setReasoningBudgetTokens(512L);
		source.setTokenLimitMode(ModelTokenLimitMode.MAX_TOKENS.name());
		source.setTemperaturePolicy(ModelTemperaturePolicy.OMIT.name());
		source.setStructuredOutputMode(ModelStructuredOutputMode.JSON_OBJECT.name());
		source.setPreservedReasoningPolicy(ModelPreservedReasoningPolicy.DROP.name());

		ModelConfigDTO roundTrip = ModelConfigConverter.toDTO(ModelConfigConverter.toEntity(source));

		assertEquals(source.getEndpointDialect(), roundTrip.getEndpointDialect());
		assertEquals(source.getReasoningProtocol(), roundTrip.getReasoningProtocol());
		assertEquals(source.getReasoningMode(), roundTrip.getReasoningMode());
		assertEquals(source.getReasoningLevel(), roundTrip.getReasoningLevel());
		assertEquals(source.getReasoningBudgetTokens(), roundTrip.getReasoningBudgetTokens());
		assertEquals(source.getTokenLimitMode(), roundTrip.getTokenLimitMode());
		assertEquals(source.getTemperaturePolicy(), roundTrip.getTemperaturePolicy());
		assertEquals(source.getStructuredOutputMode(), roundTrip.getStructuredOutputMode());
		assertEquals(source.getPreservedReasoningPolicy(), roundTrip.getPreservedReasoningPolicy());
	}

	@Test
	void updateConfigInDb_updatesModelTypeFromRequestBody() {
		ModelConfigDTO config = chatConfig();
		config.setId(10L);
		config.setModelType("AUDIO_TRANSCRIPTION");
		ModelConfig existing = modelConfig(10L, ModelType.CHAT);
		when(modelConfigMapper.findById(10L)).thenReturn(existing);

		ModelConfig updated = service.updateConfigInDb(config);

		assertEquals(ModelType.AUDIO_TRANSCRIPTION, updated.getModelType());
		assertEquals("AUDIO_TRANSCRIPTION", config.getModelType());
		ArgumentCaptor<ModelConfig> captor = ArgumentCaptor.forClass(ModelConfig.class);
		verify(modelConfigMapper).updateById(captor.capture());
		assertEquals(ModelType.AUDIO_TRANSCRIPTION, captor.getValue().getModelType());
		verify(modelConfigMapper, never()).deactivateOthers(anyString(), anyLong(), anyString());
	}

	@Test
	void updateConfigInDb_normalizesFormattedModelType() {
		ModelConfigDTO config = chatConfig();
		config.setId(11L);
		config.setModelType(" chat ");
		ModelConfig existing = modelConfig(11L, ModelType.CHAT);
		when(modelConfigMapper.findById(11L)).thenReturn(existing);

		ModelConfig updated = service.updateConfigInDb(config);

		assertEquals(ModelType.CHAT, updated.getModelType());
		assertEquals("CHAT", config.getModelType());
	}

	@Test
	void updateConfigInDbPublishesModelChangeAfterTransactionCommit() {
		ModelConfigDTO config = chatConfig();
		config.setId(16L);
		when(modelConfigMapper.findById(16L)).thenReturn(modelConfig(16L, ModelType.CHAT));
		TransactionSynchronizationManager.setActualTransactionActive(true);
		TransactionSynchronizationManager.initSynchronization();

		service.updateConfigInDb(config);

		verifyNoInteractions(eventPublisher);
		for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
			synchronization.afterCommit();
		}
		ArgumentCaptor<ModelConfigChangedEvent> captor = ArgumentCaptor.forClass(ModelConfigChangedEvent.class);
		verify(eventPublisher).publishEvent(captor.capture());
		assertEquals(16L, captor.getValue().getModelConfigId());
	}

	@Test
	void updateConfigInDb_deactivatesOtherConfigsWhenActiveTypeChanges() {
		ModelConfigDTO config = chatConfig();
		config.setId(12L);
		config.setModelType("EMBEDDING");
		ModelConfig existing = modelConfig(12L, ModelType.CHAT);
		existing.setIsActive(true);
		when(modelConfigMapper.findById(12L)).thenReturn(existing);

		ModelConfig updated = service.updateConfigInDb(config);

		assertEquals(ModelType.EMBEDDING, updated.getModelType());
		verify(modelConfigMapper).deactivateOthers("EMBEDDING", 12L, "tenant-1");
	}

	@Test
	void switchActiveStatusUsesCurrentTenant() {
		when(modelConfigMapper.findById(12L)).thenReturn(modelConfig(12L, ModelType.CHAT));

		service.switchActiveStatus(12L, ModelType.CHAT);

		verify(modelConfigMapper).deactivateOthers("CHAT", 12L, "tenant-1");
		verify(modelConfigMapper).findActiveIdsByTypeExcluding("CHAT", 12L, "tenant-1");
	}

	@Test
	void updateConfigInDbPublishesChangesForConfigsItDeactivatesAfterCommit() {
		ModelConfigDTO config = chatConfig();
		config.setId(12L);
		ModelConfig existing = modelConfig(12L, ModelType.CHAT);
		existing.setIsActive(true);
		when(modelConfigMapper.findById(12L)).thenReturn(existing);
		when(modelConfigMapper.findActiveIdsByTypeExcluding("CHAT", 12L, "tenant-1")).thenReturn(List.of(11L));
		TransactionSynchronizationManager.setActualTransactionActive(true);
		TransactionSynchronizationManager.initSynchronization();

		service.updateConfigInDb(config);

		for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
			synchronization.afterCommit();
		}
		ArgumentCaptor<ModelConfigChangedEvent> captor = ArgumentCaptor.forClass(ModelConfigChangedEvent.class);
		verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
		assertEquals(List.of(11L, 12L), captor.getAllValues().stream()
			.map(ModelConfigChangedEvent::getModelConfigId)
			.toList());
	}

	@Test
	void updateConfigInDb_clearsBlankCompletionsPath() {
		ModelConfigDTO config = chatConfig();
		config.setId(13L);
		config.setCompletionsPath(" ");
		ModelConfig existing = modelConfig(13L, ModelType.CHAT);
		existing.setCompletionsPath("/v1");
		when(modelConfigMapper.findById(13L)).thenReturn(existing);

		ModelConfig updated = service.updateConfigInDb(config);

		assertNull(config.getCompletionsPath());
		assertNull(updated.getCompletionsPath());
		ArgumentCaptor<ModelConfig> captor = ArgumentCaptor.forClass(ModelConfig.class);
		verify(modelConfigMapper).updateById(captor.capture());
		assertNull(captor.getValue().getCompletionsPath());
	}

	@Test
	void modelConfig_completionsPathAlwaysParticipatesInUpdates() throws NoSuchFieldException {
		TableField tableField = ModelConfig.class.getDeclaredField("completionsPath").getAnnotation(TableField.class);

		assertNotNull(tableField);
		assertEquals(FieldStrategy.ALWAYS, tableField.updateStrategy());
	}

	@Test
	void deleteConfig_logicallyDeletesInactiveConfig() {
		when(modelConfigMapper.findById(20L)).thenReturn(modelConfig(20L, ModelType.CHAT));
		when(modelConfigMapper.deleteById(20L)).thenReturn(1);

		service.deleteConfig(20L);

		verify(modelConfigMapper).deleteById(20L);
	}

	@Test
	void getActiveRuntimeConfigByType_withoutTenantReturnsNull() {
		when(platformScopePermissionService.currentTenantId()).thenReturn(null);
		when(modelConfigMapper.selectActiveByType("CHAT", "tenant-1"))
			.thenReturn(modelConfig(21L, ModelType.CHAT));

		assertNull(service.getActiveRuntimeConfigByType(ModelType.CHAT));
	}

	@Test
	void getActiveRuntimeConfigByType_usesExplicitTenant() {
		ModelConfig existing = modelConfig(22L, ModelType.EMBEDDING);
		existing.setIsActive(true);
		when(modelConfigMapper.selectActiveByType("EMBEDDING", "tenant-b")).thenReturn(existing);
		when(cryptoService.decryptRuntimeSecret(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

		ModelConfigDTO dto = service.getActiveRuntimeConfigByType(ModelType.EMBEDDING, "tenant-b");

		assertNotNull(dto);
		assertEquals(22L, dto.getId());
		verify(modelConfigMapper).selectActiveByType("EMBEDDING", "tenant-b");
	}

	@Test
	void getRuntimeConfigById_rejectsOtherTenant() {
		when(modelConfigMapper.findById(22L)).thenReturn(modelConfig(22L, ModelType.EMBEDDING));

		RuntimeException ex = assertThrows(RuntimeException.class,
				() -> service.getRuntimeConfigById(22L, ModelType.EMBEDDING, "tenant-b"));

		assertTrue(ex.getMessage().contains("不存在"));
	}

	@Test
	void getRuntimeConfigById_usesExplicitTenant() {
		when(modelConfigMapper.findById(22L)).thenReturn(modelConfig(22L, ModelType.EMBEDDING));
		when(cryptoService.decryptRuntimeSecret(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

		ModelConfigDTO dto = service.getRuntimeConfigById(22L, ModelType.EMBEDDING, "tenant-1");

		assertNotNull(dto);
		assertEquals(22L, dto.getId());
	}

	@Test
	void deleteConfig_rejectsActiveConfig() {
		ModelConfig active = modelConfig(21L, ModelType.CHAT);
		active.setIsActive(true);
		when(modelConfigMapper.findById(21L)).thenReturn(active);

		RuntimeException ex = assertThrows(RuntimeException.class, () -> service.deleteConfig(21L));

		assertTrue(ex.getMessage().contains("正在使用"));
		verify(modelConfigMapper, never()).deleteById(21L);
	}

	private ModelConfigDTO chatConfig() {
		return ModelConfigDTO.builder()
			.provider("custom")
			.apiKey("sk-test")
			.baseUrl("https://api.example.com")
			.modelName("test-chat")
			.modelType("CHAT")
			.temperature(0.0)
			.maxTokens(2000L)
			.contextWindowTokens(32768L)
			.isActive(false)
			.proxyEnabled(false)
			.build();
	}

	private ModelConfig modelConfig(Long id, ModelType modelType) {
		return ModelConfig.builder()
			.id(id)
			.tenantId("tenant-1")
			.provider("old-provider")
			.baseUrl("https://old.example.com")
			.apiKey("old-secret")
			.modelName("old-model")
			.modelType(modelType)
			.isActive(false)
			.build();
	}

	private static Stream<Arguments> knownProvidersWithForeignNativeDialect() {
		return Stream.of(Arguments.of("qwen", ModelEndpointDialect.DEEPSEEK_NATIVE),
				Arguments.of("dashscope", ModelEndpointDialect.DEEPSEEK_NATIVE),
				Arguments.of("step", ModelEndpointDialect.DEEPSEEK_NATIVE),
				Arguments.of("stepfun", ModelEndpointDialect.DEEPSEEK_NATIVE),
				Arguments.of("deepseek", ModelEndpointDialect.MOONSHOT_NATIVE),
				Arguments.of("glm", ModelEndpointDialect.DEEPSEEK_NATIVE),
				Arguments.of("zhipu", ModelEndpointDialect.DEEPSEEK_NATIVE),
				Arguments.of("kimi", ModelEndpointDialect.DEEPSEEK_NATIVE),
				Arguments.of("moonshot", ModelEndpointDialect.DEEPSEEK_NATIVE));
	}

}
