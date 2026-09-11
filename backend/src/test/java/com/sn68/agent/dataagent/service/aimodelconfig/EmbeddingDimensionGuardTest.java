/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.aimodelconfig;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.framework.commons.exception.CheckedException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.test.util.ReflectionTestUtils;

class EmbeddingDimensionGuardTest {

	private final DynamicModelFactory modelFactory = mock(DynamicModelFactory.class);

	private final EmbeddingDimensionGuard guard = new EmbeddingDimensionGuard(modelFactory);

	@Test
	void rejectsActivationWhenModelDimensionDoesNotMatchTheVectorColumn() {
		configure("pgvector", -1);
		ModelConfigDTO config = embeddingConfig();
		stubEmbeddingModel(config, 1536);

		CheckedException ex = assertThrows(CheckedException.class, () -> guard.verifyActivatable(config));

		assertTrue(ex.getMessage().contains("1536"), ex.getMessage());
		assertTrue(ex.getMessage().contains("1024"), ex.getMessage());
	}

	@Test
	void acceptsActivationWhenModelDimensionMatchesTheVectorColumn() {
		configure("pgvector", -1);
		ModelConfigDTO config = embeddingConfig();
		stubEmbeddingModel(config, 1024);

		guard.verifyActivatable(config);
	}

	@Test
	void explicitPgvectorDimensionsPropertyWinsOverTheDdlConvention() {
		configure("pgvector", 1536);
		ModelConfigDTO config = embeddingConfig();
		stubEmbeddingModel(config, 1536);

		guard.verifyActivatable(config);
	}

	@Test
	void skipsProbeWhenVectorStoreHasNoFixedWidthColumn() {
		configure("simple", -1);
		ModelConfigDTO config = embeddingConfig();

		guard.verifyActivatable(config);

		verify(modelFactory, never()).createEmbeddingModel(config);
	}

	@Test
	void rejectsActivationWhenDimensionCannotBeProbed() {
		configure("pgvector", -1);
		ModelConfigDTO config = embeddingConfig();
		when(modelFactory.createEmbeddingModel(config)).thenThrow(new IllegalStateException("connection refused"));

		CheckedException ex = assertThrows(CheckedException.class, () -> guard.verifyActivatable(config));

		assertTrue(ex.getMessage().contains("无法探测"), ex.getMessage());
	}

	private void configure(String vectorStoreType, int configuredDimensions) {
		ReflectionTestUtils.setField(guard, "vectorStoreType", vectorStoreType);
		ReflectionTestUtils.setField(guard, "configuredDimensions", configuredDimensions);
	}

	private void stubEmbeddingModel(ModelConfigDTO config, int dimensions) {
		EmbeddingModel model = mock(EmbeddingModel.class);
		when(model.dimensions()).thenReturn(dimensions);
		when(modelFactory.createEmbeddingModel(config)).thenReturn(model);
	}

	private ModelConfigDTO embeddingConfig() {
		return ModelConfigDTO.builder()
			.id(1L)
			.provider("custom")
			.baseUrl("https://embedding.example.com")
			.modelName("bge-large-zh")
			.modelType(ModelType.EMBEDDING.getCode())
			.build();
	}

}
