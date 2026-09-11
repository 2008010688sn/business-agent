/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeApproval;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

class AgentRuntimeApprovalMapperTest {

	@BeforeAll
	static void initTableInfo() {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
				AgentRuntimeApproval.class);
	}

	@Test
	void findByIdAndTenantIdAppliesTenantPredicate() {
		AgentRuntimeApprovalMapper mapper = mock(AgentRuntimeApprovalMapper.class, Answers.CALLS_REAL_METHODS);

		mapper.findByIdAndTenantId(11L, "7");

		ArgumentCaptor<LbqWrapper<AgentRuntimeApproval>> captor = ArgumentCaptor.captor();
		verify(mapper).selectOne(captor.capture());
		String sql = captor.getValue().getSqlSegment();
		assertTrue(sql.contains("tenant_id"), sql);
		assertTrue(captor.getValue().getParamNameValuePairs().containsValue("7"), sql);
	}

	@Test
	void findByIdAndTenantIdRejectsMissingTenant() {
		AgentRuntimeApprovalMapper mapper = mock(AgentRuntimeApprovalMapper.class, Answers.CALLS_REAL_METHODS);

		CheckedException ex = assertThrows(CheckedException.class, () -> mapper.findByIdAndTenantId(11L, null));

		assertEquals(403, ex.getCode());
		assertTrue(ex.getMessage().contains("租户"));
	}

}
