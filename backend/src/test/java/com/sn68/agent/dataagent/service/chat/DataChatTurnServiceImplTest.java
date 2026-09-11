/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.entity.DataChatTurn;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore;
import com.sn68.agent.dataagent.observability.SessionTraceStore;
import com.sn68.agent.dataagent.repository.AgentOrchestrationRunMapper;
import com.sn68.agent.dataagent.repository.AgentOrchestrationStepMapper;
import com.sn68.agent.dataagent.repository.DataChatMessageMapper;
import com.sn68.agent.dataagent.repository.DataChatSessionMapper;
import com.sn68.agent.dataagent.repository.DataChatTurnMapper;
import com.sn68.agent.dataagent.routing.RouteUnavailableException;
import com.sn68.agent.dataagent.routing.model.RouteDecision;
import com.sn68.agent.dataagent.routing.model.RouteDegradeMode;
import com.sn68.agent.dataagent.routing.model.RouteTiming;
import com.sn68.agent.dataagent.service.permission.DataAgentThinkingPermissionService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DataChatTurnServiceImplTest {

	private final DataChatTurnMapper turnMapper = mock(DataChatTurnMapper.class);

	private final DataChatTurnServiceImpl service = new DataChatTurnServiceImpl(turnMapper,
			mock(DataChatSessionMapper.class), mock(DataChatMessageMapper.class),
			mock(AgentOrchestrationRunMapper.class), mock(AgentOrchestrationStepMapper.class),
			mock(AnswerTraceExplainStore.class), mock(SessionTraceStore.class),
			mock(DataAgentThinkingPermissionService.class), mock(ObjectMapper.class));

	@Test
	void preservesValidRouteReasonCode() {
		DataChatTurn turn = completeTurn("MODEL_SELECT");

		assertEquals("MODEL_SELECT", turn.getRouteReasonCode());
		assertEquals(DataChatTurnService.STATUS_SUCCESS, turn.getStatus());
	}

	@Test
	void preservesSixtyFourCharacterRouteReasonCode() {
		String reasonCode = "A".repeat(64);

		assertEquals(reasonCode, completeTurn(reasonCode).getRouteReasonCode());
	}

	@Test
	void replacesOverflowRouteReasonCode() {
		DataChatTurn turn = completeTurn("A".repeat(65));

		assertEquals("INVALID_ROUTE_REASON_CODE", turn.getRouteReasonCode());
		assertEquals(DataChatTurnService.STATUS_SUCCESS, turn.getStatus());
	}

	@Test
	void replacesNaturalLanguageRouteReason() {
		DataChatTurn turn = completeTurn("用户明确表达下单需求，与需求单创建场景匹配");

		assertEquals("INVALID_ROUTE_REASON_CODE", turn.getRouteReasonCode());
		assertEquals(DataChatTurnService.STATUS_SUCCESS, turn.getStatus());
	}

	@Test
	void completeFailedTurnPersistsUserVisibleAnswerAndFailedStatus() {
		AgentRequest request = AgentRequest.builder().agentId("1").threadId("100")
			.runtimeRequestId("runtime-failed").routedSkillCode("demand-create")
			.routeDecision("SELECT").routeReasonCode("MODEL_SELECT").build();
		DataChatTurn existing = DataChatTurn.builder().id(2L).sessionId(100L).threadId("100")
			.runtimeRequestId("runtime-failed").status(DataChatTurnService.STATUS_RUNNING)
			.startedAt(Instant.parse("2026-07-19T06:12:47Z")).build();
		when(turnMapper.findBySessionIdAndRuntimeRequestId(100L, "runtime-failed")).thenReturn(existing);

		service.completeFailedTurn(request, "创建需求单失败：缺少需求号",
				new IllegalStateException("创建需求单失败：缺少需求号"), null);

		ArgumentCaptor<DataChatTurn> captor = ArgumentCaptor.forClass(DataChatTurn.class);
		verify(turnMapper).updateById(captor.capture());
		assertEquals(DataChatTurnService.STATUS_FAILED, captor.getValue().getStatus());
		assertEquals("创建需求单失败：缺少需求号", captor.getValue().getAnswer());
	}

	@Test
	void failTurnPersistsRouteReasonCodeSoOperatorsCanLocateTheRouteFailure() {
		RouteDecision route = RouteDecision.unavailable("VECTOR_TIMEOUT", RouteDegradeMode.DEGRADED_VECTOR,
				RouteTiming.empty());
		AgentRequest request = AgentRequest.builder().agentId("1").threadId("100")
			.runtimeRequestId("runtime-route-failed").routeDecision("ROUTE_UNAVAILABLE")
			.routeReasonCode("VECTOR_TIMEOUT").routeResult(route).build();
		DataChatTurn existing = DataChatTurn.builder().id(3L).sessionId(100L).threadId("100")
			.runtimeRequestId("runtime-route-failed").status(DataChatTurnService.STATUS_RUNNING)
			.startedAt(Instant.parse("2026-07-19T06:12:47Z")).build();
		when(turnMapper.findBySessionIdAndRuntimeRequestId(100L, "runtime-route-failed")).thenReturn(existing);

		service.failTurn(request, new RouteUnavailableException("VECTOR_TIMEOUT"));

		verify(turnMapper).updateStatus(eq(100L), eq("runtime-route-failed"), eq(DataChatTurnService.STATUS_FAILED),
				eq("VECTOR_TIMEOUT"), any(Instant.class), anyLong(), eq("VECTOR_TIMEOUT"), eq("DEGRADED_VECTOR"));
	}

	@Test
	void failTurnWithoutRouteDecisionDoesNotOverwriteRouteDiagnostics() {
		AgentRequest request = AgentRequest.builder().agentId("1").threadId("100")
			.runtimeRequestId("runtime-early-failed").build();
		DataChatTurn existing = DataChatTurn.builder().id(4L).sessionId(100L).threadId("100")
			.runtimeRequestId("runtime-early-failed").status(DataChatTurnService.STATUS_RUNNING)
			.startedAt(Instant.parse("2026-07-19T06:12:47Z")).build();
		when(turnMapper.findBySessionIdAndRuntimeRequestId(100L, "runtime-early-failed")).thenReturn(existing);

		service.failTurn(request, new IllegalStateException("boom"));

		verify(turnMapper).updateStatus(eq(100L), eq("runtime-early-failed"), eq(DataChatTurnService.STATUS_FAILED),
				eq("boom"), any(Instant.class), anyLong(), eq(null), eq(null));
	}

	private DataChatTurn completeTurn(String reasonCode) {
		AgentRequest request = AgentRequest.builder()
			.agentId("1")
			.threadId("100")
			.runtimeRequestId("runtime-1")
			.routedSkillCode("demand-create")
			.routeDecision("SELECT")
			.routeReasonCode(reasonCode)
			.build();
		DataChatTurn existing = DataChatTurn.builder()
			.id(1L)
			.sessionId(100L)
			.threadId("100")
			.runtimeRequestId("runtime-1")
			.status(DataChatTurnService.STATUS_RUNNING)
			.startedAt(Instant.parse("2026-07-19T06:12:47Z"))
			.build();
		when(turnMapper.findBySessionIdAndRuntimeRequestId(100L, "runtime-1")).thenReturn(existing);

		service.completeTurn(request, "请选择二级项目", 0L, 0, 0, null);

		ArgumentCaptor<DataChatTurn> captor = ArgumentCaptor.forClass(DataChatTurn.class);
		verify(turnMapper).updateById(captor.capture());
		return captor.getValue();
	}

}
