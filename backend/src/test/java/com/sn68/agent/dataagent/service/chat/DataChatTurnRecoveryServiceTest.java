/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.service.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.agentscope.session.AgentRuntimeRegistry;
import com.sn68.agent.dataagent.entity.DataChatTurn;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.repository.DataChatTurnMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DataChatTurnRecoveryServiceTest {

	private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");

	private final DataChatTurnMapper turnMapper = mock(DataChatTurnMapper.class);

	private final AgentRuntimeRegistry runtimeRegistry = mock(AgentRuntimeRegistry.class);

	private final DataAgentProperties properties = new DataAgentProperties();

	private final DataChatTurnRecoveryService service = new DataChatTurnRecoveryService(turnMapper, runtimeRegistry,
			properties);

	@Test
	void skipsRecoveryWhenDisabled() {
		assertEquals(0, service.recoverStaleRunningTurns(NOW));
		verify(turnMapper, never()).findStaleRunning(any(), anyInt());
	}

	@Test
	void recoversInactiveTurnWithConditionalUpdate() {
		properties.getRuntime().setStaleTurnRecoveryEnabled(true);
		properties.getRuntime().setTotalTimeout(Duration.ofSeconds(120));
		properties.getRuntime().setStaleTurnGrace(Duration.ofSeconds(60));
		DataChatTurn turn = DataChatTurn.builder()
			.id(1L)
			.sessionId(100L)
			.threadId("100")
			.runtimeRequestId("runtime-1")
			.status(DataChatTurnService.STATUS_RUNNING)
			.startedAt(NOW.minusSeconds(300))
			.build();
		when(turnMapper.findStaleRunning(NOW.minusSeconds(180), 100)).thenReturn(List.of(turn));
		when(runtimeRegistry.isActive("100", "runtime-1")).thenReturn(false);
		when(turnMapper.markStaleFailedIfRunning(eq(1L), eq(NOW.minusSeconds(180)),
				eq(DataChatTurnRecoveryService.ORPHANED_ERROR), eq(NOW), eq(300_000L)))
			.thenReturn(1);

		assertEquals(1, service.recoverStaleRunningTurns(NOW));
	}

	@Test
	void preservesTurnThatIsStillActive() {
		properties.getRuntime().setStaleTurnRecoveryEnabled(true);
		DataChatTurn turn = DataChatTurn.builder()
			.id(1L)
			.threadId("100")
			.runtimeRequestId("runtime-1")
			.startedAt(NOW.minusSeconds(300))
			.build();
		when(turnMapper.findStaleRunning(any(), anyInt())).thenReturn(List.of(turn));
		when(runtimeRegistry.isActive("100", "runtime-1")).thenReturn(true);

		assertEquals(0, service.recoverStaleRunningTurns(NOW));
		verify(turnMapper, never()).markStaleFailedIfRunning(any(), any(), any(), any(), any(Long.class));
	}

}
