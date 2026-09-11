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
package com.sn68.agent.dataagent.service.hybrid.factory;

import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.service.hybrid.fusion.FusionStrategy;
import com.sn68.agent.dataagent.service.hybrid.retrieval.HybridRetrievalStrategy;
import com.sn68.agent.dataagent.service.hybrid.retrieval.impl.DefaultHybridRetrievalStrategy;
import com.sn68.agent.dataagent.service.hybrid.retrieval.impl.ElasticsearchHybridRetrievalStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;

/**
 * 混合检索策略工厂类 根据配置条件创建并注册相应的 HybridRetrievalStrategy 实现类
 */
@Slf4j
@Component
public class HybridRetrievalStrategyFactory implements FactoryBean<HybridRetrievalStrategy> {

	// spring ai 的官方属性；缺省值与 local.properties 保持一致，避免属性缺失时按单机内存库选策略（PRD F-1）
	@Value("${spring.ai.vectorstore.type:pgvector}")
	private String vectorStoreType;

	// spring ai Elasticsearch VectorStore的官方属性
	@Value("${spring.ai.vectorstore.elasticsearch.index-name:spring-ai-document-index}")
	private String elasticsearchIndexName;

	private final ExecutorService executorService;

	private final VectorStore vectorStore;

	private final FusionStrategy fusionStrategy;

	private final DataAgentProperties dataAgentProperties;

	public HybridRetrievalStrategyFactory(
			@Qualifier("dbOperationExecutor") ExecutorService executorService, @Lazy VectorStore vectorStore,
			FusionStrategy fusionStrategy, DataAgentProperties dataAgentProperties) {
		this.executorService = executorService;
		this.vectorStore = vectorStore;
		this.fusionStrategy = fusionStrategy;
		this.dataAgentProperties = dataAgentProperties;
	}

	@Override
	public HybridRetrievalStrategy getObject() throws Exception {

		if (!dataAgentProperties.getVectorStore().isEnableHybridSearch()) {
			log.warn("Hybrid search is disabled. Returning null HybridRetrievalStrategy.");
			return null;
		}
		if ("elasticsearch".equalsIgnoreCase(vectorStoreType)) {
			log.info("Creating ElasticsearchHybridRetrievalStrategy with index: {}", elasticsearchIndexName);
			ElasticsearchHybridRetrievalStrategy strategy = new ElasticsearchHybridRetrievalStrategy(executorService,
					vectorStore, fusionStrategy);
			// 设置索引名称
			strategy.setIndexName(elasticsearchIndexName);
			// 从DataAgentProperties获取最小分数
			strategy.setMinScore(dataAgentProperties.getVectorStore().getElasticsearchMinScore());
			return strategy;
		}
		else {
			log.warn(
					"Creating DefaultHybridRetrievalStrategy (default) without keyword-search ability,maybe you should implement interface -> HybridRetrievalStrategy ");
			return new DefaultHybridRetrievalStrategy(executorService, vectorStore, fusionStrategy);
		}
	}

	@Override
	public Class<?> getObjectType() {
		return HybridRetrievalStrategy.class;
	}

	@Override
	public boolean isSingleton() {
		return true;
	}

}
