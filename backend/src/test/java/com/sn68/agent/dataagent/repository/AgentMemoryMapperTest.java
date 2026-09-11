/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sn68.agent.dataagent.entity.AgentMemory;
import com.sn68.agent.dataagent.enums.AgentMemoryStatus;
import com.sn68.agent.dataagent.enums.MemoryScope;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

class AgentMemoryMapperTest {

	private static final String TENANT = "tenant-7";

	@BeforeAll
	static void initTableInfo() {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentMemory.class);
	}

	@Test
	void findByScopeAlwaysAppliesTenantPredicate() {
		AgentMemoryMapper mapper = mock(AgentMemoryMapper.class, Answers.CALLS_REAL_METHODS);

		mapper.findByScope(1L, MemoryScope.EMPLOYEE_USER, "u1", TENANT);

		ArgumentCaptor<LbqWrapper<AgentMemory>> captor = ArgumentCaptor.captor();
		verify(mapper).selectList(captor.capture());
		String sql = captor.getValue().getSqlSegment();
		assertTrue(sql.contains("tenant_id"), sql);
		assertTrue(captor.getValue().getParamNameValuePairs().containsValue(TENANT), sql);
	}

	@Test
	void findRecallCandidatesRejectsMissingTenant() {
		AgentMemoryMapper mapper = mock(AgentMemoryMapper.class, Answers.CALLS_REAL_METHODS);

		CheckedException ex = assertThrows(CheckedException.class, () -> mapper.findRecallCandidates(1L, "u1", " "));

		assertEquals(403, ex.getCode());
		assertTrue(ex.getMessage().contains("租户"));
	}

	@Test
	void updateStatusAppliesTenantPredicate() {
		AgentMemoryMapper mapper = mock(AgentMemoryMapper.class, Answers.CALLS_REAL_METHODS);

		mapper.updateStatus(1L, "u1", 9L, AgentMemoryStatus.ACTIVE, TENANT);

		ArgumentCaptor<LambdaUpdateWrapper<AgentMemory>> captor = ArgumentCaptor.captor();
		verify(mapper).update(org.mockito.ArgumentMatchers.isNull(), captor.capture());
		String sql = captor.getValue().getSqlSegment();
		assertTrue(sql.contains("tenant_id"), sql);
		assertTrue(captor.getValue().getParamNameValuePairs().containsValue(TENANT), sql);
	}

}
