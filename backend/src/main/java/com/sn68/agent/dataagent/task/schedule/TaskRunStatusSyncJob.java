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
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 任务运行状态对账作业（PR-6 清单第 10 条，Snail Job）。
 *
 * <p>运行时 RUN_STARTED 事件不走 Outbox，任务侧没有事件源推进 PENDING → RUNNING，
 * 本作业按运行时实况对账三件事：
 * 1) PENDING 且已挂 Run 的行——运行时活跃（RUNNING、WAITING_APPROVAL、CANCELLING 等）则推进 RUNNING，
 *    运行时已终态则兜底同步任务终态（主路径是 Outbox 事件与运行时守护作业，这里补漏网）；
 * 2) PENDING 且长时间未挂 Run 的行——按拉起中断收敛 FAILED（重启恢复语义）；
 * 3) FORBID 并发槽镜像（agent_task_definition.active_run_id）指向非活跃运行时定向回收，
 *    修复崩溃/异常路径残留的镜像占用。
 *
 * <p>启动时由 {@link TaskSnailClusterJobRegistrar} 与定时扫描作业一并注册；
 * 全部动作幂等（状态谓词 CAS 兜底），多副本与重复触发安全。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskRunStatusSyncJob {

	/** PENDING 且未挂 RuntimeRun 的僵尸判定窗口：超过即按拉起中断收敛。 */
	private static final Duration LAUNCH_GRACE = Duration.ofMinutes(30);

	private static final int SYNC_BATCH = 200;

	/** 运行时终态 → 任务终态兜底映射（与 TaskRunTerminalSyncListener 事件映射同口径，超时归失败）。 */
	private static final Map<RuntimeRunState, TaskRunStatus> TERMINAL_MAPPING = Map.of(
			RuntimeRunState.SUCCEEDED, TaskRunStatus.SUCCESS,
			RuntimeRunState.FAILED, TaskRunStatus.FAILED,
			RuntimeRunState.TIMED_OUT, TaskRunStatus.FAILED,
			RuntimeRunState.CANCELLED, TaskRunStatus.CANCELLED);

	/** 非成功终态的台账原因（对账兜底口径），成功终态不写 error_message。 */
	private static final Map<RuntimeRunState, String> TERMINAL_REASON = Map.of(
			RuntimeRunState.FAILED, "运行时已收敛为失败终态(对账兜底)",
			RuntimeRunState.TIMED_OUT, "运行时已收敛为超时终态(对账兜底)",
			RuntimeRunState.CANCELLED, "运行时已收敛为取消终态(对账兜底)");

	private final AgentTaskRunService taskRunService;

	private final AgentTaskRunMapper taskRunMapper;

	private final AgentTaskDefinitionMapper definitionMapper;

	private final RuntimeRunService runtimeRunService;

	/**
	 * Snail Job 回调入口。
	 */
	public void jobExecute() {
		int advanced = syncPendingRuns();
		int recycled = recycleIdleSlotMirrors();
		int interrupted = failInterruptedLaunches();
		log.info("任务运行状态对账完成: RUNNING/终态推进 {} 行, 槽位镜像回收 {} 个, 拉起中断收敛 {} 行",
				advanced, recycled, interrupted);
	}

	/**
	 * PENDING 且已挂 Run 的行按运行时实况推进：终态兜底同步；
	 * 活跃态（非 PENDING 非终态，即 RUNNING/WAITING_APPROVAL/WAITING_INPUT/CANCELLING）推进 RUNNING
	 * （任务侧无中间态，粗粒度映射「已进入执行生命周期」）。
	 */
	private int syncPendingRuns() {
		int advanced = 0;
		for (AgentTaskRun taskRun : taskRunMapper.findPendingWithRuntime(SYNC_BATCH)) {
			RuntimeRunState state = findRunStateQuietly(taskRun);
			if (state == null) {
				continue;
			}
			TaskRunStatus terminal = TERMINAL_MAPPING.get(state);
			if (terminal != null) {
				if (taskRunService.markTerminalByRuntimeRun(taskRun.getRuntimeRunId(), terminal,
						TERMINAL_REASON.get(state))) {
					log.info("任务运行对账兜底终态同步。taskRunId={}, runtimeRunId={}, runtimeState={}",
							taskRun.getId(), taskRun.getRuntimeRunId(), state);
					advanced++;
				}
				continue;
			}
			if (state != RuntimeRunState.PENDING
					&& taskRunService.markRunningByRuntimeRun(taskRun.getRuntimeRunId())) {
				log.info("任务运行对账推进 RUNNING。taskRunId={}, runtimeRunId={}, runtimeState={}", taskRun.getId(),
						taskRun.getRuntimeRunId(), state);
				advanced++;
			}
		}
		return advanced;
	}

	/**
	 * FORBID 槽位回收：镜像指向非活跃运行时释放；指向仍为 PENDING/RUNNING 时按 Runtime
	 * 实况收敛（Runtime 已终态或已不存在则把任务运行写成终态并放槽）。
	 */
	private int recycleIdleSlotMirrors() {
		int recycled = 0;
		for (AgentTaskDefinition definition : definitionMapper.findOccupiedSlotDefinitions(SYNC_BATCH)) {
			AgentTaskRun mirrorRun = taskRunMapper.selectById(definition.getActiveRunId());
			if (!isActive(mirrorRun)) {
				if (definitionMapper.releaseSlot(definition.getId(), definition.getActiveRunId()) > 0) {
					log.info("并发槽镜像已对账回收。definitionId={}, staleRunId={}", definition.getId(),
							definition.getActiveRunId());
					recycled++;
				}
				continue;
			}
			if (reconcileOccupiedActiveRun(mirrorRun)) {
				recycled++;
			}
		}
		return recycled;
	}

	/** 占槽的活跃任务运行：Runtime 终态/缺失时收敛台账并放槽，真正在跑则保持占用。 */
	private boolean reconcileOccupiedActiveRun(AgentTaskRun mirrorRun) {
		if (mirrorRun.getRuntimeRunId() == null) {
			return false;
		}
		RuntimeRunState state;
		try {
			state = runtimeRunService.findRunState(mirrorRun.getTenantId(),
					mirrorRun.getRuntimeRunId());
		}
		catch (Exception ex) {
			log.warn("占槽运行对账查询 Runtime 失败, 本轮跳过。taskRunId={}, runtimeRunId={}", mirrorRun.getId(),
					mirrorRun.getRuntimeRunId(), ex);
			return false;
		}
		if (state != null && state.terminal()) {
			TaskRunStatus terminal = TERMINAL_MAPPING.get(state);
			if (terminal != null && taskRunService.markTerminalByRuntimeRun(mirrorRun.getRuntimeRunId(), terminal,
					TERMINAL_REASON.get(state))) {
				log.info("占槽运行已按 Runtime 终态对账释放。taskRunId={}, runtimeRunId={}, runtimeState={}",
						mirrorRun.getId(), mirrorRun.getRuntimeRunId(), state);
				return true;
			}
			return false;
		}
		if (state == null && taskRunService.markTerminalById(mirrorRun.getId(), TaskRunStatus.FAILED,
				"[RUNTIME_MISSING] 运行时记录已不存在, 已对账释放并发槽")) {
			log.warn("占槽运行的 Runtime 已不存在, 已对账 FAILED 并放槽。taskRunId={}, runtimeRunId={}",
					mirrorRun.getId(), mirrorRun.getRuntimeRunId());
			return true;
		}
		return false;
	}

	/** 拉起中断收敛：受理后长时间未挂 RuntimeRun 的 PENDING 行落 FAILED（重启恢复语义）。 */
	private int failInterruptedLaunches() {
		Instant olderThan = Instant.now().minus(LAUNCH_GRACE);
		int failed = 0;
		for (AgentTaskRun taskRun : taskRunMapper.findPendingWithoutRuntime(olderThan, SYNC_BATCH)) {
			if (taskRunService.markTerminalById(taskRun.getId(), TaskRunStatus.FAILED,
					"[LAUNCH_INTERRUPTED] 任务运行受理后长时间未挂接运行时(服务崩溃或拉起中断), 已对账收敛")) {
				log.warn("拉起中断的僵尸 PENDING 运行已收敛 FAILED。taskRunId={}, definitionId={}, createTime={}",
						taskRun.getId(), taskRun.getDefinitionId(), taskRun.getCreateTime());
				failed++;
			}
		}
		return failed;
	}

	private boolean isActive(AgentTaskRun run) {
		return run != null && (TaskRunStatus.PENDING.getValue().equals(run.getRunStatus())
				|| TaskRunStatus.RUNNING.getValue().equals(run.getRunStatus()));
	}

	/** 单行查询失败不中断整轮对账（租户解析失败/数据残留均可能，跳过该行下一轮再试）。 */
	private RuntimeRunState findRunStateQuietly(AgentTaskRun taskRun) {
		try {
			return runtimeRunService.findRunState(taskRun.getTenantId(),
					taskRun.getRuntimeRunId());
		}
		catch (Exception ex) {
			log.warn("任务运行对账查询运行时状态失败, 跳过该行。taskRunId={}, runtimeRunId={}", taskRun.getId(),
					taskRun.getRuntimeRunId(), ex);
			return null;
		}
	}

}
