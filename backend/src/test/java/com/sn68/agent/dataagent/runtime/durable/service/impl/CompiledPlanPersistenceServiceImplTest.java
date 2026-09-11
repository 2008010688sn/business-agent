/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.routing.v2.model.CapabilityRiskLevel;
import com.sn68.agent.dataagent.routing.v2.model.CompiledPlan;
import com.sn68.agent.dataagent.routing.v2.model.CompiledPlanIdempotencyPolicy;
import com.sn68.agent.dataagent.routing.v2.model.RouteProposalMode;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimePlan;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimePlanMapper;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编译计划持久化测试：影子计划落库（status=SHADOW、不替换 ACTIVE）与正式保存不被影子行顶替。
 */
class CompiledPlanPersistenceServiceImplTest {

	@BeforeAll
	static void initTableInfo() {
		// supersedeOldActivePlans 走 Wraps.<AgentRuntimePlan>lbU()，lambda 列名解析依赖 MP 的
		// TableInfo 缓存；纯单测没有 Spring 做 mapper 扫描，按 DataAgentRouteProfileMapperTest 同款写法手动注册。
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
				AgentRuntimePlan.class);
	}

	private AgentRuntimePlanMapper planMapper;

	private CompiledPlanPersistenceServiceImpl service;

	@BeforeEach
	void setUp() {
		planMapper = mock(AgentRuntimePlanMapper.class);
		service = new CompiledPlanPersistenceServiceImpl(planMapper, new ObjectMapper());
	}

	@Test
	void saveShadowInsertsShadowStatusWithoutSupersedingActivePlans() {
		when(planMapper.findByRunIdAndPlanHash(77L, "hash-1")).thenReturn(null);
		when(planMapper.findLatestByRunId(77L)).thenReturn(null);

		service.saveShadow(77L, plan("hash-1"));

		AgentRuntimePlan inserted = capturedInsert();
		assertEquals("SHADOW", inserted.getStatus());
		assertEquals(77L, inserted.getRunId());
		assertEquals(1, inserted.getPlanVersion());
		assertEquals(7L, inserted.getTenantId());
		// 影子计划不参与执行，不得替换该 run 下的 ACTIVE 计划
		verify(planMapper, never()).update(any(), any());
	}

	@Test
	void saveShadowIsIdempotentByRunIdAndPlanHash() {
		AgentRuntimePlan existing = AgentRuntimePlan.builder().status("ACTIVE").planVersion(2).build();
		when(planMapper.findByRunIdAndPlanHash(77L, "hash-1")).thenReturn(existing);

		AgentRuntimePlan result = service.saveShadow(77L, plan("hash-1"));

		assertSame(existing, result);
		verify(planMapper, never()).insert(any(AgentRuntimePlan.class));
	}

	@Test
	void saveDoesNotReuseShadowRowAndCreatesActivePlan() {
		AgentRuntimePlan shadowRow = AgentRuntimePlan.builder().status("SHADOW").planVersion(3).build();
		when(planMapper.findByRunIdAndPlanHash(77L, "hash-1")).thenReturn(shadowRow);
		when(planMapper.findLatestByRunId(77L)).thenReturn(shadowRow);

		service.save(77L, plan("hash-1"));

		AgentRuntimePlan inserted = capturedInsert();
		assertEquals("ACTIVE", inserted.getStatus());
		assertEquals(4, inserted.getPlanVersion());
	}

	@Test
	void saveConflictLosingToShadowRowRetriesWithNewVersionInsteadOfReturningShadow() {
		// S1 遗留竞态：save 与 saveShadow 同 planHash 竞争 plan_version，save 插入冲突后
		// 回查只看到影子行——必须换新版本重插正式 ACTIVE 计划，绝不把影子行当正式计划返回
		AgentRuntimePlan shadowWinner = AgentRuntimePlan.builder().status("SHADOW").planVersion(1).build();
		when(planMapper.findNonShadowByRunIdAndPlanHash(77L, "hash-1")).thenReturn(null);
		when(planMapper.findLatestByRunId(77L)).thenReturn(null, shadowWinner);
		when(planMapper.insert(any(AgentRuntimePlan.class)))
			.thenThrow(new org.springframework.dao.DuplicateKeyException("(run_id, plan_version) duplicated"))
			.thenReturn(1);

		service.save(77L, plan("hash-1"));

		ArgumentCaptor<AgentRuntimePlan> captor = ArgumentCaptor.forClass(AgentRuntimePlan.class);
		verify(planMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
		AgentRuntimePlan retried = captor.getAllValues().get(1);
		assertEquals("ACTIVE", retried.getStatus());
		assertEquals(2, retried.getPlanVersion());
	}

	@Test
	void saveConflictReturnsConcurrentActiveWinnerWithoutShadowRow() {
		AgentRuntimePlan activeWinner = AgentRuntimePlan.builder().status("ACTIVE").planVersion(2).build();
		when(planMapper.findNonShadowByRunIdAndPlanHash(77L, "hash-1")).thenReturn(null, activeWinner);
		when(planMapper.findLatestByRunId(77L)).thenReturn(null);
		when(planMapper.insert(any(AgentRuntimePlan.class)))
			.thenThrow(new org.springframework.dao.DuplicateKeyException("(run_id, plan_version) duplicated"));

		AgentRuntimePlan result = service.save(77L, plan("hash-1"));

		assertSame(activeWinner, result);
		verify(planMapper, org.mockito.Mockito.times(1)).insert(any(AgentRuntimePlan.class));
	}

	private AgentRuntimePlan capturedInsert() {
		ArgumentCaptor<AgentRuntimePlan> captor = ArgumentCaptor.forClass(AgentRuntimePlan.class);
		verify(planMapper).insert(captor.capture());
		return captor.getValue();
	}

	private static CompiledPlan plan(String planHash) {
		return new CompiledPlan(planHash, "7", 0L, 100L, 0L, "{}", Instant.now().plusSeconds(60),
				RouteProposalMode.CHAT, null, List.of(), CapabilityRiskLevel.READ_ONLY, false, 1_000L,
				CompiledPlanIdempotencyPolicy.PLAN_HASH_STEP_KEY, "{}");
	}

}
