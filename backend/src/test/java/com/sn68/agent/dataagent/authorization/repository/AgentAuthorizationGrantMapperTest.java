/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.authorization.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sn68.agent.dataagent.authorization.entity.AgentAuthorizationGrant;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

class AgentAuthorizationGrantMapperTest {

	@BeforeAll
	static void initTableInfo() {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
				AgentAuthorizationGrant.class);
	}

	@Test
	void listByOwnerAlwaysAppliesTenantPredicate() {
		AgentAuthorizationGrantMapper mapper = mock(AgentAuthorizationGrantMapper.class, Answers.CALLS_REAL_METHODS);

		mapper.listByOwner(AuthorizationOwnerType.DIGITAL_EMPLOYEE, 55L, "tenant-1");

		ArgumentCaptor<LbqWrapper<AgentAuthorizationGrant>> captor = ArgumentCaptor.captor();
		verify(mapper).selectList(captor.capture());
		String sql = captor.getValue().getSqlSegment();
		assertTrue(sql.contains("tenant_id"), sql);
		assertTrue(captor.getValue().getParamNameValuePairs().containsValue("tenant-1"), sql);
	}

	@Test
	void listByOwnerRejectsMissingTenant() {
		AgentAuthorizationGrantMapper mapper = mock(AgentAuthorizationGrantMapper.class, Answers.CALLS_REAL_METHODS);

		CheckedException ex = assertThrows(CheckedException.class,
				() -> mapper.listByOwner(AuthorizationOwnerType.DIGITAL_EMPLOYEE, 55L, null));

		assertEquals(403, ex.getCode());
		assertTrue(ex.getMessage().contains("租户"));
	}

}
