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
package com.sn68.agent.dataagent.task.schedule;

import com.sn68.agent.dataagent.task.entity.AgentTaskTrigger;
import com.sn68.agent.dataagent.task.enums.TaskConstants;
import com.sn68.agent.dataagent.task.enums.TaskMisfirePolicy;
import com.sn68.agent.dataagent.task.enums.TaskTriggerType;
import com.sn68.agent.dataagent.task.repository.AgentTaskTriggerMapper;
import com.sn68.agent.dataagent.task.service.TaskLaunchRequest;
import com.sn68.agent.dataagent.task.service.TaskRunLauncher;
import com.sn68.agent.framework.redis.plus.exception.RedisLockException;
import com.sn68.agent.framework.redis.plus.lock.RedisLockHelper;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * SCHEDULE 触发执行器：扫描到期触发器，处理时区、DST 与错过执行策略，幂等发起任务运行。
 *
 * <p>系统级组件（跨租户扫描），任务归属租户取自触发器行内 tenant_id。
 * 多副本安全：单触发器粒度 Redis 锁互斥 + task_run 幂等键兜底，重复扫描不会重复执行。
 *
 * <p>错过执行（misfire）判定：计划时刻落后当前时间超过 {@link #MISFIRE_TOLERANCE}
 * 视为错过（服务停机/调度暂停/DST 跳变都会造成），按触发器策略处理：
 * SKIP=全部跳过从当前时间重排；FIRE_ONCE=以最早错过的计划时刻补偿执行一次后重排。
 *
 * <p>PR-6 misfire 修复：launch 抛异常（基础设施故障等）时不推进 nextFireTime，
 * 保留 occurrence 由下一轮重扫重试；幂等键 SCHEDULE:{triggerId}:{计划时刻} 保证重试不重复执行。
 * SKIPPED（并发槽被占）与 FAILED（授权失败）是 launch 的正常返回不抛出，仍照常推进下一计划时刻。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskScheduleExecutor {

	/** 允许的调度延迟容忍窗口，超过即按 misfire 策略处理（扫描周期为分钟级，容忍两个周期）。 */
	private static final Duration MISFIRE_TOLERANCE = Duration.ofMinutes(2);

	private static final String LOCK_PREFIX = "dataagent:task:schedule-trigger:";

	private static final int SCAN_BATCH = 200;

	private final AgentTaskTriggerMapper triggerMapper;

	private final TaskRunLauncher taskRunLauncher;

	private final RedisLockHelper redisLockHelper;

	/**
	 * 扫描并触发全部到期的 SCHEDULE 触发器，返回本轮成功发起的运行数。
	 */
	public int executeDueTriggers() {
		Instant now = Instant.now();
		List<AgentTaskTrigger> dueTriggers = triggerMapper.findDueScheduleTriggers(now, SCAN_BATCH);
		int fired = 0;
		for (AgentTaskTrigger due : dueTriggers) {
			try {
				fired += redisLockHelper.execute(LOCK_PREFIX + due.getId(), 0L, TimeUnit.SECONDS,
						() -> fireTrigger(due.getId(), Instant.now()));
			}
			catch (RedisLockException ex) {
				log.debug("SCHEDULE 触发器已被其他副本处理, 跳过。triggerId={}", due.getId());
			}
			catch (Exception ex) {
				// 单个触发器失败不影响本轮其他触发器；失败的触发器 nextFireTime 未推进，下一轮会重试。
				log.error("SCHEDULE 触发器处理失败。triggerId={}", due.getId(), ex);
			}
		}
		return fired;
	}

	/**
	 * 处理单个触发器（持锁执行）。返回 1 表示发起了一次运行，0 表示跳过。
	 */
	private int fireTrigger(Long triggerId, Instant now) {
		// 锁内重读，避免用扫描时的陈旧快照重复触发或覆盖他人推进的 nextFireTime。
		AgentTaskTrigger trigger = triggerMapper.selectById(triggerId);
		if (trigger == null || !TaskConstants.STATUS_ENABLED.equalsIgnoreCase(trigger.getStatus())
				|| trigger.getNextFireTime() == null || trigger.getNextFireTime().isAfter(now)) {
			return 0;
		}
		CronExpression cron = parseCron(trigger);
		if (cron == null) {
			// cron 损坏时停用触发器并保留现场，比无限重扫更可见。
			log.error("SCHEDULE 触发器 cron 无效, 已停用。triggerId={}, config={}", trigger.getId(),
					trigger.getTriggerConfig());
			trigger.setStatus(TaskConstants.STATUS_DISABLED);
			triggerMapper.updateById(trigger);
			return 0;
		}
		ZoneId zone = resolveZone(trigger);
		Instant plannedTime = trigger.getNextFireTime();
		boolean misfired = Duration.between(plannedTime, now).compareTo(MISFIRE_TOLERANCE) > 0;
		TaskMisfirePolicy policy = TaskMisfirePolicy.ofOrDefault(trigger.getMisfirePolicy());
		int fired = 0;
		boolean launched = true;
		if (!misfired || policy == TaskMisfirePolicy.FIRE_ONCE) {
			// FIRE_ONCE 补偿时沿用最早错过的计划时刻做幂等键，同一错过时刻多副本补偿也只会执行一次。
			launched = launchQuietly(trigger, plannedTime, misfired);
			fired = launched ? 1 : 0;
		}
		else {
			log.info("SCHEDULE 触发器错过执行且策略为 SKIP, 跳过计划时刻。triggerId={}, plannedTime={}",
					trigger.getId(), plannedTime);
		}
		if (!launched) {
			// PR-6 misfire 修复：launch 失败不推进 nextFireTime 也不动 lastFireTime，
			// 保留 occurrence 状态由下一轮重扫重试（幂等键防重复）。
			return 0;
		}
		// 无论执行还是跳过都从当前时间向后重排，错过的历史时刻不再重复补偿。
		ZonedDateTime next = cron.next(now.atZone(zone));
		trigger.setNextFireTime(next == null ? null : next.toInstant());
		trigger.setLastFireTime(now);
		triggerMapper.updateById(trigger);
		return fired;
	}

	/**
	 * 发起运行，返回是否成功受理（含幂等返回）。失败只记日志不再抛出且不推进
	 * nextFireTime（PR-6 misfire 修复）：调用方据返回值保留 occurrence 由下一轮重扫重试；
	 * SKIPPED/FAILED 是 launch 的正常返回（非异常），照常推进调度。
	 */
	private boolean launchQuietly(AgentTaskTrigger trigger, Instant plannedTime, boolean misfired) {
		try {
			taskRunLauncher.launch(new TaskLaunchRequest(trigger, TaskTriggerType.SCHEDULE, null, plannedTime,
					Map.of()));
			if (misfired) {
				log.info("SCHEDULE 触发器错过执行, 已按 FIRE_ONCE 补偿一次。triggerId={}, plannedTime={}",
						trigger.getId(), plannedTime);
			}
			return true;
		}
		catch (Exception ex) {
			log.error("SCHEDULE 触发任务运行失败, 保留计划时刻待下一轮重扫。triggerId={}, plannedTime={}",
					trigger.getId(), plannedTime, ex);
			return false;
		}
	}

	private CronExpression parseCron(AgentTaskTrigger trigger) {
		Object cron = trigger.getTriggerConfig() == null ? null
				: trigger.getTriggerConfig().get(TaskConstants.CONFIG_CRON);
		String expression = cron == null ? null : String.valueOf(cron).trim();
		if (!StringUtils.hasText(expression) || !CronExpression.isValidExpression(expression)) {
			return null;
		}
		return CronExpression.parse(expression);
	}

	private ZoneId resolveZone(AgentTaskTrigger trigger) {
		try {
			return ZoneId.of(StringUtils.hasText(trigger.getTimezone()) ? trigger.getTimezone().trim()
					: TaskConstants.DEFAULT_TIMEZONE);
		}
		catch (Exception ex) {
			log.warn("SCHEDULE 触发器时区无效, 回退默认时区。triggerId={}, timezone={}", trigger.getId(),
					trigger.getTimezone());
			return ZoneId.of(TaskConstants.DEFAULT_TIMEZONE);
		}
	}

}
