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
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sn68.agent.dataagent.entity.DataAgentRoutePending;
import com.sn68.agent.dataagent.service.routing.RoutePendingService;
import java.time.Instant;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

class DataAgentRoutePendingMapperTest {

	@BeforeAll
	static void initTableInfo() {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
				DataAgentRoutePending.class);
	}

	@Test
	void expirePendingAlsoMarksTheExecutionStateTerminal() {
		DataAgentRoutePendingMapper mapper = mock(DataAgentRoutePendingMapper.class, Answers.CALLS_REAL_METHODS);

		mapper.expirePending(Instant.parse("2026-08-04T12:00:00Z"));

		ArgumentCaptor<LambdaUpdateWrapper<DataAgentRoutePending>> wrapperCaptor = ArgumentCaptor.captor();
		verify(mapper).update(org.mockito.ArgumentMatchers.isNull(), wrapperCaptor.capture());
		String sqlSet = wrapperCaptor.getValue().getSqlSet();
		assertTrue(sqlSet.contains("status"), sqlSet);
		assertTrue(sqlSet.contains("execution_state"), sqlSet);
		assertTrue(wrapperCaptor.getValue().getParamNameValuePairs().containsValue(RoutePendingStatus.EXPIRED));
		assertTrue(wrapperCaptor.getValue().getParamNameValuePairs()
			.containsValue(RoutePendingService.EXECUTION_EXPIRED));
	}

}
