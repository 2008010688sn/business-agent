/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.service.hybrid.factory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.service.hybrid.fusion.FusionStrategy;
import com.sn68.agent.dataagent.service.hybrid.retrieval.HybridRetrievalStrategy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

class HybridRetrievalStrategyFactoryLazyTest {

	private static final AtomicInteger VECTOR_STORE_CREATIONS = new AtomicInteger();

	@BeforeEach
	void resetCreationCounter() {
		VECTOR_STORE_CREATIONS.set(0);
	}

	@Test
	void typeResolutionDoesNotCreateVectorStore() {
		ExecutorService executor = mock(ExecutorService.class);

		new ApplicationContextRunner()
			.withBean("dbOperationExecutor", ExecutorService.class, () -> executor)
			.withBean(FusionStrategy.class, () -> mock(FusionStrategy.class))
			.withBean(DataAgentProperties.class, DataAgentProperties::new)
			.withUserConfiguration(LazyVectorStoreConfiguration.class)
			.withBean(HybridRetrievalStrategyFactory.class)
			.run(context -> {
				assertNull(context.getStartupFailure(), () -> String.valueOf(context.getStartupFailure()));
				HybridRetrievalStrategyFactory factory = context
					.getBean("&hybridRetrievalStrategyFactory", HybridRetrievalStrategyFactory.class);
				assertEquals(HybridRetrievalStrategy.class, factory.getObjectType());
				assertEquals(0, VECTOR_STORE_CREATIONS.get());
			});
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class LazyVectorStoreConfiguration {

		@Bean
		@Lazy
		VectorStore vectorStore() {
			VECTOR_STORE_CREATIONS.incrementAndGet();
			return mock(VectorStore.class);
		}

	}

}
