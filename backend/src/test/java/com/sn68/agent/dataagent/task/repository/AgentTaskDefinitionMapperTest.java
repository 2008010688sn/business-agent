/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.task.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sn68.agent.dataagent.task.entity.AgentTaskDefinition;
import com.sn68.agent.framework.commons.exception.CheckedException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

class AgentTaskDefinitionMapperTest {

	@BeforeAll
	static void initTableInfo() {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
				AgentTaskDefinition.class);
	}

	@Test
	void findByTenantAndIdAppliesTenantPredicate() {
		AgentTaskDefinitionMapper mapper = mock(AgentTaskDefinitionMapper.class, Answers.CALLS_REAL_METHODS);

		mapper.findByTenantAndId("7", 3L);

		ArgumentCaptor<LambdaQueryWrapper<AgentTaskDefinition>> captor = ArgumentCaptor.captor();
		verify(mapper).selectOne(captor.capture());
		String sql = captor.getValue().getSqlSegment();
		assertTrue(sql.contains("tenant_id"), sql);
		assertTrue(captor.getValue().getParamNameValuePairs().containsValue("7"), sql);
	}

	@Test
	void findByTenantAndIdRejectsMissingTenant() {
		AgentTaskDefinitionMapper mapper = mock(AgentTaskDefinitionMapper.class, Answers.CALLS_REAL_METHODS);

		CheckedException ex = assertThrows(CheckedException.class, () -> mapper.findByTenantAndId(" ", 3L));

		assertEquals(403, ex.getCode());
		assertTrue(ex.getMessage().contains("租户"));
	}

}
