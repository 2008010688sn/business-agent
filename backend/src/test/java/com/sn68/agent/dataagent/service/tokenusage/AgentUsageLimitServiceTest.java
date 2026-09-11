/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.tokenusage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.dto.tokenusage.AgentUsageLimitPolicyReq;
import com.sn68.agent.dataagent.entity.AgentUsageLimitPolicy;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.repository.AgentUsageLimitPolicyMapper;
import com.sn68.agent.dataagent.repository.DataAgentMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

class AgentUsageLimitServiceTest {

	private final AgentUsageLimitPolicyMapper policyMapper = mock(AgentUsageLimitPolicyMapper.class);

	private final DataAgentMapper dataAgentMapper = mock(DataAgentMapper.class);

	@SuppressWarnings("unchecked")
	private final ObjectProvider<StringRedisTemplate> redisTemplateProvider = mock(ObjectProvider.class);

	private final AgentUsageLimitService service = new AgentUsageLimitService(policyMapper, dataAgentMapper,
			redisTemplateProvider);

	@Test
	void createPolicyPersistsAgentNameSnapshotWithoutIamNickname() {
		DataAgent agent = new DataAgent();
		agent.setName("Support Agent");
		when(dataAgentMapper.findById(100L)).thenReturn(agent);
		stubInsert();
		ArgumentCaptor<AgentUsageLimitPolicy> captor = ArgumentCaptor.forClass(AgentUsageLimitPolicy.class);

		service.createPolicy(request("USER", "u1", 100L));

		verify(policyMapper).insert(captor.capture());
		assertNull(captor.getValue().getUserNickName());
		assertEquals("Support Agent", captor.getValue().getAgentName());
	}

	@Test
	void createPolicyDoesNotBlockWhenNameResolutionFails() {
		when(dataAgentMapper.findById(100L)).thenThrow(new IllegalStateException("agent unavailable"));
		stubInsert();
		ArgumentCaptor<AgentUsageLimitPolicy> captor = ArgumentCaptor.forClass(AgentUsageLimitPolicy.class);

		service.createPolicy(request("USER", "u1", 100L));

		verify(policyMapper).insert(captor.capture());
		assertNull(captor.getValue().getUserNickName());
		assertNull(captor.getValue().getAgentName());
	}

	private void stubInsert() {
		doAnswer(invocation -> {
			AgentUsageLimitPolicy policy = invocation.getArgument(0);
			policy.setId(1L);
			return 1;
		}).when(policyMapper).insert(any(AgentUsageLimitPolicy.class));
	}

	private AgentUsageLimitPolicyReq request(String scopeType, String scopeId, Long agentId) {
		AgentUsageLimitPolicyReq req = new AgentUsageLimitPolicyReq();
		req.setScopeType(scopeType);
		req.setScopeId(scopeId);
		req.setAgentId(agentId);
		req.setPolicyType("TOKEN_QUOTA");
		req.setWindowType("DAY");
		req.setLimitValue(100L);
		req.setAction("WARN");
		return req;
	}

}
