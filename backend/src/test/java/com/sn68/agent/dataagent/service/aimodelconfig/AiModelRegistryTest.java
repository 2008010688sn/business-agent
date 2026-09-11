/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.aimodelconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.dataagent.routing.RouteEmbeddingFingerprint;
import com.sn68.agent.dataagent.service.permission.PlatformScopePermissionService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;

class AiModelRegistryTest {

	@Test
	void routeEmbeddingOverrideIsNestedAndAlwaysRestored() {
		DynamicModelFactory modelFactory = mock(DynamicModelFactory.class);
		ModelConfigDataService configService = mock(ModelConfigDataService.class);
		ModelConfigDTO config = ModelConfigDTO.builder().id(1L).modelType(ModelType.EMBEDDING.getCode()).build();
		EmbeddingModel global = mock(EmbeddingModel.class);
		EmbeddingModel route = mock(EmbeddingModel.class);
		EmbeddingModel nested = mock(EmbeddingModel.class);
		when(configService.getActiveRuntimeConfigByType(ModelType.EMBEDDING)).thenReturn(config);
		when(modelFactory.createEmbeddingModel(config)).thenReturn(global);
		AiModelRegistry registry = newRegistry(modelFactory, configService);

		assertSame(global, registry.getEmbeddingModel());
		assertSame(route, registry.withEmbeddingModel(route, () -> {
			assertSame(route, registry.getEmbeddingModel());
			assertSame(nested, registry.withEmbeddingModel(nested, registry::getEmbeddingModel));
			return registry.getEmbeddingModel();
		}));
		assertSame(global, registry.getEmbeddingModel());

		assertThrows(IllegalStateException.class,
				() -> registry.withEmbeddingModel(route, () -> {
					throw new IllegalStateException("failed");
				}));
		assertSame(global, registry.getEmbeddingModel());
	}

	@Test
	void fallbackModelRefusesToEmbedInsteadOfReturningAnEmptyVector() {
		ModelConfigDataService configService = mock(ModelConfigDataService.class);
		when(configService.getActiveRuntimeConfigByType(ModelType.EMBEDDING)).thenReturn(null);
		AiModelRegistry registry = newRegistry(mock(DynamicModelFactory.class), configService);

		EmbeddingModel fallback = registry.getEmbeddingModel();

		// SimpleVectorStore.doAdd 走的正是 embed(Document)，返回空向量会被静默持久化
		assertThrows(CheckedException.class, () -> fallback.embed(new Document("text")));
		assertThrows(CheckedException.class, () -> fallback.embed("text"));
		assertThrows(CheckedException.class, () -> fallback.embed(java.util.List.of("text")));
		assertThrows(CheckedException.class, () -> fallback.embedForResponse(java.util.List.of("text")));
	}

	@Test
	void fallbackModelTellsAConfiguredButBrokenModelApartFromAMissingOne() {
		ModelConfigDataService missingConfigService = mock(ModelConfigDataService.class);
		when(missingConfigService.getActiveRuntimeConfigByType(ModelType.EMBEDDING)).thenReturn(null);
		CheckedException missing = assertThrows(CheckedException.class,
				() -> newRegistry(mock(DynamicModelFactory.class), missingConfigService).getEmbeddingModel()
					.embed("text"));

		DynamicModelFactory brokenFactory = mock(DynamicModelFactory.class);
		ModelConfigDataService brokenConfigService = mock(ModelConfigDataService.class);
		ModelConfigDTO config = ModelConfigDTO.builder().id(1L).modelType(ModelType.EMBEDDING.getCode()).build();
		when(brokenConfigService.getActiveRuntimeConfigByType(ModelType.EMBEDDING)).thenReturn(config);
		when(brokenFactory.createEmbeddingModel(config)).thenThrow(new IllegalStateException("bad base url"));
		CheckedException broken = assertThrows(CheckedException.class,
				() -> newRegistry(brokenFactory, brokenConfigService).getEmbeddingModel().embed("text"));

		assertTrue(missing.getMessage().contains("未配置启用中的 EMBEDDING 模型"), missing.getMessage());
		assertTrue(broken.getMessage().contains("已配置但初始化失败"), broken.getMessage());
	}

	@Test
	void embeddingIdentityIsExposedForRealModelsAndClearedOnRefresh() {
		DynamicModelFactory modelFactory = mock(DynamicModelFactory.class);
		ModelConfigDataService configService = mock(ModelConfigDataService.class);
		ModelConfigDTO config = ModelConfigDTO.builder()
			.id(1L)
			.provider("custom")
			.baseUrl("https://embedding.example.com")
			.modelName("bge-large-zh")
			.modelType(ModelType.EMBEDDING.getCode())
			.build();
		EmbeddingModel model = mock(EmbeddingModel.class);
		when(model.dimensions()).thenReturn(1024);
		when(configService.getActiveRuntimeConfigByType(ModelType.EMBEDDING)).thenReturn(config);
		when(modelFactory.createEmbeddingModel(config)).thenReturn(model);
		AiModelRegistry registry = newRegistry(modelFactory, configService);

		AiModelRegistry.EmbeddingIdentity identity = registry.getEmbeddingIdentity();

		assertNotNull(identity);
		assertTrue(identity.isUsable());
		assertEquals(1024, identity.dimensions());
		assertEquals(new RouteEmbeddingFingerprint().calculate(config), identity.fingerprint());

		registry.refreshEmbedding();
		when(configService.getActiveRuntimeConfigByType(ModelType.EMBEDDING)).thenReturn(null);
		assertNull(registry.getEmbeddingIdentity());
	}

	@Test
	void embeddingModelForTenant_usesThatTenantAndDoesNotInventOne() {
		DynamicModelFactory modelFactory = mock(DynamicModelFactory.class);
		ModelConfigDataService configService = mock(ModelConfigDataService.class);
		ModelConfigDTO config = ModelConfigDTO.builder().id(8L).modelType(ModelType.EMBEDDING.getCode()).build();
		EmbeddingModel model = mock(EmbeddingModel.class);
		when(configService.getActiveRuntimeConfigByType(ModelType.EMBEDDING, "tenant-b")).thenReturn(config);
		when(modelFactory.createEmbeddingModel(config)).thenReturn(model);
		AiModelRegistry registry = newRegistry(modelFactory, configService);

		assertSame(model, registry.embeddingModelForTenant("tenant-b"));
		assertNull(registry.embeddingModelForTenant(null));
		assertNull(registry.embeddingModelForTenant(" "));
		verify(configService).getActiveRuntimeConfigByType(ModelType.EMBEDDING, "tenant-b");
		verify(configService, never()).getActiveRuntimeConfigByType(ModelType.EMBEDDING);
	}

	@Test
	void chatClientForTenantUsesThatTenantAndDoesNotFallBack() {
		DynamicModelFactory modelFactory = mock(DynamicModelFactory.class);
		ModelConfigDataService configService = mock(ModelConfigDataService.class);
		ModelConfigDTO config = ModelConfigDTO.builder().id(3L).modelType(ModelType.CHAT.getCode()).build();
		when(configService.getActiveRuntimeConfigByType(ModelType.CHAT, "tenant-b")).thenReturn(config);
		when(modelFactory.createChatModel(config)).thenReturn(mock(org.springframework.ai.chat.model.ChatModel.class));
		AiModelRegistry registry = newRegistry(modelFactory, configService);

		assertNotNull(registry.chatClientForTenant("tenant-b"));
		assertThrows(CheckedException.class, () -> registry.chatClientForTenant("tenant-a"));
		verify(configService).getActiveRuntimeConfigByType(ModelType.CHAT, "tenant-b");
		verify(configService, never()).getActiveRuntimeConfigByType(ModelType.CHAT);
	}

	private AiModelRegistry newRegistry(DynamicModelFactory modelFactory, ModelConfigDataService configService) {
		PlatformScopePermissionService permissions = mock(PlatformScopePermissionService.class);
		when(permissions.requireCurrentTenantId(org.mockito.ArgumentMatchers.anyString())).thenReturn("tenant-b");
		return new AiModelRegistry(modelFactory, configService, new RouteEmbeddingFingerprint(), permissions);
	}

}
