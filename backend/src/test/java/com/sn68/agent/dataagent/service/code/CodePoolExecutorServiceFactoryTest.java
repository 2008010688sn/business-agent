/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.service.code;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.enums.CodePoolExecutorEnum;
import com.sn68.agent.dataagent.properties.CodeExecutorProperties;
import com.sn68.agent.dataagent.service.code.impls.AiSimulationCodeExecutorService;
import com.sn68.agent.dataagent.service.code.impls.DockerCodePoolExecutorService;
import com.sn68.agent.dataagent.service.code.impls.LocalCodePoolExecutorService;
import com.sn68.agent.dataagent.service.llm.LlmService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.NestedExceptionUtils;

@SuppressWarnings("unchecked")
class CodePoolExecutorServiceFactoryTest {

	@Test
	void codePoolExecutorDefaultsToDocker() {
		assertEquals(CodePoolExecutorEnum.DOCKER, new CodeExecutorProperties().getCodePoolExecutor());
	}

	@Test
	void localModeFailsStartupWithoutExplicitOptIn() {
		CodeExecutorProperties properties = new CodeExecutorProperties();
		properties.setCodePoolExecutor(CodePoolExecutorEnum.LOCAL);
		CodePoolExecutorServiceFactory factory = new CodePoolExecutorServiceFactory(properties,
				mock(ObjectProvider.class));

		CheckedException failure = assertThrows(CheckedException.class, factory::validateExecutorSelection);

		assertTrue(failure.getMessage().contains(CodePoolExecutorServiceFactory.UNSAFE_LOCAL_EXECUTION_PROPERTY));
		// 产品是懒加载的，所以启动期校验之外还得堵住实际取用这一条路。
		assertThrows(CheckedException.class, factory::getObject);
	}

	/**
	 * 直接证明「误配成 local 就起不来」：FactoryBean 的产品没有消费方，只有把校验挂在 Bean 初始化上，
	 * 容器刷新才会真的失败。
	 */
	@Test
	void applicationContextRefreshFailsWhenLocalExecutorIsNotOptedIn() {
		CodeExecutorProperties properties = new CodeExecutorProperties();
		properties.setCodePoolExecutor(CodePoolExecutorEnum.LOCAL);

		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.registerBean(CodeExecutorProperties.class, () -> properties);
			context.registerBean(CodePoolExecutorServiceFactory.class);

			BeanCreationException failure = assertThrows(BeanCreationException.class, context::refresh);

			Throwable cause = NestedExceptionUtils.getMostSpecificCause(failure);
			assertInstanceOf(CheckedException.class, cause);
			assertTrue(cause.getMessage().contains(CodePoolExecutorServiceFactory.UNSAFE_LOCAL_EXECUTION_PROPERTY));
		}
	}

	@Test
	void dockerModeDoesNotFallBackToLocal() {
		CodeExecutorProperties properties = new CodeExecutorProperties();
		properties.setCodePoolExecutor(CodePoolExecutorEnum.DOCKER);
		CodePoolExecutorServiceFactory factory = new CodePoolExecutorServiceFactory(properties,
				mock(ObjectProvider.class));

		CodePoolExecutorService service = factory.getObject();

		assertInstanceOf(DockerCodePoolExecutorService.class, service);
	}

	@Test
	void localModeDoesNotResolveLlmService() {
		CodeExecutorProperties properties = new CodeExecutorProperties();
		properties.setCodePoolExecutor(CodePoolExecutorEnum.LOCAL);
		properties.setAllowUnsafeLocalExecution(true);
		ObjectProvider<LlmService> provider = mock(ObjectProvider.class);

		CodePoolExecutorServiceFactory factory = new CodePoolExecutorServiceFactory(properties, provider);

		assertInstanceOf(LocalCodePoolExecutorService.class, factory.getObject());
		verifyNoInteractions(provider);
	}

	@Test
	void aiSimulationResolvesLlmServiceOnlyWhenSelected() {
		CodeExecutorProperties properties = new CodeExecutorProperties();
		properties.setCodePoolExecutor(CodePoolExecutorEnum.AI_SIMULATION);
		ObjectProvider<LlmService> provider = mock(ObjectProvider.class);
		LlmService llmService = mock(LlmService.class);
		when(provider.getObject()).thenReturn(llmService);

		CodePoolExecutorServiceFactory factory = new CodePoolExecutorServiceFactory(properties, provider);

		assertInstanceOf(AiSimulationCodeExecutorService.class, factory.getObject());
		verify(provider).getObject();
	}

}
