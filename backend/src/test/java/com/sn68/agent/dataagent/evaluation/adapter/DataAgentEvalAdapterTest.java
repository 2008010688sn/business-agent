package com.sn68.agent.dataagent.evaluation.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.entity.DataChatSession;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalCase;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalSubject;
import com.sn68.agent.dataagent.repository.DataChatTurnMapper;
import com.sn68.agent.dataagent.service.agent.AgentInvocationService;
import com.sn68.agent.dataagent.service.chat.DataChatSessionService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataAgentEvalAdapterTest {

	private final AgentInvocationService invocationService = mock(AgentInvocationService.class);

	private final DataChatSessionService chatSessionService = mock(DataChatSessionService.class);

	private final DataChatTurnMapper turnMapper = mock(DataChatTurnMapper.class);

	private final DataAgentEvalAdapter adapter = new DataAgentEvalAdapter(invocationService, chatSessionService,
			turnMapper, new ObjectMapper());

	@Test
	void invokePassesRunSnapshotsToAgentRequest() {
		DataChatSession session = new DataChatSession();
		session.setId(55L);
		when(chatSessionService.createSession(7L, "评估-快照用例", 100L)).thenReturn(session);
		when(invocationService.invoke(any(AgentRequest.class))).thenReturn("命中答案");
		ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);

		EvalInvocationResult result = adapter.invoke(new EvalInvocationPrepared(subject(), evalCase(), run(),
				Duration.ofSeconds(3)));

		verify(invocationService).invoke(captor.capture());
		AgentRequest request = captor.getValue();
		assertTrue(result.success());
		assertEquals("100", request.getUserIdSnapshot());
		assertEquals("tenant-1", request.getTenantIdSnapshot());
		assertEquals("tenant-code", request.getTenantCodeSnapshot());
		assertEquals("pc-web", request.getClientIdSnapshot());
		assertEquals(List.of("team-a", "team-b"), request.getTeamIdsSnapshot());
		assertEquals("EVAL", request.getRequestSource());
		assertEquals("候选提示", request.getEvalSystemInstructionOverride());
		assertTrue(request.isIsolatedMemory());
		assertEquals(Duration.ofSeconds(3), request.getRuntimeTimeout());
	}

	private DataAgentEvalSubject subject() {
		DataAgentEvalSubject subject = new DataAgentEvalSubject();
		subject.setSubjectId("7");
		return subject;
	}

	private DataAgentEvalCase evalCase() {
		DataAgentEvalCase evalCase = new DataAgentEvalCase();
		evalCase.setId(9L);
		evalCase.setCaseName("快照用例");
		evalCase.setUserInput("查询订单");
		return evalCase;
	}

	private DataAgentEvalRun run() {
		DataAgentEvalRun run = new DataAgentEvalRun();
		run.setId(3L);
		run.setTenantId("tenant-1");
		run.setTenantCode("tenant-code");
		run.setUserIdSnapshot("100");
		run.setClientIdSnapshot("pc-web");
		run.setTeamIdsJson("[\"team-a\",\"team-b\"]");
		run.setAgentConfigSnapshotJson("{\"evalMode\":\"INVOKE\",\"candidateOverlay\":\"{\\\"prompt\\\":\\\"候选提示\\\"}\"}");
		return run;
	}

}
