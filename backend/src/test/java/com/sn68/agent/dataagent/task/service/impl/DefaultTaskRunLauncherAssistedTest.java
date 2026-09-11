/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package com.sn68.agent.dataagent.task.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.authorization.pep.TaskAuthorizationGuard;
import com.sn68.agent.dataagent.employee.auth.EmployeeAuthContextException;
import com.sn68.agent.dataagent.employee.auth.EmployeeAuthTokenContext;
import com.sn68.agent.dataagent.employee.auth.EmployeeExecutionContextClient;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunCreateReq;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunResp;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeRunState;
import com.sn68.agent.dataagent.runtime.durable.event.RuntimeOutboxEvent;
import com.sn68.agent.dataagent.runtime.durable.service.AgentApprovalService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeRunService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStateService;
import com.sn68.agent.dataagent.task.entity.AgentTaskDefinition;
import com.sn68.agent.dataagent.task.entity.AgentTaskRun;
import com.sn68.agent.dataagent.task.entity.AgentTaskTrigger;
import com.sn68.agent.dataagent.task.enums.TaskConstants;
import com.sn68.agent.dataagent.task.enums.TaskTriggerType;
import com.sn68.agent.dataagent.task.repository.AgentTaskDefinitionMapper;
import com.sn68.agent.dataagent.task.repository.AgentTaskRunMapper;
import com.sn68.agent.dataagent.task.service.AgentTaskRunService;
import com.sn68.agent.dataagent.task.service.TaskLaunchRequest;
import com.sn68.agent.framework.commons.exception.CheckedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务发起器测试：ASSISTED 挂接审批 + PR-6 受限执行身份解析（员工就绪态、IAM 签发、
 * START_UNATTENDED 守卫）与 FORBID 并发槽消费（SLOT_CONFLICT 落 SKIPPED 不再挂接运行）。
 */
class DefaultTaskRunLauncherAssistedTest {

	private AgentTaskRunService taskRunService;

	private AgentTaskRunMapper taskRunMapper;

	private AgentTaskDefinitionMapper definitionMapper;

	private RuntimeRunService runtimeRunService;

	private RuntimeStateService runtimeStateService;

	private AgentApprovalService approvalService;

	private TaskAuthorizationGuard taskAuthorizationGuard;

	private EmployeeExecutionContextClient employeeExecutionContextClient;

	private DigitalEmployeeMapper digitalEmployeeMapper;

	private DefaultTaskRunLauncher launcher;

	@BeforeEach
	void setUp() {
		taskRunService = mock(AgentTaskRunService.class);
		taskRunMapper = mock(AgentTaskRunMapper.class);
		definitionMapper = mock(AgentTaskDefinitionMapper.class);
		runtimeRunService = mock(RuntimeRunService.class);
		runtimeStateService = mock(RuntimeStateService.class);
		approvalService = mock(AgentApprovalService.class);
		taskAuthorizationGuard = mock(TaskAuthorizationGuard.class);
		employeeExecutionContextClient = mock(EmployeeExecutionContextClient.class);
		digitalEmployeeMapper = mock(DigitalEmployeeMapper.class);
		launcher = new DefaultTaskRunLauncher(taskRunService, taskRunMapper, definitionMapper, runtimeRunService,
				runtimeStateService, approvalService, new ObjectMapper(), taskAuthorizationGuard,
				employeeExecutionContextClient, digitalEmployeeMapper);
	}

	@Test
	void assistedLaunchCreatesApprovalAndSuspendsRun() {
		AgentTaskDefinition definition = definition(true, null);
		when(definitionMapper.findByTenantAndId("7", 2L)).thenReturn(definition);
		AgentTaskRun claimed = taskRun(5L, null);
		stubClaimedSlot(claimed);
		stubReadyEmployee();
		when(runtimeRunService.create(eq("7"), any(), any())).thenReturn(runtimeRun(88L));
		when(runtimeRunService.findRunState("7", 88L)).thenReturn(RuntimeRunState.PENDING);
		when(runtimeStateService.transitionRunWithRetry(eq(88L), eq(RuntimeRunState.WAITING_APPROVAL), any(),
				anyInt())).thenReturn(true);

		launcher.launch(new TaskLaunchRequest(trigger(), TaskTriggerType.API, "evt-1", null, null));

		ArgumentCaptor<AgentApprovalService.ApprovalCreateRequest> captor = ArgumentCaptor
			.forClass(AgentApprovalService.ApprovalCreateRequest.class);
		verify(approvalService).createPending(captor.capture());
		assertEquals("agent-task:launch", captor.getValue().capabilityCode());
		assertEquals(88L, captor.getValue().runId());
		assertEquals("TASK_RUN", captor.getValue().source());
		assertEquals("5", captor.getValue().sourceRefId());
		verify(runtimeStateService).transitionRunWithRetry(eq(88L), eq(RuntimeRunState.WAITING_APPROVAL), any(),
				anyInt());
	}

	@Test
	void autonomousLaunchSkipsApproval() {
		when(definitionMapper.findByTenantAndId("7", 2L)).thenReturn(definition(false, "AUTONOMOUS"));
		stubClaimedSlot(taskRun(5L, null));
		stubReadyEmployee();
		when(runtimeRunService.create(eq("7"), any(), any())).thenReturn(runtimeRun(88L));

		launcher.launch(new TaskLaunchRequest(trigger(), TaskTriggerType.API, "evt-1", null, null));

		verify(approvalService, never()).createPending(any());
		verify(runtimeStateService, never()).transitionRunWithRetry(anyLong(), any(), any(), anyInt());
	}

	/**
	 * 任务定义固定绑定数字员工与发布版本（PR-1 起替代 workspace 绑定三元组），
	 * owner 维度与 digitalEmployeeId 必须随 Run 落库，运行台账才能按数字员工精确归集而不是靠 agentId 近似。
	 * PR-6：执行主体用员工 Principal（IAM 侧实况）而非定义快照。
	 */
	@Test
	void launchWritesOwnerAndDigitalEmployeeOntoRuntimeRun() {
		when(definitionMapper.findByTenantAndId("7", 2L)).thenReturn(definition(false, "AUTONOMOUS"));
		stubClaimedSlot(taskRun(5L, null));
		stubReadyEmployee();
		when(runtimeRunService.create(eq("7"), any(), any())).thenReturn(runtimeRun(88L));

		launcher.launch(new TaskLaunchRequest(trigger(), TaskTriggerType.API, "evt-1", null, null));

		ArgumentCaptor<RuntimeRunCreateReq> captor = ArgumentCaptor.forClass(RuntimeRunCreateReq.class);
		verify(runtimeRunService).create(eq("7"), eq("sp-employee-1"), captor.capture());
		assertEquals("DIGITAL_EMPLOYEE", captor.getValue().ownerType());
		assertEquals(9L, captor.getValue().ownerId());
		assertEquals(9L, captor.getValue().digitalEmployeeId());
		assertEquals(1L, captor.getValue().releaseId());
	}

	/** PR-6：并发槽被占（SLOT_CONFLICT）直接返回 SKIPPED 台账行，不再解析执行身份也不创建 RuntimeRun。 */
	@Test
	void slotConflictReturnsSkippedWithoutRuntimeRun() {
		when(definitionMapper.findByTenantAndId("7", 2L)).thenReturn(definition(false, "AUTONOMOUS"));
		AgentTaskRun skipped = taskRun(6L, null);
		skipped.setRunStatus("SKIPPED");
		skipped.setErrorMessage("[" + TaskConstants.REASON_CONCURRENT_SLOT_LOCKED + "] FORBID 并发槽被其他运行占用");
		when(taskRunService.claimSlot(any(AgentTaskRun.class)))
			.thenReturn(new AgentTaskRunService.SlotClaim(skipped, AgentTaskRunService.SlotOutcome.SLOT_CONFLICT));

		AgentTaskRun result = launcher.launch(new TaskLaunchRequest(trigger(), TaskTriggerType.API, "evt-1", null, null));

		assertEquals("SKIPPED", result.getRunStatus());
		verify(digitalEmployeeMapper, never()).findByIdAndTenantId(anyLong(), anyString());
		verify(runtimeRunService, never()).create(anyString(), any(), any());
	}

	/** PR-6：Principal 未就绪（非 READY）落 FAILED + [AUTHORIZATION_DENIED]，不创建 RuntimeRun。 */
	@Test
	void launchFailsClosedWhenPrincipalNotReady() {
		when(definitionMapper.findByTenantAndId("7", 2L)).thenReturn(definition(false, "AUTONOMOUS"));
		AgentTaskRun claimed = taskRun(5L, null);
		stubClaimedSlot(claimed);
		DigitalEmployee pending = DigitalEmployee.builder()
			.tenantId("7")
			.status("ENABLED")
			.iamPrincipalId("sp-employee-1")
			.principalStatus("PENDING")
			.build();
		when(digitalEmployeeMapper.findByIdAndTenantId(9L, "7")).thenReturn(pending);
		when(taskRunService.markTerminalById(eq(5L), any(), anyString())).thenReturn(true);

		AgentTaskRun result = launcher.launch(new TaskLaunchRequest(trigger(), TaskTriggerType.API, "evt-1", null, null));

		assertEquals("FAILED", result.getRunStatus());
		assertTrue(result.getErrorMessage().contains(TaskConstants.REASON_AUTHORIZATION_DENIED));
		ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
		verify(taskRunService).markTerminalById(eq(5L), any(), reasonCaptor.capture());
		assertTrue(reasonCaptor.getValue().contains(TaskConstants.REASON_AUTHORIZATION_DENIED));
		verify(employeeExecutionContextClient, never()).issueContext(anyString(), anyString(), anyString());
		verify(runtimeRunService, never()).create(anyString(), any(), any());
	}

	/** PR-6：IAM 签发失败（WAITING_AUTH）落 FAILED 可重试语义，不创建 RuntimeRun。 */
	@Test
	void launchMarksWaitingAuthWhenIssueContextFails() {
		when(definitionMapper.findByTenantAndId("7", 2L)).thenReturn(definition(false, "AUTONOMOUS"));
		stubClaimedSlot(taskRun(5L, null));
		stubReadyEmployee();
		when(employeeExecutionContextClient.issueContext(anyString(), anyString(), anyString()))
			.thenThrow(EmployeeAuthContextException.waitingAuth("WAITING_AUTH: IAM 不可用", null));
		when(taskRunService.markTerminalById(eq(5L), any(), anyString())).thenReturn(true);

		AgentTaskRun result = launcher.launch(new TaskLaunchRequest(trigger(), TaskTriggerType.API, "evt-1", null, null));

		assertEquals("FAILED", result.getRunStatus());
		assertTrue(result.getErrorMessage().contains(TaskConstants.REASON_WAITING_AUTH));
		verify(runtimeRunService, never()).create(anyString(), any(), any());
	}

	/** PR-6：START_UNATTENDED 守卫 ENFORCE 拒绝落 FAILED + [AUTHORIZATION_DENIED]，不创建 RuntimeRun。 */
	@Test
	void launchFailsClosedWhenGuardRejects() {
		when(definitionMapper.findByTenantAndId("7", 2L)).thenReturn(definition(false, "AUTONOMOUS"));
		stubClaimedSlot(taskRun(5L, null));
		stubReadyEmployee();
		when(taskAuthorizationGuard.authorizeTaskStart(any(), any()))
			.thenThrow(CheckedException.fail("任务拉起被授权策略拒绝（BUSINESS_DENIED）：reasonCode=DENY"));
		when(taskRunService.markTerminalById(eq(5L), any(), anyString())).thenReturn(true);

		AgentTaskRun result = launcher.launch(new TaskLaunchRequest(trigger(), TaskTriggerType.API, "evt-1", null, null));

		assertEquals("FAILED", result.getRunStatus());
		assertTrue(result.getErrorMessage().contains(TaskConstants.REASON_AUTHORIZATION_DENIED));
		verify(runtimeRunService, never()).create(anyString(), any(), any());
	}

	@Test
	void launchApprovedResumesWaitingRun() {
		when(taskRunMapper.selectById(5L)).thenReturn(taskRun(5L, 88L));
		when(runtimeRunService.findRunState("7", 88L)).thenReturn(RuntimeRunState.WAITING_APPROVAL);

		launcher.launchApproved(5L);

		verify(runtimeRunService).resume(eq("7"), any(), eq(88L));
	}

	@Test
	void launchApprovedIsIdempotentWhenRunAlreadyResumed() {
		when(taskRunMapper.selectById(5L)).thenReturn(taskRun(5L, 88L));
		when(runtimeRunService.findRunState("7", 88L)).thenReturn(RuntimeRunState.RUNNING);

		launcher.launchApproved(5L);

		verify(runtimeRunService, never()).resume(anyString(), any(), anyLong());
	}

	@Test
	void approvalDecidedEventTriggersRelaunchAndConsumesApproval() {
		when(taskRunMapper.selectById(5L)).thenReturn(taskRun(5L, 88L));
		when(runtimeRunService.findRunState("7", 88L)).thenReturn(RuntimeRunState.WAITING_APPROVAL);
		when(approvalService.consume(9L)).thenReturn(true);

		launcher.onApprovalDecided(new RuntimeOutboxEvent("7", 3L, "88", "APPROVAL_DECIDED", "approval-decided:9",
				"{\"approvalId\":9,\"state\":\"APPROVED\",\"source\":\"TASK_RUN\",\"sourceRefId\":\"5\"}"));

		verify(runtimeRunService).resume(eq("7"), any(), eq(88L));
		verify(approvalService).consume(9L);
	}

	@Test
	void rejectedDecisionAndForeignEventsAreIgnored() {
		launcher.onApprovalDecided(new RuntimeOutboxEvent("7", 3L, "88", "APPROVAL_DECIDED", "approval-decided:9",
				"{\"approvalId\":9,\"state\":\"REJECTED\",\"source\":\"TASK_RUN\",\"sourceRefId\":\"5\"}"));
		launcher.onApprovalDecided(new RuntimeOutboxEvent("7", 3L, "88", "APPROVAL_DECIDED", "approval-decided:10",
				"{\"approvalId\":10,\"state\":\"APPROVED\",\"source\":\"CAPABILITY_GATEWAY\"}"));
		launcher.onApprovalDecided(new RuntimeOutboxEvent("7", 3L, "88", "RUN_SUCCEEDED", "run-finished:88", "{}"));

		verify(taskRunMapper, never()).selectById(anyLong());
		verify(runtimeRunService, never()).resume(anyString(), any(), anyLong());
	}

	private void stubClaimedSlot(AgentTaskRun claimed) {
		when(taskRunService.claimSlot(any(AgentTaskRun.class)))
			.thenReturn(new AgentTaskRunService.SlotClaim(claimed, AgentTaskRunService.SlotOutcome.CLAIMED));
	}

	/** PR-6 执行身份链路的就绪态员工：ENABLED + Principal READY，IAM 签发返回可用 token。 */
	private void stubReadyEmployee() {
		DigitalEmployee employee = DigitalEmployee.builder()
			.tenantId("7")
			.employeeName("巡检员工")
			.status("ENABLED")
			.iamPrincipalId("sp-employee-1")
			.principalStatus("READY")
			.build();
		when(digitalEmployeeMapper.findByIdAndTenantId(9L, "7")).thenReturn(employee);
		when(employeeExecutionContextClient.issueContext(eq("7"), eq("sp-employee-1"), anyString()))
			.thenReturn(new EmployeeAuthTokenContext("tok-value", "Bearer", 3600L, 3L));
	}

	private AgentTaskTrigger trigger() {
		AgentTaskTrigger trigger = AgentTaskTrigger.builder()
			.tenantId("7")
			.definitionId(2L)
			.taskVersionId(1L)
			.triggerType("API")
			.concurrencyPolicy("ALLOW")
			.status("enabled")
			.build();
		trigger.setId(4L);
		return trigger;
	}

	private AgentTaskDefinition definition(boolean highRiskWrite, String autonomyLevel) {
		AgentTaskDefinition definition = AgentTaskDefinition.builder()
			.tenantId("7")
			.digitalEmployeeId(9L)
			.employeeReleaseId(1L)
			.taskName("巡检任务")
			.defaultAutonomyLevel(autonomyLevel)
			.highRiskWrite(highRiskWrite)
			.servicePrincipal("sp-1")
			.status("enabled")
			.build();
		definition.setId(2L);
		return definition;
	}

	private AgentTaskRun taskRun(Long id, Long runtimeRunId) {
		AgentTaskRun run = AgentTaskRun.builder()
			.tenantId("7")
			.definitionId(2L)
			.triggerId(4L)
			.triggerType("API")
			.idempotencyKey("API:4:evt-1")
			.runStatus("PENDING")
			.runtimeRunId(runtimeRunId)
			.servicePrincipal("sp-1")
			.build();
		run.setId(id);
		return run;
	}

	private RuntimeRunResp runtimeRun(Long id) {
		return new RuntimeRunResp(id, "DIGITAL_EMPLOYEE", 9L, 9L, 1L, 1L, "API:4:evt-1", null, "rr-1", "API",
				"AGENT_LOOP", "巡检任务", "PENDING", 0L, 0L, null, null, null, null, null, null, null, null, null,
				"DIGITAL_EMPLOYEE", null);
	}

}
