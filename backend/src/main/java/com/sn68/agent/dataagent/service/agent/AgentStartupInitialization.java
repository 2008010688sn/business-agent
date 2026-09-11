/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.agent;

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
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Warms schema vectors for published Skill-version snapshots.
 *
 * <p>The name is retained for Spring wiring compatibility. It must not derive a Skill resource
 * scope from an Agent ID.</p>
 */
@Slf4j
@Service
public class AgentStartupInitialization implements ApplicationRunner {

	private final DataAgentSkillMapper skillMapper;

	private final DataAgentSkillVersionMapper skillVersionMapper;

	private final AgentVectorStoreService agentVectorStoreService;

	private final SkillDatasourceService skillDatasourceService;

	private final SkillVersionResourceLoader resourceLoader;

	private final AiModelRegistry modelRegistry;

	private final ExecutorService executorService;

	public AgentStartupInitialization(DataAgentSkillMapper skillMapper,
			DataAgentSkillVersionMapper skillVersionMapper, AgentVectorStoreService agentVectorStoreService,
			SkillDatasourceService skillDatasourceService, SkillVersionResourceLoader resourceLoader,
			AiModelRegistry modelRegistry, @Qualifier("dbOperationExecutor") ExecutorService executorService) {
		this.skillMapper = skillMapper;
		this.skillVersionMapper = skillVersionMapper;
		this.agentVectorStoreService = agentVectorStoreService;
		this.skillDatasourceService = skillDatasourceService;
		this.resourceLoader = resourceLoader;
		this.modelRegistry = modelRegistry;
		this.executorService = executorService;
	}

	@Override
	public void run(ApplicationArguments args) {
		CompletableFuture.runAsync(this::initializePublishedSkills, executorService)
			.exceptionally(throwable -> {
				log.error("Failed to initialize published Skill schema resources", throwable);
				return null;
			});
	}

	private void initializePublishedSkills() {
		List<DataAgentSkill> publishedSkills = skillMapper.selectList(Wraps.<DataAgentSkill>lbQ()
			.eq(DataAgentSkill::getStatus, SkillVersionStatus.PUBLISHED.name())
			.eq(DataAgentSkill::getDeleted, false));
		if (publishedSkills.isEmpty()) {
			log.info("No published Skills require schema initialization");
			return;
		}
		int initialized = 0;
		int skipped = 0;
		int failed = 0;
		for (DataAgentSkill skill : publishedSkills) {
			try {
				if (initializeSkillDatasource(skill)) {
					initialized++;
				}
				else {
					skipped++;
				}
			}
			catch (Exception ex) {
				failed++;
				log.error("Failed to initialize Skill datasource. skillId={}, skillCode={}", skill.getId(),
						skill.getSkillCode(), ex);
			}
		}
		log.info("Published Skill schema initialization completed. initialized={}, skipped={}, failed={}", initialized,
				skipped, failed);
	}

	private boolean initializeSkillDatasource(DataAgentSkill skill) {
		if (skill.getPublishedVersionId() == null) {
			return false;
		}
		if (!StringUtils.hasText(skill.getTenantId())) {
			log.warn("Skip Skill schema initialization, tenantId is empty. skillId={}, skillCode={}", skill.getId(),
					skill.getSkillCode());
			return false;
		}
		DataAgentSkillVersion version = skillVersionMapper.selectById(skill.getPublishedVersionId());
		if (version == null || !SkillVersionStatus.PUBLISHED.name().equals(version.getStatus())) {
			return false;
		}
		SkillVersionResources resources = resourceLoader.load(version);
		if (!resources.hasDatasourceAccess()) {
			return false;
		}
		EmbeddingModel embeddingModel = modelRegistry.embeddingModelForTenant(skill.getTenantId());
		if (embeddingModel == null) {
			log.warn("Skip Skill schema initialization, tenant has no active EMBEDDING model. skillId={}, skillCode={}, tenantId={}",
					skill.getId(), skill.getSkillCode(), skill.getTenantId());
			return false;
		}
		Long datasourceId = resources.datasourceId();
		String skillId = String.valueOf(resources.skillId());
		return Boolean.TRUE.equals(modelRegistry.withEmbeddingModel(embeddingModel, () -> {
			if (agentVectorStoreService.hasSkillSchemaDocuments(skillId, String.valueOf(datasourceId))) {
				return false;
			}
			List<String> tables = resources.datasource().tables().stream()
				.map(SkillVersionResources.TableScope::table)
				.toList();
			if (tables.isEmpty()) {
				throw new IllegalStateException("Published Skill datasource snapshot has no tables");
			}
			Boolean initialized = skillDatasourceService.initializeSchemaForPublishedSkill(skill, datasourceId, tables);
			if (!Boolean.TRUE.equals(initialized)) {
				throw new IllegalStateException("Schema initialization returned false");
			}
			return true;
		}));
	}
}
