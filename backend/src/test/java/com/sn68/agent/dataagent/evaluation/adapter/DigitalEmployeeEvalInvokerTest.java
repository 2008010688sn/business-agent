/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.adapter;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.context.DataAgentAsyncContextBridge;
import com.sn68.agent.dataagent.employee.auth.EmployeeExecutionContextClient;
import com.sn68.agent.dataagent.employee.config.DigitalEmployeeProperties;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeDeployment;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.employee.service.EmployeeDeploymentService;
import com.sn68.agent.dataagent.entity.DataChatSession;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalCase;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalSubject;
import com.sn68.agent.dataagent.repository.DataChatTurnMapper;
import com.sn68.agent.dataagent.service.agent.AgentInvocationService;
import com.sn68.agent.dataagent.service.chat.DataChatSessionService;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DigitalEmployeeEvalInvokerTest {

	private final DigitalEmployeeMapper employeeMapper = mock(DigitalEmployeeMapper.class);

	private final EmployeeDeploymentService deploymentService = mock(EmployeeDeploymentService.class);

	private final AgentInvocationService invocationService = mock(AgentInvocationService.class);

	private final DataChatSessionService chatSessionService = mock(DataChatSessionService.class);

	private final DataChatTurnMapper turnMapper = mock(DataChatTurnMapper.class);

	private final DigitalEmployeeEvalInvoker invoker = new DigitalEmployeeEvalInvoker(employeeMapper, deploymentService,
			invocationService, chatSessionService, turnMapper, mock(EmployeeExecutionContextClient.class),
			mock(DataAgentAsyncContextBridge.class), new DigitalEmployeeProperties());

	@BeforeEach
	void setUp() {
		DigitalEmployee employee = DigitalEmployee.builder().id(77L).tenantId("1").employeeName("评测员工").status("DRAFT")
			.build();
		when(employeeMapper.findByIdAndTenantId(77L, "1")).thenReturn(employee);
		DataChatSession session = new DataChatSession();
		session.setId(501L);
		when(chatSessionService.createSession(anyLong(), anyString(), nullable(Long.class), anyString(),
				nullable(String.class), nullable(String.class), nullable(String.class), anyString()))
			.thenReturn(session);
		when(invocationService.invoke(any(AgentRequest.class))).thenReturn("SANDBOX回答");
	}

	@Test
	void failsClosedWhenSandboxDeploymentMissing() {
		when(deploymentService.findCurrent(77L, "SANDBOX")).thenReturn(null);

		EvalInvocationResult result = invoker.invoke(prepared(), 77L, null);

		assertFalse(result.success());
		assertTrue(result.errorMessage().contains("SANDBOX"));
		verify(invocationService, never()).invoke(any());
		verify(deploymentService, never()).findCurrent(77L, "PRODUCTION");
	}

	@Test
	void pinsSandboxReleaseAndDoesNotReadProduction() {
		DigitalEmployeeDeployment sandbox = DigitalEmployeeDeployment.builder().activeReleaseId(88L).build();
		when(deploymentService.findCurrent(77L, "SANDBOX")).thenReturn(sandbox);

		EvalInvocationResult result = invoker.invoke(prepared(), 77L, "候选提示");

		assertTrue(result.success());
		assertEquals("SANDBOX回答", result.answer());
		ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);
		verify(invocationService).invoke(captor.capture());
		assertEquals(88L, captor.getValue().getReleaseId());
		assertEquals("DIGITAL_EMPLOYEE", captor.getValue().getOwnerType());
		assertEquals(77L, captor.getValue().getOwnerId());
		assertEquals("EVAL", captor.getValue().getRequestSource());
		assertEquals("DRY_RUN", captor.getValue().getExecutionIntent());
		assertEquals("候选提示", captor.getValue().getEvalSystemInstructionOverride());
		assertTrue(captor.getValue().isIsolatedMemory());
		verify(deploymentService, never()).findCurrent(77L, "PRODUCTION");
	}

	private EvalInvocationPrepared prepared() {
		DataAgentEvalSubject subject = new DataAgentEvalSubject();
		subject.setSubjectId("77");
		DataAgentEvalCase evalCase = new DataAgentEvalCase();
		evalCase.setId(5L);
		evalCase.setCaseName("真跑用例");
		evalCase.setUserInput("今日发运量");
		DataAgentEvalRun run = DataAgentEvalRun.builder().id(9L).tenantId("1").executionIntent("DRY_RUN").build();
		return new EvalInvocationPrepared(subject, evalCase, run, Duration.ofSeconds(30));
	}

}
