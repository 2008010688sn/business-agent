/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeEvent;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeEventType;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeEventMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 带 fence 的事件追加：缺 fence 拒绝、插入成功、event_key 幂等、fence 失效区分，以及无 fence 的原 append 仍可用。
 */
class RuntimeEventServiceImplFenceTest {

	private static final String TENANT = "7";

	private static final Long RUN_ID = 88L;

	private static final String EVENT_KEY = "assistant-delta:1";

	private static final Long FENCE_TOKEN = 3L;

	private static final String LEASE_OWNER = "worker-a";

	private AgentRuntimeEventMapper eventMapper;

	private RuntimeEventServiceImpl service;

	@BeforeEach
	void setUp() {
		eventMapper = mock(AgentRuntimeEventMapper.class);
		service = new RuntimeEventServiceImpl(eventMapper, new ObjectMapper());
	}

	@Test
	void appendFencedRejectsMissingFence() {
		CheckedException missingToken = assertThrows(CheckedException.class,
				() -> service.appendFenced(TENANT, RUN_ID, EVENT_KEY, RuntimeEventType.ASSISTANT_DELTA, null,
						Map.of("text", "hi"), null, LEASE_OWNER));
		assertTrue(missingToken.getMessage().contains("事件追加缺少 fence"));

		CheckedException missingOwner = assertThrows(CheckedException.class,
				() -> service.appendFenced(TENANT, RUN_ID, EVENT_KEY, RuntimeEventType.ASSISTANT_DELTA, null,
						Map.of("text", "hi"), FENCE_TOKEN, null));
		assertTrue(missingOwner.getMessage().contains("事件追加缺少 fence"));

		CheckedException blankOwner = assertThrows(CheckedException.class,
				() -> service.appendFenced(TENANT, RUN_ID, EVENT_KEY, RuntimeEventType.ASSISTANT_DELTA, null,
						Map.of("text", "hi"), FENCE_TOKEN, " "));
		assertTrue(blankOwner.getMessage().contains("事件追加缺少 fence"));

		verify(eventMapper, never()).appendWithSeqAndFence(any(), any(), any(), any(), any(), any(), any(), any(),
				any());
	}

	@Test
	void appendFencedReturnsTrueWhenInserted() {
		when(eventMapper.appendWithSeqAndFence(eq(TENANT), eq(RUN_ID), eq(EVENT_KEY), eq("ASSISTANT_DELTA"),
				nullable(String.class), any(), any(Instant.class), eq(FENCE_TOKEN), eq(LEASE_OWNER))).thenReturn(1);

		boolean inserted = service.appendFenced(TENANT, RUN_ID, EVENT_KEY, RuntimeEventType.ASSISTANT_DELTA, null,
				Map.of("text", "hi"), FENCE_TOKEN, LEASE_OWNER);

		assertTrue(inserted);
		verify(eventMapper, never()).findByRunIdAndEventKey(any(), any());
	}

	@Test
	void appendFencedReturnsFalseWhenEventKeyExists() {
		when(eventMapper.appendWithSeqAndFence(eq(TENANT), eq(RUN_ID), eq(EVENT_KEY), eq("ASSISTANT_DELTA"),
				nullable(String.class), any(), any(Instant.class), eq(FENCE_TOKEN), eq(LEASE_OWNER))).thenReturn(0);
		when(eventMapper.findByRunIdAndEventKey(RUN_ID, EVENT_KEY)).thenReturn(AgentRuntimeEvent.builder()
			.runId(RUN_ID)
			.eventKey(EVENT_KEY)
			.build());

		boolean inserted = service.appendFenced(TENANT, RUN_ID, EVENT_KEY, RuntimeEventType.ASSISTANT_DELTA, null,
				Map.of("text", "hi"), FENCE_TOKEN, LEASE_OWNER);

		assertFalse(inserted);
	}

	@Test
	void appendFencedFailsWhenFenceMismatchAndEventMissing() {
		when(eventMapper.appendWithSeqAndFence(eq(TENANT), eq(RUN_ID), eq(EVENT_KEY), eq("ASSISTANT_DELTA"),
				nullable(String.class), any(), any(Instant.class), eq(FENCE_TOKEN), eq(LEASE_OWNER))).thenReturn(0);
		when(eventMapper.findByRunIdAndEventKey(RUN_ID, EVENT_KEY)).thenReturn(null);

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.appendFenced(TENANT, RUN_ID, EVENT_KEY, RuntimeEventType.ASSISTANT_DELTA, null,
						Map.of("text", "hi"), FENCE_TOKEN, LEASE_OWNER));

		assertTrue(ex.getMessage().contains("事件追加被拒绝：fence 已失效或租约不属于当前写者"));
	}

	@Test
	void appendStillWorksWithoutFence() {
		when(eventMapper.appendWithSeq(eq(TENANT), eq(RUN_ID), eq(EVENT_KEY), eq("RUN_CREATED"), nullable(String.class),
				any(), any(Instant.class))).thenReturn(1);

		boolean inserted = service.append(TENANT, RUN_ID, EVENT_KEY, RuntimeEventType.RUN_CREATED, null,
				Map.of("text", "hi"));

		assertTrue(inserted);
		verify(eventMapper, never()).appendWithSeqAndFence(any(), any(), any(), any(), any(), any(), any(), any(),
				any());
	}

}
