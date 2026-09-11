/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sn68.agent.dataagent.employee.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.runtime.ConversationAuthorizationGuard;
import com.sn68.agent.dataagent.agentscope.service.DataAgentService;
import com.sn68.agent.dataagent.agentscope.vo.AgentResponse;
import com.sn68.agent.dataagent.authorization.model.AuthorizationAction;
import com.sn68.agent.dataagent.context.DataAgentAsyncContextBridge;
import com.sn68.agent.dataagent.context.DataAgentOutboundContext;
import com.sn68.agent.dataagent.employee.auth.EmployeeAuthTokenContext;
import com.sn68.agent.dataagent.employee.auth.EmployeeExecutionContextClient;
import com.sn68.agent.dataagent.employee.config.DigitalEmployeeProperties;
import com.sn68.agent.dataagent.employee.dto.EmployeeConversationReq;
import com.sn68.agent.dataagent.employee.dto.EmployeeConversationResp;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeDeployment;
import com.sn68.agent.dataagent.employee.enums.EmployeeStatusDict;
import com.sn68.agent.dataagent.employee.enums.PrincipalProvisionStatusDict;
import com.sn68.agent.dataagent.employee.guard.EmployeeConversationGuard;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.employee.service.EmployeeDeploymentService;
import com.sn68.agent.dataagent.entity.DataChatSession;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunCreateReq;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunResp;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeRunService;
import com.sn68.agent.dataagent.service.chat.DataChatSessionService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * 对话 Facade 测试：rollout=false 走 MODEL_ONLY（不触碰 IAM）；
 * rollout=true + Principal READY + 生产 Release 走 PRINCIPAL。
 */
class EmployeeConversationServiceImplTest {

	private static final String TENANT_ID = "7";

	private static final Long EMPLOYEE_ID = 9L;

	private final DigitalEmployeeMapper employeeMapper = mock(DigitalEmployeeMapper.class);

	private final EmployeeDeploymentService deploymentService = mock(EmployeeDeploymentService.class);

	private final ConversationAuthorizationGuard conversationAuthorizationGuard = mock(
			ConversationAuthorizationGuard.class);

	private final EmployeeConversationGuard employeeConversationGuard = mock(EmployeeConversationGuard.class);

	private final EmployeeExecutionContextClient executionContextClient = mock(EmployeeExecutionContextClient.class);

	private final DataAgentService dataAgentService = mock(DataAgentService.class);

	private final DataChatSessionService chatSessionService = mock(DataChatSessionService.class);

	private final DataAgentAsyncContextBridge asyncContextBridge = mock(DataAgentAsyncContextBridge.class);

	private final DigitalEmployeeProperties properties = new DigitalEmployeeProperties();

	private final AuthenticationContext authenticationContext = mock(AuthenticationContext.class);

	private final RuntimeRunService runtimeRunService = mock(RuntimeRunService.class);

	private final DataAgentProperties dataAgentProperties = new DataAgentProperties();

	private EmployeeConversationServiceImpl service;

	@BeforeEach
	void setUp() {
		service = new EmployeeConversationServiceImpl(employeeMapper, deploymentService,
				conversationAuthorizationGuard, employeeConversationGuard, executionContextClient, dataAgentService,
				chatSessionService, asyncContextBridge, properties, authenticationContext, runtimeRunService,
				dataAgentProperties);
		when(authenticationContext.tenantId()).thenReturn(TENANT_ID);
		when(authenticationContext.userId()).thenReturn("3");
		DataChatSession session = new DataChatSession(EMPLOYEE_ID, "数字员工-测试员工", "active", 3L);
		session.setId(88L);
		when(chatSessionService.createSession(any(), anyString(), any(), anyString(), any(), any(), any(), any()))
			.thenReturn(session);
		when(asyncContextBridge.snapshotForDelegatedToken(anyString(), any(DataAgentOutboundContext.Snapshot.class)))
			.thenReturn(DataAgentAsyncContextBridge.Snapshot.empty());
		when(asyncContextBridge.supplyWith(any(), any()))
			.thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
	}

	private DigitalEmployee employee(String status, String principalStatus, String principalId) {
		return DigitalEmployee.builder()
			.id(EMPLOYEE_ID)
			.tenantId(TENANT_ID)
			.employeeName("测试员工")
			.status(status)
			.principalStatus(principalStatus)
			.iamPrincipalId(principalId)
			.build();
	}

	private EmployeeConversationReq request() {
		EmployeeConversationReq req = new EmployeeConversationReq();
		req.setQuery("今天有什么安排");
		return req;
	}

	private RuntimeRunResp chatRun() {
		return new RuntimeRunResp(101L, "DIGITAL_EMPLOYEE", EMPLOYEE_ID, EMPLOYEE_ID, null, EMPLOYEE_ID,
				"CHAT:88:req", "88", "rr-chat-1", "CHAT", "CHAT", "今天有什么安排", "RUNNING", 1L, 0L, null, null, null,
				null, null, null, "3", null, null, "DIGITAL_EMPLOYEE", null);
	}

	@Test
	@DisplayName("rollout=false：MODEL_ONLY Guard 放行纯模型对话（不触碰 IAM）")
	void converseRunsModelOnlyWhenRolloutDisabled() {
		when(employeeMapper.findByIdAndTenantId(EMPLOYEE_ID, TENANT_ID))
			.thenReturn(employee(EmployeeStatusDict.ENABLED.getValue(),
					PrincipalProvisionStatusDict.READY.getValue(), "sp_abc"));
		when(dataAgentService.executeAgentOnce(any(AgentRequest.class))).thenReturn("模型回复");
		when(runtimeRunService.startInteractiveRun(any(), any(), any())).thenReturn(chatRun());

		EmployeeConversationResp resp = service.converse(EMPLOYEE_ID, request());

		assertEquals("MODEL_ONLY", resp.executionMode());
		assertEquals("模型回复", resp.reply());
		assertEquals(88L, resp.sessionId());
		assertEquals(101L, resp.runtimeRunId());
		verifyNoInteractions(executionContextClient);
		verifyNoInteractions(asyncContextBridge);
		ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);
		verify(dataAgentService).executeAgentOnce(captor.capture());
		AgentRequest agentRequest = captor.getValue();
		assertEquals(String.valueOf(EMPLOYEE_ID), agentRequest.getAgentId());
		assertEquals("DIGITAL_EMPLOYEE", agentRequest.getOwnerType());
		assertEquals(EMPLOYEE_ID, agentRequest.getOwnerId());
		assertNull(agentRequest.getReleaseId());
		assertEquals(List.of(), agentRequest.getPinnedSkillVersionIds());
		assertEquals("3", agentRequest.getUserIdSnapshot());
		verify(employeeConversationGuard).requireAllowed(AuthorizationAction.READ_KNOWLEDGE);
		verify(conversationAuthorizationGuard, never()).authorizeConversationUse(any());
	}

	@Test
	@DisplayName("未绑定 sourceAgentId 仍可对话（运行时钉员工自身）")
	void converseDoesNotRequireSourceAgentId() {
		DigitalEmployee unbound = employee(EmployeeStatusDict.ENABLED.getValue(), null, null);
		unbound.setSourceAgentId(null);
		when(employeeMapper.findByIdAndTenantId(EMPLOYEE_ID, TENANT_ID)).thenReturn(unbound);
		when(dataAgentService.executeAgentOnce(any(AgentRequest.class))).thenReturn("沙箱回复");

		EmployeeConversationResp resp = service.converse(EMPLOYEE_ID, request());

		assertEquals("MODEL_ONLY", resp.executionMode());
		assertEquals("沙箱回复", resp.reply());
	}

	@Test
	@DisplayName("rollout=true + READY 但无生产 Release：仍走 MODEL_ONLY")
	void converseStaysModelOnlyWithoutProductionRelease() {
		properties.getRollout().setEnabled(true);
		when(employeeMapper.findByIdAndTenantId(EMPLOYEE_ID, TENANT_ID))
			.thenReturn(employee(EmployeeStatusDict.ENABLED.getValue(),
					PrincipalProvisionStatusDict.READY.getValue(), "sp_abc"));
		when(dataAgentService.executeAgentOnce(any(AgentRequest.class))).thenReturn("沙箱回复");

		EmployeeConversationResp resp = service.converse(EMPLOYEE_ID, request());

		assertEquals("MODEL_ONLY", resp.executionMode());
		verifyNoInteractions(executionContextClient);
	}

	@Test
	@DisplayName("rollout=true + ENABLED + Principal READY + 生产 Release：签发委托 token 并以员工身份执行")
	void converseRunsPrincipalWhenReady() {
		properties.getRollout().setEnabled(true);
		when(employeeMapper.findByIdAndTenantId(EMPLOYEE_ID, TENANT_ID))
			.thenReturn(employee(EmployeeStatusDict.ENABLED.getValue(),
					PrincipalProvisionStatusDict.READY.getValue(), "sp_abc"));
		when(deploymentService.findCurrent(EMPLOYEE_ID, "PRODUCTION")).thenReturn(DigitalEmployeeDeployment.builder()
			.activeReleaseId(12L)
			.build());
		when(executionContextClient.issueContext(TENANT_ID, "sp_abc", "测试员工"))
			.thenReturn(new EmployeeAuthTokenContext("principal-token", "Bearer", 600L, 5L));
		when(dataAgentService.executeAgentOnce(any(AgentRequest.class))).thenReturn("员工回复");

		EmployeeConversationResp resp = service.converse(EMPLOYEE_ID, request());

		assertEquals("PRINCIPAL", resp.executionMode());
		assertEquals("员工回复", resp.reply());
		verify(executionContextClient).issueContext(TENANT_ID, "sp_abc", "测试员工");
		verify(asyncContextBridge).snapshotForDelegatedToken(anyString(),
				any(DataAgentOutboundContext.Snapshot.class));
		ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);
		verify(dataAgentService).executeAgentOnce(captor.capture());
		assertEquals(12L, captor.getValue().getReleaseId());
		assertEquals("sp_abc", captor.getValue().getUserIdSnapshot());
		assertNull(captor.getValue().getPinnedSkillVersionIds());
		verify(conversationAuthorizationGuard).authorizeConversationUse(any(AgentRequest.class));
		verify(employeeConversationGuard, never()).requireAllowed(any());
		ArgumentCaptor<DataAgentOutboundContext.Snapshot> outbound = ArgumentCaptor
			.forClass(DataAgentOutboundContext.Snapshot.class);
		verify(asyncContextBridge).snapshotForDelegatedToken(eq("principal-token"), outbound.capture());
		assertTrue(outbound.getValue().forceHttpAuthorization());
		assertEquals("principal-token", outbound.getValue().headers().get("V4-Authorization"));
		assertEquals(TENANT_ID, outbound.getValue().headers().get("x-tenant-id"));
	}

	@Test
	@DisplayName("已封存员工拒绝对话")
	void converseRejectsArchivedEmployee() {
		when(employeeMapper.findByIdAndTenantId(EMPLOYEE_ID, TENANT_ID))
			.thenReturn(employee(EmployeeStatusDict.ARCHIVED.getValue(), null, null));

		CheckedException ex = assertThrows(CheckedException.class, () -> service.converse(EMPLOYEE_ID, request()));

		assertTrue(ex.getMessage().contains("封存"), ex.getMessage());
		verifyNoInteractions(dataAgentService);
	}

	@Test
	@DisplayName("人工对话写入 CHAT RuntimeRun，并按员工 ID 归集")
	void conversePersistsChatRuntimeRun() {
		when(employeeMapper.findByIdAndTenantId(EMPLOYEE_ID, TENANT_ID))
			.thenReturn(employee(EmployeeStatusDict.ENABLED.getValue(), null, null));
		when(dataAgentService.executeAgentOnce(any(AgentRequest.class))).thenReturn("模型回复");
		when(runtimeRunService.startInteractiveRun(eq("7"), eq("3"), any(RuntimeRunCreateReq.class)))
			.thenReturn(chatRun());

		service.converse(EMPLOYEE_ID, request());

		ArgumentCaptor<RuntimeRunCreateReq> runCaptor = ArgumentCaptor.forClass(RuntimeRunCreateReq.class);
		verify(runtimeRunService).startInteractiveRun(eq("7"), eq("3"), runCaptor.capture());
		RuntimeRunCreateReq createReq = runCaptor.getValue();
		assertEquals("DIGITAL_EMPLOYEE", createReq.ownerType());
		assertEquals(EMPLOYEE_ID, createReq.ownerId());
		assertEquals(EMPLOYEE_ID, createReq.digitalEmployeeId());
		assertEquals(EMPLOYEE_ID, createReq.agentId());
		assertEquals("88", createReq.threadId());
		assertEquals("今天有什么安排", createReq.query());
		assertEquals("CHAT", createReq.runMode());
		assertEquals(1200L, createReq.deadlineSeconds());
		assertEquals("CHAT", createReq.triggerSource());
		assertTrue(createReq.clientRequestId().startsWith("CHAT:88:"), createReq.clientRequestId());
		verify(runtimeRunService).succeedInteractiveRun("7", 101L, "模型回复");
		ArgumentCaptor<AgentRequest> agentCaptor = ArgumentCaptor.forClass(AgentRequest.class);
		verify(dataAgentService).executeAgentOnce(agentCaptor.capture());
		assertEquals("rr-chat-1", agentCaptor.getValue().getRuntimeRequestId());
		assertEquals(101L, agentCaptor.getValue().getDurableRunId());
		assertEquals("CHAT", agentCaptor.getValue().getRequestSource());
	}

	@Test
	@DisplayName("对话执行失败时运行记录落 FAILED，异常继续抛出")
	void converseMarksRunFailedWhenExecuteThrows() {
		when(employeeMapper.findByIdAndTenantId(EMPLOYEE_ID, TENANT_ID))
			.thenReturn(employee(EmployeeStatusDict.ENABLED.getValue(), null, null));
		when(runtimeRunService.startInteractiveRun(eq("7"), eq("3"), any(RuntimeRunCreateReq.class)))
			.thenReturn(chatRun());
		when(dataAgentService.executeAgentOnce(any(AgentRequest.class)))
			.thenThrow(new IllegalStateException("模型超时"));

		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> service.converse(EMPLOYEE_ID, request()));

		assertEquals("模型超时", ex.getMessage());
		verify(runtimeRunService).failInteractiveRun("7", 101L, "CONVERSATION_FAILED", "模型超时");
		verify(runtimeRunService, never()).succeedInteractiveRun(any(), any(), any());
	}

	@Test
	@DisplayName("运行记录创建失败时对话失败关闭，禁止裸跑")
	void converseFailsWhenRunCreateFails() {
		when(employeeMapper.findByIdAndTenantId(EMPLOYEE_ID, TENANT_ID))
			.thenReturn(employee(EmployeeStatusDict.ENABLED.getValue(), null, null));
		when(runtimeRunService.startInteractiveRun(any(), any(), any()))
			.thenThrow(new RuntimeException("运行表写入失败"));

		RuntimeException ex = assertThrows(RuntimeException.class, () -> service.converse(EMPLOYEE_ID, request()));

		assertEquals("运行表写入失败", ex.getMessage());
		verify(dataAgentService, never()).executeAgentOnce(any(AgentRequest.class));
		verify(runtimeRunService, never()).succeedInteractiveRun(any(), any(), any());
	}

	@Test
	@DisplayName("既有会话按 sessionId 复用并校验归属，不重复创建会话")
	void converseReusesExistingSessionForSameUser() {
		when(employeeMapper.findByIdAndTenantId(EMPLOYEE_ID, TENANT_ID))
			.thenReturn(employee(EmployeeStatusDict.ENABLED.getValue(), null, null));
		DataChatSession existing = new DataChatSession(EMPLOYEE_ID, "既有会话", "active", 3L);
		existing.setId(99L);
		when(chatSessionService.requireSessionForAgent(99L, EMPLOYEE_ID)).thenReturn(existing);
		when(dataAgentService.executeAgentOnce(any(AgentRequest.class))).thenReturn("ok");
		EmployeeConversationReq req = request();
		req.setSessionId(99L);

		EmployeeConversationResp resp = service.converse(EMPLOYEE_ID, req);

		assertEquals(99L, resp.sessionId());
		verify(chatSessionService).requireSessionForAgent(99L, EMPLOYEE_ID);
		verify(chatSessionService, never()).createSession(any(), anyString(), any(), anyString(), any(), any(), any(),
				any());
	}

	@Test
	@DisplayName("无主人既有会话失败关闭，禁止认领")
	void converseRejectsExistingSessionWithoutOwner() {
		when(employeeMapper.findByIdAndTenantId(EMPLOYEE_ID, TENANT_ID))
			.thenReturn(employee(EmployeeStatusDict.ENABLED.getValue(), null, null));
		DataChatSession existing = new DataChatSession(EMPLOYEE_ID, "无主会话", "active", null);
		existing.setId(99L);
		when(chatSessionService.requireSessionForAgent(99L, EMPLOYEE_ID)).thenReturn(existing);
		EmployeeConversationReq req = request();
		req.setSessionId(99L);

		CheckedException ex = assertThrows(CheckedException.class, () -> service.converse(EMPLOYEE_ID, req));

		assertTrue(ex.getMessage().contains("不属于当前用户"), ex.getMessage());
		verifyNoInteractions(dataAgentService);
		verify(employeeConversationGuard, never()).requireAllowed(any());
	}

	@Test
	@DisplayName("既有会话属于其他用户时禁止对话")
	void converseRejectsExistingSessionOwnedByOtherUser() {
		when(employeeMapper.findByIdAndTenantId(EMPLOYEE_ID, TENANT_ID))
			.thenReturn(employee(EmployeeStatusDict.ENABLED.getValue(), null, null));
		DataChatSession existing = new DataChatSession(EMPLOYEE_ID, "他人会话", "active", 99L);
		existing.setId(99L);
		when(chatSessionService.requireSessionForAgent(99L, EMPLOYEE_ID)).thenReturn(existing);
		EmployeeConversationReq req = request();
		req.setSessionId(99L);

		CheckedException ex = assertThrows(CheckedException.class, () -> service.converse(EMPLOYEE_ID, req));

		assertTrue(ex.getMessage().contains("不属于当前用户"), ex.getMessage());
		verifyNoInteractions(dataAgentService);
	}

	@Test
	@DisplayName("流式对话复用 streamSearch 事件协议，并打上 Facade 标记")
	void converseStreamReusesStreamSearchAndMarksFacade() {
		when(employeeMapper.findByIdAndTenantId(EMPLOYEE_ID, TENANT_ID))
			.thenReturn(employee(EmployeeStatusDict.ENABLED.getValue(), null, null));
		when(runtimeRunService.startInteractiveRun(any(), any(), any())).thenReturn(chatRun());
		when(dataAgentService.streamSearch(any(AgentRequest.class))).thenReturn(Flux.just(
				ServerSentEvent.<AgentResponse>builder()
					.event("message")
					.data(AgentResponse.builder().text("你好").build())
					.build(),
				ServerSentEvent.<AgentResponse>builder()
					.event("complete")
					.data(AgentResponse.complete(String.valueOf(EMPLOYEE_ID), "88"))
					.build()));

		List<ServerSentEvent<AgentResponse>> events = service.converseStream(EMPLOYEE_ID, request())
			.collectList()
			.block();

		assertEquals("runtime_progress", events.get(0).event());
		assertEquals("MODEL_ONLY", events.get(0).data().getMetadata().get("executionMode"));
		assertEquals(88L, events.get(0).data().getMetadata().get("sessionId"));
		assertEquals(101L, events.get(0).data().getMetadata().get("runtimeRunId"));
		assertEquals("你好", events.get(1).data().getText());
		assertEquals("complete", events.get(2).event());
		ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);
		verify(dataAgentService).streamSearch(captor.capture());
		assertTrue(captor.getValue().isEmployeeFacadeStream());
		assertEquals("DIGITAL_EMPLOYEE", captor.getValue().getOwnerType());
		assertEquals("3", captor.getValue().getUserIdSnapshot());
		verify(dataAgentService, never()).executeAgentOnce(any());
		verify(employeeConversationGuard).requireAllowed(AuthorizationAction.READ_KNOWLEDGE);
		verify(conversationAuthorizationGuard, never()).authorizeConversationUse(any());
		verify(runtimeRunService).succeedInteractiveRun("7", 101L, "你好");
	}

	@Test
	@DisplayName("流式 PRINCIPAL 换票后走 streamSearch，userIdSnapshot 为 Principal")
	void converseStreamRunsPrincipalWhenReady() {
		properties.getRollout().setEnabled(true);
		when(employeeMapper.findByIdAndTenantId(EMPLOYEE_ID, TENANT_ID))
			.thenReturn(employee(EmployeeStatusDict.ENABLED.getValue(),
					PrincipalProvisionStatusDict.READY.getValue(), "sp_abc"));
		when(deploymentService.findCurrent(EMPLOYEE_ID, "PRODUCTION")).thenReturn(DigitalEmployeeDeployment.builder()
			.activeReleaseId(12L)
			.build());
		when(executionContextClient.issueContext(TENANT_ID, "sp_abc", "测试员工"))
			.thenReturn(new EmployeeAuthTokenContext("principal-token", "Bearer", 600L, 5L));
		when(runtimeRunService.startInteractiveRun(any(), any(), any())).thenReturn(chatRun());
		when(dataAgentService.streamSearch(any(AgentRequest.class))).thenReturn(Flux.just(ServerSentEvent
			.<AgentResponse>builder()
			.event("complete")
			.data(AgentResponse.complete(String.valueOf(EMPLOYEE_ID), "88"))
			.build()));

		List<ServerSentEvent<AgentResponse>> events = service.converseStream(EMPLOYEE_ID, request())
			.collectList()
			.block();

		assertEquals("PRINCIPAL", events.get(0).data().getMetadata().get("executionMode"));
		verify(executionContextClient).issueContext(TENANT_ID, "sp_abc", "测试员工");
		verify(asyncContextBridge).snapshotForDelegatedToken(anyString(),
				any(DataAgentOutboundContext.Snapshot.class));
		ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);
		verify(dataAgentService).streamSearch(captor.capture());
		assertEquals("sp_abc", captor.getValue().getUserIdSnapshot());
		assertEquals(12L, captor.getValue().getReleaseId());
		assertTrue(captor.getValue().isEmployeeFacadeStream());
		verify(dataAgentService, never()).executeAgentOnce(any());
		verify(conversationAuthorizationGuard).authorizeConversationUse(any(AgentRequest.class));
		verify(employeeConversationGuard, never()).requireAllowed(any());
	}

	@Test
	@DisplayName("流式 error 事件将运行记录落 FAILED")
	void converseStreamMarksRunFailedOnErrorEvent() {
		when(employeeMapper.findByIdAndTenantId(EMPLOYEE_ID, TENANT_ID))
			.thenReturn(employee(EmployeeStatusDict.ENABLED.getValue(), null, null));
		when(runtimeRunService.startInteractiveRun(any(), any(), any())).thenReturn(chatRun());
		when(dataAgentService.streamSearch(any(AgentRequest.class))).thenReturn(Flux.just(ServerSentEvent
			.<AgentResponse>builder()
			.event("error")
			.data(AgentResponse.error(String.valueOf(EMPLOYEE_ID), "88", "模型超时"))
			.build()));

		service.converseStream(EMPLOYEE_ID, request()).collectList().block();

		verify(runtimeRunService).failInteractiveRun("7", 101L, "CONVERSATION_FAILED", "模型超时");
		verify(runtimeRunService, never()).succeedInteractiveRun(any(), any(), any());
	}

	@Test
	@DisplayName("流式对话同样拒绝已封存员工")
	void converseStreamRejectsArchivedEmployee() {
		when(employeeMapper.findByIdAndTenantId(EMPLOYEE_ID, TENANT_ID))
			.thenReturn(employee(EmployeeStatusDict.ARCHIVED.getValue(), null, null));

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.converseStream(EMPLOYEE_ID, request()));

		assertTrue(ex.getMessage().contains("封存"), ex.getMessage());
		verifyNoInteractions(dataAgentService);
	}

}
