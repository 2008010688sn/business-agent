/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.repository;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sn68.agent.dataagent.entity.DataAgentFlowInstance;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

class DataAgentFlowInstanceMapperTest {

	@BeforeAll
	static void initTableInfo() {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
				DataAgentFlowInstance.class);
	}

	@Test
	void findActiveExcludesExpiredInstances() {
		DataAgentFlowInstanceMapper mapper = mock(DataAgentFlowInstanceMapper.class, Answers.CALLS_REAL_METHODS);

		mapper.findActive("tenant-1", 8L, "thread-1", "user-1");

		assertNotExpiredPredicate(capturedSelectOne(mapper));
	}

	@Test
	void findActiveByIdExcludesExpiredInstances() {
		DataAgentFlowInstanceMapper mapper = mock(DataAgentFlowInstanceMapper.class, Answers.CALLS_REAL_METHODS);

		mapper.findActiveById(9L, "tenant-1", 8L, "thread-1", "user-1");

		assertNotExpiredPredicate(capturedSelectOne(mapper));
	}

	private LbqWrapper<DataAgentFlowInstance> capturedSelectOne(DataAgentFlowInstanceMapper mapper) {
		ArgumentCaptor<LbqWrapper<DataAgentFlowInstance>> wrapperCaptor = ArgumentCaptor.captor();
		verify(mapper).selectOne(wrapperCaptor.capture());
		return wrapperCaptor.getValue();
	}

	private void assertNotExpiredPredicate(LbqWrapper<DataAgentFlowInstance> wrapper) {
		String sql = wrapper.getSqlSegment();
		assertTrue(sql.contains("expires_at"), sql);
		assertTrue(sql.toLowerCase().contains("is null"), sql);
	}

}
