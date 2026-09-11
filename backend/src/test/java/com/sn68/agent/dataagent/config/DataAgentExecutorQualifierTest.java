/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeProgressService;
import com.sn68.agent.dataagent.capability.CapabilityGateway;
import com.sn68.agent.dataagent.connector.accessor.AccessorFactory;
import com.sn68.agent.dataagent.context.DataAgentAsyncContextBridge;
import com.sn68.agent.dataagent.flow.DefaultFlowEngine;
import com.sn68.agent.dataagent.flow.FlowConditionEvaluator;
import com.sn68.agent.dataagent.flow.FlowContextMapper;
import com.sn68.agent.dataagent.flow.FlowDefinitionValidator;
import com.sn68.agent.dataagent.flow.FlowEventService;
import com.sn68.agent.dataagent.flow.FlowFieldExtractor;
import com.sn68.agent.dataagent.flow.FlowInstanceService;
import com.sn68.agent.dataagent.flow.FlowNodeExecutorRegistry;
import com.sn68.agent.dataagent.flow.FlowSchemaValidator;
import com.sn68.agent.dataagent.flow.FlowTemporalNormalizer;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceVersionMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.runtime.hook.service.RuntimeHookActionExecutor;
import com.sn68.agent.dataagent.runtime.hook.service.RuntimeHookDispatcher;
import com.sn68.agent.dataagent.runtime.hook.service.RuntimeHookService;
import com.sn68.agent.dataagent.service.agent.AgentStartupInitialization;
import com.sn68.agent.dataagent.service.agent.DataAgentService;
import com.sn68.agent.dataagent.service.aimodelconfig.AiModelRegistry;
import com.sn68.agent.dataagent.service.aimodelconfig.ModelConfigDataService;
import com.sn68.agent.dataagent.service.chat.DataChatSessionService;
import com.sn68.agent.dataagent.service.chat.SessionEventPublisher;
import com.sn68.agent.dataagent.service.chat.SessionTitleService;
import com.sn68.agent.dataagent.service.datasource.SkillDatasourceService;
import com.sn68.agent.dataagent.service.hybrid.factory.HybridRetrievalStrategyFactory;
import com.sn68.agent.dataagent.service.hybrid.fusion.FusionStrategy;
import com.sn68.agent.dataagent.service.llm.LlmService;
import com.sn68.agent.dataagent.service.schema.SchemaServiceImpl;
import com.sn68.agent.dataagent.service.schema.TableMetadataService;
import com.sn68.agent.dataagent.service.vectorstore.AgentVectorStoreService;
import com.sn68.agent.dataagent.service.vectorstore.DynamicFilterService;
import com.sn68.agent.dataagent.skill.execution.DeterministicSkillExecutor;
import com.sn68.agent.dataagent.skill.execution.SkillVersionResourceLoader;
import com.sn68.agent.dataagent.tool.ToolInvoker;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

class DataAgentExecutorQualifierTest {

	private static final String DB_EXECUTOR = "dbOperationExecutor";

	private static final String FLOW_EXECUTOR = "flowResolverExecutor";

	private static final String ROUTE_RETRIEVAL_EXECUTOR = "routeRetrievalExecutor";

	@Test
	void executorConstructorParametersRetainQualifiers() {
		assertQualifier(HybridRetrievalStrategyFactory.class, ExecutorService.class, DB_EXECUTOR);
		assertQualifier(AgentStartupInitialization.class, ExecutorService.class, DB_EXECUTOR);
		assertQualifier(SessionTitleService.class, ExecutorService.class, DB_EXECUTOR);
		assertQualifier(SchemaServiceImpl.class, ExecutorService.class, DB_EXECUTOR);
		assertQualifier(RuntimeHookDispatcher.class, ExecutorService.class, DB_EXECUTOR);
		assertQualifier(DeterministicSkillExecutor.class, ExecutorService.class, ROUTE_RETRIEVAL_EXECUTOR);
		assertQualifier(DefaultFlowEngine.class, Executor.class, FLOW_EXECUTOR);
	}

	@Test
	void dualExecutorBeansWireToIntendedConsumers() {
		ExecutorService dbExecutor = mock(ExecutorService.class);
		ExecutorService flowExecutor = mock(ExecutorService.class);
		TaskExecutor taskScheduler = mock(TaskExecutor.class);

		new ApplicationContextRunner()
			.withBean(DB_EXECUTOR, ExecutorService.class, () -> dbExecutor)
			.withBean(FLOW_EXECUTOR, ExecutorService.class, () -> flowExecutor)
			.withBean("taskScheduler", TaskExecutor.class, () -> taskScheduler)
			.withBean(ObjectMapper.class, ObjectMapper::new)
			.withBean(DataAgentProperties.class, DataAgentProperties::new)
			.withBean(VectorStore.class, () -> mock(VectorStore.class))
			.withBean(FusionStrategy.class, () -> mock(FusionStrategy.class))
			.withBean(DataAgentService.class, () -> mock(DataAgentService.class))
			.withBean(AgentVectorStoreService.class, () -> mock(AgentVectorStoreService.class))
			.withBean(SkillDatasourceService.class, () -> mock(SkillDatasourceService.class))
			.withBean(DataChatSessionService.class, () -> mock(DataChatSessionService.class))
			.withBean(SessionEventPublisher.class, () -> mock(SessionEventPublisher.class))
			.withBean(LlmService.class, () -> mock(LlmService.class))
			.withBean(AccessorFactory.class, () -> mock(AccessorFactory.class))
			.withBean(TableMetadataService.class, () -> mock(TableMetadataService.class))
			.withBean(BatchingStrategy.class, () -> mock(BatchingStrategy.class))
			.withBean(DynamicFilterService.class, () -> mock(DynamicFilterService.class))
			.withBean(RuntimeHookService.class, () -> mock(RuntimeHookService.class))
			.withBean(RuntimeHookActionExecutor.class, () -> mock(RuntimeHookActionExecutor.class))
			.withBean(FlowDefinitionValidator.class, () -> mock(FlowDefinitionValidator.class))
			.withBean(FlowNodeExecutorRegistry.class, () -> mock(FlowNodeExecutorRegistry.class))
			.withBean(FlowInstanceService.class, () -> mock(FlowInstanceService.class))
			.withBean(FlowEventService.class, () -> mock(FlowEventService.class))
			.withBean(FlowContextMapper.class, () -> mock(FlowContextMapper.class))
			.withBean(FlowConditionEvaluator.class, () -> mock(FlowConditionEvaluator.class))
			.withBean(FlowSchemaValidator.class, () -> mock(FlowSchemaValidator.class))
			.withBean(FlowFieldExtractor.class, () -> mock(FlowFieldExtractor.class))
			.withBean(ToolInvoker.class, () -> mock(ToolInvoker.class))
			.withBean(CapabilityGateway.class, () -> mock(CapabilityGateway.class))
			.withBean(AgentExecutionResourceVersionMapper.class,
					() -> mock(AgentExecutionResourceVersionMapper.class))
			.withBean(DataAgentSkillMapper.class, () -> mock(DataAgentSkillMapper.class))
			.withBean(DataAgentSkillVersionMapper.class, () -> mock(DataAgentSkillVersionMapper.class))
			.withBean(SkillVersionResourceLoader.class, () -> mock(SkillVersionResourceLoader.class))
			.withBean(AgentRuntimeProgressService.class, () -> mock(AgentRuntimeProgressService.class))
			.withBean(FlowTemporalNormalizer.class, () -> mock(FlowTemporalNormalizer.class))
			.withBean(DataAgentAsyncContextBridge.class, () -> mock(DataAgentAsyncContextBridge.class))
			.withBean(ModelConfigDataService.class, () -> mock(ModelConfigDataService.class))
			.withBean(AiModelRegistry.class, () -> mock(AiModelRegistry.class))
			.withBean("hybridRetrievalStrategyFactory", HybridRetrievalStrategyFactory.class)
			.withBean(AgentStartupInitialization.class)
			.withBean(SessionTitleService.class)
			.withBean(SchemaServiceImpl.class)
			.withBean(RuntimeHookDispatcher.class)
			.withBean(DefaultFlowEngine.class)
			.run(context -> {
				assertNull(context.getStartupFailure(), () -> String.valueOf(context.getStartupFailure()));
				HybridRetrievalStrategyFactory hybridFactory = context
					.getBean("&hybridRetrievalStrategyFactory", HybridRetrievalStrategyFactory.class);
				assertSame(dbExecutor, ReflectionTestUtils.getField(hybridFactory, "executorService"));
				assertSame(dbExecutor, ReflectionTestUtils.getField(context.getBean(AgentStartupInitialization.class),
						"executorService"));
				assertSame(dbExecutor,
						ReflectionTestUtils.getField(context.getBean(SessionTitleService.class), "executorService"));
				assertSame(dbExecutor,
						ReflectionTestUtils.getField(context.getBean(SchemaServiceImpl.class), DB_EXECUTOR));
				assertSame(dbExecutor,
						ReflectionTestUtils.getField(context.getBean(RuntimeHookDispatcher.class), DB_EXECUTOR));
				assertSame(flowExecutor,
						ReflectionTestUtils.getField(context.getBean(DefaultFlowEngine.class), FLOW_EXECUTOR));
			});
	}

	private void assertQualifier(Class<?> type, Class<?> executorType, String expectedBeanName) {
		java.lang.reflect.Constructor<?>[] constructors = type.getDeclaredConstructors();
		Parameter parameter = Arrays.stream(constructors)
			.filter(constructor -> constructors.length == 1 || constructor.isAnnotationPresent(Autowired.class))
			.flatMap(constructor -> Arrays.stream(constructor.getParameters()))
			.filter(candidate -> candidate.getType() == executorType)
			.findFirst()
			.orElseThrow();
		Qualifier qualifier = parameter.getAnnotation(Qualifier.class);
		assertNotNull(qualifier, () -> type.getSimpleName() + " executor parameter must declare @Qualifier");
		assertEquals(expectedBeanName, qualifier.value());
	}

}
