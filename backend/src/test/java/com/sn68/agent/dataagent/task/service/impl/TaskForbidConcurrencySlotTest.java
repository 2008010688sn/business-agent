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

import com.sn68.agent.dataagent.task.entity.AgentTaskRun;
import com.sn68.agent.dataagent.task.enums.TaskConstants;
import com.sn68.agent.dataagent.task.enums.TaskRunStatus;
import com.sn68.agent.dataagent.task.repository.AgentTaskDefinitionMapper;
import com.sn68.agent.dataagent.task.repository.AgentTaskRunMapper;
import com.sn68.agent.dataagent.task.service.AgentTaskRunService;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-6 FORBID 并发槽（方案A）单元测试：claimSlot 的 SKIP LOCKED 预检、唯一索引冲突
 * 源区分（幂等命中优先于槽冲突）、SKIPPED 台账落库与终态槽位释放。
 *
 * <p>事务模板 stub 直接执行回调体（单测无真实事务；PG abort 后新语句语义在集成环境验证）。</p>
 */
class TaskForbidConcurrencySlotTest {

	private AgentTaskRunMapper taskRunMapper;

	private AgentTaskDefinitionMapper definitionMapper;

	private AgentTaskRunService taskRunService;

	@BeforeEach
	void setUp() {
		taskRunMapper = mock(AgentTaskRunMapper.class);
		definitionMapper = mock(AgentTaskDefinitionMapper.class);
		TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
		doAnswer(invocation -> {
			invocation.getArgument(0, Consumer.class).accept(null);
			return null;
		}).when(transactionTemplate).executeWithoutResult(any());
		AgentTaskRunServiceImpl service = new AgentTaskRunServiceImpl(mock(AuthenticationContext.class),
				definitionMapper, transactionTemplate, mock(com.sn68.agent.dataagent.runtime.durable.service.RuntimeRunService.class));
		// baseMapper 由 Spring 注入到 ServiceImpl，纯单测手动塞入
		ReflectionTestUtils.setField(service, "baseMapper", taskRunMapper);
		taskRunService = service;
		// 插入成功即回填主键，模拟 MP 主键回填
		doAnswer(invocation -> {
			invocation.getArgument(0, AgentTaskRun.class).setId(5L);
			return 1;
		}).when(taskRunMapper).insert(any(AgentTaskRun.class));
	}

	/** FORBID + 槽空闲：插入 PENDING 行成功竞得槽位，并 CAS 占用定义侧镜像。 */
	@Test
	void forbidSlotClaimSucceedsWhenNoActiveRun() {
		when(taskRunMapper.lockActiveRunIdsByDefinition("7", 2L)).thenReturn(List.of());
		when(definitionMapper.occupySlot(2L, 5L)).thenReturn(1);

		AgentTaskRunService.SlotClaim claim = taskRunService.claimSlot(forbidRun());

		assertEquals(AgentTaskRunService.SlotOutcome.CLAIMED, claim.outcome());
		assertEquals(5L, claim.run().getId());
		verify(definitionMapper).occupySlot(2L, 5L);
	}

	/** FORBID + 预检发现活跃运行：不插 PENDING 行，转落 SKIPPED 台账行并带原因码。 */
	@Test
	void forbidSlotConflictFallsBackToSkipped() {
		when(taskRunMapper.lockActiveRunIdsByDefinition("7", 2L)).thenReturn(List.of(9L));

		AgentTaskRunService.SlotClaim claim = taskRunService.claimSlot(forbidRun());

		assertEquals(AgentTaskRunService.SlotOutcome.SLOT_CONFLICT, claim.outcome());
		ArgumentCaptor<AgentTaskRun> captor = ArgumentCaptor.forClass(AgentTaskRun.class);
		verify(taskRunMapper).insert(captor.capture());
		assertEquals(TaskRunStatus.SKIPPED.getValue(), captor.getValue().getRunStatus());
		assertTrue(captor.getValue().getErrorMessage().contains(TaskConstants.REASON_CONCURRENT_SLOT_LOCKED),
				captor.getValue().getErrorMessage());
		assertEquals("FORBID", captor.getValue().getConcurrencyPolicy());
		// 未竞得槽位不得占用镜像
		verify(definitionMapper, never()).occupySlot(anyLong(), anyLong());
	}

	/** 唯一索引冲突但幂等键回查命中：返回已有行（IDEMPOTENT_HIT），不得误判槽冲突再落 SKIPPED。 */
	@Test
	void duplicateKeyWithExistingRowFallsBackToIdempotentHit() {
		when(taskRunMapper.lockActiveRunIdsByDefinition("7", 2L)).thenReturn(List.of());
		doThrow(new DuplicateKeyException("idx_forbid_slot")).when(taskRunMapper).insert(any(AgentTaskRun.class));
		AgentTaskRun existing = forbidRun();
		existing.setId(8L);
		when(taskRunMapper.findByIdempotencyKey("SCHEDULE:4:2026-08-18T10:00:00Z")).thenReturn(existing);

		AgentTaskRunService.SlotClaim claim = taskRunService.claimSlot(forbidRun());

		assertEquals(AgentTaskRunService.SlotOutcome.IDEMPOTENT_HIT, claim.outcome());
		assertEquals(8L, claim.run().getId());
		// 幂等命中只插入过一次（冲突那次被回滚），没有补插 SKIPPED
		verify(taskRunMapper).insert(any(AgentTaskRun.class));
	}

	/** ALLOW（或未落策略快照）不参与槽竞争：走幂等落库，不做 SKIP LOCKED 预检也不占镜像。 */
	@Test
	void allowPolicySkipsSlotArbitration() {
		AgentTaskRun allowRun = forbidRun();
		allowRun.setConcurrencyPolicy(TaskConstants.CONCURRENCY_ALLOW);

		AgentTaskRunService.SlotClaim claim = taskRunService.claimSlot(allowRun);

		assertEquals(AgentTaskRunService.SlotOutcome.CLAIMED, claim.outcome());
		verify(taskRunMapper, never()).lockActiveRunIdsByDefinition(anyString(), anyLong());
		verify(definitionMapper, never()).occupySlot(anyLong(), anyLong());
	}

	/** 终态同步命中时随终态定向释放槽位镜像（释放谓词由 Mapper SQL 保证，这里只验证接线）。 */
	@Test
	void markTerminalByRuntimeRunReleasesSlotMirror() {
		AgentTaskRun active = forbidRun();
		active.setId(5L);
		when(taskRunMapper.findByRuntimeRunId(88L)).thenReturn(active);
		when(taskRunMapper.markTerminalByRuntimeRunId(anyLong(), anyString(), any(), any())).thenReturn(1);

		assertTrue(taskRunService.markTerminalByRuntimeRun(88L, TaskRunStatus.SUCCESS, null));

		verify(definitionMapper).releaseSlot(2L, 5L);
	}

	private AgentTaskRun forbidRun() {
		AgentTaskRun run = AgentTaskRun.builder()
			.tenantId("7")
			.definitionId(2L)
			.taskVersionId(1L)
			.triggerId(4L)
			.triggerType("SCHEDULE")
			.idempotencyKey("SCHEDULE:4:2026-08-18T10:00:00Z")
			.runStatus(TaskRunStatus.PENDING.getValue())
			.concurrencyPolicy(TaskConstants.CONCURRENCY_FORBID)
			.servicePrincipal("sp-1")
			.build();
		run.setId(5L);
		return run;
	}

}
