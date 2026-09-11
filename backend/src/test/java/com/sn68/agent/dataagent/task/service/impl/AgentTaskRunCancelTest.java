/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.task.service.impl;

import com.sn68.agent.dataagent.runtime.durable.service.RuntimeRunService;
import com.sn68.agent.dataagent.task.entity.AgentTaskRun;
import com.sn68.agent.dataagent.task.enums.TaskRunStatus;
import com.sn68.agent.dataagent.task.repository.AgentTaskDefinitionMapper;
import com.sn68.agent.dataagent.task.repository.AgentTaskRunMapper;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskRunCancelTest {

	private static final Long RUN_ID = 2091842960506032130L;

	private final AuthenticationContext authenticationContext = mock(AuthenticationContext.class);

	private final AgentTaskDefinitionMapper definitionMapper = mock(AgentTaskDefinitionMapper.class);

	private final AgentTaskRunMapper taskRunMapper = mock(AgentTaskRunMapper.class);

	private final RuntimeRunService runtimeRunService = mock(RuntimeRunService.class);

	private AgentTaskRunServiceImpl service;

	@BeforeEach
	void setUp() {
		when(authenticationContext.tenantId()).thenReturn("1");
		when(authenticationContext.userId()).thenReturn("1");
		service = new AgentTaskRunServiceImpl(authenticationContext, definitionMapper, mock(TransactionTemplate.class),
				runtimeRunService);
		ReflectionTestUtils.setField(service, "baseMapper", taskRunMapper);
		when(taskRunMapper.markTerminalById(eq(RUN_ID), eq(TaskRunStatus.CANCELLED.getValue()), any(), any(Instant.class)))
			.thenReturn(1);
		when(definitionMapper.releaseSlot(anyLong(), eq(RUN_ID))).thenReturn(1);
	}

	@Test
	void cancelPendingWithoutRuntimeMarksCancelledAndReleasesSlot() {
		AgentTaskRun pending = activeRun(null);
		pending.setRunStatus(TaskRunStatus.PENDING.getValue());
		when(taskRunMapper.findByTenantAndId("1", RUN_ID)).thenReturn(pending, cancelledRun());
		when(taskRunMapper.selectById(RUN_ID)).thenReturn(pending);

		AgentTaskRun result = service.cancel(RUN_ID, "用户取消占用");

		assertEquals(TaskRunStatus.CANCELLED.getValue(), result.getRunStatus());
		verify(runtimeRunService, never()).cancel(anyString(), any(), anyLong(), any());
		verify(taskRunMapper).markTerminalById(eq(RUN_ID), eq("CANCELLED"), any(), any(Instant.class));
		verify(definitionMapper).releaseSlot(pending.getDefinitionId(), RUN_ID);
	}

	@Test
	void cancelRunningWithRuntimeRequestsRuntimeCancelThenReleasesSlot() {
		AgentTaskRun running = activeRun(88L);
		when(taskRunMapper.findByTenantAndId("1", RUN_ID)).thenReturn(running, cancelledRun());
		when(taskRunMapper.selectById(RUN_ID)).thenReturn(running);

		service.cancel(RUN_ID, "用户取消占用");

		verify(runtimeRunService).cancel("1", "1", 88L, "用户在任务中心取消占用");
		verify(taskRunMapper).markTerminalById(eq(RUN_ID), eq("CANCELLED"), any(), any(Instant.class));
		verify(definitionMapper).releaseSlot(running.getDefinitionId(), RUN_ID);
	}

	@Test
	void cancelAlreadyTerminalIsIdempotent() {
		AgentTaskRun cancelled = cancelledRun();
		when(taskRunMapper.findByTenantAndId("1", RUN_ID)).thenReturn(cancelled);

		AgentTaskRun result = service.cancel(RUN_ID, "用户取消占用");

		assertEquals(TaskRunStatus.CANCELLED.getValue(), result.getRunStatus());
		verify(runtimeRunService, never()).cancel(anyString(), any(), anyLong(), any());
		verify(taskRunMapper, never()).markTerminalById(anyLong(), any(), any(), any());
		verify(definitionMapper).releaseSlot(cancelled.getDefinitionId(), RUN_ID);
	}

	private AgentTaskRun activeRun(Long runtimeRunId) {
		AgentTaskRun run = new AgentTaskRun();
		run.setId(RUN_ID);
		run.setTenantId("1");
		run.setDefinitionId(2091746742895411202L);
		run.setRunStatus(TaskRunStatus.RUNNING.getValue());
		run.setRuntimeRunId(runtimeRunId);
		return run;
	}

	private AgentTaskRun cancelledRun() {
		AgentTaskRun run = activeRun(null);
		run.setRunStatus(TaskRunStatus.CANCELLED.getValue());
		return run;
	}

}
