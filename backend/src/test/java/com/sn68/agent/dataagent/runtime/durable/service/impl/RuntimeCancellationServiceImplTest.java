/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service.impl;

import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRun;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeRunState;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeInterruptionMapper;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeOutboxService.OutboxAppend;
import com.sn68.agent.dataagent.runtime.durable.support.InMemoryDurableRuntime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * 取消服务测试：空闲运行直接落终态时必须旁路写终态 Outbox（任务台账回写与 IM 通知都靠它），
 * 在途运行只请求取消、终态由执行体收敛，不得抢先写终态消息。
 */
class RuntimeCancellationServiceImplTest {

	private static final String TENANT_ID = "7";

	private InMemoryDurableRuntime durable;

	private RuntimeCancellationServiceImpl cancellationService;

	@BeforeEach
	void setUp() {
		durable = new InMemoryDurableRuntime();
		cancellationService = new RuntimeCancellationServiceImpl(durable.runMapper,
				mock(AgentRuntimeInterruptionMapper.class), durable.stateService, durable.eventService,
				durable.outboxService);
	}

	@Test
	void idleRunCancellationWritesTerminalOutbox() {
		Long runId = durable.seedTaskRun(TENANT_ID, "待执行的巡检任务", 3L, 1L);

		Long epoch = cancellationService.requestCancel(durable.runById(runId), "用户在任务中心取消", "u-1");

		assertEquals(1L, epoch);
		assertEquals(RuntimeRunState.CANCELLED.getValue(), durable.runById(runId).getState());
		OutboxAppend terminal = terminalAppend(runId);
		assertEquals("RUN_CANCELLED", terminal.eventType());
		assertEquals(TENANT_ID, terminal.tenantId());
		assertEquals(runId, terminal.runId());
		assertEquals("CANCELLED", terminal.payload().get("state"));
		assertEquals("RUN_CANCELLED", terminal.payload().get("errorCode"));
	}

	@Test
	void inFlightRunCancellationDefersTerminalOutboxToExecutor() {
		Long runId = durable.seedTaskRun(TENANT_ID, "执行中的巡检任务", 3L, 1L);
		durable.forceRunState(runId, RuntimeRunState.RUNNING, Instant.now());

		cancellationService.requestCancel(durable.runById(runId), "用户取消", "u-1");

		assertEquals(RuntimeRunState.CANCELLING.getValue(), durable.runById(runId).getState());
		// 终态由在途执行体经调度器收敛，取消链路此刻写终态消息会让台账提前落终态
		assertNull(terminalAppendOrNull(runId));
	}

	@Test
	void repeatedCancellationDoesNotDuplicateTerminalOutbox() {
		Long runId = durable.seedTaskRun(TENANT_ID, "被重复取消的任务", 3L, 1L);
		AgentRuntimeRun snapshot = durable.runById(runId);

		cancellationService.requestCancel(snapshot, "用户取消", "u-1");
		// 用同一份过期快照重放：真实链路里重复点击/重投也是拿着旧状态进来的
		assertNull(cancellationService.requestCancel(snapshot, "用户取消", "u-1"));

		assertEquals(1, terminalAppends(runId).size());
	}

	private OutboxAppend terminalAppend(Long runId) {
		List<OutboxAppend> appends = terminalAppends(runId);
		assertEquals(1, appends.size(), "应恰好写入一条终态 Outbox 消息");
		return appends.get(0);
	}

	private OutboxAppend terminalAppendOrNull(Long runId) {
		List<OutboxAppend> appends = terminalAppends(runId);
		return appends.isEmpty() ? null : appends.get(0);
	}

	private List<OutboxAppend> terminalAppends(Long runId) {
		return durable.outboxAppends().stream()
			.filter(append -> ("run-terminal:" + runId + ":RUN_CANCELLED").equals(append.eventKey()))
			.toList();
	}

}
