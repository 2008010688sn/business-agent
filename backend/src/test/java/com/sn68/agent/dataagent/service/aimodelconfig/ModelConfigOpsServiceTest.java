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

import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.entity.ModelConfig;
import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.framework.commons.exception.CheckedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class ModelConfigOpsServiceTest {

	private final ModelConfigDataService modelConfigDataService = mock(ModelConfigDataService.class);

	private final DynamicModelFactory modelFactory = mock(DynamicModelFactory.class);

	private final AiModelRegistry aiModelRegistry = mock(AiModelRegistry.class);

	private final EmbeddingDimensionGuard embeddingDimensionGuard = new EmbeddingDimensionGuard(modelFactory);

	private final ModelConfigOpsService service = new ModelConfigOpsService(modelConfigDataService, modelFactory,
			aiModelRegistry, embeddingDimensionGuard);

	@AfterEach
	void clearTransactionContext() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
		TransactionSynchronizationManager.setActualTransactionActive(false);
	}

	@Test
	void activateConfigRefreshesTheModelCacheOnlyAfterCommit() {
		when(modelConfigDataService.findById(9L)).thenReturn(config(9L, ModelType.CHAT));
		beginTransaction();

		service.activateConfig(9L);

		// 提交前刷新会让并发请求把旧 active 行重新缓存，且提交后不再清一次
		verify(aiModelRegistry, never()).refreshChat();
		TransactionSynchronizationUtils.triggerAfterCommit();
		InOrder inOrder = inOrder(modelConfigDataService, aiModelRegistry);
		inOrder.verify(modelConfigDataService).switchActiveStatus(9L, ModelType.CHAT);
		inOrder.verify(aiModelRegistry).refreshChat();
	}

	@Test
	void updateAndRefreshRefreshesTheModelCacheOnlyAfterCommit() {
		ModelConfigDTO dto = embeddingDto();
		ModelConfig entity = config(11L, ModelType.EMBEDDING);
		entity.setIsActive(true);
		when(modelConfigDataService.updateConfigInDb(dto)).thenReturn(entity);
		when(modelConfigDataService.getRuntimeConfigById(11L, ModelType.EMBEDDING)).thenReturn(dto);
		stubEmbeddingModel(dto, 1024);
		beginTransaction();

		service.updateAndRefresh(dto);

		verify(aiModelRegistry, never()).refreshEmbedding();
		TransactionSynchronizationUtils.triggerAfterCommit();
		verify(aiModelRegistry).refreshEmbedding();
	}

	@Test
	void activateConfigRejectsAnEmbeddingModelWhoseDimensionDoesNotMatchTheVectorStore() {
		ModelConfigDTO dto = embeddingDto();
		when(modelConfigDataService.findById(7L)).thenReturn(config(7L, ModelType.EMBEDDING));
		when(modelConfigDataService.getRuntimeConfigById(7L, ModelType.EMBEDDING)).thenReturn(dto);
		stubEmbeddingModel(dto, 1536);
		beginTransaction();

		CheckedException ex = assertThrows(CheckedException.class, () -> service.activateConfig(7L));

		assertTrue(ex.getMessage().contains("已拒绝启用"), ex.getMessage());
		// 探测排在写库之后，才能复用 switchActiveStatus 里的平台管理员校验；异常向外抛出后事务整体回滚
		InOrder inOrder = inOrder(modelConfigDataService, modelFactory);
		inOrder.verify(modelConfigDataService).switchActiveStatus(7L, ModelType.EMBEDDING);
		inOrder.verify(modelFactory).createEmbeddingModel(dto);
		// 缓存刷新既没同步执行，也没登记到 afterCommit
		TransactionSynchronizationUtils.triggerAfterCommit();
		verify(aiModelRegistry, never()).refreshEmbedding();
	}

	@Test
	void testConnection_translatesModelNotFoundAndDoesNotLogApiKey(CapturedOutput output) {
		ModelConfigDTO config = chatConfig();
		when(modelFactory.createChatModel(config))
			.thenThrow(new RuntimeException(
					"503 - {\"error\":{\"code\":\"model_not_found\",\"message\":\"No available channel for model Agnes-2.0-Flash under group default\"}}"));

		RuntimeException ex = assertThrows(RuntimeException.class, () -> service.testConnection(config));

		assertEquals("模型不可用：当前供应商渠道未配置该模型或模型名称不匹配，请检查模型名称和供应商后台渠道。", ex.getMessage());
		assertFalse(output.toString().contains("sk-secret"));
	}

	private ModelConfigDTO chatConfig() {
		return ModelConfigDTO.builder()
			.provider("custom")
			.apiKey("sk-secret")
			.baseUrl("https://apihub.agnes-ai.com")
			.modelName("Agnes-2.0-Flash")
			.modelType("CHAT")
			.completionsPath("")
			.proxyEnabled(false)
			.build();
	}

	private ModelConfigDTO embeddingDto() {
		return ModelConfigDTO.builder()
			.id(11L)
			.provider("custom")
			.baseUrl("https://embedding.example.com")
			.modelName("bge-large-zh")
			.modelType(ModelType.EMBEDDING.getCode())
			.build();
	}

	private ModelConfig config(Long id, ModelType modelType) {
		ModelConfig entity = new ModelConfig();
		entity.setId(id);
		entity.setModelType(modelType);
		return entity;
	}

	private void stubEmbeddingModel(ModelConfigDTO config, int dimensions) {
		EmbeddingModel model = mock(EmbeddingModel.class);
		when(model.dimensions()).thenReturn(dimensions);
		when(modelFactory.createEmbeddingModel(config)).thenReturn(model);
	}

	private void beginTransaction() {
		ReflectionTestUtils.setField(embeddingDimensionGuard, "vectorStoreType", "pgvector");
		ReflectionTestUtils.setField(embeddingDimensionGuard, "configuredDimensions", -1);
		TransactionSynchronizationManager.setActualTransactionActive(true);
		TransactionSynchronizationManager.initSynchronization();
	}

}
