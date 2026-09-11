/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeOutbox;
import com.sn68.agent.dataagent.runtime.durable.event.RuntimeOutboxEvent;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeOutboxMapper;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeOutboxService.OutboxAppend;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Outbox 服务测试：信封落库、派发成功标记、失败退避重试与重试耗尽置 DEAD。
 */
class RuntimeOutboxServiceImplTest {

	private AgentRuntimeOutboxMapper outboxMapper;

	private ApplicationEventPublisher eventPublisher;

	private RuntimeOutboxServiceImpl service;

	@BeforeEach
	void setUp() {
		outboxMapper = mock(AgentRuntimeOutboxMapper.class);
		eventPublisher = mock(ApplicationEventPublisher.class);
		service = new RuntimeOutboxServiceImpl(outboxMapper, eventPublisher, new ObjectMapper());
	}

	@Test
	void appendPersistsEnvelopeWithEventKey() {
		service.append(new OutboxAppend("7", 3L, 88L, null, "APPROVAL_REQUESTED", "approval-requested:11",
				Map.of("approvalId", 11)));

		ArgumentCaptor<AgentRuntimeOutbox> captor = ArgumentCaptor.forClass(AgentRuntimeOutbox.class);
		verify(outboxMapper).insert(captor.capture());
		AgentRuntimeOutbox inserted = captor.getValue();
		assertEquals("APPROVAL_REQUESTED", inserted.getMessageType());
		assertEquals("PENDING", inserted.getState());
		assertEquals(0, inserted.getRetryCount());
		assertEquals(88L, inserted.getRunId());
		assertTrue(inserted.getPayload().contains("\"eventKey\":\"approval-requested:11\""));
		assertTrue(inserted.getPayload().contains("\"workspaceId\":3"));
	}

	@Test
	void appendRejectsMissingEventKey() {
		assertThrows(CheckedException.class,
				() -> service.append(new OutboxAppend("7", null, null, null, "APPROVAL_REQUESTED", " ", Map.of())));
		verify(outboxMapper, never()).insert(any(AgentRuntimeOutbox.class));
	}

	@Test
	void dispatchPublishesContractEventAndMarksDispatched() {
		AgentRuntimeOutbox message = outboxRow(0);
		when(outboxMapper.claimPending(any(Instant.class), anyInt())).thenReturn(List.of(message));
		when(outboxMapper.markDispatched(eq(1L), any(Instant.class))).thenReturn(1);

		int dispatched = service.dispatchPending(50);

		assertEquals(1, dispatched);
		ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
		verify(eventPublisher).publishEvent(captor.capture());
		RuntimeOutboxEvent event = (RuntimeOutboxEvent) captor.getValue();
		assertEquals("7", event.tenantId());
		assertEquals(3L, event.workspaceId());
		assertEquals("88", event.runId());
		assertEquals("APPROVAL_DECIDED", event.eventType());
		assertEquals("approval-decided:11", event.eventKey());
		assertTrue(event.payloadJson().contains("\"approvalId\":11"));
	}

	@Test
	void dispatchFailureSchedulesRetryWithBackoff() {
		AgentRuntimeOutbox message = outboxRow(0);
		when(outboxMapper.claimPending(any(Instant.class), anyInt())).thenReturn(List.of(message));
		doThrow(new RuntimeException("listener boom")).when(eventPublisher).publishEvent(any(Object.class));

		int dispatched = service.dispatchPending(50);

		assertEquals(0, dispatched);
		ArgumentCaptor<Instant> nextRetry = ArgumentCaptor.forClass(Instant.class);
		verify(outboxMapper).markFailed(eq(1L), eq("FAILED"), nextRetry.capture(), any());
		assertNotNull(nextRetry.getValue());
		verify(outboxMapper, never()).markDispatched(anyLong(), any(Instant.class));
	}

	@Test
	void dispatchFailureAtRetryLimitMarksDead() {
		AgentRuntimeOutbox message = outboxRow(4);
		when(outboxMapper.claimPending(any(Instant.class), anyInt())).thenReturn(List.of(message));
		doThrow(new RuntimeException("listener boom")).when(eventPublisher).publishEvent(any(Object.class));

		service.dispatchPending(50);

		ArgumentCaptor<Instant> nextRetry = ArgumentCaptor.forClass(Instant.class);
		verify(outboxMapper).markFailed(eq(1L), eq("DEAD"), nextRetry.capture(), any());
		assertNull(nextRetry.getValue());
	}

	@Test
	void slowListenerTimesOutIntoRetryWithoutBlockingNextMessage() {
		service.listenerTimeout = Duration.ofMillis(200);
		AgentRuntimeOutbox slow = outboxRow(0);
		AgentRuntimeOutbox fast = outboxRow(0);
		fast.setId(2L);
		fast.setPayload("{\"eventKey\":\"approval-decided:12\",\"workspaceId\":3,\"data\":{\"approvalId\":12}}");
		when(outboxMapper.claimPending(any(Instant.class), anyInt())).thenReturn(List.of(slow, fast));
		when(outboxMapper.markDispatched(eq(2L), any(Instant.class))).thenReturn(1);
		doAnswer(invocation -> {
			RuntimeOutboxEvent event = invocation.getArgument(0);
			if ("approval-decided:11".equals(event.eventKey())) {
				Thread.sleep(5_000);
			}
			return null;
		}).when(eventPublisher).publishEvent(any(Object.class));

		int dispatched = service.dispatchPending(50);

		assertEquals(1, dispatched);
		verify(outboxMapper).markFailed(eq(1L), eq("FAILED"), any(Instant.class), any());
		verify(outboxMapper).markDispatched(eq(2L), any(Instant.class));
	}

	@Test
	void batchBudgetStopsProcessingAndLeavesRestPending() {
		service.batchTimeBudget = Duration.ZERO;
		when(outboxMapper.claimPending(any(Instant.class), anyInt()))
			.thenReturn(List.of(outboxRow(0), outboxRow(0)));

		int dispatched = service.dispatchPending(50);

		assertEquals(0, dispatched);
		verify(eventPublisher, never()).publishEvent(any(Object.class));
		verify(outboxMapper, never()).markDispatched(anyLong(), any(Instant.class));
		verify(outboxMapper, never()).markFailed(anyLong(), any(), any(), any());
	}

	@Test
	void corruptEnvelopeGoesThroughFailurePath() {
		AgentRuntimeOutbox message = outboxRow(0);
		message.setPayload("{\"data\":{}}");
		when(outboxMapper.claimPending(any(Instant.class), anyInt())).thenReturn(List.of(message));

		int dispatched = service.dispatchPending(50);

		assertEquals(0, dispatched);
		verify(eventPublisher, never()).publishEvent(any(Object.class));
		verify(outboxMapper).markFailed(eq(1L), eq("FAILED"), any(Instant.class), any());
	}

	private AgentRuntimeOutbox outboxRow(int retryCount) {
		AgentRuntimeOutbox message = AgentRuntimeOutbox.builder()
			.tenantId("7")
			.runId(88L)
			.messageType("APPROVAL_DECIDED")
			.targetChannel("EVENT")
			.payload("{\"eventKey\":\"approval-decided:11\",\"workspaceId\":3,\"data\":{\"approvalId\":11}}")
			.state("PENDING")
			.retryCount(retryCount)
			.build();
		message.setId(1L);
		return message;
	}

}
