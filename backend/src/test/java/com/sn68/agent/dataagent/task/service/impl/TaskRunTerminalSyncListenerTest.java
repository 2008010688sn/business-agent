/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.task.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeOutbox;
import com.sn68.agent.dataagent.runtime.durable.event.RuntimeOutboxEvent;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeOutboxMapper;
import com.sn68.agent.dataagent.runtime.durable.service.impl.RuntimeOutboxServiceImpl;
import com.sn68.agent.dataagent.task.entity.AgentTaskRun;
import com.sn68.agent.dataagent.task.enums.TaskRunStatus;
import com.sn68.agent.dataagent.task.repository.AgentTaskDefinitionMapper;
import com.sn68.agent.dataagent.task.repository.AgentTaskRunMapper;
import com.sn68.agent.dataagent.task.service.AgentTaskDeliveryService;
import com.sn68.agent.dataagent.task.service.AgentTaskRunService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运行终态 Outbox 消费方测试。
 *
 * <p>台账侧用内存表复刻 {@code markTerminalByRuntimeRunId} 的原子 UPDATE
 * （{@code run_status IN ('PENDING','RUNNING')} 谓词兼作幂等闸门），因此断言直接落在台账行上；
 * 另有两条用真实 {@code RuntimeOutboxServiceImpl} 串起「派发 → 消费」，验证消费异常时消息进重试。</p>
 */
class TaskRunTerminalSyncListenerTest {

	private static final Long RUNTIME_RUN_ID = 88L;

	private static final Long TASK_RUN_ID = 5L;

	/** 与生产 SQL 谓词一致：只有未结束的运行才允许被推进到终态。 */
	private static final Set<String> ADVANCEABLE = Set.of(TaskRunStatus.PENDING.getValue(),
			TaskRunStatus.RUNNING.getValue());

	private final Map<Long, AgentTaskRun> ledger = new ConcurrentHashMap<>();

	private AgentTaskRunMapper taskRunMapper;

	private AgentTaskRunService taskRunService;

	private AgentTaskDeliveryService deliveryService;

	private TaskRunTerminalSyncListener listener;

	@BeforeEach
	void setUp() {
		ledger.clear();
		taskRunMapper = inMemoryTaskRunMapper();
		AgentTaskRunServiceImpl service = new AgentTaskRunServiceImpl(mock(AuthenticationContext.class),
				mock(AgentTaskDefinitionMapper.class), mock(TransactionTemplate.class),
				mock(com.sn68.agent.dataagent.runtime.durable.service.RuntimeRunService.class));
		// baseMapper 由 Spring 注入到 ServiceImpl，纯单测手动塞入
		ReflectionTestUtils.setField(service, "baseMapper", taskRunMapper);
		taskRunService = service;
		deliveryService = mock(AgentTaskDeliveryService.class);
		listener = new TaskRunTerminalSyncListener(taskRunService, taskRunMapper, new ObjectMapper(), deliveryService);
		seedLedger(TaskRunStatus.PENDING);
	}

	@Test
	void runSucceededEventMarksLedgerSuccess() {
		listener.onRunTerminal(terminalEvent("RUN_SUCCEEDED", "{\"state\":\"SUCCEEDED\"}"));

		AgentTaskRun taskRun = ledger.get(TASK_RUN_ID);
		assertEquals(TaskRunStatus.SUCCESS.getValue(), taskRun.getRunStatus());
		assertNotNull(taskRun.getFinishedTime(), "成功终态必须写结束时间");
		// agent_task_run 无结果摘要列（实体只有 error_message / started_time / finished_time），
		// 成功态不往错误列塞文案
		assertNull(taskRun.getErrorMessage());
		verify(deliveryService).recordWebInbox(org.mockito.ArgumentMatchers.argThat(row -> TASK_RUN_ID.equals(row.getId())));
	}

	@Test
	void runFailedEventWritesErrorCodeAndChineseMessage() {
		listener.onRunTerminal(terminalEvent("RUN_FAILED", "{\"state\":\"FAILED\",\"errorCode\":\"STEP_FAILED\"}"));

		AgentTaskRun taskRun = ledger.get(TASK_RUN_ID);
		assertEquals(TaskRunStatus.FAILED.getValue(), taskRun.getRunStatus());
		assertNotNull(taskRun.getFinishedTime());
		assertEquals("[STEP_FAILED] 运行时已收敛为失败终态", taskRun.getErrorMessage());
	}

	/** 任务侧没有独立超时态，超时事件按失败归集，错误码保留运行时口径。 */
	@Test
	void runTimedOutEventMarksLedgerFailed() {
		listener.onRunTerminal(terminalEvent("RUN_TIMED_OUT",
				"{\"state\":\"TIMED_OUT\",\"errorCode\":\"RUN_DEADLINE_EXCEEDED\"}"));

		AgentTaskRun taskRun = ledger.get(TASK_RUN_ID);
		assertEquals(TaskRunStatus.FAILED.getValue(), taskRun.getRunStatus());
		assertEquals("[RUN_DEADLINE_EXCEEDED] 运行时已收敛为超时终态", taskRun.getErrorMessage());
	}

	@Test
	void runCancelledEventMarksLedgerCancelled() {
		listener.onRunTerminal(terminalEvent("RUN_CANCELLED",
				"{\"state\":\"CANCELLED\",\"errorCode\":\"RUN_CANCELLED\"}"));

		AgentTaskRun taskRun = ledger.get(TASK_RUN_ID);
		assertEquals(TaskRunStatus.CANCELLED.getValue(), taskRun.getRunStatus());
		assertEquals("[RUN_CANCELLED] 运行时已收敛为取消终态", taskRun.getErrorMessage());
	}

	/** Outbox 是 at-least-once：同一事件重投、以及迟到的另一种终态事件都不得再次推进台账。 */
	@Test
	void repeatedDispatchDoesNotAdvanceLedgerTwice() {
		RuntimeOutboxEvent event = terminalEvent("RUN_SUCCEEDED", "{\"state\":\"SUCCEEDED\"}");
		listener.onRunTerminal(event);
		Instant firstFinishedTime = ledger.get(TASK_RUN_ID).getFinishedTime();

		listener.onRunTerminal(event);
		listener.onRunTerminal(terminalEvent("RUN_FAILED", "{\"state\":\"FAILED\",\"errorCode\":\"STEP_FAILED\"}"));

		AgentTaskRun taskRun = ledger.get(TASK_RUN_ID);
		assertEquals(TaskRunStatus.SUCCESS.getValue(), taskRun.getRunStatus());
		assertEquals(firstFinishedTime, taskRun.getFinishedTime());
		assertNull(taskRun.getErrorMessage());
	}

	/** SKIPPED 等其他链路写入的终态同样受谓词保护，不被运行终态事件覆盖。 */
	@Test
	void skippedLedgerIsNotOverwritten() {
		seedLedger(TaskRunStatus.SKIPPED);

		listener.onRunTerminal(terminalEvent("RUN_SUCCEEDED", "{\"state\":\"SUCCEEDED\"}"));

		assertEquals(TaskRunStatus.SKIPPED.getValue(), ledger.get(TASK_RUN_ID).getRunStatus());
	}

	/** 对话等非任务链路发起的运行没有台账行：跳过即可，既不报错也不误伤其他行。 */
	@Test
	void runWithoutTaskLedgerIsSkippedQuietly() {
		AgentTaskRunService spyService = mock(AgentTaskRunService.class);
		TaskRunTerminalSyncListener chatListener = new TaskRunTerminalSyncListener(spyService, taskRunMapper,
				new ObjectMapper(), deliveryService);

		chatListener.onRunTerminal(new RuntimeOutboxEvent("7", 3L, "999", "RUN_SUCCEEDED", "run-terminal:999:"
				+ "RUN_SUCCEEDED", "{\"state\":\"SUCCEEDED\"}"));

		verify(spyService, never()).markTerminalByRuntimeRun(anyLong(), any(), nullable(String.class));
		verify(deliveryService, never()).recordWebInbox(any());
		assertEquals(TaskRunStatus.PENDING.getValue(), ledger.get(TASK_RUN_ID).getRunStatus());
	}

	@Test
	void nonTerminalEventsAreIgnored() {
		listener.onRunTerminal(terminalEvent("RUN_STARTED", "{}"));
		listener.onRunTerminal(new RuntimeOutboxEvent("7", 3L, "88", "APPROVAL_DECIDED", "approval-decided:9",
				"{\"state\":\"APPROVED\"}"));
		listener.onRunTerminal(new RuntimeOutboxEvent("7", 3L, "88", null, "run-terminal:88", "{}"));
		listener.onRunTerminal(null);

		assertEquals(TaskRunStatus.PENDING.getValue(), ledger.get(TASK_RUN_ID).getRunStatus());
	}

	/** 缺运行ID 的终态消息属脏数据：失败关闭让它进重试直至 DEAD，不静默丢弃。 */
	@Test
	void terminalEventWithoutRunIdFailsClosed() {
		assertThrows(CheckedException.class, () -> listener.onRunTerminal(new RuntimeOutboxEvent("7", 3L, null,
				"RUN_SUCCEEDED", "run-terminal:?:RUN_SUCCEEDED", "{}")));
		assertThrows(CheckedException.class, () -> listener.onRunTerminal(new RuntimeOutboxEvent("7", 3L, "abc",
				"RUN_SUCCEEDED", "run-terminal:abc:RUN_SUCCEEDED", "{}")));
	}

	/** 负载损坏只丢失错误码精度，台账仍须收敛，不能因为一个脏 JSON 永远停在「在跑」。 */
	@Test
	void corruptPayloadStillConvergesLedger() {
		listener.onRunTerminal(terminalEvent("RUN_FAILED", "{not-json"));

		AgentTaskRun taskRun = ledger.get(TASK_RUN_ID);
		assertEquals(TaskRunStatus.FAILED.getValue(), taskRun.getRunStatus());
		assertEquals("[RUN_FAILED] 运行时已收敛为失败终态", taskRun.getErrorMessage());
	}

	/** 端到端：真实派发器投递终态消息 → 消费方回写台账 → 消息标记已派发。 */
	@Test
	void dispatchedTerminalMessageReachesLedger() {
		AgentRuntimeOutboxMapper outboxMapper = mock(AgentRuntimeOutboxMapper.class);
		RuntimeOutboxServiceImpl outboxService = outboxService(outboxMapper);
		when(outboxMapper.claimPending(any(Instant.class), anyInt())).thenReturn(List.of(terminalMessage()));
		when(outboxMapper.markDispatched(eq(1L), any(Instant.class))).thenReturn(1);

		assertEquals(1, outboxService.dispatchPending(10));

		assertEquals(TaskRunStatus.SUCCESS.getValue(), ledger.get(TASK_RUN_ID).getRunStatus());
	}

	/** 消费失败必须让 Outbox 记录进退避重试，而不是被吞掉后标记已派发。 */
	@Test
	void ledgerFailurePushesOutboxMessageIntoRetry() {
		AgentTaskRunService brokenService = mock(AgentTaskRunService.class);
		doThrow(CheckedException.fail("台账更新失败")).when(brokenService)
			.markTerminalByRuntimeRun(anyLong(), any(), nullable(String.class));
		listener = new TaskRunTerminalSyncListener(brokenService, taskRunMapper, new ObjectMapper(), deliveryService);
		AgentRuntimeOutboxMapper outboxMapper = mock(AgentRuntimeOutboxMapper.class);
		RuntimeOutboxServiceImpl outboxService = outboxService(outboxMapper);
		when(outboxMapper.claimPending(any(Instant.class), anyInt())).thenReturn(List.of(terminalMessage()));

		assertEquals(0, outboxService.dispatchPending(10));

		verify(outboxMapper).markFailed(eq(1L), eq("FAILED"), any(Instant.class), any());
		verify(outboxMapper, never()).markDispatched(anyLong(), any(Instant.class));
		assertEquals(TaskRunStatus.PENDING.getValue(), ledger.get(TASK_RUN_ID).getRunStatus());
	}

	/**
	 * 真实 Outbox 服务，事件发布直接驱动被测监听方（生产由 Spring 事件多播器完成同样的同步调用）。
	 */
	private RuntimeOutboxServiceImpl outboxService(AgentRuntimeOutboxMapper outboxMapper) {
		ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
		doAnswer(invocation -> {
			listener.onRunTerminal(invocation.getArgument(0));
			return null;
		}).when(publisher).publishEvent(any(Object.class));
		return new RuntimeOutboxServiceImpl(outboxMapper, publisher, new ObjectMapper());
	}

	private AgentRuntimeOutbox terminalMessage() {
		AgentRuntimeOutbox message = AgentRuntimeOutbox.builder()
			.tenantId("7")
			.runId(RUNTIME_RUN_ID)
			.messageType("RUN_SUCCEEDED")
			.targetChannel("EVENT")
			.payload("{\"eventKey\":\"run-terminal:88:RUN_SUCCEEDED\",\"workspaceId\":3,"
					+ "\"data\":{\"state\":\"SUCCEEDED\"}}")
			.state("PENDING")
			.retryCount(0)
			.build();
		message.setId(1L);
		return message;
	}

	private RuntimeOutboxEvent terminalEvent(String eventType, String payloadJson) {
		return new RuntimeOutboxEvent("7", 3L, String.valueOf(RUNTIME_RUN_ID), eventType,
				"run-terminal:" + RUNTIME_RUN_ID + ":" + eventType, payloadJson);
	}

	private void seedLedger(TaskRunStatus status) {
		AgentTaskRun taskRun = AgentTaskRun.builder()
			.tenantId("7")
			.definitionId(2L)
			.triggerId(4L)
			.triggerType("SCHEDULE")
			.idempotencyKey("SCHEDULE:4:2026-08-13T10:00:00Z")
			.runStatus(status.getValue())
			.runtimeRunId(RUNTIME_RUN_ID)
			.servicePrincipal("sp-1")
			.build();
		taskRun.setId(TASK_RUN_ID);
		ledger.put(TASK_RUN_ID, taskRun);
	}

	/**
	 * 内存台账：{@code findByRuntimeRunId} 与 {@code markTerminalByRuntimeRunId} 按生产语义作答，
	 * 后者复刻状态谓词，重复消费只有第一次命中。
	 */
	private AgentTaskRunMapper inMemoryTaskRunMapper() {
		AgentTaskRunMapper mapper = mock(AgentTaskRunMapper.class);
		doAnswer(invocation -> findByRuntimeRunId(invocation.getArgument(0))).when(mapper)
			.findByRuntimeRunId(anyLong());
		doAnswer(invocation -> markTerminal(invocation.getArgument(0), invocation.getArgument(1),
				invocation.getArgument(2), invocation.getArgument(3))).when(mapper)
					.markTerminalByRuntimeRunId(anyLong(), any(), nullable(String.class), any(Instant.class));
		return mapper;
	}

	private AgentTaskRun findByRuntimeRunId(Long runtimeRunId) {
		return ledger.values().stream()
			.filter(row -> row.getRuntimeRunId().equals(runtimeRunId))
			.findFirst()
			.map(row -> BeanUtil.copyProperties(row, AgentTaskRun.class))
			.orElse(null);
	}

	private int markTerminal(Long runtimeRunId, String toStatus, String errorMessage, Instant finishedTime) {
		AgentTaskRun row = findLedgerRow(runtimeRunId);
		if (row == null || !ADVANCEABLE.contains(row.getRunStatus())) {
			return 0;
		}
		row.setRunStatus(toStatus);
		row.setErrorMessage(errorMessage);
		row.setFinishedTime(finishedTime);
		return 1;
	}

	private AgentTaskRun findLedgerRow(Long runtimeRunId) {
		return ledger.values().stream()
			.filter(row -> row.getRuntimeRunId().equals(runtimeRunId))
			.findFirst()
			.orElse(null);
	}

}
