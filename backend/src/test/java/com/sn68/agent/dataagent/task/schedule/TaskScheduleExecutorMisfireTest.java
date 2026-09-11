/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package com.sn68.agent.dataagent.task.schedule;

import com.sn68.agent.dataagent.task.entity.AgentTaskTrigger;
import com.sn68.agent.dataagent.task.enums.TaskConstants;
import com.sn68.agent.dataagent.task.repository.AgentTaskTriggerMapper;
import com.sn68.agent.dataagent.task.service.TaskLaunchRequest;
import com.sn68.agent.dataagent.task.service.TaskRunLauncher;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.redis.plus.lock.RedisLockHelper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-6 misfire 修复单元测试：launch 抛异常（基础设施故障）时不得推进 nextFireTime/lastFireTime，
 * 保留计划时刻由下一轮重扫重试（幂等键 SCHEDULE:{triggerId}:{计划时刻} 防重复执行）；
 * 正常受理（含 SKIPPED/FAILED 正常返回）与 SKIP 策略跳过则照常推进调度。
 *
 * <p>Redis 锁 stub 直通执行 Supplier 回调（单测无 Redis，多副本互斥语义由集成环境验证）。</p>
 */
class TaskScheduleExecutorMisfireTest {

	private AgentTaskTriggerMapper triggerMapper;

	private TaskRunLauncher taskRunLauncher;

	private TaskScheduleExecutor executor;

	@BeforeEach
	void setUp() {
		triggerMapper = mock(AgentTaskTriggerMapper.class);
		taskRunLauncher = mock(TaskRunLauncher.class);
		RedisLockHelper redisLockHelper = mock(RedisLockHelper.class);
		doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get()).when(redisLockHelper)
			.execute(anyString(), anyLong(), any(TimeUnit.class), any());
		executor = new TaskScheduleExecutor(triggerMapper, taskRunLauncher, redisLockHelper);
	}

	/** PR-6 misfire 修复核心：launch 抛异常不推进 nextFireTime，保留 occurrence 待下一轮重扫重试。 */
	@Test
	void launchFailureKeepsOccurrenceForNextScan() {
		AgentTaskTrigger trigger = dueTrigger(Duration.ofMinutes(1));
		stubDue(trigger);
		doThrow(CheckedException.fail("db down")).when(taskRunLauncher).launch(any(TaskLaunchRequest.class));

		int fired = executor.executeDueTriggers();

		assertEquals(0, fired, "launch 失败本轮不得计入成功发起数");
		verify(triggerMapper, never()).updateById(any(AgentTaskTrigger.class));
	}

	/** launch 正常受理照常推进调度：nextFireTime 前移、lastFireTime 落本轮时刻。 */
	@Test
	void successfulLaunchAdvancesSchedule() {
		AgentTaskTrigger trigger = dueTrigger(Duration.ofMinutes(1));
		Instant planned = trigger.getNextFireTime();
		stubDue(trigger);

		int fired = executor.executeDueTriggers();

		assertEquals(1, fired);
		ArgumentCaptor<AgentTaskTrigger> captor = ArgumentCaptor.forClass(AgentTaskTrigger.class);
		verify(triggerMapper).updateById(captor.capture());
		assertTrue(captor.getValue().getNextFireTime().isAfter(planned), "nextFireTime 应推进到未来计划时刻");
		assertNotNull(captor.getValue().getLastFireTime(), "lastFireTime 应记录本轮实际触发时刻");
	}

	/** 错过执行且策略为 SKIP：不发起运行但照常推进调度，错过的历史时刻不补偿。 */
	@Test
	void misfiredSkipPolicyAdvancesWithoutFiring() {
		AgentTaskTrigger trigger = dueTrigger(Duration.ofMinutes(10));
		trigger.setMisfirePolicy("SKIP");
		stubDue(trigger);

		int fired = executor.executeDueTriggers();

		assertEquals(0, fired);
		verify(taskRunLauncher, never()).launch(any(TaskLaunchRequest.class));
		verify(triggerMapper).updateById(any(AgentTaskTrigger.class));
	}

	private void stubDue(AgentTaskTrigger trigger) {
		when(triggerMapper.findDueScheduleTriggers(any(), anyInt())).thenReturn(List.of(trigger));
		when(triggerMapper.selectById(trigger.getId())).thenReturn(trigger);
	}

	private AgentTaskTrigger dueTrigger(Duration overdue) {
		AgentTaskTrigger trigger = AgentTaskTrigger.builder()
			.tenantId("7")
			.definitionId(2L)
			.taskVersionId(1L)
			.triggerType("SCHEDULE")
			.triggerConfig(Map.of(TaskConstants.CONFIG_CRON, "0 * * * * *"))
			.timezone("UTC")
			.misfirePolicy("FIRE_ONCE")
			.status(TaskConstants.STATUS_ENABLED)
			.nextFireTime(Instant.now().minus(overdue))
			.build();
		trigger.setId(4L);
		return trigger;
	}

}
