/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.agent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.repository.DataAgentSkillMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.service.aimodelconfig.AiModelRegistry;
import com.sn68.agent.dataagent.service.datasource.SkillDatasourceService;
import com.sn68.agent.dataagent.service.vectorstore.AgentVectorStoreService;
import com.sn68.agent.dataagent.skill.SkillVersionStatus;
import com.sn68.agent.dataagent.skill.execution.SkillVersionResourceLoader;
import com.sn68.agent.dataagent.skill.execution.SkillVersionResources;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.ApplicationArguments;

class AgentStartupInitializationTest {

	private DataAgentSkillMapper skillMapper;

	private DataAgentSkillVersionMapper skillVersionMapper;

	private AgentVectorStoreService agentVectorStoreService;

	private SkillDatasourceService skillDatasourceService;

	private SkillVersionResourceLoader resourceLoader;

	private AiModelRegistry modelRegistry;

	private AgentStartupInitialization initialization;

	@BeforeEach
	void setUp() {
		skillMapper = mock(DataAgentSkillMapper.class);
		skillVersionMapper = mock(DataAgentSkillVersionMapper.class);
		agentVectorStoreService = mock(AgentVectorStoreService.class);
		skillDatasourceService = mock(SkillDatasourceService.class);
		resourceLoader = mock(SkillVersionResourceLoader.class);
		modelRegistry = mock(AiModelRegistry.class);
		ExecutorService executorService = mock(ExecutorService.class);
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(executorService).execute(any(Runnable.class));
		when(modelRegistry.withEmbeddingModel(any(), any())).thenAnswer(invocation -> {
			Supplier<?> action = invocation.getArgument(1);
			return action.get();
		});
		initialization = new AgentStartupInitialization(skillMapper, skillVersionMapper, agentVectorStoreService,
				skillDatasourceService, resourceLoader, modelRegistry, executorService);
	}

	@Test
	void runSkipsSkillWithoutTenantInsteadOfInventingOne() {
		DataAgentSkill skill = publishedSkill(null);
		when(skillMapper.selectList(any())).thenReturn(List.of(skill));

		initialization.run(mock(ApplicationArguments.class));

		verify(modelRegistry, never()).embeddingModelForTenant(any());
		verify(modelRegistry, never()).getEmbeddingModel();
		verify(skillDatasourceService, never()).initializeSchemaForPublishedSkill(any(), any(), any());
	}

	@Test
	void runSkipsWhenTenantHasNoActiveEmbedding() {
		DataAgentSkill skill = publishedSkill("tenant-b");
		when(skillMapper.selectList(any())).thenReturn(List.of(skill));
		when(skillVersionMapper.selectById(11L)).thenReturn(publishedVersion());
		when(resourceLoader.load(any())).thenReturn(datasourceResources());
		when(modelRegistry.embeddingModelForTenant("tenant-b")).thenReturn(null);

		initialization.run(mock(ApplicationArguments.class));

		verify(modelRegistry).embeddingModelForTenant("tenant-b");
		verify(modelRegistry, never()).getEmbeddingModel();
		verify(agentVectorStoreService, never()).hasSkillSchemaDocuments(any(), any());
		verify(skillDatasourceService, never()).initializeSchemaForPublishedSkill(any(), any(), any());
	}

	@Test
	void runInitializesSchemaWithThatSkillTenantEmbedding() {
		DataAgentSkill skill = publishedSkill("tenant-b");
		EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
		when(skillMapper.selectList(any())).thenReturn(List.of(skill));
		when(skillVersionMapper.selectById(11L)).thenReturn(publishedVersion());
		when(resourceLoader.load(any())).thenReturn(datasourceResources());
		when(modelRegistry.embeddingModelForTenant("tenant-b")).thenReturn(embeddingModel);
		when(agentVectorStoreService.hasSkillSchemaDocuments("1", "3")).thenReturn(false);
		when(skillDatasourceService.initializeSchemaForPublishedSkill(skill, 3L, List.of("orders"))).thenReturn(true);

		initialization.run(mock(ApplicationArguments.class));

		verify(modelRegistry).embeddingModelForTenant("tenant-b");
		verify(modelRegistry).withEmbeddingModel(eq(embeddingModel), any());
		verify(modelRegistry, never()).getEmbeddingModel();
		verify(skillDatasourceService).initializeSchemaForPublishedSkill(skill, 3L, List.of("orders"));
	}

	private static DataAgentSkill publishedSkill(String tenantId) {
		return DataAgentSkill.builder()
			.id(1L)
			.tenantId(tenantId)
			.skillCode("profit-query")
			.status(SkillVersionStatus.PUBLISHED.name())
			.publishedVersionId(11L)
			.deleted(false)
			.build();
	}

	private static DataAgentSkillVersion publishedVersion() {
		return DataAgentSkillVersion.builder()
			.id(11L)
			.skillId(1L)
			.tenantId("tenant-b")
			.status(SkillVersionStatus.PUBLISHED.name())
			.build();
	}

	private static SkillVersionResources datasourceResources() {
		return new SkillVersionResources(1L, 11L,
				new SkillVersionResources.DatasourceResource(3L,
						List.of(new SkillVersionResources.TableScope("orders", List.of("id"))), true, 100, Map.of(),
						Map.of()),
				List.of(), List.of(), List.of(), Map.of());
	}

}
