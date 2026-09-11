/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeInvocation;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeInvocationState;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeInvocationMapper;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeInvocationService.InvocationOpen;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结果未知调用的对账闭环测试：OUTCOME_UNKNOWN 幂等键禁止自动重试，只能经人工对账收敛；
 * 收敛前报错必须带上 invocationId 与对账入口，否则被封锁的幂等键无从解除。
 */
class RuntimeInvocationServiceImplReconcileTest {

	private static final String TENANT = "7";

	private static final long INVOCATION_ID = 4321L;

	private AgentRuntimeInvocationMapper invocationMapper;

	private RuntimeInvocationServiceImpl service;

	@BeforeEach
	void setUp() {
		invocationMapper = mock(AgentRuntimeInvocationMapper.class);
		service = new RuntimeInvocationServiceImpl(invocationMapper, new ObjectMapper());
	}

	/** 重复 open 结果未知的幂等键必须拒绝，且提示里带 invocationId + 对账端点，闭环可达。 */
	@Test
	void openOnOutcomeUnknownIsRejectedWithReconcileHint() {
		when(invocationMapper.findByRunIdAndIdempotencyKey(55L, "key-1"))
			.thenReturn(invocation(RuntimeInvocationState.OUTCOME_UNKNOWN));

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.open(new InvocationOpen(TENANT, 55L, null, null, "key-1", "crm:update", "TOOL", "fp")));

		assertTrue(ex.getMessage().contains("禁止自动重试"));
		assertTrue(ex.getMessage().contains(String.valueOf(INVOCATION_ID)));
		assertTrue(ex.getMessage().contains("/reconcile"));
	}

	@Test
	void reconcileConvergesOutcomeUnknownToSuccess() {
		when(invocationMapper.findByIdAndTenantId(INVOCATION_ID, TENANT))
			.thenReturn(invocation(RuntimeInvocationState.OUTCOME_UNKNOWN));
		when(invocationMapper.casTransition(eq(INVOCATION_ID), anyString(), anyString(), nullable(String.class),
				nullable(String.class), nullable(String.class), nullable(String.class), nullable(Instant.class),
				nullable(Instant.class), nullable(Instant.class))).thenReturn(1);

		service.reconcile(TENANT, "ops-1", INVOCATION_ID, true, "已在下游核对到单号 SO-9527");

		ArgumentCaptor<String> toState = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> receipt = ArgumentCaptor.forClass(String.class);
		verify(invocationMapper, org.mockito.Mockito.times(2)).casTransition(eq(INVOCATION_ID), anyString(),
				toState.capture(), nullable(String.class), receipt.capture(), nullable(String.class),
				nullable(String.class), nullable(Instant.class), nullable(Instant.class), nullable(Instant.class));
		assertEquals(List.of(RuntimeInvocationState.RECONCILING.getValue(),
				RuntimeInvocationState.SUCCESS.getValue()), toState.getAllValues());
		assertTrue(receipt.getAllValues().get(1).contains("ops-1"));
		assertTrue(receipt.getAllValues().get(1).contains("SO-9527"));
	}

	/** 已处于 RECONCILING 的记录不再重复 beginReconcile，直接收敛。 */
	@Test
	void reconcileFromReconcilingSkipsBeginStep() {
		when(invocationMapper.findByIdAndTenantId(INVOCATION_ID, TENANT))
			.thenReturn(invocation(RuntimeInvocationState.RECONCILING));
		when(invocationMapper.casTransition(eq(INVOCATION_ID), eq(RuntimeInvocationState.RECONCILING.getValue()),
				anyString(), nullable(String.class), nullable(String.class), nullable(String.class),
				nullable(String.class), nullable(Instant.class), nullable(Instant.class), nullable(Instant.class)))
			.thenReturn(1);

		service.reconcile(TENANT, "ops-1", INVOCATION_ID, false, "下游确认未生效");

		verify(invocationMapper, never()).casTransition(anyLong(),
				eq(RuntimeInvocationState.OUTCOME_UNKNOWN.getValue()), anyString(), nullable(String.class),
				nullable(String.class), nullable(String.class), nullable(String.class), nullable(Instant.class),
				nullable(Instant.class), nullable(Instant.class));
	}

	@Test
	void reconcileRejectsOtherTenantRecord() {
		when(invocationMapper.findByIdAndTenantId(INVOCATION_ID, TENANT)).thenReturn(null);

		assertThrows(CheckedException.class, () -> service.reconcile(TENANT, "ops-1", INVOCATION_ID, true, null));
	}

	@Test
	void reconcileRejectsAlreadyTerminalRecord() {
		when(invocationMapper.findByIdAndTenantId(INVOCATION_ID, TENANT))
			.thenReturn(invocation(RuntimeInvocationState.SUCCESS));

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.reconcile(TENANT, "ops-1", INVOCATION_ID, true, null));

		assertTrue(ex.getMessage().contains("仅结果未知/对账中"));
	}

	@Test
	void pendingReconcileListRequiresTenantAndCapsLimit() {
		assertThrows(CheckedException.class, () -> service.listPendingReconcile(null, 10));
		when(invocationMapper.listPendingReconcile(eq(TENANT), anyInt())).thenReturn(List.of());

		service.listPendingReconcile(TENANT, 100000);

		verify(invocationMapper).listPendingReconcile(TENANT, 200);
	}

	@Test
	void openRejectsIncompleteCommand() {
		assertThrows(CheckedException.class,
				() -> service.open(new InvocationOpen(TENANT, null, null, null, "key-1", null, null, null)));
		verify(invocationMapper, never()).insert(any(AgentRuntimeInvocation.class));
	}

	private AgentRuntimeInvocation invocation(RuntimeInvocationState state) {
		AgentRuntimeInvocation invocation = new AgentRuntimeInvocation();
		invocation.setId(INVOCATION_ID);
		invocation.setTenantId(TENANT);
		invocation.setRunId(55L);
		invocation.setIdempotencyKey("key-1");
		invocation.setState(state.getValue());
		return invocation;
	}

}
