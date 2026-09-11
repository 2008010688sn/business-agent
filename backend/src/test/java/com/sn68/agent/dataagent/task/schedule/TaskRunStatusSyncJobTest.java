/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.task.schedule;

import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeRunState;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeRunService;
import com.sn68.agent.dataagent.task.entity.AgentTaskDefinition;
import com.sn68.agent.dataagent.task.entity.AgentTaskRun;
import com.sn68.agent.dataagent.task.enums.TaskRunStatus;
import com.sn68.agent.dataagent.task.repository.AgentTaskDefinitionMapper;
import com.sn68.agent.dataagent.task.repository.AgentTaskRunMapper;
import com.sn68.agent.dataagent.task.service.AgentTaskRunService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务运行状态对账作业测试（PR-6 清单第 10 条）：三个职责各自独立验证——
 * 1) PENDING 且已挂 Run 的行按运行时实况终态兜底 / 活跃态推进；
 * 2) FORBID 并发槽镜像只对非活跃镜像定向释放（WHERE active_run_id = 镜像 runId，防误放）；
 * 3) 受理后超过宽限窗仍未挂 Run 的僵尸 PENDING 行收敛 FAILED。
 *
 * <p>全部推进动作用状态谓词 CAS 兜底：mark* 返回 false（0 行）表示主路径或并发副本
 * 已推进，对账侧必须安静跳过、不得重复动作（幂等约束），相关用例逐条覆盖。</p>
 */
class TaskRunStatusSyncJobTest {

	private static final String TENANT_ID = "7";

	private static final Long TASK_RUN_ID = 9L;

	private static final Long RUNTIME_RUN_ID = 100L;

	private static final Long DEFINITION_ID = 5L;

	private static final Long MIRROR_RUN_ID = 200L;

	private final AgentTaskRunService taskRunService = mock(AgentTaskRunService.class);

	private final AgentTaskRunMapper taskRunMapper = mock(AgentTaskRunMapper.class);

	private final AgentTaskDefinitionMapper definitionMapper = mock(AgentTaskDefinitionMapper.class);

	private final RuntimeRunService runtimeRunService = mock(RuntimeRunService.class);

	private final TaskRunStatusSyncJob job = new TaskRunStatusSyncJob(taskRunService, taskRunMapper, definitionMapper,
			runtimeRunService);

	@BeforeEach
	void setUp() {
		// jobExecute 是三个职责的共用入口：默认空扫描集，个别用例按职责覆盖自己的查询
		when(taskRunMapper.findPendingWithRuntime(anyInt())).thenReturn(List.of());
		when(definitionMapper.findOccupiedSlotDefinitions(anyInt())).thenReturn(List.of());
		when(taskRunMapper.findPendingWithoutRuntime(any(Instant.class), anyInt())).thenReturn(List.of());
	}

	// ---------------------------------------------------------------------------
	// 职责一：syncPendingRuns——PENDING 且已挂 Run 的行按运行时实况推进
	// ---------------------------------------------------------------------------

	/** 运行时已终态（漏网于 Outbox 主路径）时兜底同步任务终态；成功终态不写 error_message。 */
	@Test
	void runtimeTerminalStateFallsBackToTaskTerminal() {
		when(taskRunMapper.findPendingWithRuntime(anyInt())).thenReturn(List.of(pendingTaskRun()));
		when(runtimeRunService.findRunState(TENANT_ID, RUNTIME_RUN_ID)).thenReturn(RuntimeRunState.SUCCEEDED);
		when(taskRunService.markTerminalByRuntimeRun(eq(RUNTIME_RUN_ID), eq(TaskRunStatus.SUCCESS), any()))
			.thenReturn(true);

		job.jobExecute();

		verify(taskRunService).markTerminalByRuntimeRun(RUNTIME_RUN_ID, TaskRunStatus.SUCCESS, null);
		verify(taskRunService, never()).markRunningByRuntimeRun(anyLong());
	}

	/** 运行时超时按失败归集并携带对账兜底口径的原因文案（与事件监听方映射同口径）。 */
	@Test
	void runtimeTimedOutFallsBackToFailedWithReason() {
		when(taskRunMapper.findPendingWithRuntime(anyInt())).thenReturn(List.of(pendingTaskRun()));
		when(runtimeRunService.findRunState(TENANT_ID, RUNTIME_RUN_ID)).thenReturn(RuntimeRunState.TIMED_OUT);
		when(taskRunService.markTerminalByRuntimeRun(eq(RUNTIME_RUN_ID), eq(TaskRunStatus.FAILED), any()))
			.thenReturn(true);

		job.jobExecute();

		verify(taskRunService).markTerminalByRuntimeRun(eq(RUNTIME_RUN_ID), eq(TaskRunStatus.FAILED),
				argThat(reason -> reason != null && reason.contains("超时终态")));
	}

	/** 运行时活跃（非 PENDING 非终态，如 WAITING_APPROVAL/CANCELLING）则任务侧推进 RUNNING。 */
	@Test
	void activeRuntimeStateAdvancesLedgerRunning() {
		when(taskRunMapper.findPendingWithRuntime(anyInt())).thenReturn(List.of(pendingTaskRun()));
		when(runtimeRunService.findRunState(TENANT_ID, RUNTIME_RUN_ID)).thenReturn(RuntimeRunState.WAITING_APPROVAL);
		when(taskRunService.markRunningByRuntimeRun(RUNTIME_RUN_ID)).thenReturn(true);

		job.jobExecute();

		verify(taskRunService).markRunningByRuntimeRun(RUNTIME_RUN_ID);
		verify(taskRunService, never()).markTerminalByRuntimeRun(anyLong(), any(), any());
	}

	/** 运行时仍 PENDING：任务侧保持 PENDING 等运行时真正开跑，本轮不做无效推进。 */
	@Test
	void runtimeStillPendingDoesNotAdvanceLedger() {
		when(taskRunMapper.findPendingWithRuntime(anyInt())).thenReturn(List.of(pendingTaskRun()));
		when(runtimeRunService.findRunState(TENANT_ID, RUNTIME_RUN_ID)).thenReturn(RuntimeRunState.PENDING);

		job.jobExecute();

		verify(taskRunService, never()).markRunningByRuntimeRun(anyLong());
		verify(taskRunService, never()).markTerminalByRuntimeRun(anyLong(), any(), any());
	}

	/** 终态兜底 CAS 未命中（0 行 = 主路径已写终态）：安静跳过，重复对账不重复动作。 */
	@Test
	void terminalCasMissIsQuietlySafe() {
		when(taskRunMapper.findPendingWithRuntime(anyInt())).thenReturn(List.of(pendingTaskRun()));
		when(runtimeRunService.findRunState(TENANT_ID, RUNTIME_RUN_ID)).thenReturn(RuntimeRunState.SUCCEEDED);
		when(taskRunService.markTerminalByRuntimeRun(eq(RUNTIME_RUN_ID), eq(TaskRunStatus.SUCCESS), any()))
			.thenReturn(false);

		assertDoesNotThrow(job::jobExecute);

		verify(taskRunService, never()).markRunningByRuntimeRun(anyLong());
	}

	/** 单行运行时状态查询失败只跳过该行（下一轮再试），不中断整轮对账。 */
	@Test
	void runStateLookupFailureSkipsRowWithoutBreakingBatch() {
		when(taskRunMapper.findPendingWithRuntime(anyInt())).thenReturn(List.of(pendingTaskRun()));
		when(runtimeRunService.findRunState(TENANT_ID, RUNTIME_RUN_ID)).thenThrow(new RuntimeException("租户解析失败"));

		assertDoesNotThrow(job::jobExecute);

		verify(taskRunService, never()).markRunningByRuntimeRun(anyLong());
		verify(taskRunService, never()).markTerminalByRuntimeRun(anyLong(), any(), any());
	}

	// ---------------------------------------------------------------------------
	// 职责二：recycleIdleSlotMirrors——镜像指向非活跃运行时定向回收
	// ---------------------------------------------------------------------------

	/** 镜像指向终态运行：定向释放，且释放谓词必须用镜像里的 runId（防误放后续竞得槽的新运行）。 */
	@Test
	void staleMirrorIsReleasedWithTheExactMirroredRunId() {
		when(definitionMapper.findOccupiedSlotDefinitions(anyInt()))
			.thenReturn(List.of(occupiedDefinition(MIRROR_RUN_ID)));
		when(taskRunMapper.selectById(MIRROR_RUN_ID)).thenReturn(ledgerRun(TaskRunStatus.SUCCESS));
		when(definitionMapper.releaseSlot(DEFINITION_ID, MIRROR_RUN_ID)).thenReturn(1);

		job.jobExecute();

		verify(definitionMapper).releaseSlot(DEFINITION_ID, MIRROR_RUN_ID);
	}

	/** 镜像指向的运行行已不存在（被物理清理/残留脏数据）：视为非活跃，照常回收。 */
	@Test
	void mirrorPointingToMissingRunIsRecycled() {
		when(definitionMapper.findOccupiedSlotDefinitions(anyInt()))
			.thenReturn(List.of(occupiedDefinition(MIRROR_RUN_ID)));
		when(taskRunMapper.selectById(MIRROR_RUN_ID)).thenReturn(null);
		when(definitionMapper.releaseSlot(DEFINITION_ID, MIRROR_RUN_ID)).thenReturn(1);

		job.jobExecute();

		verify(definitionMapper).releaseSlot(DEFINITION_ID, MIRROR_RUN_ID);
	}

	/** 镜像指向 PENDING/RUNNING 且 Runtime 仍在跑：镜像有效，绝不释放。 */
	@Test
	void mirrorPointingToActiveRunIsKept() {
		when(definitionMapper.findOccupiedSlotDefinitions(anyInt()))
			.thenReturn(List.of(occupiedDefinition(MIRROR_RUN_ID)));
		when(taskRunMapper.selectById(MIRROR_RUN_ID)).thenReturn(occupiedActiveRun(TaskRunStatus.RUNNING));
		when(runtimeRunService.findRunState(TENANT_ID, RUNTIME_RUN_ID)).thenReturn(RuntimeRunState.RUNNING);

		job.jobExecute();

		verify(definitionMapper, never()).releaseSlot(anyLong(), anyLong());
		verify(taskRunService, never()).markTerminalByRuntimeRun(anyLong(), any(), any());
		verify(taskRunService, never()).markTerminalById(anyLong(), any(), any());
	}

	/** 占槽 RUNNING 但 Runtime 已成功：对账写成 SUCCESS 并放槽。 */
	@Test
	void occupiedRunningWithTerminalRuntimeIsReconciled() {
		when(definitionMapper.findOccupiedSlotDefinitions(anyInt()))
			.thenReturn(List.of(occupiedDefinition(MIRROR_RUN_ID)));
		when(taskRunMapper.selectById(MIRROR_RUN_ID)).thenReturn(occupiedActiveRun(TaskRunStatus.RUNNING));
		when(runtimeRunService.findRunState(TENANT_ID, RUNTIME_RUN_ID)).thenReturn(RuntimeRunState.SUCCEEDED);
		when(taskRunService.markTerminalByRuntimeRun(eq(RUNTIME_RUN_ID), eq(TaskRunStatus.SUCCESS), any()))
			.thenReturn(true);

		job.jobExecute();

		verify(taskRunService).markTerminalByRuntimeRun(RUNTIME_RUN_ID, TaskRunStatus.SUCCESS, null);
	}

	/** 占槽 RUNNING 但查询 Runtime 抛错：不得当成缺失而 FAILED，本轮跳过等下一轮。 */
	@Test
	void occupiedRunningWithLookupFailureIsKept() {
		when(definitionMapper.findOccupiedSlotDefinitions(anyInt()))
			.thenReturn(List.of(occupiedDefinition(MIRROR_RUN_ID)));
		when(taskRunMapper.selectById(MIRROR_RUN_ID)).thenReturn(occupiedActiveRun(TaskRunStatus.RUNNING));
		when(runtimeRunService.findRunState(TENANT_ID, RUNTIME_RUN_ID)).thenThrow(new RuntimeException("租户解析失败"));

		assertDoesNotThrow(job::jobExecute);

		verify(definitionMapper, never()).releaseSlot(anyLong(), anyLong());
		verify(taskRunService, never()).markTerminalByRuntimeRun(anyLong(), any(), any());
		verify(taskRunService, never()).markTerminalById(anyLong(), any(), any());
	}

	/** 占槽 RUNNING 但 Runtime 记录已不存在：对账 FAILED 并放槽。 */
	@Test
	void occupiedRunningWithMissingRuntimeIsFailed() {
		when(definitionMapper.findOccupiedSlotDefinitions(anyInt()))
			.thenReturn(List.of(occupiedDefinition(MIRROR_RUN_ID)));
		when(taskRunMapper.selectById(MIRROR_RUN_ID)).thenReturn(occupiedActiveRun(TaskRunStatus.RUNNING));
		when(runtimeRunService.findRunState(TENANT_ID, RUNTIME_RUN_ID)).thenReturn(null);
		when(taskRunService.markTerminalById(eq(MIRROR_RUN_ID), eq(TaskRunStatus.FAILED), any())).thenReturn(true);

		job.jobExecute();

		verify(taskRunService).markTerminalById(eq(MIRROR_RUN_ID), eq(TaskRunStatus.FAILED),
				argThat(reason -> reason != null && reason.contains("[RUNTIME_MISSING]")));
	}

	/** 释放 CAS 未命中（0 行 = 并发副本已改指新运行或已清空）：定向谓词保证不会误放，本轮安全结束。 */
	@Test
	void releaseCasMissIsQuietlySafe() {
		when(definitionMapper.findOccupiedSlotDefinitions(anyInt()))
			.thenReturn(List.of(occupiedDefinition(MIRROR_RUN_ID)));
		when(taskRunMapper.selectById(MIRROR_RUN_ID)).thenReturn(ledgerRun(TaskRunStatus.SUCCESS));
		when(definitionMapper.releaseSlot(DEFINITION_ID, MIRROR_RUN_ID)).thenReturn(0);

		assertDoesNotThrow(job::jobExecute);
	}

	// ---------------------------------------------------------------------------
	// 职责三：failInterruptedLaunches——30 分钟宽限窗后的僵尸 PENDING 收敛
	// ---------------------------------------------------------------------------

	/** 受理后超过 30 分钟仍未挂 Run：按拉起中断收敛 FAILED，扫描窗口须落在宽限值附近。 */
	@Test
	void interruptedLaunchIsFailedAfterTheGraceWindow() {
		when(taskRunMapper.findPendingWithoutRuntime(any(Instant.class), anyInt()))
			.thenReturn(List.of(pendingTaskRun()));
		when(taskRunService.markTerminalById(eq(TASK_RUN_ID), eq(TaskRunStatus.FAILED), any())).thenReturn(true);

		job.jobExecute();

		ArgumentCaptor<Instant> olderThan = ArgumentCaptor.forClass(Instant.class);
		verify(taskRunMapper).findPendingWithoutRuntime(olderThan.capture(), anyInt());
		Duration drift = Duration.between(olderThan.getValue(), Instant.now().minus(Duration.ofMinutes(30))).abs();
		assertTrue(drift.toMinutes() < 2, "僵尸判定窗口应为 30 分钟，实际偏移 " + drift);
		verify(taskRunService).markTerminalById(eq(TASK_RUN_ID), eq(TaskRunStatus.FAILED),
				argThat(reason -> reason != null && reason.contains("[LAUNCH_INTERRUPTED]")));
	}

	/** 僵尸收敛 CAS 未命中（0 行 = 其他链路已写终态）：安静跳过，幂等安全。 */
	@Test
	void interruptedLaunchCasMissIsQuietlySafe() {
		when(taskRunMapper.findPendingWithoutRuntime(any(Instant.class), anyInt()))
			.thenReturn(List.of(pendingTaskRun()));
		when(taskRunService.markTerminalById(eq(TASK_RUN_ID), eq(TaskRunStatus.FAILED), any())).thenReturn(false);

		assertDoesNotThrow(job::jobExecute);

		verify(taskRunService).markTerminalById(eq(TASK_RUN_ID), eq(TaskRunStatus.FAILED), any());
	}

	private AgentTaskRun pendingTaskRun() {
		AgentTaskRun taskRun = new AgentTaskRun();
		taskRun.setId(TASK_RUN_ID);
		taskRun.setTenantId(String.valueOf(TENANT_ID));
		taskRun.setDefinitionId(2L);
		taskRun.setRunStatus(TaskRunStatus.PENDING.getValue());
		taskRun.setRuntimeRunId(RUNTIME_RUN_ID);
		return taskRun;
	}

	private AgentTaskDefinition occupiedDefinition(Long activeRunId) {
		AgentTaskDefinition definition = new AgentTaskDefinition();
		definition.setId(DEFINITION_ID);
		definition.setActiveRunId(activeRunId);
		return definition;
	}

	private AgentTaskRun ledgerRun(TaskRunStatus status) {
		AgentTaskRun taskRun = new AgentTaskRun();
		taskRun.setId(MIRROR_RUN_ID);
		taskRun.setRunStatus(status.getValue());
		return taskRun;
	}

	private AgentTaskRun occupiedActiveRun(TaskRunStatus status) {
		AgentTaskRun taskRun = ledgerRun(status);
		taskRun.setTenantId(String.valueOf(TENANT_ID));
		taskRun.setRuntimeRunId(RUNTIME_RUN_ID);
		return taskRun;
	}

}
